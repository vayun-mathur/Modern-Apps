//! Peak resident set size, so a build reports its own memory ceiling.
//!
//! The planet-scale gates for `road_graph` are memory gates: the question is
//! never "did it finish" but "how close did it come to the box". Having the tool
//! print its own `VmHWM` means every run is a measurement, including the ones
//! nobody thought to instrument, and it removes the step where a gate is judged
//! from a `top` reading somebody happened to catch.
//!
//! `VmHWM` is a high-water *mark*, so it survives the frees that a `top` sample
//! between two phases would miss. It does not include the page cache or dirty
//! pages, which is the one thing it cannot answer and the reason the graph
//! writer prefers sequential writes to computed-offset ones.

/// Peak RSS in bytes, or `None` where the platform does not expose it.
///
/// Only Linux is implemented: that is where the scale gates run, and the
/// alternative on other platforms is a raw FFI declaration to carry a number
/// nothing reads.
pub fn peak_rss_bytes() -> Option<u64> {
    #[cfg(target_os = "linux")]
    {
        let status = std::fs::read_to_string("/proc/self/status").ok()?;
        parse_vm_hwm(&status)
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// `VmHWM:\t  123456 kB` -> bytes.
#[cfg(any(target_os = "linux", test))]
fn parse_vm_hwm(status: &str) -> Option<u64> {
    let line = status.lines().find(|l| l.starts_with("VmHWM:"))?;
    let kb: u64 = line
        .split_whitespace()
        .nth(1)
        .and_then(|v| v.parse().ok())?;
    Some(kb * 1024)
}

/// `"4.97 GB"`, or a note that the platform does not report it. Callers put this
/// straight into a log line.
pub fn peak_rss_report() -> String {
    match peak_rss_bytes() {
        Some(b) => format!("{:.2} GB", b as f64 / (1u64 << 30) as f64),
        None => "not reported on this platform".to_string(),
    }
}

/// A file mapped read-only, dereferencing to its bytes.
///
/// The same bytes `std::fs::read` would hand back, but paged by the OS instead of copied onto the
/// heap: no commit charge up front (which is what `mmap(MAP_ANON)` spills pay but file mappings do
/// not), and the resident cost is the working set rather than the file. Junction walks
/// `nodes.bin` in order and probes `edges.bin`/`intermediate.bin` positionally, so this is strictly
/// cheaper than the `Vec<u8>` it replaces.
///
/// The mapping owns its `File` so the handle outlives every read; an empty file maps to an empty
/// slice rather than erroring, so callers keep their own length checks and their own messages.
pub struct Mapped {
    // Held so the mapping's file description outlives the reads. Never read through directly.
    _file: std::fs::File,
    map: Option<memmap2::Mmap>,
}

impl Mapped {
    pub fn open(path: &std::path::Path) -> Result<Mapped, String> {
        let file = std::fs::File::open(path)
            .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        let len = file
            .metadata()
            .map_err(|e| format!("cannot stat {}: {e}", path.display()))?
            .len();
        // `memmap2` refuses an empty file; an empty graph file is a length error the caller
        // reports, not a mapping error here.
        let map = if len == 0 {
            None
        } else {
            // SAFETY: read-only, and the file is never truncated while mapped -- every writer in
            // this tree creates with `truncate` and closes before any reader opens.
            Some(unsafe { memmap2::Mmap::map(&file) }
                .map_err(|e| format!("cannot map {}: {e}", path.display()))?)
        };
        Ok(Mapped { _file: file, map })
    }
}

impl std::ops::Deref for Mapped {
    type Target = [u8];

    fn deref(&self) -> &[u8] {
        self.map.as_deref().unwrap_or(&[])
    }
}

// --- budgets: how much commit and disk a phase may ask for -------------------------------
//
// The planet build died at the junction tail with `cannot commit anon segment` (OS error 1455):
// anonymous spills charge RAM+pagefile ("commit"), and nothing checked the charge against the
// limit before asking. These queries let each phase size its request from counts it already has
// (node/way/relation counts, graph metadata) and either pick a file backend or fail fast with
// numbers, instead of paging 1767 s into stage A.
//
// All `Option`: a platform that cannot answer (or a sandboxed `/proc`) warns and proceeds with
// the historical behaviour rather than refusing to build.

/// Bytes of commit (RAM + pagefile) the OS will still grant, or `None` if unknown.
///
/// Windows: `ullAvailPageFile` -- the number 1455 is charged against. Linux: `CommitLimit - Committed_AS`.
pub fn commit_avail_bytes() -> Option<u64> {
    #[cfg(windows)]
    {
        let st = memory_status()?;
        Some(st.ull_avail_page_file)
    }
    #[cfg(target_os = "linux")]
    {
        let info = proc_meminfo()?;
        Some(info.commit_limit?.saturating_sub(info.committed_as?))
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        None
    }
}

/// The commit ceiling itself, for messages (`need X of Y`).
pub fn commit_limit_bytes() -> Option<u64> {
    #[cfg(windows)]
    {
        Some(memory_status()?.ull_total_page_file)
    }
    #[cfg(target_os = "linux")]
    {
        proc_meminfo()?.commit_limit
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        None
    }
}

/// Bytes free for the caller on the volume holding `path`, or `None` if unknown.
pub fn disk_free_bytes(path: &std::path::Path) -> Option<u64> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        let dir = path.parent().unwrap_or(path);
        let mut wide: Vec<u16> = dir.as_os_str().encode_wide().collect();
        wide.push(0);
        let mut avail: u64 = 0;
        // SAFETY: `wide` is null-terminated and outlives the call; the three trailing
        // pointers are valid `*mut u64` or null, and null is accepted for the totals.
        let ok = unsafe {
            platform::GetDiskFreeSpaceExW(wide.as_ptr(), &mut avail, std::ptr::null_mut(), std::ptr::null_mut())
        };
        (ok != 0).then_some(avail)
    }
    #[cfg(target_os = "linux")]
    {
        let dir = path.parent().unwrap_or(path);
        let cstr = std::ffi::CString::new(dir.as_os_str().as_encoded_bytes()).ok()?;
        // SAFETY: `cstr` is a valid null-terminated path; `statvfs` only reads through it.
        let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
        if unsafe { libc::statvfs(cstr.as_ptr(), &mut st) } != 0 {
            return None;
        }
        (st.f_bavail as u64).checked_mul(st.f_bsize as u64)
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        let _ = path;
        None
    }
}

#[cfg(target_os = "linux")]
struct Meminfo {
    commit_limit: Option<u64>,
    committed_as: Option<u64>,
}

#[cfg(target_os = "linux")]
fn proc_meminfo() -> Option<Meminfo> {
    let text = std::fs::read_to_string("/proc/meminfo").ok()?;
    let mut out = Meminfo { commit_limit: None, committed_as: None };
    for line in text.lines() {
        // `CommitLimit:   123456 kB` -- the field width varies, the unit does not.
        let mut parts = line.split_whitespace();
        let (key, value) = (parts.next()?, parts.next()?);
        let kb: u64 = value.parse().ok()?;
        match key {
            "CommitLimit:" => out.commit_limit = Some(kb.saturating_mul(1024)),
            "Committed_AS:" => out.committed_as = Some(kb.saturating_mul(1024)),
            _ => {}
        }
    }
    Some(out)
}

/// What stage A expects to hold at once, sized from counts pass 1 already measured.
///
/// Everything here is known before the bytes are asked for: `way_refs`/`way_max_ref` come from
/// [`WaySink::finish`](crate::store::WaySink) (every ref walked to encode it), `distinct` from
/// the collector, the ways-spill length from the sealed store, the feature estimate from the
/// feature count times a calibrated bytes-per-feature, and the graph from `metadata.bin`'s 40-byte
/// header. The gate compares the sum against the commit limit and the spill volume's disk free
/// and either picks the file backend or fails fast naming the numbers -- instead of paging 1767 s
/// in with `cannot commit anon segment`.
///
/// Relations move the needle less (8.4 M vs 1.1 B ways on planet) and are counted approximately:
/// member refs are already inside `distinct`; the `Relation` structs themselves are `members *
/// overhead`, estimated, not measured.
#[derive(Debug, Default, Clone)]
pub struct StageBudget {
    /// Distinct node ids the coordinate table must hold.
    pub distinct_nodes: u64,
    /// Largest node id the bitset/rank index is sized over.
    pub max_node_id: u64,
    /// Sealed ways-spill bytes (the delta-coded classified ways).
    pub ways_spill_bytes: u64,
    /// Features already spilled (labels so far); times `bytes_per_feature` for the spill so far.
    pub features_spilled: u64,
    /// Features still to come (ways + relations + externals, estimated from classified counts).
    pub features_expected: u64,
    /// Calibrated spill bytes per feature (measure one small build, reuse everywhere).
    pub bytes_per_feature: u64,
    /// Routing-graph bytes that must be addressable at once (nodes+edges+intermediate+lanes files
    /// plus the `InEdges` reverse index), from `metadata.bin` before anything is mapped.
    pub graph_bytes: u64,
    /// Resident relation-member table entries (relation count), estimated overhead each.
    pub relations: u64,
}

impl StageBudget {
    /// Rank bitset + directory over `0..=max_node_id`: `max/8` words plus one prefix-sum u64 per
    /// 8 words. The collector's own bitset is already folded in (see `from_bitset`): this is the
    /// one surviving copy.
    pub fn rank_bytes(&self) -> u64 {
        let words = (self.max_node_id.saturating_add(1)).div_ceil(64);
        words.saturating_mul(8).saturating_add(words.div_ceil(8).saturating_mul(8))
    }

    /// Per-node resolved bit (`distinct/8`) plus the mapped coordinate file (`distinct*8`).
    /// The file is disk-backed, not commit, but it still has to fit the volume -- counted in
    /// `disk_bytes` rather than here.
    pub fn resolved_bytes(&self) -> u64 {
        self.distinct_nodes.div_ceil(8)
    }

    /// Coordinate file bytes on the temp volume.
    pub fn locs_file_bytes(&self) -> u64 {
        self.distinct_nodes.saturating_mul(8)
    }

    /// Relation structs kept resident between passes (members table + `Relation` vec).
    pub fn relations_bytes(&self) -> u64 {
        // ~64 B per relation entry (HashMap node + Vec header + struct) plus ~16 B per member id.
        // Approximate: the members' refs are already inside `distinct_nodes`; this is the structs.
        self.relations.saturating_mul(96)
    }

    /// Feature spill so far plus what is still to come.
    pub fn feature_spill_bytes(&self) -> u64 {
        self.features_spilled
            .saturating_add(self.features_expected)
            .saturating_mul(self.bytes_per_feature.max(1))
    }

    /// Commit charged if everything stages anonymously: rank + resolved + ways spill +
    /// the feature spill (the graph is the caller's choice -- see `plan`).
    ///
    /// The feature spill used to be missing here, so a planet gate compared ~43 GB
    /// (rank + resolved + ways) against the allowance while the anon feature spill
    /// itself needed hundreds of GB more. The build then paid for it 1767 s in with
    /// `cannot commit anon segment` -- or, when it survived stage A, left the tiling
    /// phase with no commit headroom and OOM'd merging z14. Counting it pushes
    /// planet-scale builds to files (same records, removed on success), which is the
    /// safe side.
    pub fn anon_commit_bytes(&self) -> u64 {
        self.rank_bytes()
            .saturating_add(self.resolved_bytes())
            .saturating_add(self.ways_spill_bytes)
            .saturating_add(self.feature_spill_bytes())
    }

    /// Disk needed on the spill volume if the spills go to files: ways spill + feature spill +
    /// locs file.
    pub fn disk_bytes(&self) -> u64 {
        self.ways_spill_bytes
            .saturating_add(self.feature_spill_bytes())
            .saturating_add(self.locs_file_bytes())
    }
}

/// Which backend a spill stages in, and why.
///
/// Anonymous memory charges commit (RAM+pagefile) and needs no directory entry; files charge the
/// spill volume's free space and survive nothing on kill (existing `Drop` impls remove them).
/// Same records, same offsets, same output bytes either way -- the backend never changes a hash.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpillPlan {
    /// Commit headroom covers the anon charge with margin: keep the historical behaviour.
    Anon,
    /// Commit is tight or the estimate exceeds it: stage in files on the spill volume instead.
    /// No retained storage growth -- the files are removed on success like every other `.tmp`.
    File,
}

impl SpillPlan {
    /// Decide from a budget, the spill path (for its volume's free space) and a safety margin.
    ///
    /// Fails fast with the numbers when *neither* backend fits: that build cannot succeed on this
    /// machine, and saying so in seconds beats `cannot commit anon segment` after 1767 s. `context`
    /// names the phase (`"stage A ways spill"`) so the message says where the bytes go.
    pub fn decide(budget: &StageBudget, spill_path: &std::path::Path, context: &str) -> Result<SpillPlan, String> {
        // Half the commit limit is the most anon staging may claim: the other half is the heap
        // (relations, members, rayon buffers), the mapped graph, and the OS itself. A build that
        // needs more anon than that is one bad estimate from 1455.
        const ANON_SHARE_NUM: u64 = 1;
        const ANON_SHARE_DEN: u64 = 2;
        let need_anon = budget.anon_commit_bytes();
        let need_disk = budget.disk_bytes();

        match (commit_avail_bytes(), commit_limit_bytes(), disk_free_bytes(spill_path)) {
            (Some(avail), Some(limit), disk) => {
                let allowance = limit / ANON_SHARE_DEN * ANON_SHARE_NUM;
                if need_anon <= allowance.min(avail) {
                    return Ok(SpillPlan::Anon);
                }
                // Anon does not fit: files do if the volume holds them.
                match disk {
                    Some(free) if need_disk <= free => Ok(SpillPlan::File),
                    Some(free) => Err(format!(
                        "{context}: needs {} commit for anon staging ({} avail of {}) and {} on \
                         {} for file staging ({} free) -- neither fits; free pagefile or point \
                         the spill at a larger volume",
                        fmt_gb(need_anon),
                        fmt_gb(avail),
                        fmt_gb(limit),
                        fmt_gb(need_disk),
                        spill_path.display(),
                        fmt_gb(free),
                    )),
                    None => Err(format!(
                        "{context}: needs {} commit for anon staging ({} avail of {}), and the \
                         spill volume's free space is unknown -- refusing anon rather than \
                         risking OS error 1455",
                        fmt_gb(need_anon),
                        fmt_gb(avail),
                        fmt_gb(limit),
                    )),
                }
            }
            // Unknown limits (non-Windows/Linux, sandbox): historical behaviour, no gate.
            _ => Ok(SpillPlan::Anon),
        }
    }
}

/// `"12.34 GB"` for budget messages.
pub fn fmt_gb(bytes: u64) -> String {
    format!("{:.2} GB", bytes as f64 / (1u64 << 30) as f64)
}

#[cfg(windows)]
struct MemoryStatus {
    ull_total_page_file: u64,
    ull_avail_page_file: u64,
}

#[cfg(windows)]
fn memory_status() -> Option<MemoryStatus> {
    // SAFETY: `st` is a valid `MEMORYSTATUSEX`-layout struct with `dw_length` set, which is the
    // one precondition `GlobalMemoryStatusEx` documents.
    unsafe {
        let mut st: platform::MemoryStatusEx = std::mem::zeroed();
        st.dw_length = std::mem::size_of::<platform::MemoryStatusEx>() as u32;
        if platform::GlobalMemoryStatusEx(&mut st) == 0 {
            return None;
        }
        Some(MemoryStatus {
            ull_total_page_file: st.ull_total_page_file,
            ull_avail_page_file: st.ull_avail_page_file,
        })
    }
}

#[cfg(windows)]
mod platform {
    #[repr(C)]
    pub struct MemoryStatusEx {
        pub dw_length: u32,
        pub dw_memory_load: u32,
        pub ull_total_phys: u64,
        pub ull_avail_phys: u64,
        pub ull_total_page_file: u64,
        pub ull_avail_page_file: u64,
        pub ull_total_virtual: u64,
        pub ull_avail_virtual: u64,
        pub ull_avail_extended_virtual: u64,
    }

    #[link(name = "kernel32")]
    extern "system" {
        pub fn GlobalMemoryStatusEx(lpBuffer: *mut MemoryStatusEx) -> i32;
        pub fn GetDiskFreeSpaceExW(
            lpDirectoryName: *const u16,
            lpFreeBytesAvailable: *mut u64,
            lpTotalNumberOfBytes: *mut u64,
            lpTotalNumberOfFreeBytes: *mut u64,
        ) -> i32;
    }
}

/// Ask the filesystem to store an EMPTY scratch file uncompressed. Best effort: a no-op
/// anywhere but Linux, and any refusal (not btrfs, flag unsupported, file already has
/// data) is ignored -- the file then behaves exactly as it did before.
///
/// Why this exists: the build box mounts btrfs with `compress-force=zstd:3`, which
/// compresses every extent in 128 KiB units regardless of whether the data shrinks. For
/// `nodeloc`'s 83 GB coordinate table (planet) the data does not shrink (`compsize`: 94%)
/// and the access is random 8-byte reads through a mapping, so every 4 KiB page fault that
/// misses the page cache inflates a whole 128 KiB extent: ~32x read amplification and a
/// zstd decode per fault. Measured on the Nov 10 planet build, the lane-inheritance scan
/// ran at 4-5 cores with ~190 threads in `D` state on those faults.
///
/// btrfs only skips compression (even under `compress-force`) for a NOCOW inode
/// (`FS_NOCOW_FL`, `chattr +C`), and NOCOW only takes on a file with no data yet -- so
/// call this right after `create`/`truncate`, before the first write. Scratch only: NOCOW
/// also drops data checksums, which is fine for a file the same process deletes.
///
/// Bytes read back are identical either way; this changes only how the filesystem stores
/// them.
pub fn mark_scratch_uncompressed(file: &std::fs::File) {
    #[cfg(target_os = "linux")]
    {
        use std::os::fd::AsRawFd;
        const FS_NOCOW_FL: libc::c_long = 0x0080_0000;
        let fd = file.as_raw_fd();
        // `c_long` per the ioctl's declared size; the kernel reads/writes the low int.
        let mut flags: libc::c_long = 0;
        // SAFETY: `fd` is a live descriptor owned by `file`; both ioctls only read or write
        // the flag word through the pointer, which outlives the calls.
        unsafe {
            if libc::ioctl(fd, libc::FS_IOC_GETFLAGS, &mut flags as *mut libc::c_long) != 0 {
                return;
            }
            if flags & FS_NOCOW_FL != 0 {
                return;
            }
            flags |= FS_NOCOW_FL;
            let _ = libc::ioctl(fd, libc::FS_IOC_SETFLAGS, &flags as *const libc::c_long);
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = file;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Best effort means it must never fail or disturb the file: whatever the filesystem
    /// under the temp dir, the file stays writable and reads back what was written.
    #[test]
    fn marking_scratch_uncompressed_never_changes_the_bytes() {
        use std::io::{Read, Seek, Write};
        let path = std::env::temp_dir().join(format!("osm_nocow_test_{}.bin", std::process::id()));
        let mut f = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .unwrap();
        mark_scratch_uncompressed(&f);
        f.write_all(b"same bytes either way").unwrap();
        f.rewind().unwrap();
        let mut back = String::new();
        f.read_to_string(&mut back).unwrap();
        drop(f);
        let _ = std::fs::remove_file(&path);
        assert_eq!(back, "same bytes either way");
    }

    #[test]
    fn vm_hwm_is_read_in_kilobytes() {
        let status = "Name:\troad_graph\nVmPeak:\t 9999999 kB\nVmHWM:\t 5212672 kB\nVmRSS:\t 12 kB\n";
        assert_eq!(parse_vm_hwm(status), Some(5_212_672 * 1024));
        // VmRSS must not be mistaken for the high-water mark, and a status file
        // without the field (older kernels, or a sandbox) is absent, not zero.
        assert_eq!(parse_vm_hwm("VmRSS:\t 12 kB\n"), None);
        assert_eq!(parse_vm_hwm("VmHWM:\n"), None);
    }

    #[test]
    fn the_report_is_a_string_on_every_platform() {
        let s = peak_rss_report();
        assert!(!s.is_empty());
        #[cfg(target_os = "linux")]
        assert!(s.ends_with(" GB"), "{s}");
    }
}
