// Binary local transport for outbound `NetMsg`s (Rust -> Kotlin over JNI only).
//
// Why this exists: `ChunkData.data` is a raw VOX3 chunk (tens of KiB). Through
// `drain_outbound_json` serde_json renders every byte as a JSON number, so the
// JNI string is ~3-4x the chunk and Kotlin then pays a `JSONArray` parse of
// every number, only to re-serialise the same numbers for signing. This frame
// carries chunk payloads as raw bytes instead. All other variants are small
// and keep riding as their (single-message) serde JSON text.
//
// This is ONLY the local Rust<->Kotlin representation. It is never persisted,
// signed, encrypted or sent to the relay: Kotlin reconstructs the exact legacy
// signed-op JSON for a chunk (`OutboundFrame.chunkOpsJson`) before signing, so
// `SignedOp{author,sig,ops}` strings, `PendingOps` persistence/replay and the
// encrypted `AppendMsg` relay payloads stay byte-identical. Inbound keeps the
// JSON string leg for the same reason: the string Kotlin pushes is the signed
// `ops` payload itself, already verified verbatim.
//
// Frame (all integers little-endian):
//   "VXB1" u32 count, then per message:
//     kind u8: 0 = signed JSON, 1 = ChunkData, 2 = PlayerTransform JSON
//     kind 0/2: u32 json_len, json bytes (serde_json of the single NetMsg)
//     kind 1: dim u8, cx i32, cz i32, u32 data_len, raw chunk bytes

const BIN_MAGIC: &[u8; 4] = b"VXB1";
const KIND_JSON: u8 = 0;
const KIND_CHUNK: u8 = 1;
const KIND_TRANSFORM: u8 = 2;

/// Encodes a batch for the binary JNI drain. See the frame spec above.
pub fn encode_outbound_bin(msgs: &[NetMsg]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(BIN_MAGIC);
    out.extend_from_slice(&(msgs.len() as u32).to_le_bytes());
    for msg in msgs {
        match msg {
            NetMsg::ChunkData { dim, cx, cz, data } => {
                out.push(KIND_CHUNK);
                out.push(*dim);
                out.extend_from_slice(&cx.to_le_bytes());
                out.extend_from_slice(&cz.to_le_bytes());
                out.extend_from_slice(&(data.len() as u32).to_le_bytes());
                out.extend_from_slice(data);
            }
            other => {
                out.push(if matches!(other, NetMsg::PlayerTransform { .. }) { KIND_TRANSFORM } else { KIND_JSON });
                let json = serde_json::to_string(other).unwrap_or_else(|_| "{}".to_string());
                out.extend_from_slice(&(json.len() as u32).to_le_bytes());
                out.extend_from_slice(json.as_bytes());
            }
        }
    }
    out
}

/// Rust -> Kotlin: pull all queued outbound messages as a binary frame.
/// The JSON drain stays for tests/fallback; production Kotlin uses this.
pub fn drain_outbound_bin() -> Vec<u8> {
    let taken: Vec<NetMsg> = outbox().lock().map(|mut q| std::mem::take(&mut *q)).unwrap_or_default();
    encode_outbound_bin(&taken)
}

fn take<'a>(bytes: &'a [u8], off: &mut usize, len: usize) -> Option<&'a [u8]> {
    let end = off.checked_add(len)?;
    let slice = bytes.get(*off..end)?;
    *off = end;
    Some(slice)
}
fn take_u32(bytes: &[u8], off: &mut usize) -> Option<u32> {
    Some(u32::from_le_bytes(take(bytes, off, 4)?.try_into().ok()?))
}
fn take_i32(bytes: &[u8], off: &mut usize) -> Option<i32> {
    Some(i32::from_le_bytes(take(bytes, off, 4)?.try_into().ok()?))
}

/// Decodes a binary frame back into messages. `None` on any truncation or
/// corruption (never panics); used by tests to pin the frame format — the
/// production decoder is Kotlin's `OutboundFrame`.
pub fn decode_outbound_bin(bytes: &[u8]) -> Option<Vec<NetMsg>> {
    let mut off = 0usize;
    if take(bytes, &mut off, 4)? != BIN_MAGIC { return None; }
    let count = take_u32(bytes, &mut off)? as usize;
    // Each message costs at least one kind byte; reject absurd counts early.
    if count > bytes.len() { return None; }
    let mut msgs = Vec::with_capacity(count);
    for _ in 0..count {
        let kind = *take(bytes, &mut off, 1)?.first()?;
        match kind {
            KIND_CHUNK => {
                let dim = *take(bytes, &mut off, 1)?.first()?;
                let cx = take_i32(bytes, &mut off)?;
                let cz = take_i32(bytes, &mut off)?;
                let len = take_u32(bytes, &mut off)? as usize;
                let data = take(bytes, &mut off, len)?.to_vec();
                msgs.push(NetMsg::ChunkData { dim, cx, cz, data });
            }
            KIND_JSON | KIND_TRANSFORM => {
                let len = take_u32(bytes, &mut off)? as usize;
                let text = std::str::from_utf8(take(bytes, &mut off, len)?).ok()?;
                let msg: NetMsg = serde_json::from_str(text).ok()?;
                if (kind == KIND_TRANSFORM) != matches!(msg, NetMsg::PlayerTransform { .. }) { return None; }
                msgs.push(msg);
            }
            _ => return None,
        }
    }
    if off != bytes.len() { return None; }
    Some(msgs)
}

#[cfg(test)]
mod bin_tests {
    use super::*;

    fn chunk_msg(data: Vec<u8>) -> NetMsg {
        NetMsg::ChunkData { dim: 1, cx: -2, cz: 3, data }
    }

    #[test]
    fn binary_frame_round_trips_chunk_bytes_at_all_sizes() {
        for data in [Vec::new(), vec![0xAB], (0..128 * 1024).map(|i| (i % 251) as u8).collect::<Vec<u8>>()] {
            let msgs = vec![chunk_msg(data.clone())];
            let bin = encode_outbound_bin(&msgs);
            // Binary framing is ~1 byte/byte plus a fixed header, never ~3.
            assert!(bin.len() < data.len() + 64, "{} bytes ballooned to {}", data.len(), bin.len());
            let back = decode_outbound_bin(&bin).expect("frame must decode");
            match &back[..] {
                [NetMsg::ChunkData { dim: 1, cx: -2, cz: 3, data: got }] => assert_eq!(got, &data),
                other => panic!("wrong decode: {other:?}"),
            }
            // The legacy signed-op JSON for the same message is unchanged.
            let json = serde_json::to_string(&chunk_msg(data.clone())).unwrap();
            let parsed: NetMsg = serde_json::from_str(&json).unwrap();
            match parsed {
                NetMsg::ChunkData { data: got, .. } => assert_eq!(got, data),
                other => panic!("JSON leg changed: {other:?}"),
            }
        }
    }

    #[test]
    fn binary_frame_pins_golden_bytes_and_size_win() {
        // Golden frame for ChunkData{dim:1,cx:-2,cz:3,data:[0,255]}: pins the
        // cross-language format Kotlin's OutboundFrame decodes.
        let bin = encode_outbound_bin(&[chunk_msg(vec![0, 255])]);
        let golden: &[u8] = b"VXB1\x01\x00\x00\x00\x01\x01\xfe\xff\xff\xff\x03\x00\x00\x00\x02\x00\x00\x00\x00\xff";
        assert_eq!(bin, golden);
        let json = serde_json::to_string(&[chunk_msg(vec![0, 255])]).unwrap();
        assert_eq!(json, r#"[{"type":"ChunkData","dim":1,"cx":-2,"cz":3,"data":[0,255]}]"#);

        // Size effect on a realistic full chunk payload.
        let data: Vec<u8> = (0..128 * 1024).map(|i| (i % 256) as u8).collect();
        let bin_len = encode_outbound_bin(&[chunk_msg(data.clone())]).len();
        let json_len = serde_json::to_string(&[chunk_msg(data)]).unwrap().len();
        assert!(bin_len < json_len / 3, "binary {bin_len} vs JSON {json_len}");
    }

    #[test]
    fn binary_frame_keeps_other_variants_as_json_and_rejects_garbage() {
        let player = PlayerDto { pos: Vec3::new(1.0, 2.0, 3.0), vel: Vec3::ZERO, yaw: 0.5, pitch: -0.2, on_ground: true, sneaking: false, gliding: false, health: 20.0 };
        let msgs = vec![
            NetMsg::PlayerTransform { device: "d1".into(), player },
            NetMsg::BlockEdit { dim: 0, x: 3, y: 64, z: -7, id: 12, meta: 5 },
            NetMsg::Join { device: "d1".into(), name: "Bob".into() },
        ];
        let back = decode_outbound_bin(&encode_outbound_bin(&msgs)).expect("frame must decode");
        assert_eq!(back.len(), 3);
        assert!(matches!(back[0], NetMsg::PlayerTransform { .. }));
        assert!(matches!(back[1], NetMsg::BlockEdit { x: 3, .. }));
        // Non-chunk JSON payloads are byte-identical to the legacy serde text.
        assert_eq!(serde_json::to_string(&back[1]).unwrap(), serde_json::to_string(&msgs[1]).unwrap());

        assert!(decode_outbound_bin(b"").is_none());
        assert!(decode_outbound_bin(b"NOPE").is_none());
        let good = encode_outbound_bin(&[chunk_msg(vec![1, 2, 3])]);
        assert!(decode_outbound_bin(&good[..good.len() - 1]).is_none(), "truncation must fail");
        assert!(decode_outbound_bin(&good[..4]).is_none());
    }
}
