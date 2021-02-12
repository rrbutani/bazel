// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import static java.util.stream.Collectors.joining;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.BazelModuleContext;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ToolchainException;
import com.google.devtools.build.lib.skyframe.UnloadedToolchainContext;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainContextApi;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * Represents the data needed for a specific target's use of toolchains and platforms, including
 * specific {@link ToolchainInfo} providers for each required toolchain type.
 */
@AutoValue
@Immutable
@ThreadSafe
public abstract class ResolvedToolchainContext implements ToolchainContextApi, ToolchainContext {

  /**
   * Finishes preparing the {@link ResolvedToolchainContext} by finding the specific toolchain
   * providers to be used for each toolchain type.
   */
  public static ResolvedToolchainContext load(
      ImmutableMap<RepositoryName, RepositoryName> repoMapping,
      UnloadedToolchainContext unloadedToolchainContext,
      String targetDescription,
      Iterable<ConfiguredTargetAndData> toolchainTargets)
      throws ToolchainException {

    ImmutableMap.Builder<ToolchainTypeInfo, ToolchainInfo> toolchains =
        new ImmutableMap.Builder<>();
    ImmutableList.Builder<TemplateVariableInfo> templateVariableProviders =
        new ImmutableList.Builder<>();
    for (ConfiguredTargetAndData target : toolchainTargets) {
      // Aliases are in toolchainTypeToResolved by the original alias label, not via the final
      // target's label.
      Label discoveredLabel = target.getConfiguredTarget().getOriginalLabel();

      for (ToolchainTypeInfo toolchainType :
          unloadedToolchainContext.toolchainTypeToResolved().inverse().get(discoveredLabel)) {
        ToolchainInfo toolchainInfo = PlatformProviderUtils.toolchain(target.getConfiguredTarget());

        // If the toolchainType hadn't been resolved to an actual target, resolution would have
        // failed with an error much earlier. However, the target might still not be an actual
        // toolchain.
        if (toolchainType != null) {
          if (toolchainInfo != null) {
            toolchains.put(toolchainType, toolchainInfo);
          } else {
            throw new TargetNotToolchainException(toolchainType, discoveredLabel);
          }
        }

        // Find any template variables present for this toolchain.
        TemplateVariableInfo templateVariableInfo =
            target.getConfiguredTarget().get(TemplateVariableInfo.PROVIDER);
        if (templateVariableInfo != null) {
          templateVariableProviders.add(templateVariableInfo);
        }
      }
    }

    return new AutoValue_ResolvedToolchainContext(
        // super:
        unloadedToolchainContext.key(),
        unloadedToolchainContext.executionPlatform(),
        unloadedToolchainContext.targetPlatform(),
        unloadedToolchainContext.requiredToolchainTypes(),
        unloadedToolchainContext.resolvedToolchainLabels(),
        // this:
        repoMapping,
        targetDescription,
        unloadedToolchainContext.requestedLabelToToolchainType(),
        toolchains.build(),
        templateVariableProviders.build());
  }

  /** Returns the repository mapping applied by the Starlark 'in' operator to string-form labels. */
  abstract ImmutableMap<RepositoryName, RepositoryName> repoMapping();

  /** Returns the repository mapping from {@link repoMapping} with an extra entry that remaps `@`
   * ({@link RepositoryName.MAIN}) to the repository of the callee.
   *
   *  This is necessary so that string-form labels that begin with `//` are resolved to the repo where
   *  the `label in ctx.toolchains` expression is and not in `@//`.
   */
  private ImmutableMap<RepositoryName, RepositoryName> adjustedRepoMapping() {
    // As `Label` does, we get the module of the calling function from the call stack and use that
    // to determine which repo `@` means for this label.
    //
    // This particular edge case (string-form labels used with `ctx.toolchains`) is the reason for
    // the unforunate `getCurrentThread` function on `StarlarkThread`. Because the "current repo"
    // of the expression involving `ctx.toolchains` is a property that's *not* inherent to the
    // ToolchainContext, we can't use the workaround that was used to resolve aliases in string-form
    // labels with toolchain contexts (i.e. finding all the aliases of a toolchain ahead of time —
    // `requestedToolchainTypeLabels`).
    //
    // In order to tell where the expression involving `ctx.toolchains` that we're currently
    // evaluating is located, we need more information than is available to us. In particular, the
    // current stack frame or at the very least, the module that the last function call is located in.
    //
    // Rather than modify `StarlarkIndexable` and `EvalUtils.binaryOp` to thread this additional
    // information through and deal with the fallout that would cause, the current solution just goes
    // and sticks the current `StarlarkThread` in a thread local static variable on the `StarlarkThread`
    // class. An inelegant hack, no doubt.
    //
    // A less egregious (but breaking) change would be to have the key for `ctx.toolchains` required to
    // be a `Label` instead of a string. This way, the `//` case gets handled by the logic in `Label`
    // (logic that we're copying here anyways) and we don't have to introduce
    // `StarlarkThread.getCurrentThread` or make far reaching changes to the codebase. (`Label` manages
    // to avoid needing to introduce hacks like this because it's fairly special-cased as is.)
    StarlarkThread thread = StarlarkThread.getCurrentThread();
    Label module = BazelModuleContext.of(Module.ofInnermostEnclosingStarlarkFunction(thread)).label();

    return new ImmutableMap.Builder()
        .putAll(repoMapping())
        .put(RepositoryName.MAIN, module.getRepository())
        .build();
  }

  /** Returns a description of the target being used, for error messaging. */
  abstract String targetDescription();

  /** Sets the map from requested {@link Label} to toolchain type provider. */
  abstract ImmutableMap<Label, ToolchainTypeInfo> requestedToolchainTypeLabels();

  abstract ImmutableMap<ToolchainTypeInfo, ToolchainInfo> toolchains();

  /** Returns the template variables that these toolchains provide. */
  public abstract ImmutableList<TemplateVariableInfo> templateVariableProviders();

  /**
   * Returns the toolchain for the given type, or {@code null} if the toolchain type was not
   * required in this context.
   */
  @Nullable
  public ToolchainInfo forToolchainType(Label toolchainTypeLabel) {
    ToolchainTypeInfo toolchainTypeInfo = requestedToolchainTypeLabels().get(toolchainTypeLabel);
    if (toolchainTypeInfo == null) {
      return null;
    }
    return toolchains().get(toolchainTypeInfo);
  }

  @Nullable
  public ToolchainInfo forToolchainType(ToolchainTypeInfo toolchainType) {
    return toolchains().get(toolchainType);
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<toolchain_context.resolved_labels: ");
    printer.append(
        toolchains().keySet().stream()
            .map(ToolchainTypeInfo::typeLabel)
            .map(Label::toString)
            .collect(joining(", ")));
    printer.append(">");
  }

  private static Label transformKey(
      Object key, ImmutableMap<RepositoryName, RepositoryName> repoMapping) throws EvalException {
    if (key instanceof Label) {
      return (Label) key;
    } else if (key instanceof ToolchainTypeInfo) {
      return ((ToolchainTypeInfo) key).typeLabel();
    } else if (key instanceof String) {
      try {
        return Label.parseAbsolute((String) key, repoMapping);
      } catch (LabelSyntaxException e) {
        throw Starlark.errorf("Unable to parse toolchain label '%s': %s", key, e.getMessage());
      }
    } else {
      throw Starlark.errorf(
          "Toolchains only supports indexing by toolchain type, got %s instead",
          Starlark.type(key));
    }
  }

  @Override
  public ToolchainInfo getIndex(StarlarkSemantics semantics, Object key) throws EvalException {
    Label toolchainTypeLabel = transformKey(key, adjustedRepoMapping());

    if (!containsKey(semantics, key)) {
      // TODO(bazel-configurability): The list of available toolchain types is confusing in the
      // presence of aliases, since it only contains the actual label, not the alias passed to the
      // rule definition.
      throw Starlark.errorf(
          "In %s, toolchain type %s was requested but only types [%s] are configured",
          targetDescription(),
          toolchainTypeLabel,
          requiredToolchainTypes().stream()
              .map(ToolchainTypeInfo::typeLabel)
              .map(Label::toString)
              .collect(joining(", ")));
    }
    return forToolchainType(toolchainTypeLabel);
  }

  @Override
  public boolean containsKey(StarlarkSemantics semantics, Object key) throws EvalException {
    Label toolchainTypeLabel = transformKey(key, adjustedRepoMapping());
    return requestedToolchainTypeLabels().containsKey(toolchainTypeLabel);
  }

  /**
   * Exception used when a toolchain type is required but the resolved target does not have
   * ToolchainInfo.
   */
  static final class TargetNotToolchainException extends ToolchainException {
    TargetNotToolchainException(ToolchainTypeInfo toolchainType, Label resolvedTargetLabel) {
      super(
          String.format(
              "toolchain type %s resolved to target %s, but that target does not provide "
                  + ToolchainInfo.STARLARK_NAME,
              toolchainType.typeLabel(),
              resolvedTargetLabel));
    }

    @Override
    protected Code getDetailedCode() {
      return Code.MISSING_PROVIDER;
    }
  }
}
