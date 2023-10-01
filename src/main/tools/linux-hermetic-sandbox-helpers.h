#pragma once

// Public interface for `linux-hermetic-sandbox-helpers.rs`.

#include <cassert>
#include <cstddef>
#include <string>
#include <vector>

extern "C" {
    void handle_mounts(
        const char* sandbox_base_path,
        std::size_t sandbox_base_path_len,
        const char* const* bind_mount_sources_array,
        const char* const* bind_mount_dests_array,
        std::size_t bind_mount_count, // length of the bind mount sources and dests arrays
        const char* const* hard_exclude_paths_array,
        std::size_t hard_exclude_paths_array_len,
        const char* const* soft_exclude_paths_array,
        std::size_t soft_exclude_paths_array_len
    );
};

namespace helpers {
    inline void handle_mounts(
        const std::string& sandbox_base_path,
        const std::vector<char*>& bind_mount_sources,
        const std::vector<char*>& bind_mount_targets,
        const std::vector<char*>& hard_exclude_paths,
        const std::vector<char*>& soft_exclude_paths
    ) {
        assert(bind_mount_sources.size() == bind_mount_targets.size());

        ::handle_mounts(
            sandbox_base_path.c_str(), sandbox_base_path.length(),
            bind_mount_sources.data(), bind_mount_targets.data(), bind_mount_sources.size(),
            hard_exclude_paths.data(), hard_exclude_paths.size(),
            soft_exclude_paths.data(), soft_exclude_paths.size()
        );
    }
}
