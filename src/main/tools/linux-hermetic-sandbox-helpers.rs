use std::{
    borrow::Cow,
    collections::{HashMap, hash_map::Entry},
    ffi::{CStr, OsStr},
    fmt,
    fs,
    os::{unix::prelude::OsStrExt, raw::c_char},
    path::{Path, Component, Components},
    sync::atomic::{AtomicUsize, Ordering}, cmp,
};

use nix::{NixPath, dir, fcntl::{OFlag, self}, sys::stat::Mode, unistd, errno::Errno};

use crate::{utils::{
    CStrNewType, OsStrDisplayExt, cstring_as_path, CStringGrowablePath,
    ScopedPathAddition, debug, if_debug,
}, fs_utils::Kind};

mod utils {
    use std::{
        fs::File,
        fmt,
        io::Write,
        os::{fd::BorrowedFd, unix::prelude::OsStrExt},
        sync::OnceLock,
        panic::Location, ffi::{CStr, OsStr}, path::Path, borrow::Borrow, ops::Deref, mem,
    };

    use nix::time::{self, ClockId};

    extern "C" {
        /// From `./logging.h`:
        static global_debug: *mut libc::FILE;
    }

    pub fn if_debug<R>(func: impl FnOnce() -> R) -> Option<R> {
        // We want to optimize for the common case (not running under debug
        // mode) so we do this check first (so that we can bail quicker).
        if (!unsafe { global_debug }.is_null()) || cfg!(test) /* enable for tests */ {
            Some(func())
        } else {
            None
        }
    }

    /// Supposed to mimic `DEBUG` from `./logging.h`.
    #[track_caller]
    #[cfg_attr(test, allow(unreachable_code))]
    pub fn debug(func: impl FnOnce(&mut dyn std::io::Write)) {
        let loc = Location::caller();
    if_debug::<()>(move || {
        #[cfg(test)]
        {
            // when running tests, include the debug output; put it on stderr:
            //
            // note: we're using `eprintln!` rather than writing to
            // `std::io::stderr()` directly so that our output is captured by
            // the `libtest` stderr capture machinery
            let mut out = Vec::new();
            func(&mut out);
            eprintln!(
                "debug from {file}:{line}:\n  {msg}",
                file = loc.file(), line = loc.line(),
                msg = std::str::from_utf8(&out).unwrap(),
            );

            return;
        }

        static DEBUG_FILE: OnceLock<File> = OnceLock::new();

        if DEBUG_FILE.get().is_none() {
            // We want to be able to turn this into a `File` so we can use
            // the usual `io::Write` infrastructure but as far as I can tell
            // there's no clean way to have a `File` backed by a _borrowed_
            // fd (i.e. one that it does not try to close on Drop).
            //
            // So we go and duplicate the underlying file descriptor.
            //
            // This isn't great (and we should probably only do this once
            // and stick this in a lazy_cell or something? we can be pretty
            // sure `global_debug` on the C++ side will only be assigned to
            // once) but this isn't on the critical path so it doesn't
            // really matter much.
            //
            // update: using a static...

            // Safety: we're assuming we're single-threaded...
            let fd: BorrowedFd = unsafe {
                BorrowedFd::borrow_raw(libc::fileno(global_debug))
            };
            let fd = fd.try_clone_to_owned().unwrap();
            let debug_output = File::from(fd);
            DEBUG_FILE.set(debug_output).unwrap();
        }

        let out: &mut &File = &mut DEBUG_FILE.get().unwrap();

        // Timestamp and file location; matches `DEBUG`:
        {
            let curr = time::clock_gettime(ClockId::CLOCK_REALTIME).unwrap();
            write!(
                out, "{}.{:09}: {file}:{line}: ",
                curr.tv_sec(), curr.tv_nsec(),
                file = loc.file(), line = loc.line(),
            ).unwrap()
        }

        func(out);
        <&File as Write>::flush(out).unwrap();
    }); }

    #[macro_export]
    macro_rules! debug {
        ($($tt:tt)*) => {
            $crate::utils::debug(|o| {
                writeln!(o, $($tt)*).unwrap()
            })
        }
    }

    #[macro_export]
    macro_rules! warn {
        ($fmt_string:literal $($tt:tt)*) => {
            // bold, purple
            debug!(::std::concat!("\x1b[1m\x1b[35mWARNING\x1b[0m: ", $fmt_string) $($tt)* )
        };
    }

    // zero copy
    pub fn cstring_as_path(path: &CStr) -> &Path {
        OsStr::from_bytes(path.to_bytes()).as_ref()
    }

    // the Debug impl for `OsStr` types quotes the output...
    //
    // calling `to_str` is expensive (`O(len)` for UTF-8 validation) but it is
    // okay: we only expect to use this for debugging
    pub struct OsStrDisplay<'o>(&'o OsStr);
    impl fmt::Display for OsStrDisplay<'_> {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            if let Some(utf8) = self.0.to_str() {
                f.write_str(utf8)
            } else {
                write!(f, "{:?}", self.0)
            }
        }
    }

    pub trait OsStrDisplayExt { fn display(&self) -> OsStrDisplay<'_>; }
    impl OsStrDisplayExt for OsStr {
        fn display(&self) -> OsStrDisplay<'_> { OsStrDisplay(self) }
    }
    impl OsStrDisplayExt for CStr {
        fn display(&self) -> OsStrDisplay<'_> {
            OsStrDisplay(OsStr::from_bytes(self.to_bytes()))
        }
    }

    /// To form the destination path of the bind mounts, we need to prepend the
    /// sandbox base.
    ///
    /// This means allocating and copying. This is in contrast to the source
    /// path of the bind mounts for which we can (generally -- splatting is the
    /// exception) simply pass the [`CStr`] we received straight through to the
    /// mount syscall.
    ///
    /// This type is aimed at amortizing the cost of these allocations/copies by
    /// reusing a shared buffer and taking advantage of the fact that we walk
    /// the bind mount target directory structure with DFS, allowing us to push
    /// and pop path segments as we go.
    ///
    /// Functionally this type is a little like a [`PathBuf`] that you can get a
    /// [`CStr`] out of.
    ///
    /// NOTE: This type is very much Unix specific and makes no attempt to
    /// accomodate Windows paths.
    pub struct CStringGrowablePath {
        allocation: Vec<u8>,
        segment_lengths: Vec<usize>,
    }
    impl fmt::Debug for CStringGrowablePath {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            f.debug_tuple("CStringGrowablePath")
                .field(&self.as_c_str())
                .finish()
        }
    }

    impl CStringGrowablePath {
        // default allocation size hint:
        const MAX_EXPECTED_PATH_LEN_IN_BYTES: usize = libc::PATH_MAX as _;

        fn ensure_null_termination(&mut self) {
            match self.allocation.spare_capacity_mut() {
                [first, ..] => {
                    first.write(b'\0');
                },
                _ => {
                    // need to grow!
                    self.allocation.reserve(1);
                    self.ensure_null_termination()
                }
            }
        }

        /// Assumes base is an absolute path that:
        ///  - begins with `/`
        ///  - contains no `.`, `..`
        ///  - does *not* end with `/`
        ///  - contains no NUL bytes
        ///
        /// Note that empty strings are allowed.
        ///
        /// `intend_to_extend`:
        ///  - we have two main use cases for this type:
        ///    + destination paths that we construct as we walk the mount map
        ///      during _application_
        ///    + representing explicit source paths (i.e. bind mounts where
        ///      `dest != source`) when the mount they belong to is ["splatted"]
        ///      — in this case we need to append segments onto the original
        ///        [`CStr`] source path that we are given
        ///
        /// ["splatted"]: TODO
        pub fn new(base: impl AsRef<OsStr>, intend_to_extend: bool) -> Self {
            let base: &OsStr = base.as_ref();

            if !base.as_bytes().is_empty() {
                debug_assert_eq!(base.as_bytes()[0], b'/');
                debug_assert_ne!(base.as_bytes().last().unwrap(), &b'/');
            }

            let mut allocation = Vec::with_capacity(
                if intend_to_extend { Self::MAX_EXPECTED_PATH_LEN_IN_BYTES }
                else { base.len() * 2 }
            );
            allocation.extend(base.as_bytes());

            Self {
                allocation,
                // Note: elements of base don't contribute to segments! can't
                // pop them
                //
                // Note: we start with a zero sized allocation
                segment_lengths: Vec::with_capacity(0),
            }
        }

        // assumes segment does not start with a path separator and does not
        // contain `.`, `..`, or more than one path segment, and is not the
        // empty string, and does not contain NUL bytes
        pub fn push<'seg>(&mut self, segment: &'seg OsStr) {
            self.push_untracked(segment);
            self.segment_lengths.push(segment.as_bytes().len());
        }

        // Like `push` (requires a path segment, etc.) but does not register a
        // new pop-able entry with the growable path.
        //
        // `pop` will return `(the previous segment) + "/" + segment`
        pub fn extend_last(&mut self, segment: &OsStr) {
            self.push_untracked(segment)
        }

        // for [`ScopedPathAddition`]; cheaper since we don't need to
        // use `update_segment_lengths`
        fn push_untracked<'seg>(&mut self, segment: &'seg OsStr) {
            self.allocation.push(b'/');

            let seg = segment.as_bytes();
            self.allocation.extend(seg);

            // ideally we'd call this just in `as_c_str` but we want that method
            // to only take `&self`...
            self.ensure_null_termination();
        }

        // panics if there are no segments to pop
        pub fn pop(&mut self) {
            let last_len = self.segment_lengths.pop().unwrap();
            self.pop_untracked(last_len)
        }

        // for [`ScopedPathAddition`]; cheaper since we don't need to
        // use `update_segment_lengths`
        //
        // len is in *bytes*
        fn pop_untracked<'seg>(&mut self, last_segment_length: usize) {
            let last_segment_length = last_segment_length + 1; // path separator

            self.allocation.truncate(self.allocation.len() - last_segment_length);

            // ideally we'd call this just in `as_c_str` but we want that method
            // to only take `&self`...
            self.ensure_null_termination();
        }


        pub fn as_c_str(&self) -> &CStr {
            let allocation = self.allocation.as_slice();
            // SAFETY: `ensure_null_termination` makes this safe.
            let allocation_with_nul = unsafe {
                std::slice::from_raw_parts(allocation.as_ptr(), allocation.len() + 1)
            };

            // SAFETY: we can be sure that the slice is terminated by a nul but
            // we actually cannot be sure that safe usage of the API of this
            // type will not introduce other nuls into `allocation`...
            //
            // ultimately, I think we are okay with this, for now; we can be
            // reasonably certain that we will not run into this in practice
            // (these paths -- when not using param files -- come from command
            // line arguments which cannot include nuls; the conversion to
            // `std::string` on the C++ side will catch this anyways)
            unsafe {
                // Note: we construct a slice first and use this function
                // instead of using `from_ptr` because `from_ptr` invokes
                // `strlen`. We already know the length of the string so this is
                // unnecessary.
                CStr::from_bytes_with_nul_unchecked(allocation_with_nul)
            }
        }
    }

    /// Helper for [`CStringGrowablePath`] that associates a path segment added
    /// to [`CStringGrowablePath`] with a scope.
    ///
    /// This is useful for users that traverse paths recursively and wish to
    /// record the current path as they do so.
    ///
    /// This type essentially just makes sure you don't forget to call `pop`
    /// (and prevents you from calling `push` without constructing an instance
    /// of this type).
    ///
    /// Use [`CStringGrowablePath::scoped`] to construct an instance of this.
    pub struct ScopedPathAddition<'c, 'a> {
        inner: &'c mut CStringGrowablePath,
        addition: Option<&'a OsStr>,
    }
    impl<'c, 'a> Drop for ScopedPathAddition<'c, 'a> {
        fn drop(&mut self) {
            if let Some(last_seg) = self.addition {
                self.inner.pop_untracked(last_seg.as_bytes().len())
            }
        }
    }
    impl<'c, 'a> ScopedPathAddition<'c, 'a> {
        pub fn push<'addition, 'curr>(&'curr mut self, segment: &'addition OsStr) -> ScopedPathAddition<'curr, 'addition> {
            // since we're holding a mutable reference we can be sure no other
            // type will add their own segments in the interim
            //
            // the new addition we're making must be dropped before we are, thus
            // requiring that any new segments added after this one via
            // instances of this type are removed by the type our destructor
            // runs
            self.inner.push_untracked(segment);
            ScopedPathAddition { inner: self.inner, addition: Some(segment) }
        }

        pub fn as_c_str(&self) -> &CStr {
            self.inner.as_c_str()
        }
    }

    impl CStringGrowablePath {
        pub fn scoped<'c>(&'c mut self) -> ScopedPathAddition<'c, '_> {
            ScopedPathAddition { inner: self, addition: None }
        }
    }

    #[repr(transparent)]
    #[derive(PartialEq, Eq)]
    pub struct CStrNewType(CStr);
    impl<'c> From<&'c CStr> for &'c CStrNewType {
        fn from(value: &'c CStr) -> Self {
            // SAFETY: repr(transparent)
            unsafe { mem::transmute(value) }
        }
    }
    impl Deref for CStrNewType {
        type Target = CStr;

        fn deref(&self) -> &Self::Target {
            // SAFETY: repr(transparent)
            unsafe { mem::transmute(self) }
        }
    }
    impl fmt::Debug for CStrNewType {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            CStr::fmt(self, f)
        }
    }

    impl Borrow<CStr> for CStringGrowablePath {
        fn borrow(&self) -> &CStr { self.as_c_str() }
    }
    impl Borrow<CStrNewType> for CStringGrowablePath {
        fn borrow(&self) -> &CStrNewType {
            (self.as_c_str()).into()
        }
    }

    // so that we can use `Cow` on `CStrNewType`
    impl ToOwned for CStrNewType {
        type Owned = CStringGrowablePath;

        fn to_owned(&self) -> Self::Owned {
            CStringGrowablePath::new(cstring_as_path(self), false)
        }
    }
}

mod fs_utils {
    use std::{ffi::CStr, fmt, sync::atomic::{AtomicUsize, Ordering}};

    use libc::{S_IFMT, S_IFDIR, S_IFLNK, S_IFREG};
    use nix::{errno::Errno, sys::stat::Mode, mount};

    use crate::{utils::OsStrDisplayExt, colors};

    #[derive(Debug)]
    pub enum Kind { Dir, File, Symlink, Other }
    impl fmt::Display for Kind {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            use Kind::*;

            if f.alternate() {
                f.write_str(match self {
                    Dir => colors::BLUE,
                    File => colors::YELLOW,
                    Symlink => colors::PURPLE,
                    Other => colors::RED,
                })?;
            }

            f.write_str(match self {
                Dir => "directory",
                File => "file",
                Symlink => "symlink",
                Other => "other file",
            })?;

            if f.alternate() { f.write_str(colors::RESET)?; }

            Ok(())
        }
    }
    // returns None if doesn't exist
    #[track_caller]
    pub fn stat(p: &CStr) -> Option<Kind> {
        // we actually do not need or want the extra information
        // that stat gives us (c/a/mtime, inodes, size, owner,
        // etc.) but there doesn't seem to be a syscall that
        // elides these
        //
        // if we *really* wanted to minimize syscalls at the
        // expense of complexity, we'd have splatting cache the
        // file type that it gets back from `getdents`:
        // https://man7.org/linux/man-pages/man2/getdents.2.html
        //
        // TODO: potential perf opt
        //   - this would also let us elide syscalls for some of
        //     the checks (i.e. is directory) that future splats
        //     do

        // note that we're using `lstat` so that we can tell if
        // we've got a symlink on our hands:
        // https://man7.org/linux/man-pages/man2/stat.2.html#DESCRIPTION
        match nix::sys::stat::lstat(p) {
            Ok(stats) => Some({
                // https://man7.org/linux/man-pages/man7/inode.7.html
                match stats.st_mode {
                    m if (m & S_IFMT) == S_IFDIR => Kind::Dir,
                    m if (m & S_IFMT) == S_IFLNK => Kind::Symlink,
                    m if (m & S_IFMT) == S_IFREG => Kind::File,
                    _ => Kind::Other,
                }
            }),
            // https://man7.org/linux/man-pages/man2/stat.2.html#ERRORS
            Err(Errno::ENOENT) => None,
            Err(other) => panic!("Unable to `lstat` mount source `{}`: {other}", p.display()),
        }
    }

    static DEFAULT_DIR_PERMS: Mode = match Mode::from_bits(0o755) {
        Some(m) => m,
        None => panic!(),
    };
    // creates a directory at `path` if it doesn't already exist
    #[track_caller]
    pub fn mkdir_idempotent(path: &CStr, perms: Option<Mode>) {
        match nix::unistd::mkdir(path, perms.unwrap_or(DEFAULT_DIR_PERMS)) {
            // if error is "already exists", ok
            Ok(()) | Err(Errno::EEXIST) => {},
            // on other error: crash
            Err(other) => panic!(
                "Failed to create dir at `{}`: {other}",
                path.display()
            ),
        }
    }

    static BIND_MOUNT_COUNT: AtomicUsize = AtomicUsize::new(0);

    pub fn get_bind_mount_count() -> usize {
        BIND_MOUNT_COUNT.load(Ordering::Relaxed)
    }

    #[track_caller]
    pub fn bind_mount(src: &CStr, dest: &CStr) {
        use nix::mount::MsFlags as F;

        match mount::mount(
            Some(src),
            dest,
            None::<&CStr>,
            // TODO: revisit these mount flags!
            // https://man7.org/linux/man-pages/man2/mount.2.html
            F::MS_REC | F::MS_BIND | F::MS_RDONLY,
            None::<&CStr>,
        ) {
            Ok(()) => {},
            Err(e) => panic!(
                "Error bind mounting `{}` to `{}`: {e}",
                src.display(), dest.display(),
            )
        }

        BIND_MOUNT_COUNT.fetch_add(1, Ordering::Relaxed);
    }
}

// actually nevermind; for splatted paths this will need to be suffixed too..
//
// we'll just use the path we build up as we do the walk
/*
#[derive(Debug)]
struct MountTargetNode<'p> {
    // just for convenience so that we don't have to concat a bunch of strings
    // and walk the tree to get a c-style string for this mount's source path
    // (in the event that the source path == the dest path; we have to allocate
    // for the dest path regardless because we prefix it with the sandbox base)
    //
    // `path` should be consistent with the keys of `Neutral.children` that led
    // to this node
    //
    path: &'p CStr, // _destination_ path
    info: MountTargetNodeInfo<'p>,
}
*/

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MountTargetNode<'p> {
    // directive to not mount in this path
    Exclude,
    // mount in this path; potentially using a different source path
    //
    // this may be a file or a directory
    Include {
        // only present if different than the mount path
        source_path: Option<Cow<'p, CStrNewType>>,
    },
    // this entry only exists because something deeper wanted it
    Neutral {
        /// map from subpath of `path` (i.e. `Component::Normal`) to
        /// `MountTargetNode`
        // note: iteration order is non-deterministic...
        //
        // should be okay though
        children: HashMap<Cow<'p, OsStr>, MountTargetNode<'p>>,
    }
}

// wrapper type so that you cannot modify the internals...
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MountTargetsMap<'p> {
    root: MountTargetNode<'p>,

    // really we should use this:
    // root_dir: HashMap<Cow<'p, OsStr>, MountTargetNode<'p>>,
    //
    // but having root be a node (that actually is always a neutral node)
    // makes some of the recursion in places a little easier..
}

impl<'p> From<MountTargetNode<'p>> for MountTargetsMap<'p> {
    fn from(root: MountTargetNode<'p>) -> Self {
        MountTargetsMap { root }
    }
}

impl<'p> MountTargetsMap<'p> {
    pub fn new() -> Self {
        Self {
            root: MountTargetNode::Neutral { children: HashMap::new() },
        }
    }
}

static SPLAT_COUNT: AtomicUsize = AtomicUsize::new(0);
static SPLAT_MOUNTS: AtomicUsize = AtomicUsize::new(0);

impl<'p> MountTargetsMap<'p> {
    // count, mounts that came from splatting (not necessarily the number of
    // _additional_ mounts since each splat replaces a mount)
    pub fn get_splat_counts() -> (usize, usize) {
        (SPLAT_COUNT.load(Ordering::Relaxed), SPLAT_MOUNTS.load(Ordering::Relaxed))
    }

    fn splat<'n>(inp: &'n mut MountTargetNode<'p>, dest_path: &'_ Path) -> &'n mut HashMap<Cow<'p, OsStr>, MountTargetNode<'p>> {
        use MountTargetNode::*;

        // TODO: tune default hashmap size; what's the average directory fanout?
        let old = std::mem::replace(
            inp,
            Neutral { children: HashMap::with_capacity(10) },
        );
        let Neutral { children: ref mut map } = inp else {
            unreachable!()
        };

        // get the path to splat:
        let path = match &old {
            n @ Neutral { .. } => panic!("cannot splat a `Neutral` entry; got: {:?}", n),
            Include { source_path: Some(explicit_source) } => cstring_as_path(explicit_source.as_ref()),
            _ => dest_path,
        };

        // checks:
        if !path.exists() {
            panic!("cannot splat `{}`; does not exist", path.display());
        }
        if !path.is_dir() {
            panic!("cannot splat `{}`; is not a directory", path.display());
        }
        if path.is_symlink() {
            warn!(
                "splatting a symlink at `{}`; this will result in at least \
                one level of symlinks being elided in the sandbox's filesystem",
                path.display(),
            )
        }

        // normally we'd avoid `read_dir` in favor of the underlying `libc`
        // function (or it's `nix` wrapper`) to avoid extra allocations but...
        // `DirEntry` (on Linux) does what we'd do to produce the OsString for
        // the final path segment and doesn't add much extra overhead afaict
        // (it does have an `Arc` within that's constructed on every call to
        // `next` though...)
        //
        // actually lets just use the `nix` machinery:
        let mut dir = dir::Dir::open(
            path, OFlag::O_RDONLY | OFlag::O_DIRECTORY,
            Mode::empty(),
        ).expect("open dir for splatting");
        for child in dir.iter() {
            let child = child.expect("error reading child while splatting");

            let name = child.file_name().to_bytes();
            if name == b"." || name == b".." { continue; }
            let name = OsStr::from_bytes(name);

            let new_node = match &old {
                Exclude => Exclude,
                Include { source_path: None } => Include { source_path: None },
                Include { source_path: Some(custom_source) } => {
                    // allocates but alas
                    let mut new_custom_source_path = custom_source.clone().into_owned();

                    new_custom_source_path.push(name);

                    Include { source_path: Some(Cow::Owned(new_custom_source_path)) }
                },
                Neutral { .. } => unreachable!(),
            };

            // another unavoidable allocation
            map.insert(Cow::Owned(name.to_owned()), new_node);
        }

        SPLAT_COUNT.fetch_add(1, Ordering::Relaxed);
        SPLAT_MOUNTS.fetch_add(map.len(), Ordering::Relaxed);

        map
    }

    // ## Pseudo code
    /// entry merge: (prev, new)
    ///   - neutral, neutral => okay: if the child is already there don't add
    ///   - neutral, exclude:
    ///     + i.e. we are excluding a directory for which there are already
    ///       children in the map
    ///     + we'd need to splat this exclude and apply it (potentially
    ///       recursively)
    ///   - neutral, include:
    ///     + i.e. we are _including_ a directory for which there are already
    ///       children in the map
    ///     + need to splat this include and apply it
    ///     + I think we can guarantee that we'll never hit this case (and the
    ///       above) if we sort our list of exclude + include paths and call
    ///       `push` on them in sorted order
    ///       * this makes it so that children will always be added _after_
    ///         their parent directory paths
    ///     + I don't know if this is worth it though; I think it's still the
    ///       same number of operations?
    ///       * here's an example:
    ///         ```markdown
    ///         ops: include /foo, exclude /foo/bar/baz (where /foo/bar/quux and /foo/blue/blah) exist
    ///
    ///         sorted order:
    ///           - include /foo
    ///           - exclude /foo/bar/baz (/foo/)
    ///             + whoops, /foo exists and is not a superset (we're an
    ///               exclude): need to splat /foo
    ///           - exclude /foo/bar/baz (/foo/bar/)
    ///             + whoops: /foo/bar exists and is not a superset: splat
    ///               /foo/bar
    ///           - exclude /foo/bar/baz
    ///
    ///         backwards order:
    ///           - exclude /foo/bar/baz (ok)
    ///           - include /foo
    ///             + whoops, need to splat /foo
    ///           ...
    ///           (we end up splatting /foo/bar as well)
    ///         ```
    ///      + actually never mind: the big difference is that if we go in order
    ///        we can tell when a mount is elide-able (i.e. include below an
    ///        include)
    ///      + doing this the other way (telling if mounts beneath us are
    ///        elidable if we are to add a parent path after its children have
    ///        been added) requires that we recursively analyze every mount
    ///        beneath us
    ///        * we can save on the complexity by implementing this as replaying
    ///          everything beneath us, recursively on top of the parent mount
    ///          - but we this ends up happening a bunch or if the nesting is
    ///            very deep this can get expensive
    ///        * only upside I can come up with to going this route is that one
    ///          way to implement this involves seeing if the children of a
    ///          Neutral node are _covering_ for the parent and then rewriting
    ///          the parent as a simple include/exclude (maybe)
    ///          - this is surely not helpful for perf though; eliding the bind
    ///            mount(s) will probably not pay for the stat syscalls and the
    ///            extra compute
    ///
    ///   - include, neutral:
    ///     + i.e. we have a dest path that's below an existing include
    ///     + if the full path we're adding is ultimately an include *and* if:
    ///       * the source path of this parent include and the source path of
    ///         our full path are both `none` _or_
    ///       * if the paths are both aligned (i.e. if `prev`'s source + the rel
    ///         dest path of our remaining segments == our mount's src_path)
    ///     + then it's safe to elide this mount:
    ///       * we should make sure that the path exists though...
    ///     + otherwise, we need to splat `include` and turn it into a `neutral`
    ///       node and then continue
    ///       * when splatting `include`, need to propagate `source_path` (with
    ///         appends for the splatting)
    ///   - include, exclude:
    ///     + error, cannot include and exclude a path...
    ///     + even if the include has a different source path, this is not
    ///       something we can make sense of
    ///       * the semantics of exclude paths is that they refer to destination
    ///         paths
    ///   - include, include:
    ///     + if source path is the same, it's fine; warn about duplicate bind
    ///       mounts, maybe
    ///     + if not the same, error
    ///
    ///   - exclude, neutral:
    ///     + i.e. we have a dest path that's below an existing exclude
    ///     + if the full path we're adding is an exclude, this is okay, just do
    ///       nothing
    ///     + if the full path is an include...
    ///       * I think we should error?
    ///       * or maybe just warn and then drop the include
    ///   - exclude, exclude:
    ///     + this is okay, just a duplicate
    ///   - exclude, include:
    ///     + same as the stuff on `exclude, neutral` iff dest path: include
    ///     + makes no sense, probably warn and drop the include
    ///       * update: see the addendum below
    ///
    /// Addendums:
    ///   - can we trigger the "duplicate include mount" warning with splats?
    ///     + if so do want to not print the warning?
    ///     + For cases like:
    ///       ```text
    ///       /foo
    ///       /foo/bar (may cause /foo to be splat (if custom source?))
    ///       /foo/baz (will now overlap with `/foo/baz` and trigger this warning..)
    ///       ```
    ///   - actually I think we have a bigger issue:
    ///     + say you want to mount `/foo` except for `/foo/bar` which you want
    ///       to mount from `/baz`
    ///     + I don't think we have a way to express this currently..
    ///       * doing: inc `/foo`, exc `/foo/bar`, inc `/foo/bar` from `/baz`
    ///       * will get you an error (cannot include and exclude a path)
    ///     + maybe to get around this we do allow includes to shadow excludes?
    ///       * (and then sort our mounts accordingly to put excludes for a
    ///          destination before includes)
    ///       * TODO: how does this interact with splatting? (I think it's
    ///         fine?)
    ///
    ///   - ... it's actually only for "not present in the source" mounts and
    ///     excludes that you need to splat
    ///     + i.e. if I have some `/foo/{bar,baz,...}` and I want to exclude
    ///       just `/foo/bar` but I'm okay with `/foo/bar` being an empty
    ///       directory, I don't need to splat `/foo`; I can just mount an empty
    ///       dir over `/foo/bar`.
    ///     + ditto with other bind mounts; so long as `/foo/<whatever>` exists
    ///       I don't actually need to splat `/foo`
    ///     + it's only when `/foo/<subdir>` doesn't exist (or is a file when I
    ///       want to mount a directory or vice versa) that I need to splat
    ///       `/foo`
    ///     + ...
    ///     + this derails our plans, significantly
    ///     + ...
    ///     + we can model "I can tolerate empty directory/file"-style excludes
    ///       by modeling them as bind mounts with custom sources
    ///       * this distinction can thus live entirely in the Bazel layer
    ///       * or maybe a _little_ in this layer, we'd need to know whether to
    ///         pick the empty file or dir to mount over the existing path...
    ///     + so the major change is that we can "overlay" some kinds of mounts
    ///       on includes:
    ///       * mounts where the dest path already exists (need to inspect
    ///         source path for this...)
    ///         - unless the source of this mount is a symlink :(
    ///           + ... unless we're on a new enough Linux machine where we can
    ///             bind mount symlinks without resolving their source arg
    ///             * also what about destination symlinks under this scheme...
    ///               since we're mounting over files we did not create, this
    ///               has the potential to create trouble; we really don't want
    ///               to follow such symlinks
    ///       * we can also still elide bind mounts that are redundant with the
    ///         same rules
    ///     + resolving splats actually doesn't get all that much more complex
    ///     + if we go through with this I think we'd also want to adopt the
    ///       "more specific path wins" rule; i.e. if you have an exclude for
    ///       `/foo/bar` and then a mount for `/foo/bar/baz`, we're mounting
    ///       `/foo/bar/baz`
    ///       + the reason why this isn't totally nonsensensical is: suppose
    ///         there was a mount for `/foo` and the `/foo/bar` exclude exists
    ///         to splat that mount...
    ///     + I definitely do not want to go this far but the rabbit-hole
    ///       actually goes deeper still: say we have a "I can tolerate an empty
    ///       directory" style exclude for `/foo/bar` in the above example and
    ///       then say I have a bunch of bind mounts layered onto `/foo/bar/...`
    ///       where the path doesn't exist on the source filesystem
    ///       + under the scheme described above, we'd splat the `/foo/bar`
    ///         mount (and also the `/foo` mount) so that we can make these
    ///         directories
    ///       + but actually, since we can control the "empty" directory we're
    ///         mounting to `/foo/bar`, the optimal thing to do here would be to
    ///         craft an dir to bind mount to `/foo/bar` that has the necessary
    ///         empty dirs/files for our bind mounts...
    ///         * this isn't as simple as just checking if the parent dir for
    ///           mounts we're crafting is writable; the special case here is
    ///           that it's a stand-in empty directory in our control
    ///         * if we just did simple empty dir checks, we'd hit errors if the
    ///           empty dir was bind mounted in two places where we had the same
    ///           child mount and where one was a file and one was a
    ///           directory...
    ///         * we could mitigate against this by... making a unique empty
    ///           directory to bind mount for each overlay-able dir
    ///           - another pernicious perf tradeoff...
    ///     + I'm not going to attempt to implement all the things described
    ///       above... I think just exposing an "unchecked empty directory
    ///       excludes" option that runs after all of this precise
    ///       includes/excludes logic and blindly mounts empty dirs over the
    ///       given paths will get us most of the benefits without making
    ///       implementation and testing a nightmare
    ///       + "unchecked empty directory excludes" with some caveats:
    ///         * destination must already exist
    ///         * no warnings on shadowing other bind mounts, even mounts to the
    ///           very same path
    ///         * if the destination exists and is a symlink, it's an error (on
    ///           older Linux kernels our only way to keep from following the
    ///           symlink is to splat (recursively if needed) the mounts leading
    ///           to that directory until we have a mount permitting us to write
    ///           and then to mount everything *but* that symlink in the
    ///           directory — since we're not attempting to implement that,
    ///           we'll just error)
    ///       + or: for bind mounts beneath a bind mount:
    ///         * if:
    ///           - dest isn't a symlink
    ///           - dest exists and the file/dir type matches
    ///         * set it off to the side and apply it as a bind mount later?
    ///         * update: no; this doesn't work because a more specific path can
    ///           come along and require that we splat the layered bind mount...
    ///       + yeah okay fine; I think the unchecked thing described above is a
    ///         reasonable tradeoff
    ///
    ///
    /// ## Args
    ///
    /// If `new` is a [`MountTargetNode::Neutral`] its `children` must be empty;
    /// we expect that you'll handle adding in the path segment for your next
    /// node after invoking this function.
    ///
    /// To faciliate this, when given a "neutral" new node this function returns
    /// the children map for the merged neutral node (unless the dest path for
    /// the node has been determined as elide-able).
    fn merge<'node, 'path>(
        full_dest_path: &'_ Path, // for debugging and splatting
        remaining_components_from_full_dest_path: &'_ Path,
        full_dest_node_is_exclude: bool,
        full_dest_custom_source_path: Option<&'path Path>,
        current: &'node mut MountTargetNode<'path>,
        new: MountTargetNode<'path>,
    ) -> Option<&'node mut HashMap<Cow<'path, OsStr>, MountTargetNode<'path>>> {
        use MountTargetNode::*;
        if cfg!(debug_assertions) {
            match &new {
                Exclude => debug_assert_eq!(full_dest_node_is_exclude, true),
                Include { .. } => debug_assert_eq!(full_dest_node_is_exclude, false),
                Neutral { children } => debug_assert_eq!(children.len(), 0, "should add children of `new` after merge"),
            }

            if let Exclude | Include { .. } = new {
                assert!(remaining_components_from_full_dest_path.is_empty())
            }

            if full_dest_custom_source_path.is_some() {
                assert!(!full_dest_node_is_exclude);
            }

            assert!(full_dest_path.ends_with(remaining_components_from_full_dest_path));
        }
        let kind = if full_dest_node_is_exclude { "exclude" } else { "include" };
        let curr_path: &Path = {
            let full_bytes = full_dest_path.as_os_str().as_bytes();
            let rest_bytes = remaining_components_from_full_dest_path.as_os_str().as_bytes();
            let mut bytes = &full_bytes[0..(full_bytes.len() - rest_bytes.len())];

            debug_assert!(bytes.len() > 1); // shouldn't get `curr = /`

            // final nodes will not have a trailing `/` which is why we need this check
            if bytes.last() == Some(&b'/') {
                bytes = &bytes[0..(bytes.len() - 1)];
            }
            OsStr::from_bytes(bytes).as_ref()
        };

        match (current, new) {
            (Neutral { children }, Neutral { .. }) => Some(&mut *children),
            (curr @ Neutral { .. }, new @ (Exclude | Include { .. })) => {
                panic!(
                    "You are adding a path that's _shorter_ (a prefix) of an \
                    existing path, this is not allowed. Sorting paths prior to \
                    inserting into the map should make this impossible. \
                    At, curr: {:?} (path = `{}`), new: {:?} (for destination path: {} ({}))",
                    curr, curr_path.display(), new, full_dest_path.display(), kind
                )
            },

            // NOTE: in the context of a splat (i.e. `inc: /foo` followed by
            // `exc: /foo/bar`) we actually *do* need to allow this... (we'll
            // splat `/foo` and then go and try to replace the `inc: /foo/bar`
            // with `exc: /foo/bar).
            //
            // the rule may need to move towards "the more specific thing wins"
            //
            // re this "shadowing": we could try to distinguish between
            // splatting causing this vs. actually contradictory mounts being
            // specified (i.e. exclude and include of the same path) but I think
            // it isn't worth it
            (inc @ Include { .. }, Exclude) => {
                let Include { ref source_path } = inc else { unreachable!() };
                debug!("shadowing include at `{}` (custom source: {:?}) with exclude", full_dest_path.display(), source_path);
                *inc = Exclude;
                None
            },
            (Include { source_path: orig_source }, Include { source_path: new_source }) => if *orig_source != new_source {
                panic!(
                    "Conflicting bind mounts at path `{}`: \
                    \n  existing custom source: {:?} \
                    \n  new custom source: {:?} \
                    ",
                    full_dest_path.display(), orig_source, new_source,
                )
            } else {
                // duplicate but that's okay
                debug!("got duplicate bind mount for `{}` (with custom source {:?})", full_dest_path.display(), orig_source);
                None
            }

            // See above; we can run into this case when splatting: i.e.
            // `inc: /foo` then `exc: /foo/bar` followed by `inc: /foo/bar/baz`.
            //
            // (the `inc: /foo` isn't necessary for us to run into this case but
            // it provides a realistic example of why we might end up with an
            // exclude of an ancestor (`/foo/bar`) of an include
            // (`/foo/bar/baz`))
            (exc @ Exclude, inc @ Include { .. }) => {
                let Include { ref source_path } = inc else { unreachable!() };
                warn!("shadowing exclude at `{}` with bind mount (custom source: {:?})", full_dest_path.display(), source_path);
                *exc = inc;
                None
            }
            (Exclude, Exclude) => {
                // duplicate but that's fine
                warn!("got duplicate exclude for `{}`", full_dest_path.display());
                None
            }

            // now the hard cases:
            (inc @ Include { .. }, Neutral { .. }) => {
                let Include { ref source_path } = inc else { unreachable!() };
                // i.e.: dest path that's below an existing include
                //
                // first see if we can elide this dest path's mount:
                let can_elide = (|| {
                    if full_dest_node_is_exclude { return false }

                    match (source_path, full_dest_custom_source_path) {
                        // no need to check anything
                        (None, None) => true,
                        // Need to check if the custom source are aligned; i.e.:
                        // `this + (rel dest path of the new node to this)` ==
                        // `child_source`
                        //
                        // where "rel dest path of the new node" is the
                        // remaining path components of the path being inserted
                        // into the map
                        (Some(this), Some(child_source)) => {
                            let base = cstring_as_path(this.as_ref());
                            let rest = remaining_components_from_full_dest_path;
                            debug_assert!(remaining_components_from_full_dest_path.is_relative());

                            let this = base.components().chain(rest.components());
                            this.eq(child_source.components())
                        },

                        // the effective source for the "child" mount will only
                        // match the current mount if the current mount's
                        // explicit source is actually redundant...
                        (Some(this), None) => {
                            if cstring_as_path(this.as_ref()) == curr_path {
                                debug_assert_eq!(full_dest_path, curr_path);
                                debug!(
                                    "redundant explicit source for bind mount at {}",
                                    full_dest_path.display(),
                                );

                                true
                            } else {
                                false
                            }
                        },

                        // same as above but if the child's explicit source is
                        // redundant:
                        (None, Some(child_source)) => {
                            if full_dest_path == child_source {
                                debug!(
                                    "redundant explicit source for bind mount at {}",
                                    full_dest_path.display(),
                                );

                                true
                            } else {
                                false
                            }
                        }
                    }
                })();

                if can_elide {
                    // check if the source path exists...
                    //
                    // then return None
                    utils::if_debug(|| {
                        let child_include_mount_source_path = if let Some(explicit_source) = full_dest_custom_source_path {
                            explicit_source
                        } else {
                            // should be the same as: curr + rest
                            full_dest_path
                        };

                        if !child_include_mount_source_path.exists() {
                            warn!(
                                "bind mount at `{}` (custom source: {:?}) has a non-existent source!",
                                full_dest_path.display(), full_dest_custom_source_path,
                            )
                        }
                    });

                    None
                } else {
                    // splat current and then return the children of the new
                    // Neutral node
                    debug!(
                        "splatting bind mount at {} (source: {:?}) to accomodate {} mount at {}",
                        curr_path.display(), full_dest_custom_source_path, kind,
                        full_dest_path.display(),
                    );

                    Some(MountTargetsMap::splat(inc, curr_path))
                }
            }
            (exc @ Exclude, Neutral { .. }) => {
                // i.e.: dest path that's below an existing exclude
                //
                // if the full dest node is an exclude we can safely elide this
                // exclude
                let can_elide = full_dest_node_is_exclude;

                if can_elide {
                    // check if the exclude exists? nah, it's fine
                    None
                } else {
                    // splat current exclude and then return the children of the
                    // new Neutral node
                    debug!(
                        "splatting exclude at {} to accomodate {} mount at {}",
                        curr_path.display(), kind, full_dest_path.display(),
                    );
                    Some(MountTargetsMap::splat(exc, curr_path))
                }
            }
        }
    }

    // must call in sorted order!
    pub fn insert(&mut self, dest_path: &'p CStr, src_path: Option<&'p CStr>, is_exclude: bool) {
        // walk from root using dest path
        //   - insert neutral at all parent levels, insert include|exclude at last level

        // entry merge (see above)

        // note: sort mounts lexicographically by dest? and then by exclude > include (i.e. excludes after includes)
        //   - handled by `insert_paths`
        // note: assert path is absolute

        let dest_path = cstring_as_path(dest_path);
        debug_assert!(dest_path.is_absolute());

        let MountTargetNode::Neutral { children: root_dir } = &mut self.root else { unreachable!() };
        put(dest_path, root_dir, dest_path, src_path, is_exclude);

        fn put<'p>(
            full_dest_path: &Path, // for debugging and splatting
            parent_dir: &mut HashMap<Cow<'p, OsStr>, MountTargetNode<'p>>,
            relative_dest_path: &'p Path,
            src_path: Option<&'p CStr>,
            is_exclude: bool,
        ) {
            let (segment, rest) = {
                fn get_next_seg<'p>(it: &mut Components<'p>) -> &'p OsStr {
                    use Component::*;
                    match it.next().expect("non-empty dest path") {
                        Prefix(_) => unreachable!("windows?"),
                        RootDir => {
                            if it.as_path().is_empty() {
                                panic!("cannot mount the root path");
                            }

                            // skipping `/`:
                            get_next_seg(it)
                        },
                        CurDir | ParentDir => panic!("no `..` or `.` in destination paths please"),
                        Normal(seg) => seg,
                    }
                }

                let mut it = relative_dest_path.components();
                let seg = get_next_seg(&mut it);

                (seg, it.as_path())
            };

            let new_node = if NixPath::is_empty(rest) {
                // If this is the last path segment, add an include/exclude:
                if is_exclude {
                    // icky but fine for now
                    debug_assert!(src_path.is_none(), "excludes cannot have a custom source path");
                    MountTargetNode::Exclude
                } else {
                    MountTargetNode::Include { source_path: src_path.map(<&CStrNewType>::from).map(Cow::Borrowed) }
                }
            } else {
                // Otherwise it's a neutral node that we need:
                //
                // note that we do not insert any nodes in this hashmap yet --
                // we don't want to bother doing an allocation. we're probably
                // not actually going to use this HashMap for anything; it's
                // likely we're going to merge with another `Neutral` node that
                // already has it's own (non-empty) hashmap.
                MountTargetNode::Neutral { children: HashMap::new() }
            };

            // insert node, merging nodes if needed:
            let children_dir: Option<&mut HashMap<_, _>> = match parent_dir.entry(Cow::Borrowed(segment)) {
                Entry::Occupied(curr) => MountTargetsMap::<'_>::merge(
                    full_dest_path,
                    rest,
                    is_exclude,
                    src_path.map(cstring_as_path),
                    curr.into_mut(),
                    new_node,
                ),
                Entry::Vacant(slot) => {
                    match slot.insert(new_node) {
                        MountTargetNode::Neutral { children } => Some(children),
                        _ => None,
                    }
                },
            };
            // let children_map: Option<&mut HashMap<_, _>> = MountTargetsMap::<'_>::merge(
            //     full_dest_path,
            //     rest,
            //     is_exclude,
            //     src_path.map(|c| cstring_as_path(c)),
            //     node,
            //     new_node,
            // );

            // handle rest, if not empty (and if this mount wasn't elided):
            if let Some(children_dir) = children_dir {
                // // insert a replace-able node (i.e. neutral with no children)
                // // if nothing is already present:
                // let next_node = children_map
                //     .entry(Cow::Borrowed(segment))
                //     .or_insert_with(|| MountTargetNode::Neutral { children: HashMap::new() });

                // note that this is tail-recursive
                put(full_dest_path, children_dir, rest, src_path, is_exclude)
            }
        }
    }
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Mount<'p> {
    Include { dest: &'p CStr, explicit_src: Option<&'p CStr> },
    HardExclude { dest: &'p CStr },
}

impl<'p> PartialOrd for Mount<'p> {
    fn partial_cmp(&self, other: &Self) -> Option<cmp::Ordering> {
        use Mount::*; use std::cmp::Ordering::*;
        let (Include { dest, .. } | HardExclude { dest }) = self;
        let (Include { dest: other_dest, .. } | HardExclude { dest: other_dest }) = other;

        // first compare the dest paths:
        match dest.partial_cmp(other_dest) {
            Some(Equal) => {},
            other => return other,
        }

        // then compare exclude vs. include; exclude is greater than include:
        let is_exclude = matches!(self, HardExclude { .. });
        let other_is_exclude = matches!(other, HardExclude { .. });
        Some(match (is_exclude, other_is_exclude) {
            // could do a tie-breaker on `other_dest` but not going to bother
            (true, true) | (false, false) => Equal,
            // exclude is greater than include (should come _afterwards_ when
            // applying mounts)
            (true, false) => Greater,
            (false, true) => Less,
        })
    }
}

impl<'p> Ord for Mount<'p> {
    fn cmp(&self, other: &Self) -> cmp::Ordering {
        self.partial_cmp(other).unwrap()
    }
}

impl<'p> MountTargetsMap<'p> {
    pub fn insert_paths(&mut self, mounts: &mut [Mount<'p>]) {
        mounts.sort_unstable();

        for m in mounts {
            match m {
                Mount::Include { dest, explicit_src } => self.insert(dest, *explicit_src, false),
                Mount::HardExclude { dest } => self.insert(dest, None, true),
            }
        }
    }
}

impl<'p> MountTargetsMap<'p> {
    // Note: as a post-processing step we may want to do pruning of excludes?
    //
    // i.e. if a `Neutral` node only has excludes, we can omit it; this applies
    // transitively up the tree
    //
    // eliding these nodes is beneficial since it may allow us to save on
    // `mkdir`s (i.e. a deep exclude with no/few common ancestors with any
    // includes)
    pub fn prune_excludes(&mut self) {
        self.prune_excludes_with_callback::<fn(&CStr)>(None)
    }

    pub fn prune_excludes_with_callback<F: FnMut(&CStr)>(&mut self, mut callback: Option<F>) {
        // If we were given a callback, we'll keep track of the destination path
        // values as we go:
        let mut dest_path = callback.is_some().then(|| CStringGrowablePath::new("", true));
        let callback_info = if let Some((dest_path, cb)) = dest_path.as_mut().zip(callback.as_mut()) {
            Some((dest_path.scoped(), cb))
        } else {
            None
        };

        use MountTargetNode::*;
        fn remove_excludes<F: FnMut(&CStr)>(
            node: &mut MountTargetNode,
            mut callback_info: Option<(ScopedPathAddition<'_, '_>, &mut F)>,
        ) -> bool {
            match node {
                Exclude => {
                    if let Some((dest_path, cb)) = callback_info {
                        cb(dest_path.as_c_str())
                    }

                    true
                },
                Include { .. } => false,
                Neutral { children } => {
                    // Note: this will remove empty neutral nodes too!
                    let mut all_are_excludes = true;

                    children.retain(|seg, child| {
                        let callback_info = callback_info.as_mut().map(|(p, cb)| {
                            (p.push(seg), &mut **cb)
                        });

                        if remove_excludes::<F>(child, callback_info) {
                            false // remove this node
                        } else {
                            all_are_excludes = false;
                            true // keep this node
                        }
                    });

                    all_are_excludes
                }
            }
        }

        remove_excludes(&mut self.root, callback_info);
    }
}

// see `linux-sandbox-pid1.cc`
static EMPTY_FILE: &CStr = tree_macros::cstr_static_literal!("tmp/empty_file");

impl<'p> MountTargetsMap<'p> {
    // note: time of check time of use bugs; we offer no guarantees about this
    // (we assume no mutation of the directory structure of the mounts while
    // we're setting up the sandbox)
    //
    // ---
    //
    // note: what if the dest is a symlink/is beneath a symlink?
    //
    // for now we won't "do the right thing" (i.e. splat) here; we'll assume
    // that this is handled by Bazel when it assembles the list of mounts
    //
    // we will warn in debug mode though (TODO!)
    pub fn apply(self, sandbox_base: &Path) {
        // c string conversions necessary for passing the mount destination path
        // to syscalls (we need to prepend the sandbox base path)
        //
        // note: we prefer this instead of the `at` family of syscalls since
        // we really are tacking things onto this path, segment by segment
        //
        // (also there is no `mountat` syscall)
        let mut dest_path = CStringGrowablePath::new(sandbox_base, true);

        handle(&self.root, dest_path.scoped(), sandbox_base.len());

        fn handle(
            node: &MountTargetNode,
            mut dest_path: ScopedPathAddition<'_, '_>,
            sandbox_base_path_len: usize,
        ) {
            // we can strip the sandbox base from `dest_path` to get the dest
            // path within the sandbox base — a.k.a. the default source path:
            let dest_within_sandbox = {
                let rest = &dest_path.as_c_str().to_bytes_with_nul()[sandbox_base_path_len..];
                if rest.len() > 1 {
                    // if the current dest path *is* the sandbox base this path
                    // will be empty
                    debug_assert_eq!(rest[0] as char, b'/' as char, "full dest path: {}", dest_path.as_c_str().display());
                }
                unsafe { CStr::from_bytes_with_nul_unchecked(rest) }
            };

            use MountTargetNode::*;
            use colors::*;
            match &node {
                Exclude => {
                    /* nothing to do */
                    debug!("excluding path at `{}` (in sandbox: `{}`)", dest_within_sandbox.display(), dest_path.as_c_str().display());
                },
                Include { source_path: explicit_source } => {
                    let implied_source = dest_within_sandbox;
                    let source = explicit_source.as_deref().map(|x| x.as_ref()).unwrap_or_else(|| implied_source);

                    // debug check: if the parent of the source path has a
                    // symlink anywhere, we've elided a level of symlinks
                    if_debug(|| {
                        let Some(parent) = cstring_as_path(source).parent() else { return; };
                        let Ok(canon) = parent.canonicalize() else { return; };
                        if canon != parent { warn!(
                            "mount with source `{}` (to destination `<sandbox>{}`) \
                            contains a symlink somewhere in its ancestors! \
                            \n  - parent of source:     `{par}` \
                            \n  - actually resolves to: `{can}` \
                            \n\n This will result in at least one level of \
                            symlinks being elided in the sandbox's filesystem",
                            source.display(), dest_within_sandbox.display(),
                            par = parent.display(), can = canon.display(),
                        ) }
                    });

                    // Warn and skip if the source doesn't exist!
                    let Some(kind) = fs_utils::stat(source) else {
                        warn!(
                            "bind mount source at `{}` (to dest: `<sandbox>{}`) does not exist! Skipping this mount.",
                            source.display(), dest_within_sandbox.display(),
                        );
                        return;
                    };

                    // debug print:
                    if explicit_source.is_some() {
                        debug!("{BOLD}mounting {kind:#}{RESET}: {} → <sandbox>{}", source.display(), dest_within_sandbox.display());
                    } else {
                        // dest is implied, skip it for clarity
                        debug!("{BOLD}mounting {kind:#}{RESET}: {}", source.display());
                    }

                    // do the actual mount:
                    match kind {
                        Kind::Dir => {
                            // mkdir (should not already exist; just allow it if it already exists to accomodate dirty sandbox bases...)
                            // mount

                            // note: we're not ensuring that the thing that exists
                            // is a dir; if its not the mount will just fail.

                            fs_utils::mkdir_idempotent(dest_path.as_c_str(), None);
                            fs_utils::bind_mount(source, dest_path.as_c_str());
                        },
                        Kind::File | Kind::Other => {
                            // touch (or rather hardlink the empty file?) | should not already exist, but allow it..
                            // mount

                            // note: not ensuring that the thing that exists is
                            // a file; mount will fail if its not

                            // see `linux-sadbox-pid1.cc`; hardlinking is allegedly faster
                            match unistd::linkat(None, EMPTY_FILE, None, dest_path.as_c_str(), unistd::LinkatFlags::NoSymlinkFollow) {
                                Ok(()) | Err(Errno::EEXIST) => {},
                                Err(e) => panic!("error creating empty file for bind mount: {e}"),
                            }
                            fs_utils::bind_mount(source, dest_path.as_c_str());
                        }
                        Kind::Symlink => {
                            // if we support MS_NOSYMFOLLOW?
                            //   + touch empty file (should not exist!)
                            //   + mount
                            //   + ... actually, let's maybe not even bother with this
                            //     - not sure how to reliably check for `MS_NOSYMFOLLOW` at runtime and I don't know that this would be faster anyways
                            // else:
                            //   + make symlink file with same dest (actually.. copy so we don't need another syscall to read the target of the symlink)
                            //     * also preserves relative/absolute symlink
                            //     * edit: nvm, this still means we have to effectively make a new symlink
                            //     * not going to bother preserving the time/ownership of the original symlink
                            //   + don't check if dest exists or not
                            //   + make readonly
                            //     * nvm, perm bits are meaningless:
                            //       https://superuser.com/questions/303040/how-do-file-permissions-apply-to-symlinks
                            //     * they are relevant to deletion but only when
                            //       parent dirs have the sticky bit set:
                            //       https://linux.die.net/man/2/symlink

                            // Note: this allocates instead of using a stack
                            // buffer; not ideal but alas.
                            let target = match fcntl::readlink(source) {
                                Ok(symlink_target) => symlink_target,

                                // https://man7.org/linux/man-pages/man2/readlink.2.html#ERRORS
                                Err(err) => panic!(
                                    "failed to read symbolic link at `{}` (for \
                                    mount with destination: `<sandbox>{}`): \
                                    {err}",
                                    source.display(), dest_within_sandbox.display(),
                                )
                            };

                            // If the file already exists, try to delete it:
                            match fs_utils::stat(dest_path.as_c_str()) {
                                None => { /* doesn't exist, all good */ },
                                Some(Kind::File | Kind::Symlink) => {
                                    fs::remove_file(cstring_as_path(dest_path.as_c_str())).expect("failing to delete existing file (in order to make symlink)");
                                },
                                Some(Kind::Dir | Kind::Other) => {
                                    warn!("target of mount `{}` already exists and is not a file or symlink", dest_path.as_c_str().display());

                                    // TODO: check that this is an empty dir first...
                                    //  - remove_dir won't work unless it is but
                                    //    still

                                    // this comes up because Bazel makes empty
                                    // dirs for the source roots (which are
                                    // sometimes symlinks?)
                                    fs::remove_dir(cstring_as_path(dest_path.as_c_str())).expect("failing to delete existing dir/entry (in order to make symlink)");
                                },
                            }

                            // no issues with relative symlinks
                            // https://man7.org/linux/man-pages/man2/symlink.2.html
                            //
                            // note: we're leaving more perf on the table here:
                            // because `target` is an `OsString`, `NixPath` will
                            // do a stack copy so it can ensure null termination
                            //
                            // if we decide to make replace `readlink` with a
                            // version that uses a stack allocation, we can
                            // "fix" this too (TODO: perf opt)
                            unistd::symlinkat(&*target, None, dest_path.as_c_str()).unwrap();
                        }
                    }
                },
                Neutral { children } => {
                    fs_utils::mkdir_idempotent(dest_path.as_c_str(), None);

                    for (segment, child) in children {
                        let dest_path = dest_path.push(segment);
                        handle(child, dest_path, sandbox_base_path_len)
                    }

                    // mkdir sandbox_base + node.path (ensure dest does not
                    // exist / is a dir?)
                    //  - ideally we'd check that the dest is not a mountpoint
                    //    but that seems expensive..
                    //  - should *not* also assert that node.path is a directory
                    //    on the host filesystem because the child(ren) mounts
                    //    that resulted in this node existing may all have
                    //    sources that don't contain `node.path`
                    //
                    // push
                    //
                    // run `handle` for each child
                }
            }
        }
    }
}

mod colors {
    pub const RESET: &str = "\x1b[0m";
    pub const BOLD: &str = "\x1b[1m";
    pub const ITALICS: &str = "\x1b[3m";
    pub const RED: &str = "\x1b[31m";
    pub const YELLOW: &str = "\x1b[33m";
    pub const BLUE: &str = "\x1b[34m";
    pub const PURPLE: &str = "\x1b[35m";
}

// tree style output
impl fmt::Display for MountTargetsMap<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        use colors::*;

        fn print_indents(f: &mut fmt::Formatter<'_>, indents: &[bool]) -> fmt::Result {
            let Some((&last, indents)) = indents.split_last() else {
                return Ok(()) // nothing to do if `indents` is empty
            };
            for &i in indents {
                write!(f, "{}", if i { "    " } else { "│   " })?;
            }

            write!(f, "{}", if last { "└──" } else { "├──" })
        }

        // `indents` indicates whether we've hit the last element for each
        // indent level
        fn print<'p, 'parent_scope, 'curr_scope>(
            f: &mut fmt::Formatter<'_>,
            node: &MountTargetNode<'p>,
            seg: &'p OsStr,
            indents: &mut Vec<bool>,
            dest_path: &'curr_scope mut ScopedPathAddition<'parent_scope, 'parent_scope>,
        ) -> fmt::Result {
            use MountTargetNode::*;

            let mut dest_path = dest_path.push(seg);
            let dest_path = &mut dest_path;

            print_indents(f, &indents)?;
            match &node {
                Exclude => writeln!(f, "{BOLD}{RED}{}{RESET} (excluded)", seg.display()),
                Include { source_path } => {
                    let source = if let Some(custom_source_path) = source_path {
                        custom_source_path.as_ref()
                    } else {
                        dest_path.as_c_str()
                    };
                    let source = cstring_as_path(source);
                    let symlink = fs::read_link(source);

                    let color = if symlink.is_ok() { PURPLE } else { RESET };
                    write!(f, "{color}{}{RESET}", seg.display())?;

                    if let Ok(symlink_dest) = symlink {
                        write!(f, " -> {ITALICS}{}{RESET}", symlink_dest.display())?;
                    }

                    if let Some(different_source) = source_path {
                        write!(f, " (from: {BOLD}{}{RESET})", different_source.display())?;
                    }

                    writeln!(f)
                }
                Neutral { children } => {
                    writeln!(f, "{BOLD}{BLUE}{}{RESET}", seg.display())?;
                    indents.push(false);

                    // make this a knob?
                    //
                    // actually: we don't really care about perf here; I think
                    // this doesn't have to be configurable.
                    let print_in_deterministic_order = true;
                    let sorted_children;
                    let it: Box<dyn Iterator<Item = _>> = if print_in_deterministic_order {
                        let mut vec = children.iter().collect::<Vec<_>>();
                        vec.sort_by(|(a, _), (b, _)| Ord::cmp(a, b));

                        sorted_children = vec;
                        Box::new(sorted_children.into_iter())
                    } else {
                        Box::new(children.iter())
                    };

                    let mut it = it.peekable();
                    while let Some((seg, node)) = it.next() {
                        // Once we've hit the end of the list, update `indents`
                        // for this level:
                        if it.peek().is_none() { *indents.last_mut().unwrap() = true; }

                        print(f, node, seg, indents, dest_path)?
                    }

                    indents.pop();
                    Ok(())
                }
            }
        }

        let mut indents = Vec::with_capacity(64);
        let mut dest_path = CStringGrowablePath::new("", true);
        let mut scoped = dest_path.scoped();
        print(f, &self.root, OsStr::new("/"), &mut indents, &mut scoped)
    }
}

mod tree_macros {
    use std::{ffi::OsStr, borrow::Cow};

    use crate::{MountTargetNode, utils::CStringGrowablePath};
    pub use crate::dir;
    #[macro_export]
    macro_rules! dir {
        ( $($path_seg:ident: $expr:expr),* $(,)? ) => {
            $crate::MountTargetNode::Neutral {
                children: ::std::collections::HashMap::from([$(
                    (::std::borrow::Cow::Borrowed(::std::stringify!($path_seg).as_ref()), $expr),
                )*])
            }
        };
    }

    pub use MountTargetNode::Exclude as Exc;
    #[allow(bad_style)]
    pub const Inc: MountTargetNode = MountTargetNode::Include { source_path: None };
    #[allow(bad_style)]
    pub fn IncWith(p: &'_ (impl AsRef<OsStr> + ?Sized)) -> MountTargetNode<'static> {
        MountTargetNode::Include { source_path: Some(Cow::Owned(CStringGrowablePath::new(p.as_ref(), false))) }
    }

    #[macro_export]
    macro_rules! cstr_static_literal {
        ($lit:literal) => {
            {
                static STR: &::std::ffi::CStr = {
                    static LIT: &[u8] = std::concat!($lit, "\0").as_bytes();

                    match ::std::ffi::CStr::from_bytes_with_nul(LIT) {
                        Ok(res) => res,
                        _ => unreachable!(),
                    }
                };

                STR
            }
        };
    }
    pub use crate::cstr_static_literal;
}

////////////////////////////////////////////////////////////////////////////////

#[no_mangle]
extern "C" fn test() {
    debug!("heyyyy");

    use tree_macros::*;
    let mut map: MountTargetsMap = dir! {
        foo: Exc,
        bar: IncWith("/tmp/bar"),
        baz: Inc,
        quux: dir! {
            blah: Exc,
            blue: IncWith("/tmp/foo/bar"),
            bleh: dir! { keep: Inc },
            blee: Exc,
        }
    }.into();

    debug(|out| {
        writeln!(out, "mount map:\n{:#}\n", map).unwrap();

        map.prune_excludes_with_callback(Some(|x: &CStr| eprintln!("excluding path: {}", x.display())));
        writeln!(out, "mount map:\n{:#}\n", map).unwrap();
    });

    // panic!();
}

#[no_mangle]
unsafe extern "C" fn handle_mounts<'p>(
    sandbox_base_path: *const c_char,
    sandbox_base_path_len: usize, // excluding nul terminator
    bind_mount_sources_array: *const *const c_char,
    bind_mount_dests_array: *const *const c_char,
    bind_mount_count: usize, // length of the bind mount sources and dests arrays
    hard_exclude_paths_array: *const *const c_char,
    hard_exclude_paths_array_len: usize,
    soft_exclude_paths_array: *const *const c_char,
    soft_exclude_paths_array_len: usize,
) {
    let sandbox_base_path = unsafe {
        let slice = std::slice::from_raw_parts(
            sandbox_base_path as *const _,
            sandbox_base_path_len + 1,
        );
        CStr::from_bytes_with_nul_unchecked(slice)
    };

    let bind_mount_sources = unsafe { std::slice::from_raw_parts(
        bind_mount_sources_array as *const *const c_char,
        bind_mount_count,
    ) };
    let bind_mount_dests = unsafe { std::slice::from_raw_parts(
        bind_mount_dests_array as *const *const c_char,
        bind_mount_count,
    ) };
    let hard_excludes = unsafe { std::slice::from_raw_parts(
        hard_exclude_paths_array as *const *const c_char,
        hard_exclude_paths_array_len,
    ) };
    let soft_excludes = unsafe { std::slice::from_raw_parts(
        soft_exclude_paths_array as *const *const c_char,
        soft_exclude_paths_array_len,
    ) };

    let mut mounts = Vec::with_capacity(bind_mount_sources.len() + hard_excludes.len());
    let bind_mounts = bind_mount_sources.iter().zip(bind_mount_dests.iter()).map(|(&src, &dest)| {
        let dest_path = unsafe { CStr::from_ptr::<'p>(dest) };

        // NOTE: this incurs an O(N) strlen on dest/src...
        //
        // tricky to elide this, not sure if we want to (we need the len to do
        // things like clone the path for splatting efficiently)
        if src == dest {
            // relying on pointer equality to tell us whether src and dest are
            // the same
            //
            // it is definitely possible for src and dest to point to different
            // allocations but to have the same contents but this is rare: it
            // only happens when an explicit `-M -m` pair is passed in with
            // the same source/dest path
            //
            // it seems not worth doing the extra O(n) scan to check for this
            Mount::Include { dest: dest_path, explicit_src: None }
        } else {
            Mount::Include {
                dest: dest_path,
                explicit_src: Some(unsafe { CStr::from_ptr(src) })
            }
        }
    });
    let hard_excludes = hard_excludes.iter().map(|&path| {
        // see above; this too is a O(N) operation (`strlen`)
        Mount::HardExclude { dest: unsafe { CStr::from_ptr::<'p>(path) } }
    });
    mounts.extend(bind_mounts.chain(hard_excludes));

    let mut soft_excludes = soft_excludes.iter().map(|&path| {
        unsafe { CStr::from_ptr::<'p>(path) }
    }).collect();

    apply_mounts_inner(sandbox_base_path, &mut mounts, &mut soft_excludes)
}

fn apply_mounts_inner<'p>(
    sandbox_base_path: &'p CStr,
    mounts: &mut Vec<Mount<'p>>,
    soft_excludes: &mut Vec<&'p CStr>,
) {
    use colors::{BOLD, BLUE, RESET, RED};

    // bind mounts and hard excludes:
    {
        // Check that mounts do not have *sources* beneath the sandbox base:
        if_debug(|| {
            for m in mounts.iter() {
                if let Mount::Include { dest, explicit_src: Some(src) } = m {
                    if cstring_as_path(src).starts_with(cstring_as_path(sandbox_base_path)) {
                        warn!(
                            "{}bind mount source is beneath the sandbox base!{} \
                            \n    - bind mount from `{}` to `<sandbox>{}`\
                            \n    - if the source is itself a bind mount \
                            target this will lead to unpredictable behavior; \
                            bind mount ordering is not preserved and mounts are \
                            created in a topological order that's not \
                            deterministic (due to hashmap iteration ordering)",
                            RED, RESET,
                            src.display(), dest.display(),
                        );
                    }
                }
            }
        });

        let mut mount_map = MountTargetsMap::new();
        mount_map.insert_paths(mounts);

        debug!("mount map:\n{mount_map:#}\n\n");

        // Prune excludes (to potentially speed up apply a little bit? unsure;
        // this lets us skip creating only-excluded parent dirs) and print out
        // the excluded paths up-front:
        mount_map.prune_excludes_with_callback(if_debug(|| {
            |x: &CStr| debug!("excluding path: {}", x.display())
        }));

        // apply!
        mount_map.apply(cstring_as_path(sandbox_base_path));
    }

    // soft excludes:
    {
        // we'll sort soft excludes so that there's at least _some_ consistency:
        soft_excludes.sort();

        // Note: if a `mountat` syscall existed (in the style of `fstatat`) we'd
        // use it here; it'd let us avoid allocating to prefix the soft exclude
        // paths with the sandbox base.
        let mut dest_path = CStringGrowablePath::new(cstring_as_path(sandbox_base_path), true);
        let mut dest_path = dest_path.scoped();
        let mut debug_map = MountTargetsMap::new();

        // mkdir (allow dup) empty dir -- make inaccessible (no rx bits)?
        static EMPTY_DIR: &CStr = tree_macros::cstr_static_literal!("tmp/empty_dir");
        fs_utils::mkdir_idempotent(EMPTY_DIR, None); // TODO: not making inaccessible for now..

        for ex in soft_excludes {
            // we're pushing more than 1 segment but its fine
            let exclude_path = dest_path.push(cstring_as_path(&ex).as_os_str());
            let Some(kind) = fs_utils::stat(exclude_path.as_c_str()) else {
                warn!(
                    "exclude path `<sandbox>{}` does not exist, skipping",
                    ex.display(),
                );

                continue
            };

            // debug print at the top so error messages have context:
            debug!("excluding {kind:#} at `<sandbox>{}`", ex.display());

            // if file: mount empty file
            // if dir: mount empty dir
            // debug: if symlink: yell
            let source = match kind {
                Dir => EMPTY_DIR,
                File | Other => EMPTY_FILE,
                Symlink => {
                    warn!(
                        "exclude path `<sandbox>{}` is a symlink! attempting \
                        to bind mount onto this path will result in the bind \
                        mount's target being the *target* of the symlink \
                        rather than the path of the symlink... treating as a \
                        file (this will fail if the symlink's target is not a \
                        file)",
                        ex.display(),
                    );
                    EMPTY_FILE
                },
            };

            // debug: add to soft exclude tree... (with the file/dir as mount source)
            if_debug(|| {
                debug_map.insert(ex, Some(source), false);
            });

            // debug: if beneath symlink: complain
            //
            // mirrors the check in `MountTargetsMap::apply`
            if_debug(|| {
                let Some(parent) = cstring_as_path(exclude_path.as_c_str()).parent() else { return; };
                let Ok(canon) = parent.canonicalize() else { return; };
                if canon != parent { warn!(
                    "exclude at `<sandbox>{}` contains a symlink somewhere in \
                    its ancestors! \
                    \n  - parent of exclude:    `{par}` \
                    \n  - actually resolves to: `{can}` \
                    \n\n This will result in the exclude's bind mount being
                    placed beneath the resolved path above.",
                    ex.display(), par = parent.display(), can = canon.display(),
                ) }
            });

            fs_utils::bind_mount(source, exclude_path.as_c_str());
        }

        // debug: print soft exclude tree
        debug!("soft exclude map (warning: may be misleading):\n{debug_map:#}\n\n");
    }

    // counts:
    let (splat_count, mounts_from_splatting) = MountTargetsMap::get_splat_counts();
    debug!(
        "counts:\
        \n  - bind mounts: {}\
        \n  - splats: {}\
        \n  - mounts from splatting: {}",
        fs_utils::get_bind_mount_count(),
        splat_count, mounts_from_splatting
    )
}

////////////////////////////////////////////////////////////////////////////////

#[test]
fn path_addition_scope() {
    // Everything in the C89 style comments (/* ... */) is a compile time error
    // when uncommented.

    use crate::utils::CStringGrowablePath;
    let mut base = CStringGrowablePath::new("", false);
    let mut scope = base.scoped();

    let mut foo = scope.push("foo".as_ref());

    // Can't do this! `foo` is live
    /* base.push("oops".as_ref()); */
    // Can't do this! `foo` is live
    /* eprintln!("{}", base.as_c_str().display()); */

    let mut bar = foo.push("bar".as_ref());

    // Can't do this! `bar` is still live; the underlying buffer returned by
    // `as_c_str` will contain extra path segments..
    /* eprintln!("{}", foo.as_c_str().display()); */

    let mut baz = bar.push("baz".as_ref());

    // Works transitively; can't do this since `baz` borrows `bar` which borrows
    // `foo` which borrows `scope` which borrows `base`:
    /* bar.as_c_str(); */
    /* foo.push("oops".as_ref()); */
    /* scope.push("oops".as_ref()); */
    /* base.as_c_str(); */

    // Can use parents once children run out their scope or are dropped:
    {
        let quux = baz.push("quux".as_ref());
        assert_eq!("/foo/bar/baz/quux", quux.as_c_str().to_string_lossy());
    }

    assert_eq!("/foo/bar/baz", baz.as_c_str().to_string_lossy());
    drop(baz);

    assert_eq!("/foo/bar", bar.as_c_str().to_string_lossy());
    drop(bar);

    assert_eq!("/foo", foo.as_c_str().to_string_lossy());
    drop(foo);

    drop(scope);
    assert_eq!("", base.as_c_str().to_string_lossy());
}

#[cfg(test)]
mod exclude_pruning_tests {
    use super::MountTargetsMap;
    use crate::tree_macros::*;

    #[track_caller]
    fn exclude_test<'p>(source: impl Into<MountTargetsMap<'p>>, expected: impl Into<MountTargetsMap<'p>>) {
        let source = source.into();
        let expected = expected.into();

        let mut got = source.clone();
        got.prune_excludes();

        assert_eq!(
            expected, got,
            "Expected pruning sources from:\n{s}\nTo produce:\n{e}\nBut got:\n{g}",
            s = source,
            e = expected,
            g = got,
        )
    }

    #[test]
    fn exclude() {
        let before = dir! {
            foo: Exc,
            bar: Inc,
            baz: IncWith("/tmp/foo"),
            quux: dir! {
                blah: Exc,
                blue: IncWith("/tmp/foo/bar"),
                bleh: dir! { keep: Inc },
                blee: Exc,
            }
        };
        let after = dir! {
            bar: Inc,
            baz: IncWith("/tmp/foo"),
            quux: dir! {
                blue: IncWith("/tmp/foo/bar"),
                bleh: dir! { keep: Inc },
            }
        };

        exclude_test(before, after)
    }

    #[test]
    fn exclude_transitive() {
        let before = dir! {
            foo: Exc,
            bar: Inc,
            baz: IncWith("/tmp/foo"),
            quux: dir! {
                blah: Exc,
                blue: dir! {
                    elide: Exc,
                    skip: Exc,
                    remove: Exc,
                },
                bleh: dir! { omit: Exc },
                blee: Exc,
            }
        };
        let after = dir! {
            bar: Inc,
            baz: IncWith("/tmp/foo"),
        };

        exclude_test(before, after)
    }
}

#[cfg(test)]
mod insert_tests {
    use super::{MountTargetsMap, Mount};
    use crate::tree_macros::*;

    macro_rules! inc {
        ($dest:literal $(from $alt_src:literal)?) => {
            {
                let dest = cstr_static_literal!($dest);
                let _alt_src = None::<&::std::ffi::CStr>;
                $(
                    let _alt_src = Some(cstr_static_literal!($alt_src));
                )?

                Mount::Include { dest, explicit_src: _alt_src }
            }
        };
    }
    macro_rules! exc {
        ($dest:literal) => {
            Mount::HardExclude { dest: cstr_static_literal!($dest) }
        };
    }

    #[track_caller]
    fn insert_test<'p, const PRUNE_EXCLUDES: bool>(mounts: &'p [Mount<'p>], expected: impl Into<MountTargetsMap<'p>>) {
        let expected = expected.into();

        let mut got = MountTargetsMap::new();
        let mut mutable_mounts = Vec::from(mounts);
        got.insert_paths(&mut mutable_mounts);

        if PRUNE_EXCLUDES {
            got.prune_excludes();
        }

        assert_eq!(
            expected, got,
            "Expected inserting mounts from:\n{m:?}\nTo produce:\n{e}\nBut got:\n{g}",
            m = mounts,
            e = expected,
            g = got,
        )
    }

    #[test]
    fn simple() {
        let mounts = [
            inc!("/a"),
            inc!("/b"),
            inc!("/c"),
            exc!("/d"),
            inc!("/e" from "/foo"),
            inc!("/foo/bar/baz"),
            exc!("/foo/bar/blue"),
        ];

        let expected = dir! {
            a: Inc,
            b: Inc,
            c: Inc,
            d: Exc,
            e: IncWith("/foo"),
            foo: dir! {
                bar: dir! {
                    baz: Inc,
                    blue: Exc,
                },
            },
        };

        insert_test::<false>(&mounts, expected)
    }

    #[test]
    fn elide() {
        let mounts = [
            inc!("/etc"),
            inc!("/etc/fstab"),
            // this is elided even though it doesn't exist (but we do get a warning)
            inc!("/etc/_som_path_that_definitely_doesnt_exist"),

            exc!("/foo"),
            exc!("/foo/bar"),
            exc!("/foo/bar/baz/blue/blah"),
        ];

        let expected = dir! {
            etc: Inc,
            foo: Exc,
        };

        insert_test::<false>(&mounts, expected);
    }

    #[test]
    #[ignore]
    fn splat() {
        let mounts = [
            inc!("/etc"),
            exc!("/etc/fstab"),
        ];

        let expected = dir! {
            etc: Inc,
        };

        insert_test::<false>(&mounts, expected);
    }

    #[test]
    #[should_panic = "cannot mount the root path"]
    fn cant_mount_root_path() {
        let mounts = [
            inc!("/foo"),
            inc!("/"),
        ];

        insert_test::<false>(&mounts, dir!{});
    }

    #[test]
    #[should_panic = "Conflicting bind mounts at path `/foo`"]
    fn conflicting_inc_mounts() {
        let mounts = [
            inc!("/foo"),
            inc!("/foo" from "/bar"),
        ];

        insert_test::<false>(&mounts, dir!{})
    }

    #[test]
    fn conflicting_inc_and_exc_mounts() {
        let mounts = [
            inc!("/foo"),
            exc!("/foo"),
        ];

        // excludes have priority over includes (they are applied later)
        insert_test::<false>(&mounts, dir!{ foo: Exc })
    }

    #[test]
    fn nested_splatting() {
        let mounts = [
            exc!("/proc"),
            inc!("/proc/bus/input/devices"),
        ];

        let expected = dir! {
            proc: dir! { bus: dir! { input: dir! { devices: Inc } } },
        };

        insert_test::<true>(&mounts, expected);
    }

    #[test]
    fn overlap() {
        // more specific should win
        let mounts = [
            exc!("/etc"),
            inc!("/etc/fstab"),
        ];

        let expected = dir! {
            etc: dir! { fstab: Inc },
        };

        // prune excludes
        //
        // NOTE: in this case splatting the exclude is wasteful...
        // TODO: if we're guaranteed to only get more specific mounts as we
        // proceed is splatting excludes _always_ unnecessary? not sure
        //   - update: I think so... we just need to be able to specify exclude
        //     paths so that, when applied, they splat/alter the mount tree as
        //     needed
        //   - we don't actually need a node variant for exclude (though it is
        //     nice for visualization/debugging)
        insert_test::<true>(&mounts, expected);
    }


    #[test]
    fn more_specific() {
        // bad; presumes that `/proc/bus` only has `pci` and `input` subdirs..
        let mounts = [
            exc!("/proc"),
            inc!("/proc/bus"),
            exc!("/proc/bus/input"),
            inc!("/proc/bus/input/devices"),
        ];

        let expected = dir! {
            proc: dir! {
                bus: dir! {
                    input: dir! { devices: Inc },
                    pci: Inc,
                },
            },
        };

        insert_test::<true>(&mounts, expected);
    }

    #[test]
    fn explicit_source() {
        let mounts = [
            inc!("/foo" from "/proc/bus"),
            exc!("/foo/pci"),
            inc!("/foo/bar" from "/proc/bus/pci"),
            inc!("/foo/baz" from "/proc/bus/input"),
            exc!("/foo/baz/devices"),
        ];

        let expected = dir! {
            foo: dir! { // /proc/bus:
                pci: Exc,
                input: IncWith("/proc/bus/input"),
                bar: IncWith("/proc/bus/pci"),
                baz: dir! {
                    devices: Exc,
                    handlers: IncWith("/proc/bus/input/handlers"),
                }
            }
        };

        insert_test::<false>(&mounts, expected);
    }
}
