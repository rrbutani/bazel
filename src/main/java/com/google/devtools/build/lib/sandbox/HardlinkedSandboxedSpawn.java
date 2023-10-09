// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.sandbox;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * Creates an execRoot for a Spawn that contains input files as hardlinks to their original
 * destination.
 */
public class HardlinkedSandboxedSpawn extends AbstractContainerizingSandboxedSpawn {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private boolean sandboxDebug = false;

  public HardlinkedSandboxedSpawn(
      Path sandboxPath,
      Path sandboxExecRoot,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      SandboxInputs inputs,
      SandboxOutputs outputs,
      Set<Path> writableDirs,
      TreeDeleter treeDeleter,
      @Nullable Path sandboxDebugPath,
      @Nullable Path statisticsPath,
      boolean sandboxDebug,
      String mnemonic) {
    super(
        sandboxPath,
        sandboxExecRoot,
        arguments,
        environment,
        inputs,
        outputs,
        writableDirs,
        treeDeleter,
        sandboxDebugPath,
        statisticsPath,
        mnemonic);
    this.sandboxDebug = sandboxDebug;
  }

  @Override
  public void filterInputsAndDirsToCreate(
      Set<PathFragment> inputsToCreate, LinkedHashSet<PathFragment> dirsToCreate)
      throws IOException, InterruptedException
  {
    // With the "hardlinked" sandbox spawner used by the hermetic linux sandbox,
    // we represent artifacts with symlinks.
    //
    // This poses problems for directory (tree) artifacts for which we also have
    // artifacts beneath; i.e. if an action uses `foo/bar/` (a directory) and
    // also uses `foo/bar/baz.sh`, we have a problem:
    //   - if we create the symlink for `foo/bar` first we'll run into issues
    //     creating `foo/bar/baz.sh` (it'd be beneath a symlink)
    //   - if we create `foo/bar/baz.sh` first, now we can't create the symlink
    //     for `foo/bar`: we'd be trying to replace a directory with a symlink
    //
    // For *external* source artifacts it's probably a safe bet that if we're
    // depending on a directory and artifacts within the directory, we can
    // safely elide the artifacts within the directory (i.e. in the above we
    // should be able to just map `foo/bar` into the sandbox without having any
    // impact on the action).
    //
    // `SandboxHelpers.createDirectories` (see:
    // `AbstractContainerizingSandboxedSpawn`'s `createFileSystem` method) is
    // aware that there may be "nested" artifacts like this (due to directory
    // artifacts) but it does not seem to mitigate against this; it only goes as
    // far as ensuring that we get EEXISTS if we run into this scenario.
    //
    // So, here we elide "more specific" artifacts but only when:
    //   - using the hermetic sandbox (i.e. the "hardlinked" sandbox spawner —
    //     haven't yet renamed this)
    //   - the artifacts are beneath `external`
    //     + somewhat arbitrary restriction; really we want to gate on _source_
    //       artifacts but that's a little hard to determine in this context
    //     + edit: nvm, we'll have this be unrestricted for now

    // contains the paths of every input and all its ancestors
    //
    // the value (bool) indicates whether the path corresponds to a *directory*
    // (false) or an input (true)
    //
    // the reason we store ancestor paths as entries is to make the lookup that
    // checks whether a new input is beneath any existing paths cheaper; instead
    // of needing to walk all the up to the new input path's root, it can now
    // stop once it hits any known path (either another input in which case it
    // conflicts or a directory in which case it is okay)
    //
    // really this is a job for a tree-based structure (at the very least to
    // save memory/allocations) but we'll leave that optimization for another
    // day (TODO)
    Map<PathFragment, Boolean> knownPaths = new HashMap<>(inputsToCreate.size() * 5);

    // to filter inputs, we'll:
    //   - sort our inputs, least to most deep
    //     + this is how we ensure that we catch all conflicts
    //   - add each input and all its parents, until it hits a known path
    List<PathFragment> sortedInputs = new ArrayList<>(inputsToCreate);
    sortedInputs.sort(Comparator.naturalOrder());

    input_loop: for (PathFragment inp : sortedInputs) {
      PathFragment origInp = inp;

      // since our inputs come from a set we can be sure this path isn't already
      // in `knownPaths`/
      //
      // even if this input ultimately is elided its fine for this to be in
      // knownPaths (since its value (true) is still accurate — no inputs with
      // paths beneath this path will be allowed)
      knownPaths.put(inp, true);

      // at this point we don't know if this input will be preserved so we don't
      // add to `knownPaths` just yet
      //
      // i.e. we'll add all of these paths but we don't know if we'll be setting
      // their value to `true` (conflicts with something already) or `false`
      // (okay, just a directory)
      List<PathFragment> ancestorPaths = new ArrayList<>(inp.segmentCount());

      while (!inp.isEmpty()) {
        inp = inp.getParentDirectory();

        var val = knownPaths.get(inp);
        if (val == null) {
          // not yet reached a parent path, add and keep going
          ancestorPaths.add(inp);
        } else if (val == true) {
          // conflict!
          logger.atWarning().log(
            "input that would be mapped to `%s` conflicts with other inputs; eliding...", origInp
          );

          // remove this path from `inputsToCreate`:
          var removed = inputsToCreate.remove(origInp);
          assert removed;

          // mark all ancestors as not permitting children:
          for (var a : ancestorPaths) { knownPaths.put(a, true); }

          // skip to the next input:
          continue input_loop;
        } else if (val == false) {
          // reached a parent directory, all is well
          break;
        }
      }

      // if we've exhausted our input, all is well: we hit no conflicts so this
      // input path can stay
      for (var a : ancestorPaths) { knownPaths.put(a, false); }
    }

    // same process for directories but with a twist: directories do not
    // prohibit other (nested) directories from being created
    //
    // this removes the need for sorting:
    dirsToCreate.removeIf((d) -> {
      var origDir = d;
      var ancestors = new ArrayList<PathFragment>(d.segmentCount());
      var canKeep = true;

      while (!d.isEmpty()) {
        var val = knownPaths.get(d);
        if (val == null) {
          ancestors.add(d);
        } else if (val == true) {
          // conflict, can't add
          logger.atWarning().log(
            "directory that would be created at `%s` conflicts with other inputs; eliding...", origDir
          );

          canKeep = false;
          break;
        } else if (val == false) {
          // hit parent dir, can add!
          break;
        }

        d = d.getParentDirectory();
      }

      for (PathFragment a : ancestors) { knownPaths.put(a, canKeep); }

      return !canKeep;
    });

    // TODO: sandbox re-use? see `SymlinkedSandboxedSpawn`
  }


  // source is an in-sandbox path that's bind-mounted in
  // target is an absolute path (prefixed with the absolute path of the sandbox
  // dir) to where the file should land...
  //
  // the issue here is that `source` is an in-sandbox path...
  // `hardLinkRecursive` will try to make hard links or copies _before_ we enter
  // the sandbox
  //
  // for now let's just make a symlink to the in-sandbox path of the file.
  // we probably want to prefer hardlinks or bindmounts but we cannot do either
  // of those with the information we have here (we do not know the actual path
  // of `source` on the host file system... this is not an issue for symlinks
  // because they are effectively "late bound"; i.e. they can dangle)
  //
  // because we do not know the path of `source` we also cannot check and
  // resolve symlinks! this is unfortunate but it'll have to do for now.
  @Override
  protected void copyFile(Path source, Path target) throws IOException {
    // hardLinkRecursive(source, target);

    // the symlink created will only be valid within the sandbox; it points at
    // paths that are bind-mounted into the sandbox and *not* present on the
    // host
    if (this.sandboxDebug) {
      logger.atInfo().log(
        "symlink: sandbox:%s -> %s", source, target.relativeTo(this.sandboxPath));
    }

    target.createSymbolicLink(source);

    /*
    // issues arise when our target is a directory that already exists (because
    // there's another artifact that's beneath said directory)
    //
    // we'd need our own form of splatting to resolve this properly...
    //
    // instead we'll just delete the existing directory and hope that we cover
    // it's contents with the symlink we're making:
    try {
      target.createSymbolicLink(source);
    } catch (IOException e) {
      if (target.exists(Symlinks.NOFOLLOW) && target.isDirectory()) {
        target.deleteTree();
        target.createSymbolicLink(source);
      } else {
        throw e;
      }
    }
    // target.getFileSystem().createSy
    */
  }

  /**
   * Recursively creates hardlinks for all files in {@code source} path, in {@code target} path.
   * Symlinks are resolved. If files is located on another disk, hardlink will fail and a copy will
   * be made instead. Throws IllegalArgumentException if source path is a subdirectory of target
   * path.
   */
  private void hardLinkRecursive(Path source, Path target) throws IOException {
    if (source.isSymbolicLink()) {
      source = source.resolveSymbolicLinks();
    }

    if (source.isFile(Symlinks.NOFOLLOW)) {
      try {
        source.createHardLink(target);
      } catch (IOException e) {
        if (sandboxDebug) {
          logger.atInfo().log(
              "File %s could not be hardlinked, file will be copied instead.", source);
        }
        FileSystemUtils.copyFile(source, target);
      }
    } else if (source.isDirectory()) {
      if (source.startsWith(target)) {
        throw new IllegalArgumentException(source + " is a subdirectory of " + target);
      }
      target.createDirectory();
      Collection<Path> entries = source.getDirectoryEntries();
      for (Path entry : entries) {
        Path toPath = target.getChild(entry.getBaseName());
        hardLinkRecursive(entry, toPath);
      }
    }
  }
}
