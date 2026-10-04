
list:
  <!-- - "Rewind repo fetches for files lost outside action execution": https://github.com/bazelbuild/bazel/pull/30465 -->
  <!-- - "Rename external to bazel-external, add a symlink": https://github.com/bazelbuild/bazel/pull/30584 -->
  <!-- - "Add `{rctx,mctx}.copy`": https://github.com/bazelbuild/bazel/pull/31461 -->
  - "bazel: add a GraalVM native-image server": https://github.com/bazelbuild/bazel/pull/30282
    + plus dzbarsky follow ups:
      * 17df4cece1bc320b3a9abf51adb945923f367b8c
  - "Add automatic OS trust store (cacerts) detection to Bazel C++ client": 3d52724e735544f11140c65221c49753c8f73d2e.
  - "Add `external_directory` info item": https://github.com/bazelbuild/bazel/pull/31170
  - "Add an opt-in native POSIX test wrapper": https://github.com/bazelbuild/bazel/pull/30837
  - "Add path mapping support for `cc_common`": https://github.com/bazelbuild/bazel/pull/30789
  - "Add pre-execution input discovery for StarlarkAction unused_inputs_list": https://github.com/bazelbuild/bazel/pull/29193
  - "remote: avoid tiny zstd upload blocks": https://github.com/bazelbuild/bazel/pull/31528

  <!-- everything below this list is not really a cherry-pick; our own patches  -->
  - `--experimental_prefetch_registry_module_files`: [`copilot/dev-prefetch-checksummed-bzlmod-files`](https://github.com/rrbutani/bazel/commits/copilot/dev-prefetch-checksummed-bzlmod-files/)
  - `--experimental_remote_repo_contents_cache_prefetch_regex`: [`experiment/rrcc-custom-prefetch-knob`](https://github.com/rrbutani/bazel/commits/experiment/rrcc-custom-prefetch-knob)
  - RRCC misc: [`experiment/rrcc-info-and-opts`](https://github.com/rrbutani/bazel/commits/experiment/rrcc-info-and-opts); has:
    + info on what induces full repo materialization (w/RRCC)
    + tweaks to avoid full materialization for some `module_ctx`/`repository_ctx` functions
  - `--experimental_include_reproducible_module_extensions_in_lockfile`: [`experiment/force-mod-ext-in-lockfile`](https://github.com/rrbutani/bazel/commits/experiment/force-mod-ext-in-lockfile)

  - [unbreak the hermetic linux sandbox](https://github.com/bazelbuild/bazel/pull/31527): [`fix/hermetic-linux-sandbox`](https://github.com/rrbutani/bazel/commits/fix/hermetic-linux-sandbox)

release command:
```bash
DATE="$(date +%Y%m%d)"
REV="$(git rev-parse HEAD)"

BUILD_BINARIES=1 \
XDG_CACHE_HOME=/tmp/bcache3 \
BENCHMARK_REF=upstream/master \
STAMP_TAG=10.0.0.pre.${DATE}.${REV} \
  ./graalvm_native_pgo_report.sh

echo "based on: $(git merge-base upstream/master HEAD)"
echo "make tag: bazel-10.0.0.pre.${DATE}.${REV}"
```
