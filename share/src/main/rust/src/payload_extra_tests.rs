//! Payload tests for the builders and classifiers that had no direct
//! fixture: the bandwidth-upgrade refusal frame, the file-type guess
//! in the introduction, and `FileMeta::from_sharing`'s defaults.

use super::{
    build_bandwidth_upgrade_failure, build_introduction_frame, parse_offline_frame,
    parse_sharing_frame, FileMeta,
};
use crate::frame::{BandwidthUpgradeEvent, OfflineFrameType, SharingFileMetadata, SharingFileType};

#[test]
fn the_bandwidth_upgrade_failure_is_an_upgrade_failure() {
    let body = build_bandwidth_upgrade_failure();
    let frame = parse_offline_frame(&body).expect("a parseable OfflineFrame");
    let v1 = frame.v1.expect("v1 present");
    assert_eq!(v1.r#type, OfflineFrameType::BandwidthUpgradeNegotiation as i32);
    let negotiation = v1.bandwidth_upgrade_negotiation.expect("negotiation present");
    assert_eq!(negotiation.event_type, BandwidthUpgradeEvent::UpgradeFailure as i32);
}

fn announced_type(name: &str, mime: &str) -> i32 {
    let files = vec![FileMeta {
        name: name.to_string(),
        size_bytes: 1,
        mime_type: mime.to_string(),
        payload_id: 0,
    }];
    let body = build_introduction_frame(&files, 41);
    // The introduction travels as a SharingFrame inside a BYTES payload;
    // decode the sharing frame the builder produced directly.
    let sharing = parse_sharing_frame(&body).expect("a parseable SharingFrame");
    let intro = sharing.v1.and_then(|v1| v1.introduction).expect("introduction present");
    let meta = intro.file_metadata.first().expect("one file announced");
    meta.r#type
}

#[test]
fn the_introduction_classifies_files_by_mime_then_extension() {
    assert_eq!(announced_type("photo.jpg", "image/jpeg"), SharingFileType::Image as i32);
    assert_eq!(announced_type("clip.mov", "video/quicktime"), SharingFileType::Video as i32);
    assert_eq!(announced_type("song.flac", "audio/flac"), SharingFileType::Audio as i32);
    assert_eq!(announced_type("app.apk", ""), SharingFileType::AndroidApp as i32);
    assert_eq!(
        announced_type("app.bin", "application/vnd.android.package-archive"),
        SharingFileType::AndroidApp as i32
    );
    assert_eq!(announced_type("card.vcf", ""), SharingFileType::ContactCard as i32);
    assert_eq!(announced_type("card.txt", "text/x-vcard"), SharingFileType::ContactCard as i32);
    assert_eq!(announced_type("notes.txt", "text/plain"), SharingFileType::Document as i32);
    // Extension case does not matter to the guess.
    assert_eq!(announced_type("APP.APK", ""), SharingFileType::AndroidApp as i32);
}

#[test]
fn from_sharing_applies_the_proto_defaults() {
    let meta = FileMeta::from_sharing(&SharingFileMetadata {
        name: "a.bin".to_string(),
        size: -5,
        mime_type: String::new(),
        payload_id: 77,
        ..Default::default()
    });
    assert_eq!(meta.size_bytes, 0, "a negative announced size clamps to zero");
    assert_eq!(meta.mime_type, crate::frame::DEFAULT_MIME_TYPE);
    assert_eq!(meta.payload_id, 77);
    assert_eq!(meta.name, "a.bin");
}
