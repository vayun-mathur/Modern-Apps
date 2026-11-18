//! Coverage for browse.rs / browse_part1.rs via public API with a stub transport.
use std::sync::{Mutex, MutexGuard};
use serde_json::json;
use youpipe_extractor::browse;
use youpipe_extractor::decoder::clear_caches;
use youpipe_extractor::http::{HttpClient, Request, Response, Result};
use youpipe_extractor::innertube::reset_client_version;
use youpipe_extractor::model::SearchItem;

static LOCK: Mutex<()> = Mutex::new(());
fn lock() -> MutexGuard<'static, ()> { let g = LOCK.lock().unwrap_or_else(|e| e.into_inner()); reset_client_version(); clear_caches(); g }
fn resp(url: &str, body: String) -> Response { Response { code: 200, body: body.into_bytes(), latest_url: url.to_string(), headers: [("Content-Type".into(), vec!["application/json".into()])].into_iter().collect() } }

#[derive(Default, Clone)]
struct Client { browse: String, browse_cont: String, next: String, next_cont: String }
impl HttpClient for Client {
    fn execute(&self, req: Request<'_>) -> Result<Response> {
        if req.url.contains("sw.js") { return Ok(resp(&req.url, r#"{"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20260120.01.00"}"#.into())); }
        if req.url.contains("latest-player") { return Ok(resp(&req.url, r#"{"player":"testplayer","signatureTimestamp":12345}"#.into())); }
        let body = req.body.map(|b| String::from_utf8_lossy(b).into_owned()).unwrap_or_default();
        let is_cont = body.contains("\"continuation\"");
        if req.url.contains("youtubei/v1/browse") { return Ok(resp(&req.url, if is_cont { self.browse_cont.clone() } else { self.browse.clone() })); }
        if req.url.contains("youtubei/v1/next") { return Ok(resp(&req.url, if is_cont { self.next_cont.clone() } else { self.next.clone() })); }
        Ok(resp(&req.url, "{}".into()))
    }
}
fn vid(id: &str, title: &str) -> serde_json::Value { json!({"videoRenderer":{"videoId":id,"title":{"simpleText":title},"lengthText":{"simpleText":"4:20"},"viewCountText":{"simpleText":"1,234 views"},"publishedTimeText":{"simpleText":"2 days ago"},"longBylineText":{"runs":[{"text":"Chan","navigationEndpoint":{"browseEndpoint":{"browseId":"UC12345678901234567890"}}}]},"thumbnail":{"thumbnails":[{"url":"//i.ytimg.com/a.jpg","width":120,"height":90}]},"ownerBadges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}]}}) }

fn channel_fixture() -> String {
    json!({
      "metadata":{"channelMetadataRenderer":{"externalId":"UC12345678901234567890","title":"My Channel","description":"desc","channelUrl":"https://www.youtube.com/@my","avatar":{"thumbnails":[{"url":"//i.ytimg.com/a.jpg","width":88,"height":88}]}}},
      "header":{"c4TabbedHeaderRenderer":{"title":{"simpleText":"My Channel"},"subscriberCountText":{"simpleText":"1.2M subscribers"},"banner":{"thumbnails":[{"url":"https://i.ytimg.com/b.jpg","width":100,"height":20}]},"badges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}]}},
      "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
        {"tabRenderer":{"content":{"richGridRenderer":{"contents":[
            {"richItemRenderer":{"content": vid("aaaaaaaaaaa","Vid1")}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"CONT1"}}}}
        ]}}}},
        {"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"itemSectionRenderer":{"contents":[
                {"shelfRenderer":{"content":{"verticalListRenderer":{"items":[vid("bbbbbbbbbbb","Vid2")]}}}},
                {"gridRenderer":{"items":[{"gridVideoRenderer":{"videoId":"ccccccccccc","title":{"simpleText":"Vid3"}}}]}},
                vid("ddddddddddd","Vid4"),
                {"playlistVideoRenderer":{"videoId":"eeeeeeeeeee","title":{"runs":[{"text":"Vid5"}]},"lengthSeconds":"125"}}
            ]}}
        ]}}}}
      ]}}
    }).to_string()
}

#[test]
fn channel_info_full() {
    let _g = lock();
    let c = Client { browse: channel_fixture(), ..Default::default() };
    let info = browse::channel_info(&c, "UC12345678901234567890", "en", "US").unwrap();
    assert_eq!(info.id, "UC12345678901234567890");
    assert_eq!(info.name, "My Channel");
    assert_eq!(info.description.as_deref(), Some("desc"));
    assert_eq!(info.subscriber_count, 1_200_000);
    assert!(info.verified);
    assert_eq!(info.avatars.len(), 1);
    assert_eq!(info.banners.len(), 1);
    assert_eq!(info.items.len(), 5, "items: {:?}", info.items);
    assert_eq!(info.next_page_token.as_deref(), Some("CONT1"));
    match &info.items[0] { SearchItem::Stream(s) => { assert_eq!(s.duration_seconds, Some(260)); assert_eq!(s.view_count, 1234); assert!(s.uploader_verified); assert_eq!(s.uploader_url.as_deref(), Some("https://www.youtube.com/channel/UC12345678901234567890")); } _ => panic!("stream") }
}

#[test]
fn channel_info_header_fallback_and_url_fallback() {
    let _g = lock();
    // No metadata title/channelUrl, header is pageHeaderRenderer only
    let fixture = json!({"metadata":{"channelMetadataRenderer":{"externalId":"UC99999999999999999999"}},"header":{"pageHeaderRenderer":{"title":{"simpleText":"Fallback Name"},"subscriberCountText":{"runs":[{"text":"15K subscribers"}]}}},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[]}}}).to_string();
    let c = Client { browse: fixture, ..Default::default() };
    let info = browse::channel_info(&c, "UC99999999999999999999", "en", "US").unwrap();
    assert_eq!(info.name, "Fallback Name");
    assert_eq!(info.subscriber_count, 15_000);
    assert!(info.url.contains("UC99999999999999999999"));
    assert!(info.items.is_empty());
    // carousel header variant
    let fixture2 = json!({"header":{"carouselHeaderRenderer":{"title":{"simpleText":"Carousel"}}},"contents":{}}).to_string();
    let c2 = Client { browse: fixture2, ..Default::default() };
    let info2 = browse::channel_info(&c2, "@handle", "en", "US").unwrap();
    assert_eq!(info2.name, "Carousel");
}

#[test]
fn playlist_info_header_and_fallbacks() {
    let _g = lock();
    let fixture = json!({
      "microformat":{"microformatDataRenderer":{"title":"Micro Title","description":"mdesc","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/p.jpg","width":10,"height":10}]}}},
      "sidebar":{"playlistSidebarRenderer":{"items":[{"playlistSidebarPrimaryInfoRenderer":{"title":{"simpleText":"Sidebar Title"}}}]}},
      "header":{"playlistHeaderRenderer":{"title":{"runs":[{"text":"Header Title"}]},"numVideosText":{"simpleText":"42 videos"}}},
      "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"playlistVideoListRenderer":{"contents":[vid("aaaaaaaaaaa","P1"), vid("bbbbbbbbbbb","P2")]}}]}}]}}}}]}}
    }).to_string();
    let c = Client { browse: fixture, ..Default::default() };
    let info = browse::playlist_info(&c, "PLabc", "en", "US").unwrap();
    assert_eq!(info.name, "Header Title");
    assert_eq!(info.stream_count, 42);
    assert_eq!(info.items.len(), 2);
    assert_eq!(info.url, "https://www.youtube.com/playlist?list=PLabc");
    // sidebar fallback (no header title)
    let fixture2 = json!({"microformat":{"microformatDataRenderer":{"title":"Micro Title"}},"sidebar":{"playlistSidebarRenderer":{"items":[{"playlistSidebarPrimaryInfoRenderer":{"title":{"simpleText":"Sidebar Title"}}}]}},"header":{"playlistHeaderRenderer":{}},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[]}}}).to_string();
    let info2 = browse::playlist_info(&Client{browse: fixture2, ..Default::default()}, "PLx", "en", "US").unwrap();
    assert_eq!(info2.name, "Sidebar Title");
    // microformat fallback
    let fixture3 = json!({"microformat":{"microformatDataRenderer":{"title":"Micro Title"}},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[]}}}).to_string();
    let info3 = browse::playlist_info(&Client{browse: fixture3, ..Default::default()}, "PLx", "en", "US").unwrap();
    assert_eq!(info3.name, "Micro Title");
    assert_eq!(info3.stream_count, -1);
}

#[test]
fn trending_and_browse_continuation() {
    let _g = lock();
    let c = Client { browse: channel_fixture(), ..Default::default() };
    let page = browse::trending(&c, "en", "US").unwrap();
    assert_eq!(page.items.len(), 5);
    // continuation: both actions + endpoints, richItem, direct, continuation token (button spelling)
    let cont = json!({
      "onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
          {"richItemRenderer":{"content": vid("aaaaaaaaaaa","C1")}},
          vid("bbbbbbbbbbb","C2"),
          {"continuationItemRenderer":{"button":{"buttonRenderer":{"command":{"continuationCommand":{"token":"NEXTTOK"}}}}}}
      ]}}],
      "onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[vid("ccccccccccc","C3")]}}]
    }).to_string();
    let page2 = browse::browse_continuation(&Client{browse_cont: cont, ..Default::default()}, "tok", "en", "US").unwrap();
    assert_eq!(page2.items.len(), 3);
    assert_eq!(page2.next_page_token.as_deref(), Some("NEXTTOK"));
}

#[test]
fn comments_viewmodel_and_legacy() {
    let _g = lock();
    let next_primary = json!({"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
        {"itemSectionRenderer":{"contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"CTOK"}}}}]}}
    ]}}}}}).to_string();
    let comments_page = json!({
      "frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"entityKey":"k1","payload":{"commentEntityPayload":{"properties":{"commentId":"c1","content":{"content":"hello vm"},"publishedTime":"3 days ago"},"author":{"displayName":"Alice","channelId":"UC12345678901234567890","isVerified":true,"avatarThumbnailUrl":{"thumbnails":[{"url":"https://i.ytimg.com/av.jpg","width":40,"height":40}]}},"toolbar":{"likeCountNotliked":"1.2K","replyCount":"5"}}}}]}},
      "onResponseReceivedEndpoints":[
        {"reloadContinuationItemsCommand":{"continuationItems":[
            {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentKey":"k1"}}}},
            {"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"c2","contentText":{"simpleText":"legacy hi"},"authorText":{"simpleText":"Bob"},"authorEndpoint":{"browseEndpoint":{"browseId":"UC12345678901234567890"}},"authorThumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/b.jpg","width":40,"height":40}]},"authorCommentBadge":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}],"likeCount":42,"replyCount":3,"publishedTimeText":{"simpleText":"1 day ago"},"pinnedCommentBadge":{"x":1},"actionButtons":{"commentActionButtonsRenderer":{"creatorHeart":{"y":1}}}}}}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXTC"}}}}
        ]}},
        {"appendContinuationItemsAction":{"continuationItems":[
            {"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"c3","contentText":{"runs":[{"text":"second legacy"}]}}}}}
        ]}}
      ]
    }).to_string();
    let c = Client { next: next_primary, next_cont: comments_page.clone(), ..Default::default() };
    let page = browse::comments(&c, "aaaaaaaaaaa", "en", "US").unwrap();
    assert_eq!(page.comments.len(), 3, "{:?}", page.comments);
    assert_eq!(page.next_page_token.as_deref(), Some("NEXTC"));
    let vm = &page.comments[0];
    assert_eq!(vm.id, "c1"); assert_eq!(vm.text, "hello vm"); assert_eq!(vm.author_name.as_deref(), Some("Alice")); assert!(vm.author_verified); assert_eq!(vm.like_count, 1200); assert_eq!(vm.reply_count, 5);
    let legacy = &page.comments[1];
    assert_eq!(legacy.id, "c2"); assert_eq!(legacy.text, "legacy hi"); assert!(legacy.is_pinned); assert!(legacy.is_hearted); assert_eq!(legacy.like_count, 42);
    // direct comments_page entry
    let direct = browse::comments_page(&Client{next_cont: comments_page, ..Default::default()}, "tok", "en", "US").unwrap();
    assert_eq!(direct.comments.len(), 3);
}

#[test]
fn comments_disabled_returns_empty() {
    let _g = lock();
    let next_primary = json!({"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[{"itemSectionRenderer":{"contents":[{"videoRenderer":{}}]}}]}}}}}).to_string();
    let c = Client { next: next_primary, ..Default::default() };
    let page = browse::comments(&c, "aaaaaaaaaaa", "en", "US").unwrap();
    assert!(page.comments.is_empty());
    // token found via second search path inside itemSection contents
    let next2 = json!({"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
        {"itemSectionRenderer":{"contents":[{"foo":1},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"T2"}}}}]}}
    ]}}}}}).to_string();
    let cont_page = json!({"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}).to_string();
    let page2 = browse::comments(&Client{next: next2, next_cont: cont_page, ..Default::default()}, "aaaaaaaaaaa", "en", "US").unwrap();
    assert!(page2.comments.is_empty());
}

#[test]
fn browse_error_paths() {
    let _g = lock();
    struct Code(u16, String);
    impl HttpClient for Code {
        fn execute(&self, req: Request<'_>) -> Result<Response> {
            if req.url.contains("sw.js") { return Ok(resp(&req.url, r#"{"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20260120.01.00"}"#.into())); }
            Ok(Response{code: self.0, body: self.1.as_bytes().to_vec(), latest_url: req.url.clone(), headers: [("Content-Type".into(), vec!["application/json".into()])].into_iter().collect()})
        }
    }
    assert!(browse::channel_info(&Code(404, "x".repeat(100)), "UC", "en", "US").is_err());
    assert!(browse::channel_info(&Code(200, "{}".into()), "UC", "en", "US").is_err());
    // malformed list video reported as error, unknown skipped (via trending)
    let fixture = json!({"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[
        {"richItemRenderer":{"content":{"videoRenderer":{"title":{"simpleText":"no id"}}}}},
        {"richItemRenderer":{"content":{"someFutureRenderer":{"x":1}}}},
        {"richItemRenderer":{"content": vid("aaaaaaaaaaa","Good")}}
    ]}}}}]}}}).to_string();
    let page = browse::trending(&Client{browse: fixture, ..Default::default()}, "en", "US").unwrap();
    assert_eq!(page.items.len(), 1);
    assert_eq!(page.errors.len(), 1);
}
