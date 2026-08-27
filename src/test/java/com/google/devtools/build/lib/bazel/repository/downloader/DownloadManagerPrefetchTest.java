// Copyright 2026 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for registry file prefetching in {@link DownloadManager}. */
@RunWith(JUnit4.class)
public final class DownloadManagerPrefetchTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final ExtendedEventHandler eventHandler = mock(ExtendedEventHandler.class);
  private final JavaIoFileSystem fileSystem = new JavaIoFileSystem(DigestHashFunction.SHA256);
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @After
  public void tearDown() {
    executor.shutdownNow();
  }

  @Test
  public void prefetchRegistryFiles_skipsIneligibleEntriesAndUsesGeneralDownloader()
      throws Exception {
    Downloader generalDownloader = mock(Downloader.class);
    HttpDownloader legacyBzlmodDownloader = mock(HttpDownloader.class);
    byte[] bytes = "module(name='foo')".getBytes(UTF_8);
    when(generalDownloader.downloadAndRead(
            any(), anyMap(), any(), any(), eq(""), eq(eventHandler), anyMap(), any()))
        .thenReturn(bytes);
    try (DownloadManager downloadManager =
        newDownloadManager(new DownloadCache(), generalDownloader, legacyBzlmodDownloader, true)) {
      Checksum checksum = sha256("module(name='foo')");
      downloadManager.prefetchRegistryFiles(
          URI.create("https://registry.example/base"),
          ImmutableMap.of(),
          ImmutableMap.of(
              "https://registry.example/base/modules/foo/1.0/MODULE.bazel",
              Optional.of(checksum),
              "https://registry.example/base/modules/foo/1.0/source.json",
              Optional.of(checksum),
              "https://registry.example/base/modules/foo/metadata.json",
              Optional.of(checksum),
              "https://registry.example/base/modules/foo/1.0/patches/a.patch",
              Optional.of(checksum),
              "https://registry.example/base/bazel_registry.json",
              Optional.of(checksum),
              "https://other.example/base/modules/bar/1.0/MODULE.bazel",
              Optional.of(checksum),
              "file:///tmp/registry/modules/baz/1.0/MODULE.bazel",
              Optional.of(checksum),
              "https://registry.example/base/modules/missing/1.0/MODULE.bazel",
              Optional.empty()));

      verify(generalDownloader, timeout(2000).times(1))
          .downloadAndRead(
              eq(ImmutableList.of(URI.create("https://registry.example/base/modules/foo/1.0/MODULE.bazel"))),
              anyMap(),
              any(),
              eq(Optional.of(checksum)),
              eq(""),
              eq(eventHandler),
              anyMap(),
              eq("Bazel module fetching"));
      verify(generalDownloader, timeout(2000).times(1))
          .downloadAndRead(
              eq(ImmutableList.of(URI.create("https://registry.example/base/modules/foo/1.0/source.json"))),
              anyMap(),
              any(),
              eq(Optional.of(checksum)),
              eq(""),
              eq(eventHandler),
              anyMap(),
              eq("Bazel module fetching"));
      verify(generalDownloader, timeout(2000).times(1))
          .downloadAndRead(
              eq(ImmutableList.of(URI.create("https://registry.example/base/modules/foo/metadata.json"))),
              anyMap(),
              any(),
              eq(Optional.of(checksum)),
              eq(""),
              eq(eventHandler),
              anyMap(),
              eq("Bazel module fetching"));
      verify(generalDownloader, timeout(2000).times(1))
          .downloadAndRead(
              eq(ImmutableList.of(URI.create("https://registry.example/base/bazel_registry.json"))),
              anyMap(),
              any(),
              eq(Optional.of(checksum)),
              eq(""),
              eq(eventHandler),
              anyMap(),
              eq("Bazel module fetching"));
      verifyNoInteractions(legacyBzlmodDownloader);
    }
  }

  @Test
  public void downloadAndReadRegistryFile_reusesInFlightPrefetchForConcurrentDemand()
      throws Exception {
    Downloader generalDownloader = mock(Downloader.class);
    HttpDownloader legacyBzlmodDownloader = mock(HttpDownloader.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    byte[] bytes = "module(name='foo')".getBytes(UTF_8);
    doAnswer(
            invocation -> {
              calls.incrementAndGet();
              started.countDown();
              release.await();
              return bytes;
            })
        .when(generalDownloader)
        .downloadAndRead(any(), anyMap(), any(), any(), any(), any(), anyMap(), any());

    URI url = URI.create("https://registry.example/modules/foo/1.0/MODULE.bazel");
    Checksum checksum = sha256("module(name='foo')");
    try (DownloadManager downloadManager =
        newDownloadManager(new DownloadCache(), generalDownloader, legacyBzlmodDownloader, true)) {
      downloadManager.prefetchRegistryFiles(
          URI.create("https://registry.example"),
          ImmutableMap.of(),
          ImmutableMap.of(url.toString(), Optional.of(checksum)));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      Future<byte[]> first =
          executor.submit(
              () ->
                  downloadManager.downloadAndReadRegistryFile(
                      url, ImmutableMap.of(), Optional.of(checksum)));
      Future<byte[]> second =
          executor.submit(
              () ->
                  downloadManager.downloadAndReadRegistryFile(
                      url, ImmutableMap.of(), Optional.of(checksum)));

      release.countDown();
      assertThat(first.get()).isEqualTo(bytes);
      assertThat(second.get()).isEqualTo(bytes);
      assertThat(calls.get()).isEqualTo(1);
    }
  }

  @Test
  public void downloadAndReadRegistryFile_cacheHitAvoidsDownloader() throws Exception {
    Downloader generalDownloader = mock(Downloader.class);
    HttpDownloader legacyBzlmodDownloader = mock(HttpDownloader.class);
    DownloadCache downloadCache = new DownloadCache();
    downloadCache.setPath(fileSystem.getPath(temporaryFolder.newFolder("cache").getAbsolutePath()));
    byte[] bytes = "module(name='foo')".getBytes(UTF_8);
    Checksum checksum = sha256("module(name='foo')");
    downloadCache.put(checksum.toString(), bytes, checksum.getKeyType());

    URI url = URI.create("https://registry.example/modules/foo/1.0/MODULE.bazel");
    try (DownloadManager downloadManager =
        newDownloadManager(downloadCache, generalDownloader, legacyBzlmodDownloader, true)) {
      downloadManager.prefetchRegistryFiles(
          URI.create("https://registry.example"),
          ImmutableMap.of(),
          ImmutableMap.of(url.toString(), Optional.of(checksum)));

      assertThat(
              downloadManager.downloadAndReadRegistryFile(
                  url, ImmutableMap.of(), Optional.of(checksum)))
          .isEqualTo(bytes);
      verifyNoInteractions(generalDownloader);
      verifyNoInteractions(legacyBzlmodDownloader);
    }
  }

  @Test
  public void downloadAndReadOneUrlForBzlmod_cacheHitAvoidsDownloader() throws Exception {
    Downloader generalDownloader = mock(Downloader.class);
    HttpDownloader legacyBzlmodDownloader = mock(HttpDownloader.class);
    DownloadCache downloadCache = new DownloadCache();
    downloadCache.setPath(fileSystem.getPath(temporaryFolder.newFolder("cache-direct").getAbsolutePath()));
    byte[] bytes = "module(name='foo')".getBytes(UTF_8);
    Checksum checksum = sha256("module(name='foo')");
    downloadCache.put(checksum.toString(), bytes, checksum.getKeyType());

    URI url = URI.create("https://registry.example/modules/foo/1.0/MODULE.bazel");
    try (DownloadManager downloadManager =
        newDownloadManager(downloadCache, generalDownloader, legacyBzlmodDownloader, true)) {
      assertThat(
              downloadManager.downloadAndReadOneUrlForBzlmod(
                  url, ImmutableMap.of(), Optional.of(checksum)))
          .isEqualTo(bytes);
      verifyNoInteractions(generalDownloader);
      verifyNoInteractions(legacyBzlmodDownloader);
    }
  }

  @Test
  public void downloadAndReadRegistryFile_failedPrefetchFallsBackToFreshDownload()
      throws Exception {
    Downloader generalDownloader = mock(Downloader.class);
    HttpDownloader legacyBzlmodDownloader = mock(HttpDownloader.class);
    byte[] bytes = "module(name='foo')".getBytes(UTF_8);
    when(generalDownloader.downloadAndRead(any(), anyMap(), any(), any(), any(), any(), anyMap(), any()))
        .thenThrow(new IOException("prefetch failed"))
        .thenReturn(bytes);

    URI url = URI.create("https://registry.example/modules/foo/1.0/MODULE.bazel");
    Checksum checksum = sha256("module(name='foo')");
    try (DownloadManager downloadManager =
        newDownloadManager(new DownloadCache(), generalDownloader, legacyBzlmodDownloader, true)) {
      downloadManager.prefetchRegistryFiles(
          URI.create("https://registry.example"),
          ImmutableMap.of(),
          ImmutableMap.of(url.toString(), Optional.of(checksum)));

      assertThat(
              downloadManager.downloadAndReadRegistryFile(
                  url, ImmutableMap.of(), Optional.of(checksum)))
          .isEqualTo(bytes);
      verify(generalDownloader, times(2))
          .downloadAndRead(any(), anyMap(), any(), any(), any(), any(), anyMap(), any());
    }
  }

  @Test
  public void downloadAndReadOneUrlForBzlmod_usesLegacyBzlmodDownloader() throws Exception {
    Downloader generalDownloader = mock(Downloader.class);
    HttpDownloader legacyBzlmodDownloader = mock(HttpDownloader.class);
    byte[] bytes = "module(name='foo')".getBytes(UTF_8);
    when(legacyBzlmodDownloader.downloadAndRead(any(), any(), any(), any(), anyMap()))
        .thenReturn(bytes);

    URI url = URI.create("https://registry.example/modules/foo/1.0/MODULE.bazel");
    Checksum checksum = sha256("module(name='foo')");
    try (DownloadManager downloadManager =
        newDownloadManager(new DownloadCache(), generalDownloader, legacyBzlmodDownloader, true)) {
      assertThat(
              downloadManager.downloadAndReadOneUrlForBzlmod(
                  url, ImmutableMap.of(), Optional.of(checksum)))
          .isEqualTo(bytes);
      verify(legacyBzlmodDownloader)
          .downloadAndRead(
              eq(ImmutableList.of(url)), any(), eq(Optional.of(checksum)), eq(eventHandler), anyMap());
      verifyNoInteractions(generalDownloader);
    }
  }

  @Test
  public void close_cancelsOutstandingPrefetches() throws Exception {
    Downloader generalDownloader = mock(Downloader.class);
    HttpDownloader legacyBzlmodDownloader = mock(HttpDownloader.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              started.countDown();
              try {
                while (true) {
                  Thread.sleep(1000);
                }
              } catch (InterruptedException e) {
                interrupted.countDown();
                throw new InterruptedIOException("interrupted");
              }
            })
        .when(generalDownloader)
        .downloadAndRead(any(), anyMap(), any(), any(), any(), any(), anyMap(), any());

    URI url = URI.create("https://registry.example/modules/foo/1.0/MODULE.bazel");
    Checksum checksum = sha256("module(name='foo')");
    DownloadManager downloadManager =
        newDownloadManager(new DownloadCache(), generalDownloader, legacyBzlmodDownloader, true);
    downloadManager.prefetchRegistryFiles(
        URI.create("https://registry.example"),
        ImmutableMap.of(),
        ImmutableMap.of(url.toString(), Optional.of(checksum)));
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    downloadManager.close();

    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThrows(
        InterruptedException.class,
        () ->
            downloadManager.downloadAndReadRegistryFile(
                url, ImmutableMap.of(), Optional.of(checksum)));
    verify(legacyBzlmodDownloader, never())
        .downloadAndRead(any(), any(), any(), any(), any(), any(), anyMap(), any());
  }

  @Test
  public void downloadAndReadRegistryFile_reusesInFlightPrefetchForSourceJson() throws Exception {
    Downloader generalDownloader = mock(Downloader.class);
    HttpDownloader legacyBzlmodDownloader = mock(HttpDownloader.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    byte[] bytes = "{\"url\":\"https://example.com/archive.zip\"}".getBytes(UTF_8);
    doAnswer(
            invocation -> {
              calls.incrementAndGet();
              started.countDown();
              release.await();
              return bytes;
            })
        .when(generalDownloader)
        .downloadAndRead(any(), anyMap(), any(), any(), any(), any(), anyMap(), any());

    URI url = URI.create("https://registry.example/modules/foo/1.0/source.json");
    Checksum checksum = sha256("{\"url\":\"https://example.com/archive.zip\"}");
    try (DownloadManager downloadManager =
        newDownloadManager(new DownloadCache(), generalDownloader, legacyBzlmodDownloader, true)) {
      downloadManager.prefetchRegistryFiles(
          URI.create("https://registry.example"),
          ImmutableMap.of(),
          ImmutableMap.of(url.toString(), Optional.of(checksum)));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      Future<byte[]> demand =
          executor.submit(
              () ->
                  downloadManager.downloadAndReadRegistryFile(
                      url, ImmutableMap.of(), Optional.of(checksum)));

      release.countDown();
      assertThat(demand.get()).isEqualTo(bytes);
      assertThat(calls.get()).isEqualTo(1);
    }
  }

  private DownloadManager newDownloadManager(
      DownloadCache downloadCache,
      Downloader generalDownloader,
      HttpDownloader legacyBzlmodDownloader,
      boolean enablePrefetch) {
    return new DownloadManager(
        downloadCache, generalDownloader, legacyBzlmodDownloader, eventHandler, enablePrefetch);
  }

  private static Checksum sha256(String value) throws Checksum.InvalidChecksumException {
    return Checksum.fromString(
        DownloadCache.KeyType.SHA256, Hashing.sha256().hashString(value, UTF_8).toString());
  }
}
