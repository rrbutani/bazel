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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.authandtls.StaticCredentials;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache.KeyType;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCacheHitEvent;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache;
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
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nullable;

/**
 * Bazel file downloader.
 *
 * <p>This class uses a {@link Downloader} to download files from external mirrors and writes them
 * to disk.
 */
public class DownloadManager implements AutoCloseable {
  private static final int REGISTRY_MODULE_FILE_PREFETCH_THREADS = 8;
  private static final String BZLMOD_DOWNLOAD_CONTEXT = "Bazel module fetching";

  private final DownloadCache downloadCache;
  private ImmutableList<Path> distdir = ImmutableList.of();
  private UrlRewriter rewriter;
  private final Downloader downloader;
  private final HttpDownloader bzlmodHttpDownloader;
  private final ExtendedEventHandler eventHandler;
  @Nullable private final ListeningExecutorService registryModuleFilePrefetchExecutor;
  private final ConcurrentHashMap<RegistryModuleFilePrefetchKey, ListenableFuture<byte[]>>
      registryModuleFilePrefetches = new ConcurrentHashMap<>();
  private final Set<String> registryModuleFilePrefetchesStarted = ConcurrentHashMap.newKeySet();
  private boolean disableDownload = false;
  private int retries = 0;
  @Nullable private Credentials netrcCreds;
  private CredentialFactory credentialFactory = StaticCredentials::new;

  /** Creates {@code Credentials} from a map of per-{@code URI} authentication headers. */
  public interface CredentialFactory {
    Credentials create(Map<URI, Map<String, List<String>>> authHeaders);
  }

  /**
   * Creates a new {@link DownloadManager}.
   *
   * @param downloader The (delegating) downloader to use to download files. Is either a
   *     HttpDownloader, or a GrpcRemoteDownloader.
   * @param bzlmodHttpDownloader The downloader to use for downloading files from the bzlmod
   *     registry.
   */
  public DownloadManager(
      DownloadCache downloadCache,
      Downloader downloader,
      HttpDownloader bzlmodHttpDownloader,
      ExtendedEventHandler eventHandler) {
    this(downloadCache, downloader, bzlmodHttpDownloader, eventHandler, /* prefetchRegistryModuleFiles= */ false);
  }

  public DownloadManager(
      DownloadCache downloadCache,
      Downloader downloader,
      HttpDownloader bzlmodHttpDownloader,
      ExtendedEventHandler eventHandler,
      boolean prefetchRegistryModuleFiles) {
    this.downloadCache = downloadCache;
    this.downloader = downloader;
    this.bzlmodHttpDownloader = bzlmodHttpDownloader;
    this.eventHandler = eventHandler;
    this.registryModuleFilePrefetchExecutor =
        prefetchRegistryModuleFiles
            ? MoreExecutors.listeningDecorator(
                Executors.newFixedThreadPool(
                    REGISTRY_MODULE_FILE_PREFETCH_THREADS,
                    new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("bzlmod-registry-prefetch-%d")
                        .build()))
            : null;
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

  public void prefetchRegistryModuleFiles(
      URI registryUri,
      Map<String, String> clientEnv,
      Map<String, Optional<Checksum>> knownFileHashes) {
    if (registryModuleFilePrefetchExecutor == null) {
      return;
    }
    String scheme = registryUri.getScheme();
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      return;
    }
    String registryPrefix = stripTrailingSlash(registryUri.toString()) + "/";
    if (!registryModuleFilePrefetchesStarted.add(registryPrefix)) {
      return;
    }
    for (Entry<String, Optional<Checksum>> entry : knownFileHashes.entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }
      String url = entry.getKey();
      if (!isChecksummedRegistryModuleFileUrl(url, registryPrefix)) {
        continue;
      }
      scheduleRegistryModuleFilePrefetch(URI.create(url), clientEnv, entry.getValue().get());
    }
  }

  @Override
  public void close() {
    if (registryModuleFilePrefetchExecutor == null) {
      return;
    }
    for (ListenableFuture<byte[]> future : registryModuleFilePrefetches.values()) {
      future.cancel(true);
    }
    registryModuleFilePrefetchExecutor.shutdownNow();
  }

  public Future<Path> startDownload(
      ExecutorService executorService,
      List<URI> originalUrls,
      Map<String, List<String>> headers,
      Map<URI, Map<String, List<String>>> authHeaders,
      Optional<Checksum> checksum,
      String canonicalId,
      Optional<String> type,
      Path output,
      Map<String, String> clientEnv,
      String context,
      Phaser downloadPhaser,
      boolean mayHardlink) {
    return executorService.submit(
        () -> {
          if (downloadPhaser.register() != 0) {
            // Not in download phase, must already have been cancelled.
            throw new InterruptedException();
          }
          try (SilentCloseable c = Profiler.instance().profile("fetching: " + context)) {
            return downloadInExecutor(
                originalUrls,
                headers,
                authHeaders,
                checksum,
                canonicalId,
                type,
                output,
                clientEnv,
                context,
                mayHardlink);
          } finally {
            downloadPhaser.arrive();
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
   * @param clientEnv environment variables in shell issuing this command
   * @param context the context in which the file was fetched; used only for reporting
   * @param mayHardlink whether the output is known not to be modified after download and thus may
   *     be created as a hardlink to the cache copy
   * @throws IllegalArgumentException on parameter badness, which should be checked beforehand
   * @throws IOException if download was attempted and ended up failing
   * @throws InterruptedException if this thread is being cast into oblivion
   */
  private Path downloadInExecutor(
      List<URI> originalUrls,
      Map<String, List<String>> headers,
      Map<URI, Map<String, List<String>>> authHeaders,
      Optional<Checksum> checksum,
      String canonicalId,
      Optional<String> type,
      Path output,
      Map<String, String> clientEnv,
      String context,
      boolean mayHardlink)
      throws IOException, InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    // TODO(andreisolo): This code path is inconsistent as the authHeaders are fetched from a
    //  .netrc only if it comes from a http_{archive,file,jar} - and it is handled directly
    //  by Starlark code -, or if a UrlRewriter is present. However, if it comes directly from a
    //  ctx.download{,_and_extract}, this not the case. Should be refactored to handle all .netrc
    //  parsing in one place, in Java code (similarly to #downloadAndReadOneUrl).
    ImmutableList<URI> rewrittenUrls = ImmutableList.copyOf(originalUrls);
    Map<URI, Map<String, List<String>>> rewrittenAuthHeaders = authHeaders;

    if (rewriter != null) {
      ImmutableList<UrlRewriter.RewrittenURL> rewrittenUrlMappings = rewriter.amend(originalUrls);
      rewrittenUrls =
          rewrittenUrlMappings.stream().map(RewrittenURL::url).collect(toImmutableList());
      rewrittenAuthHeaders =
          rewriter.updateAuthHeaders(rewrittenUrlMappings, authHeaders, netrcCreds);
    }

    URI mainUrl; // The "main" URL for this request, used for reporting.
    // The URL used to derive the download's file name. When the rewriter blocks all URLs, this
    // falls back to the first original URL so that its extension (used, e.g., by
    // download_and_extract to infer the archive type) is preserved instead of being lost to the
    // "cacheprobe" placeholder.
    URI fileNameUrl;
    if (rewrittenUrls.isEmpty()) {
      if (type.isPresent() && !Strings.isNullOrEmpty(type.get())) {
        mainUrl = URI.create("http://nonexistent.example.org/cacheprobe." + type.get());
      } else {
        mainUrl = URI.create("http://nonexistent.example.org/cacheprobe");
      }
      fileNameUrl = Iterables.getFirst(originalUrls, mainUrl);
    } else {
      mainUrl = rewrittenUrls.get(0);
      fileNameUrl = mainUrl;
    }
    Path destination = getDownloadDestination(fileNameUrl, type, output);
    ImmutableSet<String> candidateFileNames = getCandidateFileNames(mainUrl, destination);

    // Is set to true if the value should be cached by the checksum value provided
    boolean isCachingByProvidedChecksum = false;

    if (checksum.isPresent()) {
      String cacheKey = checksum.get().toString();
      KeyType cacheKeyType = checksum.get().getKeyType();
      try {
        eventHandler.post(
            new CacheProgress(mainUrl.toString(), "Checking in " + cacheKeyType + " cache"));
        String currentChecksum = DownloadCache.getChecksum(cacheKeyType, destination);
        if (currentChecksum.equals(cacheKey)) {
          // No need to download.
          return destination;
        }
      } catch (IOException e) {
        // Ignore error trying to hash. We'll attempt to retrieve from cache or just download again.
      } finally {
        eventHandler.post(new CacheProgress(mainUrl.toString()));
      }

      if (downloadCache.isEnabled()) {
        isCachingByProvidedChecksum = true;

        try {
          Path cachedDestination =
              downloadCache.get(cacheKey, destination, cacheKeyType, canonicalId, mayHardlink);
          if (cachedDestination != null) {
            // Cache hit!
            eventHandler.post(new DownloadCacheHitEvent(context, cacheKey, mainUrl));
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
              match = DownloadCache.getChecksum(cacheKeyType, candidate).equals(cacheKey);
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
                  downloadCache.put(cacheKey, candidate, cacheKeyType, canonicalId);
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

    for (int attempt = 0; ; ++attempt) {
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
            type,
            context);
        break;
      } catch (InterruptedIOException e) {
        throw new InterruptedException(e.getMessage());
      } catch (IOException e) {
        if (!shouldRetryDownload(e, attempt)) {
          throw e;
        }
      }
    }

    if (isCachingByProvidedChecksum) {
      downloadCache.put(
          checksum.get().toString(), destination, checksum.get().getKeyType(), canonicalId);
    } else if (downloadCache.isEnabled()) {
      var unused = downloadCache.put(destination, KeyType.SHA256, canonicalId);
    }

    return destination;
  }

  private boolean shouldRetryDownload(IOException e, int attempt) {
    if (attempt >= retries) {
      return false;
    }

    if (isRetryableException(e)) {
      return true;
    }

    for (var suppressed : e.getSuppressed()) {
      if (isRetryableException(suppressed)) {
        return true;
      }
    }

    return false;
  }

  private boolean isRetryableException(Throwable e) {
    // HttpConnector already retries connection attempts. Retrying a final ConnectException here
    // repeats its entire backoff sequence.
    return e instanceof ContentLengthMismatchException
        || (e instanceof SocketException && !(e instanceof ConnectException))
        || e instanceof UnknownHostException;
  }

  /**
   * Downloads the contents of one URL and reads it into a byte array.
   *
   * <p>This is only meant to be used for Bzlmod registry downloads as it ignores the value of
   * <code>--repository_disable_download</code>.
   *
   * <p>If the checksum and path to the repository cache is specified, attempt to load the file from
   * the {@link RepositoryCache}. If it doesn't exist, proceed to download the file and load it into
   * the cache prior to returning the value.
   *
   * @param originalUrl the original URL of the file
   * @param clientEnv environment variables in shell issuing this command
   * @param checksum checksum of the file used to verify the content and obtain repository cache
   *     hits
   * @throws IllegalArgumentException on parameter badness, which should be checked beforehand
   * @throws IOException if download was attempted and ended up failing
   * @throws InterruptedException if this thread is being cast into oblivion
   */
  public byte[] downloadAndReadRegistryModuleFile(
      URI originalUrl, Map<String, String> clientEnv, Optional<Checksum> checksum)
      throws IOException, InterruptedException {
    if (registryModuleFilePrefetchExecutor == null || checksum.isEmpty()) {
      return downloadAndReadOneUrlForBzlmod(originalUrl, clientEnv, checksum);
    }
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    Optional<byte[]> cacheHit = getBytesFromCache(originalUrl, checksum);
    if (cacheHit.isPresent()) {
      return cacheHit.get();
    }
    RegistryModuleFilePrefetchKey prefetchKey =
        RegistryModuleFilePrefetchKey.create(originalUrl, checksum);
    if (prefetchKey != null) {
      ListenableFuture<byte[]> future = registryModuleFilePrefetches.get(prefetchKey);
      if (future != null) {
        long waitStartTime = Profiler.instance().nanoTimeMaybe();
        try {
          byte[] content = future.get();
          Profiler.instance()
              .logSimpleTask(
                  waitStartTime,
                  ProfilerTask.BZLMOD,
                  "wait for prefetched registry module file: " + originalUrl);
          return content;
        } catch (CancellationException e) {
          registryModuleFilePrefetches.remove(prefetchKey, future);
          throw new InterruptedException();
        } catch (ExecutionException e) {
          Profiler.instance()
              .logSimpleTask(
                  waitStartTime,
                  ProfilerTask.BZLMOD,
                  "wait for prefetched registry module file: " + originalUrl);
          registryModuleFilePrefetches.remove(prefetchKey, future);
          Throwable cause = e.getCause();
          Throwables.throwIfInstanceOf(cause, InterruptedException.class);
          if (cause instanceof InterruptedIOException interrupted) {
            throw new InterruptedException(interrupted.getMessage());
          }
          Throwables.throwIfUnchecked(cause);
          if (!(cause instanceof IOException)) {
            throw new IllegalStateException(cause);
          }
        }
      }
    }
    return downloadAndReadOneUrlForBzlmodDirect(
        originalUrl, clientEnv, checksum, /* bestEffortCacheWrite= */ false);
  }

  public byte[] downloadAndReadOneUrlForBzlmod(
      URI originalUrl, Map<String, String> clientEnv, Optional<Checksum> checksum)
      throws IOException, InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    Optional<byte[]> cacheHit = getBytesFromCache(originalUrl, checksum);
    if (cacheHit.isPresent()) {
      return cacheHit.get();
    }

    Map<URI, Map<String, List<String>>> authHeaders = ImmutableMap.of();
    ImmutableList<URI> rewrittenUrls = ImmutableList.of(originalUrl);

    if (netrcCreds != null) {
      try {
        Map<String, List<String>> metadata = netrcCreds.getRequestMetadata(originalUrl);
        if (!metadata.isEmpty()) {
          Entry<String, List<String>> headers = metadata.entrySet().iterator().next();
          authHeaders =
              ImmutableMap.of(
                  originalUrl,
                  ImmutableMap.of(headers.getKey(), ImmutableList.of(headers.getValue().get(0))));
        }
      } catch (IOException e) {
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

    byte[] content;
    for (int attempt = 0; ; ++attempt) {
      try {
        content =
            bzlmodHttpDownloader.downloadAndRead(
                rewrittenUrls,
                credentialFactory.create(authHeaders),
                checksum,
                eventHandler,
                clientEnv);
        break;
      } catch (InterruptedIOException e) {
        throw new InterruptedException(e.getMessage());
      } catch (IOException e) {
        if (!shouldRetryDownload(e, attempt)) {
          throw e;
        }
      }
    }
    if (content == null) {
      throw new IllegalStateException("Unexpected error: file should have been downloaded.");
    }

    if (downloadCache.isEnabled()) {
      if (checksum.isPresent()) {
        downloadCache.put(checksum.get().toString(), content, checksum.get().getKeyType());
      } else {
        downloadCache.put(content, KeyType.SHA256);
      }
    }
    return content;
  }

  private Optional<byte[]> getBytesFromCache(URI originalUrl, Optional<Checksum> checksum) {
    if (!downloadCache.isEnabled() || checksum.isEmpty()) {
      return Optional.empty();
    }
    String cacheKey = checksum.get().toString();
    try {
      byte[] content = downloadCache.getBytes(cacheKey, checksum.get().getKeyType());
      if (content != null) {
        eventHandler.post(new DownloadCacheHitEvent(BZLMOD_DOWNLOAD_CONTEXT, cacheKey, originalUrl));
        return Optional.of(content);
      }
    } catch (IOException e) {
      // Ignore error trying to get. We'll just download again.
    } catch (InterruptedException e) {
      // ??
    }
    return Optional.empty();
  }

  private void scheduleRegistryModuleFilePrefetch(
      URI url, Map<String, String> clientEnv, Checksum checksum) {
    RegistryModuleFilePrefetchKey key =
        RegistryModuleFilePrefetchKey.create(url, Optional.of(checksum));
    if (key == null || registryModuleFilePrefetchExecutor == null) {
      return;
    }
    registryModuleFilePrefetches.computeIfAbsent(
        key,
        unused -> {
          long startTime = Profiler.instance().nanoTimeMaybe();
          try {
            ListenableFuture<byte[]> future =
                registryModuleFilePrefetchExecutor.submit(
                    () -> {
                      try (SilentCloseable c =
                          Profiler.instance()
                              .profile(
                                  ProfilerTask.BZLMOD,
                                  () -> "prefetch registry module file: " + url)) {
                        return downloadAndReadOneUrlForBzlmodDirect(
                            url,
                            clientEnv,
                            Optional.of(checksum),
                            /* bestEffortCacheWrite= */ true);
                      }
                    });
            Profiler.instance()
                .logSimpleTask(
                    startTime, ProfilerTask.BZLMOD, "schedule registry module prefetch: " + url);
            return future;
          } catch (RejectedExecutionException e) {
            return null;
          }
        });
  }

  private byte[] downloadAndReadOneUrlForBzlmodDirect(
      URI originalUrl,
      Map<String, String> clientEnv,
      Optional<Checksum> checksum,
      boolean bestEffortCacheWrite)
      throws IOException, InterruptedException {
    Optional<byte[]> cacheHit = getBytesFromCache(originalUrl, checksum);
    if (cacheHit.isPresent()) {
      return cacheHit.get();
    }

    Map<URI, Map<String, List<String>>> authHeaders = ImmutableMap.of();
    ImmutableList<URI> rewrittenUrls = ImmutableList.of(originalUrl);

    if (netrcCreds != null) {
      try {
        Map<String, List<String>> metadata = netrcCreds.getRequestMetadata(originalUrl);
        if (!metadata.isEmpty()) {
          Entry<String, List<String>> headers = metadata.entrySet().iterator().next();
          authHeaders =
              ImmutableMap.of(
                  originalUrl,
                  ImmutableMap.of(headers.getKey(), ImmutableList.of(headers.getValue().get(0))));
        }
      } catch (IOException e) {
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

    byte[] content;
    for (int attempt = 0; ; ++attempt) {
      try {
        content =
            downloader.downloadAndRead(
                rewrittenUrls,
                ImmutableMap.of(),
                credentialFactory.create(authHeaders),
                checksum,
                "",
                eventHandler,
                clientEnv,
                BZLMOD_DOWNLOAD_CONTEXT);
        break;
      } catch (InterruptedIOException e) {
        throw new InterruptedException(e.getMessage());
      } catch (IOException e) {
        if (!shouldRetryDownload(e, attempt)) {
          throw e;
        }
      }
    }
    if (content == null) {
      throw new IllegalStateException("Unexpected error: file should have been downloaded.");
    }

    if (downloadCache.isEnabled()) {
      try {
        if (checksum.isPresent()) {
          downloadCache.put(checksum.get().toString(), content, checksum.get().getKeyType());
        } else {
          downloadCache.put(content, KeyType.SHA256);
        }
      } catch (IOException e) {
        if (!bestEffortCacheWrite) {
          throw e;
        }
      }
    }
    return content;
  }

  private static boolean isChecksummedRegistryModuleFileUrl(String url, String registryPrefix) {
    return url.startsWith(registryPrefix)
        && url.endsWith("/MODULE.bazel")
        && (url.startsWith("http://") || url.startsWith("https://"));
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private record RegistryModuleFilePrefetchKey(URI url, Checksum checksum) {
    @Nullable
    static RegistryModuleFilePrefetchKey create(URI url, Optional<Checksum> checksum) {
      return checksum.map(value -> new RegistryModuleFilePrefetchKey(url, value)).orElse(null);
    }
  }

  @Nullable
  private String getRewriterBlockedAllUrlsMessage(List<URI> originalUrls) {
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

  // The complement of a conservative range of characters that are valid for all reasonable file
  // systems.
  private static final CharMatcher FS_UNSAFE_CHARS =
      CharMatcher.inRange('a', 'z')
          .or(CharMatcher.inRange('A', 'Z'))
          .or(CharMatcher.inRange('0', '9'))
          .or(CharMatcher.anyOf(".-_"))
          .negate();

  private Path getDownloadDestination(URI url, Optional<String> type, Path output) {
    if (!type.isPresent()) {
      return output;
    }
    String basename = MoreObjects.firstNonNull(Strings.emptyToNull(getUrlBaseName(url)), "temp");
    if (!type.get().isEmpty()) {
      String suffix = "." + type.get();
      if (!basename.endsWith(suffix)) {
        basename += suffix;
      }
    }
    // The basename may contain characters that aren't legal in a path with all file systems. Those
    // characters won't matter for type determination.
    return output.getRelative(FS_UNSAFE_CHARS.replaceFrom(basename, '_'));
  }

  /**
   * Determine the list of filenames to look for in the distdirs. Note that an output name may be
   * specified that is unrelated to the primary URL. This happens, e.g., when the parameter output
   * is specified in ctx.download.
   */
  @VisibleForTesting
  static ImmutableSet<String> getCandidateFileNames(URI url, Path destination) {
    String urlBaseName = getUrlBaseName(url);
    if (!Strings.isNullOrEmpty(urlBaseName) && !urlBaseName.equals(destination.getBaseName())) {
      return ImmutableSet.of(urlBaseName, destination.getBaseName());
    } else {
      return ImmutableSet.of(destination.getBaseName());
    }
  }

  private static String getUrlBaseName(URI url) {
    String path = url.getPath();
    if (path == null && url.isOpaque()) {
      // Match URL#getPath() behavior for opaque file URIs such as file:../archive.tgz.
      String rawPath = url.getRawSchemeSpecificPart();
      int queryStart = rawPath.indexOf('?');
      if (queryStart != -1) {
        rawPath = rawPath.substring(0, queryStart);
      }
      path =
          rawPath.isEmpty()
              ? ""
              : URI.create(url.getScheme() + ":" + rawPath).getSchemeSpecificPart();
    }
    return path == null ? "" : PathFragment.create(path).getBaseName();
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
