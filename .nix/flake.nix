{
  inputs = {
    nixpkgs.url = github:NixOS/nixpkgs;
    flake-utils.url = github:numtide/flake-utils;
  };
  outputs = { nixpkgs, flake-utils, ... }: flake-utils.lib.eachDefaultSystem (sys: let
    np = nixpkgs.legacyPackages.${sys};
  in {
    devShells.default = np.mkShell {
      nativeBuildInputs = with np; [
        bazel_6 openjdk
        bazel-buildtools # for buildozer

        # jetbrains.idea-community

        clang-tools_16 # clangd
      ];

      # see: https://github.com/Mic92/nix-ld
      #
      # `external/remote_java_tools_linux/java_tools/ijar/ijar` is a prebuilt
      # binary that bazel pulls in during its build; it needs `libstdc++.so`.
      NIX_LD_LIBRARY_PATH = np.lib.makeLibraryPath [
        np.stdenv.cc.cc
        np.zlib # bin/jlink needs this (in: `//src:embedded_jdk_minimal`)

        # `//src:embedded_tools_jdk_minimal` invokes a prebuilt Python that
        # wants `libcrypt.so.1`; ideally we'd swap out the Python instance
        # altogether (for a nixpkgs supplied one) but... this is less effort
        np.libxcrypt-legacy
      ];
      #
      # cannot read from store paths (without IFD?) in pure eval mode..; we read
      # this in shell hook instead.
      # NIX_LD = np.lib.fileContents "${np.stdenv.cc}/nix-support/dynamic-linker";

      shellHook = ''
        echo $PWD
        export NIX_LD="$(cat ${np.stdenv.cc}/nix-support/dynamic-linker | tr -d '\n')"
        export BAZEL_PATH="${
          np.lib.makeSearchPathOutput "out" "bin" (with np; [
            coreutils
            bash
            gnutar
            gzip
            findutils
            gnused
            zip unzip
            gnugrep
            stdenv.cc
            python3
          ])
        }"

        if [[ -f WORKSPACE ]]; then
          cat <<EOF > user.bazelrc
        # essentially lifted from the nixpkgs bazel package's definition; needed
        # to get bazel to use the bundled jdk
        build --java_runtime_version=local_jdk # nix
        build --extra_toolchains=@local_jdk//:all
        build --tool_java_runtime_version=local_jdk

        build --incompatible_strict_action_env=true

        build --action_env=NIX_LD=$NIX_LD
        build --action_env=NIX_LD_LIBRARY_PATH=$NIX_LD_LIBRARY_PATH
        build --action_env=PATH=$BAZEL_PATH

        build --host_action_env=NIX_LD=$NIX_LD
        build --host_action_env=NIX_LD_LIBRARY_PATH=$NIX_LD_LIBRARY_PATH
        build --host_action_env=PATH=$BAZEL_PATH
        EOF

          cat <<EOF > bzl
        #!/usr/bin/env bash
        export NIX_LD="$NIX_LD"
        export NIX_LD_LIBRARY_PATH="$NIX_LD_LIBRARY_PATH"
        exec "$(realpath .)"/bazel-bin/src/bazel "\$@"
        EOF
          chmod +x bzl
        fi
      '';
    };
  });
}
