// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.filegroup;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.analysis.OutputGroupInfo.INTERNAL_SUFFIX;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.PrerequisiteArtifacts;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.gson.annotations.SerializedName;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * ConfiguredTarget for "filegroup".
 */
public class Filegroup implements RuleConfiguredTargetFactory {

  /** Allows users to suppress soundness warnings for directory source artifacts.
   *
   * Only applies to files in `srcs` of the `filegroup`; does not apply to file
   * sources that are included in the filegroup transitively.
  */
  private static final String ALLOW_UNSOUND_DIRECTORY_SOURCES_TAG = "allow-unsound-directory-sources-in-direct-srcs";

  /** Error message for output groups that are explicitly forbidden from filegroup reference. */
  public static final String ILLEGAL_OUTPUT_GROUP_ERROR =
      "Output group %s is not permitted for " + "reference in filegroups.";

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    String outputGroupName = ruleContext.attributes().get("output_group", Type.STRING);
    BuildConfigurationValue configuration = checkNotNull(ruleContext.getConfiguration());
    if (outputGroupName.endsWith(INTERNAL_SUFFIX)) {
      ruleContext.throwWithAttributeError(
          "output_group", String.format(ILLEGAL_OUTPUT_GROUP_ERROR, outputGroupName));
    }

    NestedSet<Artifact> filesToBuild =
        outputGroupName.isEmpty()
            ? PrerequisiteArtifacts.nestedSet(ruleContext, "srcs")
            : getArtifactsForOutputGroup(outputGroupName, ruleContext.getPrerequisites("srcs"));
    boolean has_directory_source_exemption =
        ruleContext
          .attributes()
          .get("tags", Type.STRING_LIST)
          .contains(ALLOW_UNSOUND_DIRECTORY_SOURCES_TAG);
    if (has_directory_source_exemption) {
      // TODO: do better re-assembling the NestedSet here...
      NestedSetBuilder<Artifact> newFilesToBuild = NestedSetBuilder.stableOrder();
      ImmutableSet<Label> directDeps =  ImmutableSet.copyOf(
        (List<Label>)ruleContext.attributes().get("srcs", BuildType.LABEL_LIST)
      );

      // TODO: what's a better way to do this?
      //
      // We only want to apply the exemption to source artifacts that are
      // "direct" sources of this filegroup; i.e.:
      // ```
      // filegroup(
      //   name = "a", srcs = ["dir", ":other"], tags = ["<exempt-tag>"],
      // )
      // filegroup(
      //   name = "other", srcs = ["other-dir"],
      // )
      // ```
      //
      // in the above, `other-dir` should *not* be exempted
      //
      // unfortunately for us, source artifacts are their own owners — once we
      // ask `other` for it's `FileProvider` files we lose that `other-dir` is a
      // dep of `a` via the `other` filegroup

      for (Artifact file : filesToBuild.toList()) {
        ArtifactOwner owner = file.getArtifactOwner();

        if (directDeps.contains(owner.getLabel()) && file instanceof SourceArtifact) {
          SourceArtifact sourceFile = (SourceArtifact)file;
          sourceFile.setUntrackedDirectoryExemption(true);

          newFilesToBuild.add(sourceFile);
        } else {
          newFilesToBuild.add(file);
        }
      }

      filesToBuild = newFilesToBuild.build();
    }

    // Handle excludes, if present:
    var pathAttr = ruleContext.attributes().get("path", Type.STRING);
    if (!pathAttr.isEmpty()) {
      ExcludeInfo excludeInfo;
      try {
        excludeInfo = ExcludeInfo.fromJson(pathAttr);
      } catch (JsonParseException exc) {
        throw ruleContext.throwWithAttributeError(
          "path", "error parsing JSON payload: " + exc.toString()
        );
      }

      boolean empty = excludeInfo.hardExcludes.isEmpty() && excludeInfo.softExcludes.isEmpty();

      // These are only value if there's a single source and no data elements
      // in the file group:
      if (!empty && !filesToBuild.isSingleton()) {
        throw ruleContext.throwWithRuleError(
          "hard and soft excludes can only be specified when there " +
          "is a *single* directory source in `srcs`; we got " + filesToBuild.toList().size() +
          " sources"
        );
      }

      Artifact single = filesToBuild.getSingleton();
      Label label = single.getArtifactOwner().getLabel();
      if (!single.isSourceArtifact()) {
        throw ruleContext.throwWithAttributeError("srcs",
          "hard and soft excludes can only be specified when there " +
          "is a single *directory source* in `srcs`; " + label.toString() +
          " is not a source artifact"
        );
      }

      // TODO: check that this is a directory...
      // System.out.println(single.getExecPathString());
      // System.out.println(single.getRootRelativePathString());
      // System.out.println(Label.getContainingDirectory(label));

      SourceArtifact dir = (SourceArtifact)single;
      excludeInfo.hardExcludes.forEach((h) -> dir.addHardExclude(h));
      excludeInfo.softExcludes.forEach((s) -> dir.addSoftExclude(s));

      filesToBuild = NestedSetBuilder.create(Order.STABLE_ORDER, dir);
    }

    InstrumentedFilesInfo instrumentedFilesProvider =
        InstrumentedFilesCollector.collect(
            ruleContext,
            // Seems strange to have "srcs" in "dependency attributes" instead of "source
            // attributes", but that's correct behavior here because this rule just forwards
            // files, it doesn't process them. It doesn't know if the dependencies of the stuff
            // in srcs is a runtime dependency of its consumers or not. Consumers decide which
            // of the following is the case about a filegroup it depends on based on whether the
            // attribute the dependency is via is in the consumer's source attributes or
            // dependency attributes:
            // * If the filegroup contains coverage-relevant source files, it should be depended
            //   on via something in source attributes. The dependencies for actions which generate
            //   source files are generally not runtime dependencies.
            // * If the dependencies of the filegroup might be coverage-relevant source files (e.g.
            //   a binary target is included in filegroup's srcs and the filegroup target is
            //   included in some other target's data), it should be depended on via something in
            //   dependency attributes.
            new InstrumentationSpec(FileTypeSet.ANY_FILE).withDependencyAttributes("srcs", "data"),
            /* reportedToActualSources= */ NestedSetBuilder.create(Order.STABLE_ORDER));

    RunfilesProvider runfilesProvider =
        RunfilesProvider.withData(
            new Runfiles.Builder(
                    ruleContext.getWorkspaceName(), configuration.legacyExternalRunfiles())
                .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES)
                .build(),
            // If you're visiting a filegroup as data, then we also visit its data as data.
            new Runfiles.Builder(
                    ruleContext.getWorkspaceName(), configuration.legacyExternalRunfiles())
                .addTransitiveArtifacts(filesToBuild)
                .addDataDeps(ruleContext)
                .build());

    RuleConfiguredTargetBuilder builder =
        new RuleConfiguredTargetBuilder(ruleContext)
            .addProvider(RunfilesProvider.class, runfilesProvider)
            .setFilesToBuild(filesToBuild)
            .setRunfilesSupport(null, getExecutable(filesToBuild))
            .addNativeDeclaredProvider(instrumentedFilesProvider)
            .addNativeDeclaredProvider(new FilegroupPathProvider(getFilegroupPath(ruleContext)));

    return builder.build();
  }

  /**
   * Returns the single Artifact from filesToBuild or {@code null} if there are multiple elements.
   */
  @Nullable
  private Artifact getExecutable(NestedSet<Artifact> filesToBuild) {
    return filesToBuild.isSingleton() ? filesToBuild.getSingleton() : null;
  }

  private PathFragment getFilegroupPath(RuleContext ruleContext) {
    String attr = ruleContext.attributes().get("path", Type.STRING);
    if (attr.isEmpty()) {
      return PathFragment.EMPTY_FRAGMENT;
    } else {
      return ruleContext.getLabel().getPackageIdentifier().getSourceRoot().getRelative(attr);
    }
  }

  /** Returns the artifacts from the given targets that are members of the given output group. */
  private static NestedSet<Artifact> getArtifactsForOutputGroup(
      String outputGroupName, List<? extends TransitiveInfoCollection> deps) {
    NestedSetBuilder<Artifact> result = NestedSetBuilder.stableOrder();

    for (TransitiveInfoCollection dep : deps) {
      OutputGroupInfo outputGroupInfo = OutputGroupInfo.get(dep);
      if (outputGroupInfo != null) {
        result.addTransitive(outputGroupInfo.getOutputGroup(outputGroupName));
      }
    }

    return result.build();
  }
}

// NOTE: these excludes are specified via the `path` argument, within a JSON
// blob.
//
// This *is* distasteful and ideally we'd have new attributes for these but we
// it to be easy for users to maintain compatiblity with Bazel releases that
// don't have this patch.
//
// Normally we'd use `execution_properties` but those aren't permitted on
// filegroups. `path` already existed and was unused. Another option would have
// been to abuse tags (i.e. scan for tags with a prefix and then a payload) but
// that seems even worse.
//
// NOTE: we require specifying the single source that these excludes are
// relative to within `srcs` rather than within our `path` payload so that we do
// not have to reinvent logic to create a `SourceArtifact` for that path and
// verify that is exists, etc.
//
// NOTE: this silently allows extra fields... not ideal
class ExcludeInfo {
  /** List of relative paths indicating which paths to exclude when creating
   ** bind mounts for the hermetic linux sandbox.
  *
  * This attribute is to be specified within the `path` argument's stringified
  * (JSON) payload; it should correspond to a value that is a list of strings.
  *
  * This attribute can only be specified when there is a single value in `srcs`
  * that corresponds to a source directory. The given relative paths will be
  * relative to the resolved (realpath) of this directory.
  *
  * When not using the hermetic linux sandbox, this attribute has no effect.
  *
  * "Hard" excludes instruct the sandbox to rearrange the other bind mounts such
  * that no file or directory is present at the excluded path. In the case of
  * deeply nested excludes and/or excludes with ancestor directories containing
  * many immediate children, this can be expensive. See the comments on
  * {@link SourceArtifact#getHardExcludes} for details.
  *
  */
  @SerializedName("hermetic-sandbox-bind-mount-hard-excludes")
  final ArrayList<PathFragment> hardExcludes;

  /** Like {@link ExcludeInfo#hardExcludes} but for "soft" excludes.
   *
   * See the comments on {@link SourceArtifact#getSoftExcludes} for details.
   */
  @SerializedName("hermetic-sandbox-bind-mount-soft-excludes")
  final ArrayList<PathFragment> softExcludes;

  ExcludeInfo() {
    this.hardExcludes = new ArrayList<PathFragment>();
    this.softExcludes = new ArrayList<PathFragment>();
  }

  static ExcludeInfo fromJson(String source) throws JsonParseException {
    var gsonBuilder = new GsonBuilder().setPrettyPrinting();
    gsonBuilder.registerTypeAdapter(
      PathFragment.class,
      new JsonDeserializer<PathFragment>() {
        public PathFragment deserialize(JsonElement elem, java.lang.reflect.Type _typeOfT, JsonDeserializationContext _ctx)
          throws JsonParseException
        {
          // Note: normalized `..`s out internally; this is incorrect in the
          // presence of symlinks
          PathFragment frag = PathFragment.create(elem.getAsJsonPrimitive().getAsString());
          if (frag.isAbsolute()) {
            throw new JsonParseException(
              "exclude paths must be relative but an absolute path was given: " + frag.toString()
            );
          }

          if (frag.containsUplevelReferences()) {
            throw new JsonParseException(
              "exclude paths cannot contain `..`: " + frag.toString()
            );
          }

          return frag;
        }
      }
    );

    return gsonBuilder.create().fromJson(source, ExcludeInfo.class);
  }
}
