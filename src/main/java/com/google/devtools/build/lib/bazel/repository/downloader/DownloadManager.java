// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository.downloader;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auth.Credentials;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.authandtls.StaticCredentials;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache.KeyType;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCacheHitEvent;
import com.google.devtools.build.lib.bazel.repository.downloader.UrlRewriter.RewrittenURL;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Bazel file downloader.
 *
 * <p>This class uses a {@link Downloader} to download files from external mirrors and writes them
 * to disk.
 */
public class DownloadManager {
  private static final ExecutorService DOWNLOAD_EXECUTOR =
      Executors.newFixedThreadPool(
          // There is also GrpcRemoteDownloader so if we set the thread pool to the same size as
          // the allowed number of HTTP downloads, it might unnecessarily block. No, this is not a
          // very
          // principled approach; ideally, we'd grow the thread pool as needed with some generous
          // upper
          // limit.
          2 * HttpDownloader.MAX_PARALLEL_DOWNLOADS,
          new ThreadFactoryBuilder().setNameFormat("download-manager-%d").build());

  private final RepositoryCache repositoryCache;
  private List<Path> distdir = ImmutableList.of();
  private UrlRewriter rewriter;
  private final Downloader downloader;
  private boolean disableDownload = false;
  private int retries = 0;
  @Nullable private Credentials netrcCreds;
  private CredentialFactory credentialFactory = StaticCredentials::new;
  @Nullable private volatile RegistryFilePrefetcher registryFilePrefetcher;

  /** Creates {@code Credentials} from a map of per-{@code URI} authentication headers. */
  public interface CredentialFactory {
    Credentials create(Map<URI, Map<String, List<String>>> authHeaders);
  }

  public DownloadManager(RepositoryCache repositoryCache, Downloader downloader) {
    this.repositoryCache = repositoryCache;
    this.downloader = downloader;
  }

  public void setDistdir(List<Path> distdir) {
    this.distdir = ImmutableList.copyOf(distdir);
  }

  public void setUrlRewriter(UrlRewriter rewriter) {
    this.rewriter = rewriter;
  }

  public void setDisableDownload(boolean disableDownload) {
    this.disableDownload = disableDownload;
  }

  public void setRetries(int retries) {
    checkArgument(retries >= 0, "Invalid retries");
    this.retries = retries;
  }

  public void setNetrcCreds(Credentials netrcCreds) {
    this.netrcCreds = netrcCreds;
  }

  public void setCredentialFactory(CredentialFactory credentialFactory) {
    this.credentialFactory = credentialFactory;
  }

  public synchronized void setBzlmodRegistryModuleFilePrefetch(
      boolean enabled, ImmutableMap<String, String> registryFileHashes) {
    if (registryFilePrefetcher != null) {
      registryFilePrefetcher.close();
      registryFilePrefetcher = null;
    }
    if (!enabled || registryFileHashes.isEmpty()) {
      return;
    }
    registryFilePrefetcher =
        new RegistryFilePrefetcher(parseRegistryFileChecksums(registryFileHashes));
  }

  public synchronized void clearBzlmodRegistryModuleFilePrefetch() {
    if (registryFilePrefetcher != null) {
      registryFilePrefetcher.close();
      registryFilePrefetcher = null;
    }
  }

  public void startBzlmodRegistryModuleFilePrefetch(
      URI registryUri, ExtendedEventHandler eventHandler, Map<String, String> clientEnv) {
    RegistryFilePrefetcher prefetcher = registryFilePrefetcher;
    if (prefetcher != null) {
      prefetcher.startForRegistry(registryUri, eventHandler, clientEnv);
    }
  }

  public Optional<Checksum> getChecksumFromRegistryFileHashes(URL url) {
    RegistryFilePrefetcher prefetcher = registryFilePrefetcher;
    if (prefetcher == null) {
      return Optional.empty();
    }
    return prefetcher.getChecksumForUrl(url);
  }

  public Future<Path> startDownload(
      List<URL> originalUrls,
      Map<String, List<String>> headers,
      Map<URI, Map<String, List<String>>> authHeaders,
      Optional<Checksum> checksum,
      String canonicalId,
      Optional<String> type,
      Path output,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv,
      String context) {
    return DOWNLOAD_EXECUTOR.submit(
        () -> {
          try (SilentCloseable c = Profiler.instance().profile("fetching: " + context)) {
            return downloadInExecutor(
                originalUrls,
                headers,
                authHeaders,
                checksum,
                canonicalId,
                type,
                output,
                eventHandler,
                clientEnv,
                context);
          }
        });
  }

  public Path finalizeDownload(Future<Path> download) throws IOException, InterruptedException {
    try {
      return download.get();
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      Throwables.throwIfInstanceOf(e.getCause(), InterruptedException.class);
      Throwables.throwIfUnchecked(e.getCause());
      throw new IllegalStateException(e);
    }
  }

  public Path download(
      List<URL> originalUrls,
      Map<String, List<String>> headers,
      Map<URI, Map<String, List<String>>> authHeaders,
      Optional<Checksum> checksum,
      String canonicalId,
      Optional<String> type,
      Path output,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv,
      String context)
      throws IOException, InterruptedException {
    Future<Path> future =
        startDownload(
            originalUrls,
            headers,
            authHeaders,
            checksum,
            canonicalId,
            type,
            output,
            eventHandler,
            clientEnv,
            context);
    return finalizeDownload(future);
  }

  /**
   * Downloads file to disk and returns path.
   *
   * <p>If the checksum and path to the repository cache is specified, attempt to load the file from
   * the {@link RepositoryCache}. If it doesn't exist, proceed to download the file and load it into
   * the cache prior to returning the value.
   *
   * @param originalUrls list of mirror URLs with identical content
   * @param checksum valid checksum which is checked, or absent to disable
   * @param type extension, e.g. "tar.gz" to force on downloaded filename, or empty to not do this
   * @param output destination filename if {@code type} is <i>absent</i>, otherwise output directory
   * @param eventHandler CLI progress reporter
   * @param clientEnv environment variables in shell issuing this command
   * @param context the context in which the file was fetched; used only for reporting
   * @throws IllegalArgumentException on parameter badness, which should be checked beforehand
   * @throws IOException if download was attempted and ended up failing
   * @throws InterruptedException if this thread is being cast into oblivion
   */
  private Path downloadInExecutor(
      List<URL> originalUrls,
      Map<String, List<String>> headers,
      Map<URI, Map<String, List<String>>> authHeaders,
      Optional<Checksum> checksum,
      String canonicalId,
      Optional<String> type,
      Path output,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv,
      String context)
      throws IOException, InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    // TODO(andreisolo): This code path is inconsistent as the authHeaders are fetched from a
    //  .netrc only if it comes from a http_{archive,file,jar} - and it is handled directly
    //  by Starlark code -, or if a UrlRewriter is present. However, if it comes directly from a
    //  ctx.download{,_and_extract}, this not the case. Should be refactored to handle all .netrc
    //  parsing in one place, in Java code (similarly to #downloadAndReadOneUrl).
    ImmutableList<URL> rewrittenUrls = ImmutableList.copyOf(originalUrls);
    Map<URI, Map<String, List<String>>> rewrittenAuthHeaders = authHeaders;

    if (rewriter != null) {
      ImmutableList<UrlRewriter.RewrittenURL> rewrittenUrlMappings = rewriter.amend(originalUrls);
      rewrittenUrls =
          rewrittenUrlMappings.stream().map(url -> url.url()).collect(toImmutableList());
      rewrittenAuthHeaders =
          rewriter.updateAuthHeaders(rewrittenUrlMappings, authHeaders, netrcCreds);
    }

    URL mainUrl; // The "main" URL for this request
    // Used for reporting only and determining the file name only.
    if (rewrittenUrls.isEmpty()) {
      if (type.isPresent() && !Strings.isNullOrEmpty(type.get())) {
        mainUrl = new URL("http://nonexistent.example.org/cacheprobe." + type.get());
      } else {
        mainUrl = new URL("http://nonexistent.example.org/cacheprobe");
      }
    } else {
      mainUrl = rewrittenUrls.get(0);
    }
    Path destination = getDownloadDestination(mainUrl, type, output);
    ImmutableSet<String> candidateFileNames = getCandidateFileNames(mainUrl, destination);

    // Is set to true if the value should be cached by the checksum value provided
    boolean isCachingByProvidedChecksum = false;

    if (checksum.isPresent()) {
      String cacheKey = checksum.get().toString();
      KeyType cacheKeyType = checksum.get().getKeyType();
      try {
        eventHandler.post(
            new CacheProgress(mainUrl.toString(), "Checking in " + cacheKeyType + " cache"));
        String currentChecksum = RepositoryCache.getChecksum(cacheKeyType, destination);
        if (currentChecksum.equals(cacheKey)) {
          // No need to download.
          return destination;
        }
      } catch (IOException e) {
        // Ignore error trying to hash. We'll attempt to retrieve from cache or just download again.
      } finally {
        eventHandler.post(new CacheProgress(mainUrl.toString()));
      }

      if (repositoryCache.isEnabled()) {
        isCachingByProvidedChecksum = true;

        try {
          Path cachedDestination =
              repositoryCache.get(cacheKey, destination, cacheKeyType, canonicalId);
          if (cachedDestination != null) {
            // Cache hit!
            eventHandler.post(new RepositoryCacheHitEvent(context, cacheKey, mainUrl));
            return cachedDestination;
          }
        } catch (IOException e) {
          // Ignore error trying to get. We'll just download again.
        }
      }

      if (rewrittenUrls.isEmpty()) {
        StringBuilder message = new StringBuilder("Cache miss and no url specified");
        if (!originalUrls.isEmpty()) {
          message.append(" - ");
          message.append(getRewriterBlockedAllUrlsMessage(originalUrls));
        }
        throw new IOException(message.toString());
      }

      for (Path dir : distdir) {
        if (!dir.exists()) {
          // This is not a warning (and probably we even should drop the message); it is
          // perfectly fine to have a common rc-file pointing to a volume that is sometimes,
          // but not always mounted.
          eventHandler.handle(Event.info("non-existent distdir " + dir));
        } else if (!dir.isDirectory()) {
          eventHandler.handle(Event.warn("distdir " + dir + " is not a directory"));
        } else {
          for (String name : candidateFileNames) {
            boolean match = false;
            Path candidate = dir.getRelative(name);
            try {
              eventHandler.post(
                  new CacheProgress(
                      mainUrl.toString(), "Checking " + cacheKeyType + " of " + candidate));
              match = RepositoryCache.getChecksum(cacheKeyType, candidate).equals(cacheKey);
            } catch (IOException e) {
              // Not finding anything in a distdir is a normal case, so handle it absolutely
              // quietly. In fact, it is common to specify a whole list of dist dirs,
              // with the assumption that only one will contain an entry.
            } finally {
              eventHandler.post(new CacheProgress(mainUrl.toString()));
            }
            if (match) {
              if (isCachingByProvidedChecksum) {
                try {
                  repositoryCache.put(cacheKey, candidate, cacheKeyType, canonicalId);
                } catch (IOException e) {
                  eventHandler.handle(
                      Event.warn("Failed to copy " + candidate + " to repository cache: " + e));
                }
              }
              destination.getParentDirectory().createDirectoryAndParents();
              FileSystemUtils.copyFile(candidate, destination);
              return destination;
            }
          }
        }
      }
    }

    if (disableDownload) {
      throw new IOException(String.format("Failed to download %s: download is disabled.", context));
    }

    if (rewrittenUrls.isEmpty() && !originalUrls.isEmpty()) {
      throw new IOException(getRewriterBlockedAllUrlsMessage(originalUrls));
    }

    for (int attempt = 0; attempt <= retries; ++attempt) {
      try {
        downloader.download(
            rewrittenUrls,
            headers,
            credentialFactory.create(rewrittenAuthHeaders),
            checksum,
            canonicalId,
            destination,
            eventHandler,
            clientEnv,
            type);
        break;
      } catch (ContentLengthMismatchException e) {
        if (attempt == retries) {
          throw e;
        }
      } catch (InterruptedIOException e) {
        throw new InterruptedException(e.getMessage());
      }
    }

    if (isCachingByProvidedChecksum) {
      repositoryCache.put(
          checksum.get().toString(), destination, checksum.get().getKeyType(), canonicalId);
    } else if (repositoryCache.isEnabled()) {
      repositoryCache.put(destination, KeyType.SHA256, canonicalId);
    }

    return destination;
  }

  /**
   * Downloads the contents of one URL and reads it into a byte array.
   *
   * <p>If the checksum and path to the repository cache is specified, attempt to load the file from
   * the {@link RepositoryCache}. If it doesn't exist, proceed to download the file and load it into
   * the cache prior to returning the value.
   *
   * @param originalUrl the original URL of the file
   * @param eventHandler CLI progress reporter
   * @param clientEnv environment variables in shell issuing this command
   * @throws IllegalArgumentException on parameter badness, which should be checked beforehand
   * @throws IOException if download was attempted and ended up failing
   * @throws InterruptedException if this thread is being cast into oblivion
   */
  public byte[] downloadAndReadOneUrl(
      URL originalUrl, ExtendedEventHandler eventHandler, Map<String, String> clientEnv)
      throws IOException, InterruptedException {
    return downloadAndReadOneUrl(originalUrl, Optional.empty(), eventHandler, clientEnv);
  }

  public byte[] downloadAndReadOneUrl(
      URL originalUrl,
      Optional<Checksum> checksum,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv)
      throws IOException, InterruptedException {
    RegistryFilePrefetcher prefetcher = registryFilePrefetcher;
    if (prefetcher != null && checksum.isPresent()) {
      return downloadAndReadOneUrlWithPrefetch(
          originalUrl, checksum.get(), prefetcher, eventHandler, clientEnv);
    }
    return downloadAndReadOneUrlDirectHttp(originalUrl, checksum, eventHandler, clientEnv);
  }

  private byte[] downloadAndReadOneUrlDirectHttp(
      URL originalUrl,
      Optional<Checksum> checksum,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv)
      throws IOException, InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    Map<URI, Map<String, List<String>>> authHeaders = ImmutableMap.of();
    ImmutableList<URL> rewrittenUrls = ImmutableList.of(originalUrl);

    if (netrcCreds != null) {
      try {
        Map<String, List<String>> metadata = netrcCreds.getRequestMetadata(originalUrl.toURI());
        if (!metadata.isEmpty()) {
          Entry<String, List<String>> headers = metadata.entrySet().iterator().next();
          authHeaders =
              ImmutableMap.of(
                  originalUrl.toURI(),
                  ImmutableMap.of(headers.getKey(), ImmutableList.of(headers.getValue().get(0))));
        }
      } catch (URISyntaxException e) {
        // If the credentials extraction failed, we're letting bazel try without credentials.
      }
    }

    if (rewriter != null) {
      ImmutableList<UrlRewriter.RewrittenURL> rewrittenUrlMappings =
          rewriter.amend(ImmutableList.of(originalUrl));
      rewrittenUrls =
          rewrittenUrlMappings.stream().map(RewrittenURL::url).collect(toImmutableList());
      authHeaders = rewriter.updateAuthHeaders(rewrittenUrlMappings, authHeaders, netrcCreds);
    }

    if (rewrittenUrls.isEmpty()) {
      throw new IOException(getRewriterBlockedAllUrlsMessage(ImmutableList.of(originalUrl)));
    }

    HttpDownloader httpDownloader = new HttpDownloader();
    for (int attempt = 0; attempt <= retries; ++attempt) {
      try {
        return httpDownloader.downloadAndReadOneUrl(
            rewrittenUrls.get(0),
            credentialFactory.create(authHeaders),
            checksum,
            eventHandler,
            clientEnv);
      } catch (ContentLengthMismatchException e) {
        if (attempt == retries) {
          throw e;
        }
      } catch (InterruptedIOException e) {
        throw new InterruptedException(e.getMessage());
      }
    }

    throw new IllegalStateException("Unexpected error: file should have been downloaded.");
  }

  private byte[] downloadAndReadOneUrlWithPrefetch(
      URL originalUrl,
      Checksum checksum,
      RegistryFilePrefetcher prefetcher,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv)
      throws IOException, InterruptedException {
    Optional<byte[]> cached = readFromRepositoryCache(checksum);
    if (cached.isPresent()) {
      return cached.get();
    }

    Optional<byte[]> prefetched =
        prefetcher.consumeOrSchedule(originalUrl, checksum, eventHandler, clientEnv);
    if (prefetched.isPresent()) {
      maybeWriteToRepositoryCache(checksum, prefetched.get(), eventHandler);
      return prefetched.get();
    }

    byte[] downloaded =
        downloadAndReadOneUrlDirectHttp(
            originalUrl, Optional.of(checksum), eventHandler, clientEnv);
    maybeWriteToRepositoryCache(checksum, downloaded, eventHandler);
    return downloaded;
  }

  private Optional<byte[]> readFromRepositoryCache(Checksum checksum) {
    if (!repositoryCache.isEnabled()) {
      return Optional.empty();
    }
    Path contentAddressablePath = repositoryCache.getContentAddressableCachePath();
    if (contentAddressablePath == null) {
      return Optional.empty();
    }
    Path cacheValuePath =
        checksum
            .getKeyType()
            .getCachePath(contentAddressablePath)
            .getChild(checksum.toString())
            .getChild(RepositoryCache.DEFAULT_CACHE_FILENAME);
    if (!cacheValuePath.exists()) {
      return Optional.empty();
    }
    try {
      if (!RepositoryCache.getChecksum(checksum.getKeyType(), cacheValuePath)
          .equals(checksum.toString())) {
        return Optional.empty();
      }
      return Optional.of(
          FileSystemUtils.readWithKnownFileSize(cacheValuePath, cacheValuePath.getFileSize()));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private void maybeWriteToRepositoryCache(
      Checksum checksum, byte[] content, ExtendedEventHandler eventHandler)
      throws InterruptedException {
    if (!repositoryCache.isEnabled()) {
      return;
    }
    Path root = repositoryCache.getRootPath();
    if (root == null) {
      return;
    }
    Path tempPath =
        root.getRelative("tmp")
            .getRelative("bzlmod-prefetch-" + Thread.currentThread().getId());
    try {
      tempPath.getParentDirectory().createDirectoryAndParents();
      FileSystemUtils.writeContent(tempPath, content);
      repositoryCache.put(checksum.toString(), tempPath, checksum.getKeyType(), "");
    } catch (IOException e) {
      eventHandler.handle(
          Event.warn("Failed to write bzlmod prefetch result to repository cache: " + e));
    } finally {
      try {
        tempPath.delete();
      } catch (IOException e) {
        // Best effort cleanup.
      }
    }
  }

  private byte[] downloadAndReadOneUrlWithDownloader(
      URL originalUrl,
      Optional<Checksum> checksum,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv)
      throws IOException, InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    Map<URI, Map<String, List<String>>> authHeaders = ImmutableMap.of();
    ImmutableList<URL> rewrittenUrls = ImmutableList.of(originalUrl);

    if (netrcCreds != null) {
      try {
        Map<String, List<String>> metadata = netrcCreds.getRequestMetadata(originalUrl.toURI());
        if (!metadata.isEmpty()) {
          Entry<String, List<String>> headers = metadata.entrySet().iterator().next();
          authHeaders =
              ImmutableMap.of(
                  originalUrl.toURI(),
                  ImmutableMap.of(headers.getKey(), ImmutableList.of(headers.getValue().get(0))));
        }
      } catch (URISyntaxException e) {
        // If the credentials extraction failed, we're letting bazel try without credentials.
      }
    }

    if (rewriter != null) {
      ImmutableList<UrlRewriter.RewrittenURL> rewrittenUrlMappings =
          rewriter.amend(ImmutableList.of(originalUrl));
      rewrittenUrls =
          rewrittenUrlMappings.stream().map(RewrittenURL::url).collect(toImmutableList());
      authHeaders = rewriter.updateAuthHeaders(rewrittenUrlMappings, authHeaders, netrcCreds);
    }

    if (rewrittenUrls.isEmpty()) {
      throw new IOException(getRewriterBlockedAllUrlsMessage(ImmutableList.of(originalUrl)));
    }

    for (int attempt = 0; attempt <= retries; ++attempt) {
      try {
        return downloader.downloadAndReadOneUrl(
            rewrittenUrls.get(0),
            credentialFactory.create(authHeaders),
            checksum,
            "",
            eventHandler,
            clientEnv);
      } catch (ContentLengthMismatchException e) {
        if (attempt == retries) {
          throw e;
        }
      } catch (InterruptedIOException e) {
        throw new InterruptedException(e.getMessage());
      }
    }

    throw new IllegalStateException("Unexpected error: file should have been downloaded.");
  }

  private static ImmutableMap<String, Checksum> parseRegistryFileChecksums(
      ImmutableMap<String, String> registryFileHashes) {
    HashMap<String, Checksum> checksums = new HashMap<>();
    for (Entry<String, String> entry : registryFileHashes.entrySet()) {
      Optional<Checksum> checksum = parseChecksum(entry.getValue());
      if (checksum.isPresent()) {
        checksums.put(entry.getKey(), checksum.get());
      }
    }
    return ImmutableMap.copyOf(checksums);
  }

  private static Optional<Checksum> parseChecksum(String rawChecksum) {
    if (Strings.isNullOrEmpty(rawChecksum)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Checksum.fromSubresourceIntegrity(rawChecksum));
    } catch (Checksum.InvalidChecksumException e) {
      // Fallback to plain SHA256.
    }
    try {
      return Optional.of(Checksum.fromString(KeyType.SHA256, rawChecksum));
    } catch (Checksum.InvalidChecksumException e) {
      return Optional.empty();
    }
  }

  private final class RegistryFilePrefetcher {
    private static final int PREFETCH_THREADS = 8;

    private final ImmutableMap<String, Checksum> checksumsByUrl;
    private final ExecutorService executor;
    private final ConcurrentMap<PrefetchKey, Future<byte[]>> futures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> startedRegistries = new ConcurrentHashMap<>();

    RegistryFilePrefetcher(ImmutableMap<String, Checksum> checksumsByUrl) {
      this.checksumsByUrl = checksumsByUrl;
      this.executor =
          Executors.newFixedThreadPool(
              PREFETCH_THREADS,
              new ThreadFactoryBuilder().setNameFormat("registry-file-prefetch-%d").build());
    }

    Optional<Checksum> getChecksumForUrl(URL url) {
      Checksum checksum = checksumsByUrl.get(url.toString());
      if (checksum == null) {
        return Optional.empty();
      }
      return Optional.of(checksum);
    }

    void startForRegistry(
        URI registryUri, ExtendedEventHandler eventHandler, Map<String, String> clientEnv) {
      String registry = normalizeRegistry(registryUri.toString());
      if (startedRegistries.putIfAbsent(registry, true) != null) {
        return;
      }
      for (Entry<String, Checksum> entry : checksumsByUrl.entrySet()) {
        URL url = toUrl(entry.getKey());
        if (url == null || !isEligibleRegistryModuleFileUrl(url)) {
          continue;
        }
        if (!normalizeRegistry(url.toString()).startsWith(registry)) {
          continue;
        }
        ensureFuture(url, entry.getValue(), eventHandler, clientEnv);
      }
    }

    Optional<byte[]> consumeOrSchedule(
        URL url,
        Checksum checksum,
        ExtendedEventHandler eventHandler,
        Map<String, String> clientEnv)
        throws InterruptedException {
      Future<byte[]> future = ensureFuture(url, checksum, eventHandler, clientEnv);
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.BZLMOD, () -> "wait prefetch: " + url)) {
        return Optional.of(future.get());
      } catch (CancellationException e) {
        throw new InterruptedException(e.getMessage());
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof InterruptedException) {
          throw (InterruptedException) cause;
        }
        return Optional.empty();
      }
    }

    private Future<byte[]> ensureFuture(
        URL url,
        Checksum checksum,
        ExtendedEventHandler eventHandler,
        Map<String, String> clientEnv) {
      PrefetchKey key = new PrefetchKey(url.toString(), checksum.toString());
      return futures.computeIfAbsent(
          key,
          unused ->
              executor.submit(
                  () ->
                      downloadAndReadOneUrlWithDownloader(
                          url, Optional.of(checksum), eventHandler, clientEnv)));
    }

    void close() {
      for (Future<byte[]> future : futures.values()) {
        future.cancel(true);
      }
      executor.shutdownNow();
      try {
        executor.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class PrefetchKey {
    private final String url;
    private final String checksum;

    private PrefetchKey(String url, String checksum) {
      this.url = url;
      this.checksum = checksum;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof PrefetchKey)) {
        return false;
      }
      PrefetchKey that = (PrefetchKey) other;
      return this.url.equals(that.url) && this.checksum.equals(that.checksum);
    }

    @Override
    public int hashCode() {
      return 31 * url.hashCode() + checksum.hashCode();
    }
  }

  private static String normalizeRegistry(String url) {
    return url.endsWith("/") ? url : url + "/";
  }

  private static boolean isEligibleRegistryModuleFileUrl(URL url) {
    String protocol = url.getProtocol();
    if (!protocol.equals("http") && !protocol.equals("https")) {
      return false;
    }
    return url.getPath().endsWith("/MODULE.bazel");
  }

  @Nullable
  private static URL toUrl(String urlString) {
    try {
      return new URL(urlString);
    } catch (IOException e) {
      return null;
    }
  }

  @Nullable
  private String getRewriterBlockedAllUrlsMessage(List<URL> originalUrls) {
    if (rewriter == null) {
      return null;
    }
    StringBuilder message = new StringBuilder("Configured URL rewriter blocked all URLs: ");
    message.append(originalUrls);
    String rewriterMessage = rewriter.getAllBlockedMessage();
    if (rewriterMessage != null) {
      message.append(" - ").append(rewriterMessage);
    }
    return message.toString();
  }

  private Path getDownloadDestination(URL url, Optional<String> type, Path output) {
    if (!type.isPresent()) {
      return output;
    }
    String basename =
        MoreObjects.firstNonNull(
            Strings.emptyToNull(PathFragment.create(url.getPath()).getBaseName()), "temp");
    if (!type.get().isEmpty()) {
      String suffix = "." + type.get();
      if (!basename.endsWith(suffix)) {
        basename += suffix;
      }
    }
    return output.getRelative(basename);
  }

  /**
   * Deterimine the list of filenames to look for in the distdirs. Note that an output name may be
   * specified that is unrelated to the primary URL. This happens, e.g., when the paramter output is
   * specified in ctx.download.
   */
  private static ImmutableSet<String> getCandidateFileNames(URL url, Path destination) {
    String urlBaseName = PathFragment.create(url.getPath()).getBaseName();
    if (!Strings.isNullOrEmpty(urlBaseName) && !urlBaseName.equals(destination.getBaseName())) {
      return ImmutableSet.of(urlBaseName, destination.getBaseName());
    } else {
      return ImmutableSet.of(destination.getBaseName());
    }
  }

  private static class CacheProgress implements ExtendedEventHandler.FetchProgress {
    private final String originalUrl;
    private final String progress;
    private final boolean isFinished;

    CacheProgress(String originalUrl, String progress) {
      this.originalUrl = originalUrl;
      this.progress = progress;
      this.isFinished = false;
    }

    CacheProgress(String originalUrl) {
      this.originalUrl = originalUrl;
      this.progress = "";
      this.isFinished = true;
    }

    @Override
    public String getResourceIdentifier() {
      return originalUrl;
    }

    @Override
    public String getProgress() {
      return progress;
    }

    @Override
    public boolean isFinished() {
      return isFinished;
    }
  }
}
