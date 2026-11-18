//! Coverage for stream.rs / stream_part1.rs / search.rs via public API.
use std::sync::{Mutex, MutexGuard};
use serde_json::json;
use youpipe_extractor::decoder::clear_caches;
use youpipe_extractor::http::{HttpClient, Request, Response, Result};
use youpipe_extractor::innertube::reset_client_version;
use youpipe_extractor::model::{SearchItem, StreamInfo, StreamKind};
use youpipe_extractor::{search, stream};

static LOCK: Mutex<()> = Mutex::new(());
fn lock() -> MutexGuard<'static, ()> { let g = LOCK.lock().unwrap_or_else(|e| e.into_inner()); reset_client_version(); clear_caches(); g }
fn resp(url: &str, body: String) -> Response { Response { code: 200, body: body.into_bytes(), latest_url: url.to_string(), headers: [("Content-Type".into(), vec!["application/json".into()])].into_iter().collect() } }

#[derive(Default, Clone)]
struct Client { player: String, next: String, search: String, search_cont: String }
impl HttpClient for Client {
    fn execute(&self, req: Request<'_>) -> Result<Response> {
        if req.url.contains("sw.js") { return Ok(resp(&req.url, r#"{"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20260120.01.00"}"#.into())); }
        if req.url.contains("latest-player") { return Ok(resp(&req.url, r#"{"player":"testplayer","signatureTimestamp":12345}"#.into())); }
        if req.url.contains("decoder/decode") {
            // extract sig= or n= value
            let val = req.url.split(&['=', '&'][..]).filter(|s| !s.is_empty()).last().unwrap_or("x").to_string();
            // find param value: after sig= or n=
            let v = if let Some(i) = req.url.find("sig=") { req.url[i+4..].split('&').next().unwrap_or("").to_string() } else if let Some(i) = req.url.find("&n=") { req.url[i+3..].split('&').next().unwrap_or("").to_string() } else if let Some(i) = req.url.find("?n=") { req.url[i+3..].split('&').next().unwrap_or("").to_string() } else { val };
            let body = json!({"type":"result","responses":[{"type":"result","data":{ v.clone(): format!("decoded-{v}") }}]}).to_string();
            return Ok(resp(&req.url, body));
        }
        let body = req.body.map(|b| String::from_utf8_lossy(b).into_owned()).unwrap_or_default();
        let is_cont = body.contains("\"continuation\"");
        if req.url.contains("youtubei/v1/player") { return Ok(resp(&req.url, self.player.clone())); }
        if req.url.contains("youtubei/v1/next") { return Ok(resp(&req.url, self.next.clone())); }
        if req.url.contains("youtubei/v1/search") { return Ok(resp(&req.url, if is_cont { self.search_cont.clone() } else { self.search.clone() })); }
        Ok(resp(&req.url, "{}".into()))
    }
}

fn player_fixture() -> String {
    json!({
      "playabilityStatus":{"status":"OK"},
      "videoDetails":{"title":"Test Vid","lengthSeconds":"214","viewCount":"1234567","author":"Uploader","channelId":"UC12345678901234567890","shortDescription":"desc","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/t.jpg","width":120,"height":90}]},"keywords":["a","b"]},
      "microformat":{"playerMicroformatRenderer":{"uploadDate":"2024-01-02","category":"Music","isFamilySafe":true,"likeCount":"999"}},
      "streamingData":{"dashManifestUrl":"https://example.com/dash","hlsManifestUrl":"https://example.com/hls",
        "formats":[{"itag":18,"mimeType":"video/mp4; codecs=\"avc1.64001F\"","url":"https://example.com/v18?itag=18","width":640,"height":360,"bitrate":500000,"qualityLabel":"360p","contentLength":"12345","fps":30}],
        "adaptiveFormats":[
          {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","url":"https://example.com/a140?itag=140","bitrate":130000,"averageBitrate":128000,"audioSampleRate":"44100","audioChannels":2,"audioTrack":{"id":"en.4","displayName":"English"},"contentLength":"999","initRange":{"start":0,"end":10},"indexRange":{"start":11,"end":20}},
          {"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","url":"https://example.com/v137?itag=137&n=XYZABC","width":1920,"height":1080,"qualityLabel":"1080p","bitrate":4000000},
          {"itag":999,"mimeType":"text/plain","url":"https://example.com/x"},
          {"itag":313,"mimeType":"video/webm"}
        ]}
    }).to_string()
}

#[test]
fn stream_info_full() {
    let _g = lock();
    let info = stream::stream_info(&Client{player: player_fixture(), ..Default::default()}, "aaaaaaaaaaa", "en", "US").unwrap();
    assert_eq!(info.name, "Test Vid");
    assert_eq!(info.duration_seconds, 214);
    assert_eq!(info.view_count, 1234567);
    assert_eq!(info.like_count, 999);
    assert_eq!(info.kind, StreamKind::Video);
    assert_eq!(info.age_limit, 0);
    assert_eq!(info.tags, vec!["a".to_string(), "b".to_string()]);
    assert_eq!(info.video_streams.len(), 1);
    assert_eq!(info.video_streams[0].codec.as_deref(), Some("avc1.64001F"));
    assert_eq!(info.audio_streams.len(), 1);
    assert_eq!(info.audio_streams[0].sample_rate, 44100);
    assert_eq!(info.audio_streams[0].track_id.as_deref(), Some("en.4"));
    assert_eq!(info.video_only_streams.len(), 1);
    // n param decoded
    assert!(info.video_only_streams[0].url.contains("decoded-XYZABC"), "url {}", info.video_only_streams[0].url);
    assert!(!info.sabr_only);
    assert_eq!(info.dash_manifest_url.as_deref(), Some("https://example.com/dash"));
}

#[test]
fn stream_info_live_postlive_and_sabr() {
    let _g = lock();
    let live = json!({"playabilityStatus":{"status":"OK","liveStreamability":{}},"videoDetails":{"title":"Live","isLiveContent":true},"microformat":{"playerMicroformatRenderer":{"isFamilySafe":false,"publishDate":"2024-01-01"}},"streamingData":{"serverAbrStreamingUrl":"https://example.com/sabr","adaptiveFormats":[{"itag":313,"mimeType":"video/webm"}]}}).to_string();
    let info = stream::stream_info(&Client{player: live, ..Default::default()}, "bbbbbbbbbbb", "en", "US").unwrap();
    assert_eq!(info.kind, StreamKind::Live);
    assert_eq!(info.age_limit, 18);
    assert!(info.sabr_only);
    assert_eq!(info.server_abr_streaming_url.as_deref(), Some("https://example.com/sabr"));
    assert_eq!(info.view_count, -1);
    assert_eq!(info.like_count, -1);
    // post-live DVR via isPostLiveDvr, also live via playability liveStreamability already covered; use DVR flag
    let dvr = json!({"videoDetails":{"title":"DVR","isPostLiveDvr":true},"microformat":{"playerMicroformatRenderer":{}},"streamingData":{}}).to_string();
    let info2 = stream::stream_info(&Client{player: dvr, ..Default::default()}, "ccccccccccc", "en", "US").unwrap();
    assert_eq!(info2.kind, StreamKind::PostLive);
    let dvr2 = json!({"videoDetails":{"title":"DVR2","isLiveDvrEnabled":true},"microformat":{"playerMicroformatRenderer":{}},"streamingData":{}}).to_string();
    assert_eq!(stream::stream_info(&Client{player: dvr2, ..Default::default()}, "ccccccccccc", "en", "US").unwrap().kind, StreamKind::PostLive);
}

#[test]
fn stream_info_signature_cipher_and_errors() {
    let _g = lock();
    let fixture = json!({
      "videoDetails":{"title":"Cipher"},
      "microformat":{"playerMicroformatRenderer":{}},
      "streamingData":{"formats":[
        {"itag":22,"mimeType":"video/mp4","signatureCipher":"s=SIGVAL&sp=sig&url=https%3A%2F%2Fexample.com%2Fvideoplayback%3Fitag%3D22"},
        {"itag":23,"mimeType":"video/mp4","cipher":"s=SIG2&url=https%3A%2F%2Fexample.com%2Fv%3Fx%3D1"},
        {"itag":24,"mimeType":"video/mp4","signatureCipher":"s=NONE"}
      ],"adaptiveFormats":[
        {"itag":140,"mimeType":"audio/webm; codecs=\"opus\"","url":"https://example.com/a?x=1"}
      ]}
    }).to_string();
    let info = stream::stream_info(&Client{player: fixture, ..Default::default()}, "ddddddddddd", "en", "US").unwrap();
    assert_eq!(info.video_streams.len(), 2);
    assert!(info.video_streams[0].url.contains("decoded-SIGVAL"));
    assert!(info.video_streams[0].url.contains("sig=decoded-SIGVAL"));
    // second uses default sp=signature
    assert!(info.video_streams[1].url.contains("signature=decoded-SIG2"));
    assert_eq!(info.errors.len(), 1, "missing url in cipher is an error entry: {:?}", info.errors);
}

#[test]
fn stream_playability_via_public_api() {
    let _g = lock();
    for (status, reason, expect) in [
        ("LOGIN_REQUIRED", "inappropriate for some users", "age-restricted"),
        ("LOGIN_REQUIRED", "This video is private", "private"),
        ("LOGIN_REQUIRED", "Sign in to confirm you're not a bot", "blocking anonymous"),
        ("LOGIN_REQUIRED", "something else", "login required"),
        ("UNPLAYABLE", "requires Music Premium", "Music Premium"),
        ("UNPLAYABLE", "payment required", "paid"),
        ("UNPLAYABLE", "members only", "members-only"),
        ("UNPLAYABLE", "not available in your country", "country"),
        ("ERROR", "This account has been terminated", "terminated"),
        ("ERROR", "account closed", "terminated"),
        ("ERROR", "weird failure", "weird"),
    ] {
        let fixture = json!({"playabilityStatus":{"status":status,"reason":reason}}).to_string();
        let err = stream::stream_info(&Client{player: fixture, ..Default::default()}, "eeeeeeeeeee", "en", "US").unwrap_err().to_string();
        assert!(err.contains(expect), "{status}/{reason} -> {err} expected {expect}");
    }
    // reason via messages array
    let fixture = json!({"playabilityStatus":{"status":"UNPLAYABLE","messages":[{"simpleText":"not available in your country"}]}}).to_string();
    let err = stream::stream_info(&Client{player: fixture, ..Default::default()}, "eeeeeeeeeee", "en", "US").unwrap_err().to_string();
    assert!(err.contains("country"), "{err}");
    // case-insensitive OK
    let ok = json!({"playabilityStatus":{"status":"ok"},"videoDetails":{"title":"x"},"microformat":{"playerMicroformatRenderer":{}},"streamingData":{}}).to_string();
    assert!(stream::stream_info(&Client{player: ok, ..Default::default()}, "eeeeeeeeeee", "en", "US").is_ok());
}

#[test]
fn augment_from_next_full() {
    let _g = lock();
    let next = json!({"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
      {"videoPrimaryInfoRenderer":{"viewCount":{"videoViewCountRenderer":{"viewCount":{"simpleText":"5,678 views"}}},"videoActions":{"menuRenderer":{"topLevelButtons":[{"segmentedLikeDislikeButtonViewModel":{"likeButtonViewModel":{"likeButtonViewModel":{"toggleButtonViewModel":{"toggleButtonViewModel":{"defaultButtonViewModel":{"buttonViewModel":{"accessibilityText":"like this video along with 1,234,567 other people"}}}}}}}}]}}}},
      {"videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{"badges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}],"subscriberCountText":{"simpleText":"2.5M subscribers"},"navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/@chan"}}},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/av.jpg","width":40,"height":40}]}}}}}
    ]}}}}}).to_string();
    let mut info = StreamInfo::default();
    info.view_count = -1; info.like_count = -1;
    stream::augment_from_next(&Client{next: next.clone(), ..Default::default()}, "aaaaaaaaaaa", "en", "US", &mut info).unwrap();
    assert_eq!(info.view_count, 5678);
    assert_eq!(info.like_count, 1234567);
    assert!(info.uploader_verified);
    assert_eq!(info.uploader_subscriber_count, 2_500_000);
    assert_eq!(info.uploader_url.as_deref(), Some("https://www.youtube.com/@chan"));
    assert_eq!(info.uploader_avatars.len(), 1);
    // already-set counts not overwritten, likes extractor returns -1 when no buttons
    let next2 = json!({"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[{"videoPrimaryInfoRenderer":{"viewCount":{"videoViewCountRenderer":{"viewCount":{"simpleText":"9 views"}}}}}]}}}}}).to_string();
    let mut info2 = StreamInfo::default(); info2.view_count = 111; info2.like_count = 222; info2.uploader_url = Some("https://www.youtube.com/channel/UC123".into());
    stream::augment_from_next(&Client{next: next2, ..Default::default()}, "aaaaaaaaaaa", "en", "US", &mut info2).unwrap();
    assert_eq!(info2.view_count, 111); assert_eq!(info2.like_count, 222);
}

#[test]
fn search_all_item_types_and_filters() {
    let _g = lock();
    assert_eq!(search::filter_params(Some("videos")), Some(search::FILTER_VIDEOS));
    assert_eq!(search::filter_params(Some("channels")), Some(search::FILTER_CHANNELS));
    assert_eq!(search::filter_params(Some("playlists")), Some(search::FILTER_PLAYLISTS));
    assert_eq!(search::filter_params(Some("bogus")), None);
    assert_eq!(search::filter_params(None), None);
    let fixture = json!({"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
      {"itemSectionRenderer":{"contents":[
        {"backgroundPromoRenderer":{}},
        {"channelRenderer":{"channelId":"UC12345678901234567890","title":{"simpleText":"Chan"},"subscriberCountText":{"simpleText":"1.2M subscribers"},"videoCountText":{"simpleText":"42 videos"},"descriptionSnippet":{"simpleText":"chan desc"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/c.jpg","width":10,"height":10}]},"ownerBadges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}]}},
        {"playlistRenderer":{"playlistId":"PLabc","title":{"simpleText":"My Playlist"},"videoCount":"17","longBylineText":{"simpleText":"Uploader"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/p.jpg","width":10,"height":10}]}}},
        {"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"aaaaaaaaaaa","metadata":{"lockupMetadataViewModel":{"title":{"content":"Lock Vid"}}}}},
        {"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST","contentId":"PLxyz","metadata":{"lockupMetadataViewModel":{"title":{"content":"Lock PL"}}}}},
        {"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_PODCAST","contentId":"PLpod","metadata":{"lockupMetadataViewModel":{"title":{"content":"Pod"}}}}},
        {"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_CHANNEL","contentId":"UCx","metadata":{"lockupMetadataViewModel":{"title":{"content":"?"}}}}},
        {"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","metadata":{}}},
        {"videoRenderer":{"videoId":"bbbbbbbbbbb","title":{"simpleText":"Live Vid"},"badges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_LIVE_NOW"}}]}},
        {"videoRenderer":{"title":{"simpleText":"no id"}}},
        {"channelRenderer":{"title":{"simpleText":"no id"}}},
        {"playlistRenderer":{"title":{"simpleText":"no id"}}},
        {"someAdRenderer":{}}
      ]}},
      {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"SEARCHTOK"}}}}
    ]}}}}}).to_string();
    for filter in [None, Some("videos"), Some("channels"), Some("playlists"), Some("bogus")] {
        let r = search::search(&Client{search: fixture.clone(), ..Default::default()}, "q", filter, "en", "US").unwrap();
        assert_eq!(r.next_page_token.as_deref(), Some("SEARCHTOK"));
        // 1 channel + 1 playlist + 3 lockups + 1 live video = 6 good, errors: unknown lockup, missing-id lockup, video, channel, playlist =5
        assert_eq!(r.items.len(), 6, "filter {filter:?} items {:?}", r.items);
        assert_eq!(r.errors.len(), 5, "{:?}", r.errors);
        match &r.items[0] { SearchItem::Channel(ch) => { assert_eq!(ch.subscriber_count, 1_200_000, "subscriber count from subscriberCountText"); assert_eq!(ch.stream_count, 42); assert!(ch.verified); } _ => panic!("channel first") }
        match &r.items[1] { SearchItem::Playlist(p) => assert_eq!(p.stream_count, 17), _ => panic!("playlist") }
        match &r.items[5] { SearchItem::Stream(s) => assert!(s.is_live), _ => panic!("live stream") }
    }
}

#[test]
fn search_suggestion_didyoumean_and_page() {
    let _g = lock();
    let fixture = json!({"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
      {"itemSectionRenderer":{"contents":[{"didYouMeanRenderer":{"correctedQuery":{"runs":[{"text":"did you mean cats"}]}}}]}}
    ]}}}}}).to_string();
    let r = search::search(&Client{search: fixture, ..Default::default()}, "q", None, "en", "US").unwrap();
    assert_eq!(r.search_suggestion.as_deref(), Some("did you mean cats"));
    // search_page via continuation
    let cont = json!({"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[
        {"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"aaaaaaaaaaa","title":{"simpleText":"Paged"}}}]}},
        {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"PAGE2"}}}}
    ]}}]}).to_string();
    let r2 = search::search_page(&Client{search_cont: cont, ..Default::default()}, "tok", "en", "US").unwrap();
    assert_eq!(r2.items.len(), 1);
    assert_eq!(r2.next_page_token.as_deref(), Some("PAGE2"));
    // empty continuation response
    let r3 = search::search_page(&Client{search_cont: json!({"onResponseReceivedCommands":[],"padding":"xxxxxxxxxxxxxxxxxxxxxxxx"}).to_string(), ..Default::default()}, "tok", "en", "US").unwrap();
    assert!(r3.items.is_empty());
}

#[test]
fn search_response_validation() {
    let _g = lock();
    struct Custom(u16, String, String);
    impl HttpClient for Custom {
        fn execute(&self, req: Request<'_>) -> Result<Response> {
            if req.url.contains("sw.js") { return Ok(resp(&req.url, r#"{"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20260120.01.00"}"#.into())); }
            Ok(Response{code: self.0, body: self.1.as_bytes().to_vec(), latest_url: req.url.clone(), headers: [("Content-Type".into(), vec![self.2.clone()])].into_iter().collect()})
        }
    }
    assert!(search::search(&Custom(404, "x".repeat(100), "application/json".into()), "q", None, "en", "US").is_err());
    assert!(search::search(&Custom(200, "{}".into(), "application/json".into()), "q", None, "en", "US").is_err());
    assert!(search::search(&Custom(200, "x".repeat(100), "text/html".into()), "q", None, "en", "US").is_err());
    let bad_json = "not json at all padding padding padding padding padding padding";
    assert!(search::search(&Custom(200, bad_json.into(), "application/json".into()), "q", None, "en", "US").is_err());
}
