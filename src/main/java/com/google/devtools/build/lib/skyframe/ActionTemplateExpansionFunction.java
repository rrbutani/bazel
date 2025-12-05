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
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.AlreadyReportedActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.collect.nestedset.ArtifactNestedSetKey;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.skyframe.ActionInputMapHelper;
import com.google.devtools.build.lib.skyframe.ActionInputMetadataProvider;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue.ActionTemplateExpansionKey;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor;
import com.google.devtools.build.lib.skyframe.MetadataConsumerForMetrics;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The SkyFunction for {@link ActionTemplateExpansionValue}.
 *
 * <p>Given an action template, this function resolves its input TreeArtifact, then expands the
 * action template into a list of actions using the expanded {@link TreeFileArtifact}s under the
 * input TreeArtifact.
 */
public class ActionTemplateExpansionFunction implements SkyFunction {
  private final ActionKeyContext actionKeyContext;
  private final SkyframeActionExecutor skyframeActionExecutor;
  private final BlazeDirectories directories;

  @VisibleForTesting
  ActionTemplateExpansionFunction(ActionKeyContext actionKeyContext, SkyframeActionExecutor skyframeActionExecutor, BlazeDirectories directories) {
    this.actionKeyContext = actionKeyContext;
    this.skyframeActionExecutor = skyframeActionExecutor;
    this.directories = directories;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws ActionTemplateExpansionFunctionException, InterruptedException {
    ActionTemplateExpansionKey key = (ActionTemplateExpansionKey) skyKey.argument();
    ActionLookupValue value = (ActionLookupValue) env.getValue(key.getActionLookupKey());
    if (value == null) {
      // Because of the phase boundary separating analysis and execution, all needed
      // ActionLookupValues must have already been evaluated, so a missing ActionLookupValue is
      // unexpected. However, we tolerate this case.
      BugReport.sendBugReport(new IllegalStateException("Unexpected absent value for " + key));
      return null;
    }
    ActionTemplate<?> actionTemplate = value.getActionTemplate(key.getActionIndex());

    ImmutableList.Builder<SkyKey> inputKeys =
        ImmutableList.<SkyKey>builder().addAll(actionTemplate.getInputTreeArtifacts());

    // Following b/143205147, we unwrap the top layer of the NestedSet and evaluate the first layer
    // of the NestedSet as direct Artifact(s) and transitive NestedSet(s).
    if (!actionTemplate.getInputs().isEmpty()) {
      for (Artifact leaf : actionTemplate.getInputs().getLeaves()) {
        inputKeys.add(Artifact.key(leaf));
      }
      for (NestedSet<Artifact> nonLeaf : actionTemplate.getInputs().getNonLeaves()) {
        inputKeys.add(ArtifactNestedSetKey.create(nonLeaf));
      }
    }

    SkyframeLookupResult result = env.getValuesAndExceptions(inputKeys.build());

    // One or more `TreeArtifact`s are not ready yet.
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableList<ActionAnalysisMetadata> actions;
    ActionInputMap actionInputMap = new ActionInputMap(actionTemplate.getInputTreeArtifacts().size());

    try {
      ImmutableList.Builder<TreeFileArtifact> inputTreeFileArtifacts = ImmutableList.builder();
      for (SpecialArtifact inputTreeArtifact : actionTemplate.getInputTreeArtifacts()) {
        SkyValue skyValue = result.getOrThrow(inputTreeArtifact, ActionExecutionException.class);
        TreeArtifactValue treeArtifactValue = (TreeArtifactValue) skyValue;
        inputTreeFileArtifacts.addAll(treeArtifactValue.getChildren()); // would be nice to not have to do this eagerly?

        // only including the TreeArtifact inputs; these are the only inputs
        // that are eligible to be read in `generateAndValidateActionsFromTemplate`...
        ActionInputMapHelper.addToMap(
          actionInputMap, inputTreeArtifact, skyValue, MetadataConsumerForMetrics.NO_OP
        );
      }

      // Assemble path resolver for the input tree artifacts:
      //   - see: https://github.com/bazelbuild/bazel/blob/647bd6b177652f3ff89ee253f436bdcd9d20f71a/src/main/java/com/google/devtools/build/lib/skyframe/ActionExecutionFunction.java#L943-L1063
      //   - see: https://github.com/bazelbuild/bazel/blob/647bd6b177652f3ff89ee253f436bdcd9d20f71a/src/main/java/com/google/devtools/build/lib/skyframe/ActionExecutionFunction.java#L306-L336
      //   - see: https://github.com/bazelbuild/bazel/blob/647bd6b177652f3ff89ee253f436bdcd9d20f71a/src/main/java/com/google/devtools/build/lib/skyframe/ActionExecutionFunction.java#L690-L692
      FileSystem actionFileSystem = null;
      ArtifactPathResolver pathResolver;
      {
        if (this.skyframeActionExecutor.actionFileSystemType().isEnabled()) {
          actionFileSystem = this.skyframeActionExecutor.createActionFileSystem(
            this.directories.getRelativeOutputPath(),
            new ActionInputMetadataProvider(actionInputMap),
            /* outputArtifacts */ null
          );
        }

        pathResolver = ArtifactPathResolver.createPathResolver(
          actionFileSystem, this.skyframeActionExecutor.getExecRoot()
        );

        // TODO: action unwinding? need to check `actionFileSystem`'s
        // `missingInputs` on execption?

        // TODO: should we be asking SkyFrame for each tree artifact file?
        // (should be implied, no?)
      }

      // Expand the action template using the list of expanded input TreeFileArtifacts.
      // TODO(rduan): Add a check to verify the inputs of expanded actions are subsets of inputs
      // of the ActionTemplate.
      actions =
          generateAndValidateActionsFromTemplate(
              actionTemplate, inputTreeFileArtifacts.build(), key, env.getListener(), pathResolver);
    } catch (ActionExecutionException e) {
      env.getListener()
          .handle(
              Event.error(
                  actionTemplate.getOwner().getLocation(),
                  actionTemplate.describe() + " failed: " + e.getMessage()));
      throw new ActionTemplateExpansionFunctionException(
          new AlreadyReportedActionExecutionException(e));
    } catch (ActionConflictException e) {
      e.reportTo(env.getListener());
      throw new ActionTemplateExpansionFunctionException(e);
    }
    try {
      checkActionAndArtifactConflicts(actions, key);
    } catch (ActionConflictException e) {
      e.reportTo(env.getListener());
      throw new ActionTemplateExpansionFunctionException(e);
    } catch (Actions.ArtifactGeneratedByOtherRuleException e) {
      throw new IllegalStateException(
          "Actions generated by template "
              + actionTemplate.describe()
              + " did not all output tree file artifacts belonging to the correct output tree"
              + " artifact + ("
              + skyKey
              + ")",
          e);
    }

    return new ActionTemplateExpansionValue(actions);
  }

  /** Exception thrown by {@link ActionTemplateExpansionFunction}. */
  private static final class ActionTemplateExpansionFunctionException extends SkyFunctionException {
    ActionTemplateExpansionFunctionException(ActionConflictException e) {
      super(e, Transience.PERSISTENT);
    }

    ActionTemplateExpansionFunctionException(ActionExecutionException e) {
      super(e, Transience.PERSISTENT);
    }
  }

  private static ImmutableList<ActionAnalysisMetadata> generateAndValidateActionsFromTemplate(
      ActionTemplate<?> actionTemplate,
      ImmutableList<TreeFileArtifact> inputTreeFileArtifacts,
      ActionTemplateExpansionKey key,
      EventHandler eventHandler,
      ArtifactPathResolver pathResolver)
      throws ActionConflictException, ActionExecutionException, InterruptedException {
    Collection<Artifact> outputs = actionTemplate.getOutputs();
    for (Artifact output : outputs) {
      Preconditions.checkState(
          output.isTreeArtifact(),
          "%s declares an output which is not a tree artifact: %s",
          actionTemplate,
          output);
    }
    ImmutableList<? extends Action> actions =
        actionTemplate.generateActionsForInputArtifacts(inputTreeFileArtifacts, key, eventHandler, pathResolver);
    for (Action action : actions) {
      for (Artifact output : action.getOutputs()) {
        Preconditions.checkState(
            output.getArtifactOwner().equals(key),
            "%s generated an action with an output owned by the wrong owner %s not %s (%s)",
            actionTemplate,
            output.getArtifactOwner(),
            key,
            action);
        Preconditions.checkState(
            output.hasParent(),
            "%s generated an action which outputs a non-TreeFileArtifact %s (%s)",
            actionTemplate,
            output,
            action);
        Preconditions.checkState(
            outputs.contains(output.getParent()),
            "%s generated an action with an output %s under an undeclared tree not in %s (%s)",
            actionTemplate,
            output,
            outputs,
            action);
      }
    }
    return ImmutableList.copyOf(actions); // Just a cast, no copy performed.
  }

  private void checkActionAndArtifactConflicts(
      ImmutableList<ActionAnalysisMetadata> actions, ActionTemplateExpansionKey key)
      throws ActionConflictException,
          InterruptedException,
          Actions.ArtifactGeneratedByOtherRuleException {
    Actions.assignOwnersAndThrowIfConflict(actionKeyContext, actions, key);
    Map<ActionAnalysisMetadata, ActionConflictException> artifactPrefixConflictMap =
        findArtifactPrefixConflicts(getMapForConsistencyCheck(actions));

    if (!artifactPrefixConflictMap.isEmpty()) {
      throw artifactPrefixConflictMap.values().iterator().next();
    }
  }

  private static ImmutableMap<Artifact, ActionAnalysisMetadata> getMapForConsistencyCheck(
      List<? extends ActionAnalysisMetadata> actions) {
    if (actions.isEmpty()) {
      return ImmutableMap.of();
    }
    HashMap<Artifact, ActionAnalysisMetadata> result =
        Maps.newHashMapWithExpectedSize(actions.size() * actions.get(0).getOutputs().size());
    for (ActionAnalysisMetadata action : actions) {
      for (Artifact output : action.getOutputs()) {
        result.put(output, action);
      }
    }
    return ImmutableMap.copyOf(result);
  }

  /**
   * Finds Artifact prefix conflicts between generated artifacts. An artifact prefix conflict
   * happens if one action generates an artifact whose path is a prefix of another artifact's path.
   * Those two artifacts cannot exist simultaneously in the output tree.
   *
   * @param generatingActions a map between generated artifacts and their associated generating
   *     actions.
   * @return a map between actions that generated the conflicting artifacts and their associated
   *     {@link ActionConflictException}.
   */
  private static Map<ActionAnalysisMetadata, ActionConflictException> findArtifactPrefixConflicts(
      Map<Artifact, ActionAnalysisMetadata> generatingActions) {
    return Actions.findArtifactPrefixConflicts(
        new MapBasedImmutableActionGraph(generatingActions), generatingActions.keySet());
  }

  private static class MapBasedImmutableActionGraph implements ActionGraph {
    private final Map<Artifact, ActionAnalysisMetadata> generatingActions;

    MapBasedImmutableActionGraph(Map<Artifact, ActionAnalysisMetadata> generatingActions) {
      this.generatingActions = ImmutableMap.copyOf(generatingActions);
    }

    @Nullable
    @Override
    public ActionAnalysisMetadata getGeneratingAction(Artifact artifact) {
      return generatingActions.get(artifact);
    }
  }
}

// alternatively: get `StarlarkMapActionTemplate` to use `discoverInputs`?
//   - i.e. model as an `ActionExecutionFunction` (SkyFunction) instead of an
//     `ActionTemplateExpansionFunction` (SkyFunction)...
//   - otoh `discoverInputs` is way more general an interface... we don't need
//     the ability to list arbitrary artifacts as inputs (or really, even list
//     any additional artifacts; just to read the contents of artifacts in
//     TreeArtifacts that the `ActionTemplate` machinery has already listed as
//     inputs)

/*


how are inputs read in `discoverInputs`?
  - `SkyframeExecutor` ctor sets up `skyframeActionExecutor`
    + https://github.com/bazelbuild/bazel/blob/1af38400f71225e37a79d7edc42bfa2d76c926ca/src/main/java/com/google/devtools/build/lib/skyframe/SkyframeExecutor.java#L724-L733
    +
  - `SkyframeExecutor.skyFunctions` registers `ACTION_EXECUTION` SkyFunction
    + https://github.com/bazelbuild/bazel/blob/1af38400f71225e37a79d7edc42bfa2d76c926ca/src/main/java/com/google/devtools/build/lib/skyframe/SkyframeExecutor.java#L930
    + in `newActionExecutionFunction`: https://github.com/bazelbuild/bazel/blob/1af38400f71225e37a79d7edc42bfa2d76c926ca/src/main/java/com/google/devtools/build/lib/skyframe/SkyframeExecutor.java#L983-L993
      * `skyframeActionExecutor` = `skyframeActionExecutor`
  - `SkyframeExecutor.buildArtifacts` -> `prepareSkyframeActionExecutorForExecution`
    + https://github.com/bazelbuild/bazel/blob/1af38400f71225e37a79d7edc42bfa2d76c926ca/src/main/java/com/google/devtools/build/lib/skyframe/SkyframeExecutor.java#L1916-L1917
    +
  - ActionExecutionFunction.compute (SkyFunction) ->
    + ActionExecutionFunction.computeInternal ->
      * `state.actionFilesystem` set to `skyframeActionExecutor.createActionFileSystem(...)` -> `outputService.createActionFileSystem(...)`
        - https://github.com/bazelbuild/bazel/blob/647bd6b177652f3ff89ee253f436bdcd9d20f71a/src/main/java/com/google/devtools/build/lib/skyframe/ActionExecutionFunction.java#L331-L337
        - see: https://github.com/bazelbuild/bazel/blob/427040e8d5f8bab67e590494a440fdc97b7fe46b/src/main/java/com/google/devtools/build/lib/skyframe/SkyframeActionExecutor.java#L457-L464
    + ActionExecutionFunction.checkCacheAndExecuteIfNeeded ->
    + skyframeActionExecutor.discoverInputs
      * https://github.com/bazelbuild/bazel/blob/647bd6b177652f3ff89ee253f436bdcd9d20f71a/src/main/java/com/google/devtools/build/lib/skyframe/ActionExecutionFunction.java#L740
        - discoverInputs driver: https://github.com/bazelbuild/bazel/blob/427040e8d5f8bab67e590494a440fdc97b7fe46b/src/main/java/com/google/devtools/build/lib/skyframe/SkyframeActionExecutor.java#L913
          + takes `FileSystem` arg `state.actionFileSystem` (from `InputDiscoveryState`)
      ....
  - `Action.discoverInputs`; i.e. for `LtoBackendAction`
    + https://github.com/bazelbuild/bazel/blob/cebdb9130a237e1909312279706acbf218f198ac/src/main/java/com/google/devtools/build/lib/rules/cpp/LtoBackendAction.java#L214-L215
    + makes `ActionExecutionContext` out of... a bunch of state including `actionFileSystem`
  - `ActionExecutionContext.getInputPath`
    + https://github.com/bazelbuild/bazel/blob/b0b5497749eb1550c96d92c52f477689e07dca6f/src/main/java/com/google/devtools/build/lib/actions/ActionExecutionContext.java#L401-L403
  - `ArtifactPathResolver.toPath`; in this case `ArtifactPathResolver` is:
    + `ArtifactPathResolver.createPathResolver`: https://github.com/bazelbuild/bazel/blob/b0b5497749eb1550c96d92c52f477689e07dca6f/src/main/java/com/google/devtools/build/lib/actions/ActionExecutionContext.java#L273-L275
      * i.e. `TransformResolver`: https://github.com/bazelbuild/bazel/blob/3beaaaf23e4f6e9071ef7eabced7d64513573e80/src/main/java/com/google/devtools/build/lib/actions/ArtifactPathResolver.java#L49-L61


*/
