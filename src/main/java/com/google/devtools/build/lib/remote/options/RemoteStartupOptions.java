// Copyright 2025 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.remote.options;

import com.google.devtools.common.options.Converters.RegexPatternConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClass;
import com.google.devtools.common.options.RegexPatternOption;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Additional startup options provided by the {@link
 * com.google.devtools.build.lib.remote.RemoteModule}.
 */
@OptionsClass
public abstract class RemoteStartupOptions extends OptionsBase {
  @Option(
      name = "experimental_remote_repo_contents_cache",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          """
          If enabled, the remote cache will be used to store the results of reproducible repository
          rules. If a repository rule needs to be evaluated and its result is already in the remote
          cache, the contents of the repository will be kept in an in-memory file system and are
          only downloaded when needed, either by Bazel itself or an action that runs locally.
          """)
  public abstract boolean getUseRemoteRepoContentsCache();

  @Option(
      name = "experimental_remote_repo_contents_cache_prefetch_regex",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      converter = RegexPatternConverter.class,
      help =
          """
          Patterns for additional files to prefetch when a repository is lazily
          materialized using a remote cache (i.e. backed by an in-memory
          filesystem; see `--experimental_remote_repo_contents_cache`).

          By default only `.bzl` files within repositories are prefetched.

          Prefetching files whose contents are known to be needed during
          loading/analysis can yield speedups.
          """)
  public abstract List<RegexPatternOption> getRemoteRepoContentsCachePrefetchRegex();
}
