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
