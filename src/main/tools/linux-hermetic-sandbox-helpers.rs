
mod utils {
    use std::fs::File;
    use std::io::Write;
    use std::os::fd::BorrowedFd;
    use std::sync::OnceLock;
    use std::panic::Location;

    use nix::time::{self, ClockId};

    extern "C" {
        /// From `./logging.h`:
        static global_debug: *mut libc::FILE;
    }

    #[track_caller]
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
    pub fn debug(func: impl FnOnce(&mut dyn std::io::Write)) { if_debug(move || {
        #[cfg(test)]
        {
            // when running tests, include the debug output; put it on stderr:
            //
            // note: we're using `eprintln!` rather than writing to
            // `std::io::stderr()` directly so that our output is captured by
            // the `libtest` stderr capture machinery
            let mut out = Vec::new();
            func(&mut out);
            let loc = Location::caller();
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
            let loc = Location::caller();
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
}


#[no_mangle]
extern "C" fn test() {
    debug!("heyyyy")
}
