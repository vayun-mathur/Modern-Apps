#[cfg(test)]
mod tests {
    use super::*;

    /// Build a file the way the converter does, so the round-trip is a real check of
    /// the two implementations agreeing rather than of this module agreeing with
    /// itself.
    fn write(graph_id: u32, tensors: &[(Vec<u32>, Vec<f32>)]) -> Vec<u8> {
        let mut table = Vec::new();
        let mut data: Vec<u8> = Vec::new();
        for (dims, values) in tensors {
            while !data.len().is_multiple_of(ALIGNMENT as usize) {
                data.push(0);
            }
            let offset = data.len() as u32;
            for &v in values {
                data.extend_from_slice(&f32_to_f16(v).to_le_bytes());
            }
            let mut padded = [0u32; 4];
            padded[..dims.len()].copy_from_slice(dims);
            table.extend_from_slice(&(dims.len() as u32).to_le_bytes());
            for d in padded {
                table.extend_from_slice(&d.to_le_bytes());
            }
            table.extend_from_slice(&DTYPE_F16.to_le_bytes());
            table.extend_from_slice(&offset.to_le_bytes());
            table.extend_from_slice(&(values.len() as u32).to_le_bytes());
        }

        let mut out = Vec::new();
        out.extend_from_slice(&MAGIC);
        out.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        out.extend_from_slice(&graph_id.to_le_bytes());
        out.extend_from_slice(&(tensors.len() as u32).to_le_bytes());
        out.extend_from_slice(&[0u8; 32]);
        out.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
        out.extend_from_slice(&(data.len() as u32).to_le_bytes());
        out.extend_from_slice(&[0u8; 8]);
        out.extend_from_slice(&table);
        out.extend_from_slice(&data);
        out
    }

    /// Round-half-to-even fp32 to fp16, enough for the test fixtures above.
    fn f32_to_f16(v: f32) -> u16 {
        let bits = v.to_bits();
        let sign = ((bits >> 16) & 0x8000) as u16;
        let exponent = ((bits >> 23) & 0xff) as i32 - 127 + 15;
        let mantissa = bits & 0x007f_ffff;
        if exponent <= 0 {
            return sign;
        }
        sign | ((exponent as u16) << 10) | ((mantissa >> 13) as u16)
    }

    /// Write `bytes` into a temp file after `pad` bytes of filler, and return the file.
    ///
    /// The padding is the point: a `.maml` bundled as an asset is a *range* of the APK, not a file,
    /// so [`Streamed`] adds a base offset to every read. A fixture at offset 0 passes whether or not
    /// that offset is applied, which is the one thing worth checking here.
    fn on_disk(name: &str, pad: usize, bytes: &[u8]) -> (File, u64, u64) {
        use std::io::Write;
        let path = std::env::temp_dir().join(format!("modelrunner-{name}.maml"));
        let mut file = std::fs::File::create(&path).expect("a temp file");
        file.write_all(&vec![0xABu8; pad]).expect("the padding writes");
        file.write_all(bytes).expect("the blob writes");
        // Trailing filler as well, so the file does not end where the data section does: an asset
        // is followed by the next asset, and `Streamed` must not require otherwise.
        file.write_all(&[0xCDu8; 7]).expect("the trailer writes");
        drop(file);
        let opened = std::fs::File::open(&path).expect("the temp file reopens");
        (opened, pad as u64, bytes.len() as u64)
    }

    #[test]
    fn a_streamed_file_answers_exactly_as_a_parsed_one() {
        // The bundled path: the table is read from the file's prefix and the data section stays on
        // disk. Both halves must match what `Weights::parse` produces from the same bytes, because
        // `Net::new` uploads one and the plan was resolved against the other.
        let bytes = write(
            graph::SUPERTONIC_VE,
            &[(vec![2, 3], vec![1.0, 2.0, 4.0, 8.0, 16.0, 32.0]), (vec![2], vec![0.5, 0.25])],
        );
        let parsed = Weights::parse(&bytes, graph::SUPERTONIC_VE).expect("parses");
        let (file, at, len) = on_disk("streamed", 4096, &bytes);
        let streamed =
            Streamed::open(file, at, len, graph::SUPERTONIC_VE).expect("the file streams");

        assert_eq!(streamed.graph_id, parsed.graph_id);
        assert_eq!(streamed.len(), parsed.len());
        assert_eq!(streamed.data_len(), parsed.data_len());
        for index in 0..parsed.len() {
            assert_eq!(
                streamed.offsets().tensor(index).expect("in range"),
                parsed.tensor(index).expect("in range")
            );
        }
        // Byte for byte, and read in pieces rather than whole: the upload asks for chunks, so a
        // base offset applied once at open rather than per read would pass a single-read fixture.
        let mut got = vec![0u8; parsed.data_len() as usize];
        for (chunk, into) in got.chunks_mut(7).enumerate() {
            streamed.read_at((chunk * 7) as u64, into).expect("the chunk reads");
        }
        let mut want = vec![0u8; parsed.data_len() as usize];
        parsed.read_at(0, &mut want).expect("the whole section reads");
        assert_eq!(got, want);

        // And the host-side reads go through the same table and the same bytes.
        assert_eq!(
            streamed.reader().fp16(0, &[2, 3]).expect("the streamed tensor"),
            parsed.reader().fp16(0, &[2, 3]).expect("the parsed tensor")
        );
    }

    #[test]
    fn a_streamed_read_past_the_data_section_is_refused() {
        let bytes = write(graph::SUPERTONIC_DP, &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])]);
        let (file, at, len) = on_disk("streamed-bounds", 16, &bytes);
        let streamed = Streamed::open(file, at, len, graph::SUPERTONIC_DP).expect("streams");

        let mut into = [0u8; 8];
        assert!(streamed.read_at(streamed.data_len() - 4, &mut into).is_err());
        assert!(streamed.read_at(u64::MAX, &mut into).is_err());
        // The trailing filler `on_disk` wrote is past the data section and must stay unreachable,
        // or a truncated `.maml` would upload whatever followed it in the APK.
        assert!(streamed.read_at(streamed.data_len(), &mut into[..1]).is_err());
    }

    #[test]
    fn a_streamed_file_shorter_than_its_own_header_says_is_refused() {
        // A length the caller was told, against a header that claims more. `AssetManager` reports
        // the range it will serve, so a disagreement means the asset was truncated at build time —
        // and the reads that ran off the end would land in the next asset rather than failing.
        let bytes = write(graph::SUPERTONIC_VOC, &[(vec![8], vec![1.0; 8])]);
        let (file, at, len) = on_disk("streamed-short", 0, &bytes);
        assert!(Streamed::open(file, at, len - 8, graph::SUPERTONIC_VOC).is_err());
    }

    #[test]
    fn a_streamed_file_for_another_graph_is_refused() {
        // The same check `Weights::parse` makes, and it has to happen here too: the four Supertonic
        // plans arrive as four descriptors in a fixed order, so a caller that swapped two would
        // otherwise upload the vocoder's weights into the text encoder's buffer.
        let bytes = write(graph::SUPERTONIC_TTL, &[(vec![2], vec![1.0, 2.0])]);
        let (file, at, len) = on_disk("streamed-wrong-graph", 32, &bytes);
        assert!(Streamed::open(file, at, len, graph::SUPERTONIC_VE).is_err());
    }

    #[test]
    fn the_table_outlives_the_blob_and_answers_identically() {
        // What `Net::rebuild` depends on: the offsets a plan resolves against do not come from the
        // data section, so a retained table gives the same answers after the file is gone. If it
        // did not, a rebuilt plan would index device memory that holds something else — the right
        // shape and the wrong tensor, which no count or digest check would notice.
        let bytes = write(
            graph::SUPERTONIC_TTL,
            &[(vec![2, 1, 1, 1], vec![1.0, 2.0]), (vec![3], vec![4.0, 8.0, 16.0])],
        );
        let weights = Weights::parse(&bytes, graph::SUPERTONIC_TTL).expect("parses");
        let table = weights.offsets();
        let whole: Vec<Tensor> =
            (0..weights.len()).map(|i| weights.tensor(i).expect("in range")).collect();
        drop(weights);

        assert_eq!(table.len(), whole.len());
        for (index, expected) in whole.iter().enumerate() {
            assert_eq!(&table.tensor(index).expect("in range"), expected);
        }
        assert_eq!(table.shaped(0, &[2, 1, 1, 1]).expect("the declared shape").elem_offset(), 0);
        // And the shape check is still the shape check, not a length check.
        assert!(table.shaped(1, &[4]).is_err());
        assert!(table.tensor(2).is_err());
    }

    #[test]
    fn a_written_file_reads_back_with_the_same_table() {
        let bytes = write(
            graph::U2NETP,
            &[
                (vec![2, 1, 1, 1], vec![1.0, 2.0]),
                (vec![3], vec![4.0, 8.0, 16.0]),
            ],
        );
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("parses");
        assert_eq!(weights.len(), 2);
        assert_eq!(
            weights.tensor(0).expect("first"),
            Tensor { rank: 4, dims: [2, 1, 1, 1], offset: 0, len: 2, dtype: Dtype::F16 }
        );
        // Tensor 0 is 4 bytes but the next starts at 16: the alignment the shaders
        // rely on, and the arithmetic most likely to be got wrong.
        assert_eq!(
            weights.tensor(1).expect("second"),
            Tensor { rank: 1, dims: [3, 0, 0, 0], offset: 16, len: 3, dtype: Dtype::F16 }
        );
        assert_eq!(weights.tensor(1).expect("second").elem_offset(), 8);
        assert_eq!(weights.data().len(), 16 + 6);
    }

    #[test]
    fn an_int8_tensor_reads_back_with_a_byte_stride_and_a_word_offset() {
        // Hand-built, because `write` above only emits fp16. One int8 tensor of five bytes,
        // then an fp16 scale after it — the layout `Builder::conv_int8` expects.
        let mut table = Vec::new();
        let mut data: Vec<u8> = Vec::new();
        // int8 [5], at offset 0.
        let payload: [i8; 5] = [-128, -1, 0, 1, 127];
        for (dtype, dims, len, bytes) in [
            (DTYPE_I8, [5u32, 0, 0, 0], 5u32, payload.iter().map(|&b| b as u8).collect::<Vec<u8>>()),
            (DTYPE_F16, [1u32, 0, 0, 0], 1u32, f32_to_f16(0.25).to_le_bytes().to_vec()),
        ] {
            while !data.len().is_multiple_of(ALIGNMENT as usize) {
                data.push(0);
            }
            let offset = data.len() as u32;
            data.extend_from_slice(&bytes);
            table.extend_from_slice(&1u32.to_le_bytes());
            for d in dims {
                table.extend_from_slice(&d.to_le_bytes());
            }
            table.extend_from_slice(&dtype.to_le_bytes());
            table.extend_from_slice(&offset.to_le_bytes());
            table.extend_from_slice(&len.to_le_bytes());
        }
        let mut blob = Vec::new();
        blob.extend_from_slice(&MAGIC);
        blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        blob.extend_from_slice(&graph::SUPERTONIC_VE.to_le_bytes());
        blob.extend_from_slice(&2u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
        blob.extend_from_slice(&(data.len() as u32).to_le_bytes());
        blob.extend_from_slice(&[0u8; 8]);
        blob.extend_from_slice(&table);
        blob.extend_from_slice(&data);

        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let quantised = weights.tensor(0).expect("the int8 tensor");
        assert_eq!(quantised.dtype, Dtype::I8);
        assert_eq!(quantised.len, 5);
        // Five elements at one byte each, so the *scale* still lands at 16: the alignment is
        // in bytes, not elements, and an int8 tensor must not be read with an fp16 stride.
        let scale = weights.tensor(1).expect("the scale");
        assert_eq!(scale.dtype, Dtype::F16);
        assert_eq!(scale.offset, ALIGNMENT);
        // The shader addresses int8 through the 32-bit view, so offset 0 is word 0.
        assert_eq!(quantised.word_offset(), 0);
        assert_eq!(scale.elem_offset(), ALIGNMENT / 2);
        // And the payload survived: a five-byte tensor is not padded to an even length.
        assert_eq!(&weights.data()[0..5], &[0x80, 0xFF, 0x00, 0x01, 0x7F]);
    }

    #[test]
    fn an_int4_tensor_of_odd_length_rounds_its_last_nibble_up() {
        // The one place the converter and the reader can disagree without either looking wrong.
        // Five four-bit elements are two and a half bytes; `Dtype::bytes` rounds to three and the
        // final high nibble is padding. A converter that wrote two bytes would produce a file
        // that parses - the bounds check would pass - and whose last element read as whatever
        // followed it.
        let values: Vec<i8> = vec![-8, -1, 0, 1, 7];
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::I4(vec![5], values.clone()),
                Fixture::F16(vec![1], vec![0.5]),
            ],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let quantised = weights.tensor(0).expect("the int4 tensor");
        assert_eq!(quantised.dtype, Dtype::I4);
        assert_eq!(quantised.len, 5, "five elements, not five bytes");
        assert_eq!(Dtype::I4.bytes(5), 3, "two and a half bytes rounds up");
        // The scale still lands on the 16-byte boundary, as it does after an int8 tensor.
        let scale = weights.tensor(1).expect("the scale");
        assert_eq!(scale.offset, ALIGNMENT);
        assert_eq!(quantised.word_offset(), 0);

        // Low nibble first, sign preserved. -8 and 7 are the ends of the representable range, so
        // a reader that treated the nibbles as unsigned would give 8 and 7 rather than -8 and 7.
        let packed = &weights.data()[0..3];
        assert_eq!(packed[0], 0x08 | (0x0f << 4), "-8 then -1");
        assert_eq!(packed[1], 0x00 | (0x01 << 4), "0 then 1");
        assert_eq!(packed[2], 0x07, "7, with the high nibble left as padding");
    }

    #[test]
    fn an_int4_tensor_of_even_length_uses_exactly_half_its_elements_in_bytes() {
        let values: Vec<i8> = (0..64).map(|i| ((i % 16) - 8) as i8).collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[Fixture::I4(vec![8, 8], values), Fixture::F16(vec![8], vec![1.0; 8])],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let quantised = weights.tensor(0).expect("the int4 tensor");
        assert_eq!(quantised.len, 64);
        assert_eq!(Dtype::I4.bytes(64), 32);
        // 32 bytes is two alignment units, so the scale follows at 32 rather than at 16.
        assert_eq!(weights.tensor(1).expect("the scale").offset, 32);
    }

    #[test]
    fn an_int4_tensor_past_the_data_section_is_refused() {
        // The bounds check has to use `Dtype::bytes` too, or an int4 tensor claiming twice the
        // elements the file holds would pass a byte-stride check.
        let mut blob = Vec::new();
        blob.extend_from_slice(&MAGIC);
        blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        blob.extend_from_slice(&graph::SUPERTONIC_VE.to_le_bytes());
        blob.extend_from_slice(&1u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&((HEADER_BYTES + TENSOR_ENTRY_BYTES) as u32).to_le_bytes());
        blob.extend_from_slice(&16u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 8]);
        blob.extend_from_slice(&1u32.to_le_bytes()); // rank
        for dim in [64u32, 0, 0, 0] {
            blob.extend_from_slice(&dim.to_le_bytes());
        }
        blob.extend_from_slice(&2u32.to_le_bytes()); // DTYPE_I4
        blob.extend_from_slice(&0u32.to_le_bytes()); // offset
        blob.extend_from_slice(&64u32.to_le_bytes()); // len: 32 bytes, into a 16-byte section
        blob.extend_from_slice(&[0u8; 16]);
        let error = Weights::parse(&blob, graph::SUPERTONIC_VE).expect_err("out of bounds");
        assert!(error.contains("spans"), "{error}");
    }

    #[test]
    fn an_int4_row_is_dequantised_by_its_own_block_scales() {
        // The whole point of int4's rank-2 scale, and the failure it prevents: reading the first
        // block's scale and applying it to the row gives numbers of exactly the right magnitude
        // for the first 32 columns and nonsense after. So the fixture makes the blocks differ by
        // a factor of eight and checks every column, not a sample.
        let rows = 3u32;
        let stride = 96u32; // three whole blocks of 32
        let blocks = stride / I4_BLOCK;
        let codes: Vec<i8> = (0..(rows * stride) as i32).map(|i| ((i * 5) % 15 - 7) as i8).collect();
        let scales: Vec<f32> = (0..rows * blocks)
            .map(|i| 0.03125 * f32::from(1u8 << (i % blocks) as u8))
            .collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::I4(vec![rows, stride], codes.clone()),
                Fixture::F16(vec![rows, blocks], scales.clone()),
            ],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let reader = weights.reader();
        for row in 0..rows {
            let got = reader.int4_row(0, 1, &[rows, stride], row).expect("a row");
            assert_eq!(got.len() as u32, stride);
            for column in 0..stride {
                let code = codes[(row * stride + column) as usize];
                let scale = scales[(row * blocks + column / I4_BLOCK) as usize];
                assert!(
                    (got[column as usize] - f32::from(code) * scale).abs() < 1e-6,
                    "row {row} column {column}: {} against {}",
                    got[column as usize],
                    f32::from(code) * scale
                );
            }
        }
    }

    #[test]
    fn an_int4_row_read_refuses_an_odd_stride_and_a_row_past_the_end() {
        // An odd stride would put every second row half a byte out of step. Refusing beats
        // carrying a nibble offset that nothing in this tree needs.
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[Fixture::I4(vec![2, 3], vec![1, 2, 3, 4, 5, 6]), Fixture::F16(vec![2, 1], vec![1.0; 2])],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let reader = weights.reader();
        let odd = reader.int4_row(0, 1, &[2, 3], 0).expect_err("an odd stride");
        assert!(odd.contains("does not start on a byte"), "{odd}");

        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[Fixture::I4(vec![2, 4], vec![1, 2, 3, 4, 5, 6, 7, -8]), Fixture::F16(vec![2, 1], vec![1.0; 2])],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let reader = weights.reader();
        let past = reader.int4_row(0, 1, &[2, 4], 2).expect_err("row 2 of 2");
        assert!(past.contains("rows 2..3 of a 2-row tensor"), "{past}");
        // And the last code is -8, which only survives if the nibble is sign-extended.
        let last = reader.int4_row(0, 1, &[2, 4], 1).expect("row 1");
        assert!((last[3] + 8.0).abs() < 1e-6, "sign extension: {last:?}");
    }

    #[test]
    fn an_unknown_dtype_is_refused() {
        let mut blob = Vec::new();
        blob.extend_from_slice(&MAGIC);
        blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        blob.extend_from_slice(&graph::SELFIE.to_le_bytes());
        blob.extend_from_slice(&1u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&((HEADER_BYTES + TENSOR_ENTRY_BYTES) as u32).to_le_bytes());
        blob.extend_from_slice(&2u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 8]);
        blob.extend_from_slice(&1u32.to_le_bytes());
        for d in [1u32, 0, 0, 0] {
            blob.extend_from_slice(&d.to_le_bytes());
        }
        // A dtype nothing implements. Refusing beats reading it as whichever stride is
        // nearest and returning plausible rubbish.
        blob.extend_from_slice(&7u32.to_le_bytes());
        blob.extend_from_slice(&0u32.to_le_bytes());
        blob.extend_from_slice(&1u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 2]);
        let error = Weights::parse(&blob, graph::SELFIE).expect_err("dtype 7");
        assert!(error.contains("dtype 7"), "{error}");
    }

    #[test]
    fn a_file_for_another_graph_is_refused() {
        let bytes = write(graph::SELFIE, &[(vec![1], vec![1.0])]);
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("wrong graph");
        assert!(error.contains("graph 1"), "{error}");
    }

    #[test]
    fn a_truncated_file_is_refused_at_load() {
        let bytes = write(graph::U2NETP, &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])]);
        let error =
            Weights::parse(&bytes[..bytes.len() - 2], graph::U2NETP).expect_err("truncated");
        assert!(error.contains("sections end at"), "{error}");
    }

    #[test]
    fn a_shape_the_forward_pass_did_not_expect_is_refused() {
        let bytes = write(graph::U2NETP, &[(vec![2, 2], vec![1.0, 2.0, 3.0, 4.0])]);
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("parses");
        assert!(weights.shaped(0, &[2, 2]).is_ok());
        let error = weights.shaped(0, &[4]).expect_err("wrong shape");
        assert!(error.contains("[2, 2]"), "{error}");
    }

    #[test]
    fn a_dims_and_len_disagreement_is_refused() {
        let mut bytes = write(graph::U2NETP, &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])]);
        // Claim 5 elements for a 4-element tensor: the shape check must catch it
        // before the bounds check would.
        let len_at = HEADER_BYTES + 28;
        bytes[len_at..len_at + 4].copy_from_slice(&5u32.to_le_bytes());
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("bad len");
        assert!(error.contains("but len 5"), "{error}");
    }
}