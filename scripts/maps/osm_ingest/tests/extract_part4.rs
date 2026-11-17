#[cfg(test)]
mod tests_part4 {
    use osm_ingest::extract::{parse_args, Layer}; use std::path::PathBuf;

    #[test]
    fn args_need_a_layer_and_an_out() {
        let ok = parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "safety".into(),
            "--out".into(),
            "s.geojsonseq".into(),
            "--bbox".into(),
            "-122.6,37.2,-121.7,37.9".into(),
        ])
        .unwrap();
        assert_eq!(ok.input, PathBuf::from("in.pbf"));
        assert_eq!(ok.layer, Layer::Safety);
        assert!(ok.bbox.is_some());

        assert!(parse_args(&["in.pbf".into()]).is_err());
        assert!(parse_args(&["in.pbf".into(), "--layer".into(), "safety".into()]).is_err());
        assert!(parse_args(&["--layer".into(), "safety".into(), "--out".into(), "o".into()]).is_err());
        // A bad layer name names the ones that do exist.
        let err = parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "nope".into(),
            "--out".into(),
            "o".into(),
        ])
        .map(|_| ())
        .unwrap_err();
        assert!(err.contains("safety"), "{err}");
        // A malformed bbox is rejected here, not silently ignored.
        assert!(parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "safety".into(),
            "--out".into(),
            "o".into(),
            "--bbox".into(),
            "1,2,3".into(),
        ])
        .is_err());
    }

    #[test]
    fn threads_is_optional_and_must_be_positive() {
        let base: Vec<String> = vec![
            "in.pbf".into(),
            "--layer".into(),
            "safety".into(),
            "--out".into(),
            "o".into(),
        ];
        assert_eq!(parse_args(&base).unwrap().threads, None);

        let mut with = base.clone();
        with.extend(["--threads".to_string(), "6".to_string()]);
        assert_eq!(parse_args(&with).unwrap().threads, Some(6));

        let mut zero = base.clone();
        zero.extend(["--threads".to_string(), "0".to_string()]);
        assert!(parse_args(&zero).is_err());

        let mut bare = base;
        bare.push("--threads".into());
        assert!(parse_args(&bare).is_err());
    }
}

