extern crate nix; // to appease rust-analyzer

use std::{
    borrow::Cow,
    collections::{HashMap, hash_map::Entry},
    ffi::{CStr, OsStr},
    fmt,
    fs,
    os::unix::prelude::OsStrExt,
    path::{Path, Component, Components},
    sync::atomic::{AtomicUsize, Ordering}, cmp,
};

use nix::{NixPath, dir, fcntl::OFlag, sys::stat::Mode};

use crate::utils::{
    CStrNewType, OsStrDisplayExt, cstring_as_path, CStringGrowablePath,
    ScopedPathAddition, debug,
};

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

    pub fn if_debug(func: impl FnOnce()) {
        // We want to optimize for the common case (not running under debug
        // mode) so we do this check first (so that we can bail quicker).
        if (!unsafe { global_debug }.is_null()) || cfg!(test) /* enable for tests */ {
            func()
        }
    }

    /// Supposed to mimic `DEBUG` from `./logging.h`.
    #[track_caller]
    #[cfg_attr(test, allow(unreachable_code))]
    pub fn debug(func: impl FnOnce(&mut dyn std::io::Write)) {
        let loc = Location::caller();
    if_debug(move || {
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
    }) }

    #[macro_export]
    macro_rules! debug {
        ($($tt:tt)*) => {
            $crate::utils::debug(|o| {
                writeln!(o, $($tt)*).unwrap()
            })
        }
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
        const MAX_EXPECTED_PATH_LEN_IN_BYTES: usize = 1024;

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
        children: HashMap<&'p OsStr, MountTargetNode<'p>>,
    }
}

// wrapper type so that you cannot modify the internals...
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MountTargetsMap<'p> {
    root: MountTargetNode<'p>,
}


impl<'p> MountTargetsMap<'p> {
    pub fn new() -> Self {
        Self {
            root: MountTargetNode::Neutral { children: HashMap::new() },
        }
    }
}

impl<'p> MountTargetsMap<'p> {

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
        current: &'node mut MountTargetNode<'path>,
        new: MountTargetNode<'path>,
    ) {
        use MountTargetNode::*;

        match (current, new) {
        }
    }

    // must call in sorted order!
    pub fn insert(&mut self, dest_path: &'p CStr, src_path: Option<&'p CStr>, is_exclude: bool) {
    }
}

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
    #[allow(unused)]
    pub fn apply(self, sandbox_base: &Path) {
        // c string conversions necessary for passing the mount destination path
        // to syscalls (we need to prepend the sandbox base path)
        let mut dest_path = CStringGrowablePath::new(sandbox_base, true);

        fn handle(node: &MountTargetNode, mut dest_path: ScopedPathAddition<'_, '_>) {
            use MountTargetNode::*;
            match &node {
                Exclude => { /* nothing to do */ },
                Include { source_path } => {
                    let source = source_path.as_deref().map(|x| x.as_ref()).unwrap_or_else(|| dest_path.as_c_str());
                    let source = cstring_as_path(source);

                    fn stat(x: &Path) -> Option<()> { todo!() }

                    // debug check: if the parent of the source path has a
                    // symlink anywhere, we've elided a level of symlinks; warn

                    match stat(source).unwrap() { // panic if source doesn't exist!
                        is_dir => {
                            // mkdir (should not already exist; just allow it if it already exists to accomodate dirty sandbox bases...)
                            // mount
                        },
                        is_file => {
                            // touch (or rather hardlink the empty file?) | should not already exist
                            // mount
                        }
                        is_symlink => {
                            // if we support MS_NOSYMFOLLOW?
                            //   + touch empty file (should not exist!)
                            //   + mount
                            //   + ... actually, let's maybe not even bother with this
                            //     - not sure how to reliably check for `MS_NOSYMFOLLOW` at runtime and I don't know that this would be faster anyways
                            // else:
                            //   + make symlink file with same dest (actually.. copy so we don't need another syscall to read the target of the symlink)
                            //     * also preserves relative/absolute symlink
                            //   + don't check if dest exists or not
                            //   + make readonly
                        }
                    }
                },
                Neutral { children } => {
                    // assert that children is never empty; can't have a neutral
                    // node otherwise.. (todo: what about the root node?)
                    // yeah okay, don't bother

                    for (segment, child) in children {
                        let dest_path = dest_path.push(segment);
                        handle(child, dest_path)
                    }

                    // push
                    // mkdir sandbox_base + node.path (ensure dest does not exist / is a dir?)
                    //  - ideally we'd check that the dest is not a mountpoint
                    //    but that seems expensive..
                    //  - should *not* also assert that node.path is a directory
                    //    because the child(ren) mounts that resulted in this
                    //    node existing may all have sources that don't contain
                    //    `node.path`
                    // run `handle` for each child
                }
            }
        }
    }
}



#[no_mangle]
extern "C" fn test() {
    debug!("heyyyy")
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
