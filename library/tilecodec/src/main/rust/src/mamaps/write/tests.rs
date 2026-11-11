use super::*;
use crate::mamaps::body::Body;
    use crate::mamaps::header::Header;
    use crate::pmtiles::tile_id;

    fn tiny_body() -> Body {
        Body::new(crate::mamaps::body::DEFAULT_EXTENT)
    }

    fn two_tiles(options: Options) -> Vec<u8> {
        let mut w = StreamWriter::new(options).expect("options");
        w.append(tile_id(0, 0, 0), &tiny_body()).expect("append");
        w.append(tile_id(1, 0, 0), &tiny_body()).expect("append");
        w.finish().expect("finish")
    }

    /// Every build is a v9 container (the planet3/planet4 header): version byte 9,
    /// 128-byte header, `file_len` covering exactly header/dictionary/root/leaves/data.
    #[test]
    fn builds_are_v7_full_only() {
        let bytes = two_tiles(Options::default());
        let header = Header::parse(&bytes).expect("header");
        assert_eq!(
            header.file_len as usize, bytes.len(),
            "file_len covers exactly the v7 sections"
        );
        assert_eq!(bytes.len(), 128 + header.dict_len as usize + header.root_len as usize + header.leaf_len as usize + header.data_len as usize);
        assert_eq!(bytes[7], crate::mamaps::header::FORMAT_VERSION_V9, "a build is version byte 9");
        assert_eq!(header.wire_len(), 128);
    }

    /// `build_id_from_content`: the header id is the seed folded with the digest of every tile.
    /// The same tiles give the same id; one changed tile, or another seed, changes it; and with the
    /// option off the seed is written as given.
    #[test]
    fn a_content_build_id_follows_the_tiles() {
        let id_of = |seed: u64, extent: u16, from_content: bool| {
            let mut w = StreamWriter::new(Options {
                build_id: seed,
                build_id_from_content: from_content,
                ..Options::default()
            })
            .expect("options");
            w.append(tile_id(0, 0, 0), &tiny_body()).expect("append");
            w.append(tile_id(1, 0, 0), &Body::new(extent)).expect("append");
            let bytes = w.finish().expect("finish");
            Header::parse(&bytes).expect("header").build_id
        };
        let base = id_of(42, 4096, true);
        assert_eq!(base, id_of(42, 4096, true), "stable");
        assert_ne!(base, id_of(42, 2048, true), "a changed tile must change the id");
        assert_ne!(base, id_of(43, 4096, true), "a changed seed must change the id");
        assert_eq!(id_of(42, 4096, false), 42, "off: the seed as given");
    }
