/// `b"MAML"`, little-endian, at offset 0.
const MAGIC: [u8; 4] = *b"MAML";
/// Bumped when the layout below changes incompatibly.
///
/// Version 1 is header + table + blob. Version 2 appends a graph section after the blob
/// (see [`Graph`]); a v1 reader rejects a v2 file here, loudly, rather than running a
/// topology it cannot see.
#[cfg(test)]
const FORMAT_VERSION: u32 = 1;
/// The newest version this reader accepts. Kept distinct from [`FORMAT_VERSION`] — the
/// version *written* by the converter — so emitting v2 files is a separate, deliberate
/// step from reading them.
const FORMAT_READ_VERSION: u32 = 2;
const HEADER_BYTES: usize = 64;
const TENSOR_ENTRY_BYTES: usize = 32;
