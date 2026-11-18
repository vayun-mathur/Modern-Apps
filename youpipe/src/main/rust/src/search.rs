//! Search extraction — the `YoutubeSearchExtractor` equivalent.

use serde_json::{json, Value};

use crate::http::{Error, HttpClient, Request, Result};
use crate::innertube::{client_version, desktop_body, endpoint_url, youtube_headers};
use crate::json::JsonExt;
use crate::model::{ChannelItem, PlaylistItem, SearchItem, SearchResult, StreamItem};
use crate::parsing::{
    channel_url_from_navigation_endpoint, is_verified, parse_duration, parse_view_count,
    parse_abbreviated_count, playlist_url, stream_url, text_from_object, thumbnails_from_array,
    thumbnails_from_info_item,
};

/// `params` values selecting a single result type. `None` searches everything.
pub const FILTER_VIDEOS: &str = "EgIQAfABAQ==";
pub const FILTER_CHANNELS: &str = "EgIQAvABAQ==";
pub const FILTER_PLAYLISTS: &str = "EgIQA_ABAQ==";

pub fn filter_params(filter: Option<&str>) -> Option<&'static str> {
    match filter {
        Some("videos") => Some(FILTER_VIDEOS),
        Some("channels") => Some(FILTER_CHANNELS),
        Some("playlists") => Some(FILTER_PLAYLISTS),
        _ => None,
    }
}

/// First page of results for `query`.
pub fn search(
    http: &dyn HttpClient,
    query: &str,
    filter: Option<&str>,
    hl: &str,
    gl: &str,
) -> Result<SearchResult> {
    let version = client_version(http);

    let mut extra: Vec<(&str, Value)> = vec![("query", json!(query))];
    if let Some(params) = filter_params(filter) {
        extra.push(("params", json!(params)));
    }
    let body = desktop_body(&version, hl, gl, &extra);

    let response = http.execute(
        Request::post_json(endpoint_url("search"), &body).headers(&youtube_headers(&version)),
    )?;
    let root = parse_innertube_response(&response)?;

    let sections = root
        .obj("contents")
        .obj("twoColumnSearchResultsRenderer")
        .obj("primaryContents")
        .obj("sectionListRenderer")
        .arr("contents");

    let mut result = SearchResult {
        search_suggestion: extract_search_suggestion(sections),
        is_corrected_search: is_corrected_search(sections),
        ..Default::default()
    };

    for section in sections {
        if section.has("itemSectionRenderer") {
            collect_items(section.obj("itemSectionRenderer").arr("contents"), &mut result);
        } else if section.has("continuationItemRenderer") {
            result.next_page_token = continuation_token(section.obj("continuationItemRenderer"));
        }
    }

    Ok(result)
}

/// A subsequent page, via the token from [`SearchResult::next_page_token`].
pub fn search_page(http: &dyn HttpClient, token: &str, hl: &str, gl: &str) -> Result<SearchResult> {
    let version = client_version(http);
    let body = desktop_body(&version, hl, gl, &[("continuation", json!(token))]);

    let response = http.execute(
        Request::post_json(endpoint_url("search"), &body).headers(&youtube_headers(&version)),
    )?;
    let root = parse_innertube_response(&response)?;

    let continuation_items = root
        .arr("onResponseReceivedCommands")
        .first()
        .map(|c| c.obj("appendContinuationItemsAction").arr("continuationItems"))
        .unwrap_or_default();

    let mut result = SearchResult::default();
    for item in continuation_items {
        if item.has("itemSectionRenderer") {
            collect_items(item.obj("itemSectionRenderer").arr("contents"), &mut result);
        } else if item.has("continuationItemRenderer") {
            result.next_page_token = continuation_token(item.obj("continuationItemRenderer"));
        }
    }

    Ok(result)
}

/// Validates an InnerTube reply and parses it.
///
/// Mirrors upstream's `getValidJsonResponseBody`: YouTube signals some failures by serving an
/// HTML error page with a 200, so content type and length are checked before parsing.
fn parse_innertube_response(response: &crate::http::Response) -> Result<Value> {
    if response.code == 404 {
        return Err(Error::Response("not found (404)".into()));
    }
    if response.body.len() < 50 {
        return Err(Error::Response(format!("response too short ({} bytes)", response.body.len())));
    }
    if let Some(content_type) = response.header("Content-Type") {
        if content_type.to_ascii_lowercase().contains("text/html") {
            return Err(Error::Response(format!(
                "got HTML, expected JSON (final url: {})",
                response.latest_url
            )));
        }
    }
    serde_json::from_slice(&response.body).map_err(|e| Error::Parse(e.to_string()))
}

fn continuation_token(renderer: &Value) -> Option<String> {
    renderer
        .obj("continuationEndpoint")
        .obj("continuationCommand")
        .str("token")
        .filter(|t| !t.is_empty())
        .map(str::to_string)
}

/// YouTube's spelling correction, if the response carries one.
///
/// Both renderers are absent on an ordinary search — the case the Kotlin port asserted on and
/// threw for on essentially every query — so this returns `None` rather than failing.
fn extract_search_suggestion(sections: &[Value]) -> Option<String> {
    let first = sections
        .first()?
        .obj("itemSectionRenderer")
        .arr("contents")
        .first()?
        .clone();

    let did_you_mean = first.obj("didYouMeanRenderer");
    if !did_you_mean.is_blank() {
        return text_from_object(did_you_mean.obj("correctedQuery"));
    }
    text_from_object(first.obj("showingResultsForRenderer").obj("correctedQuery"))
}

fn is_corrected_search(sections: &[Value]) -> bool {
    sections
        .first()
        .map(|s| {
            !s.obj("itemSectionRenderer")
                .arr("contents")
                .first()
                .map(|c| c.obj("showingResultsForRenderer").is_blank())
                .unwrap_or(true)
        })
        .unwrap_or(false)
}

/// Turns a section's `contents` into items, skipping ads and unknown renderers.
///
/// A malformed entry records an error and is dropped; it never aborts the page. That mirrors
/// upstream's per-item `commit()` error collection.
fn collect_items(contents: &[Value], result: &mut SearchResult) {
    for item in contents {
        if item.has("backgroundPromoRenderer") {
            // "No results found" placeholder.
            continue;
        }

        let parsed = if item.has("videoRenderer") {
            parse_stream(item.obj("videoRenderer")).map(SearchItem::Stream)
        } else if item.has("channelRenderer") {
            parse_channel(item.obj("channelRenderer")).map(SearchItem::Channel)
        } else if item.has("playlistRenderer") {
            parse_playlist(item.obj("playlistRenderer")).map(SearchItem::Playlist)
        } else if item.has("lockupViewModel") {
            parse_lockup(item.obj("lockupViewModel"))
        } else {
            // Shelves, ads, promos and renderer types we do not handle yet.
            continue;
        };

        match parsed {
            Ok(entry) => result.items.push(entry),
            Err(message) => result.errors.push(message),
        }
    }
}

fn parse_stream(renderer: &Value) -> std::result::Result<StreamItem, String> {
    let video_id = renderer
        .str("videoId")
        .filter(|id| !id.is_empty())
        .ok_or_else(|| "videoRenderer without videoId".to_string())?;

    let name = text_from_object(renderer.obj("title"))
        .ok_or_else(|| format!("no title for video {video_id}"))?;

    // Live streams carry a badge and no duration.
    let is_live = renderer.arr("badges").iter().any(|b| {
        b.obj("metadataBadgeRenderer").str("style") == Some("BADGE_STYLE_TYPE_LIVE_NOW")
    });

    let duration_seconds = text_from_object(renderer.obj("lengthText"))
        .as_deref()
        .and_then(parse_duration);

    let view_count = text_from_object(renderer.obj("viewCountText"))
        .as_deref()
        .map_or(-1, parse_view_count);

    let uploader_endpoint = renderer
        .obj("longBylineText")
        .arr("runs")
        .first()
        .map(|r| r.obj("navigationEndpoint"))
        .unwrap_or(&Value::Null);

    Ok(StreamItem {
        url: stream_url(video_id),
        name,
        duration_seconds,
        uploader_name: text_from_object(renderer.obj("longBylineText"))
            .or_else(|| text_from_object(renderer.obj("ownerText")))
            .or_else(|| text_from_object(renderer.obj("shortBylineText"))),
        uploader_url: channel_url_from_navigation_endpoint(uploader_endpoint),
        uploader_verified: is_verified(renderer.arr("ownerBadges")),
        textual_upload_date: text_from_object(renderer.obj("publishedTimeText")),
        view_count,
        thumbnails: thumbnails_from_info_item(renderer),
        is_live,
        is_short: false,
    })
}

fn parse_channel(renderer: &Value) -> std::result::Result<ChannelItem, String> {
    let channel_id = renderer
        .str("channelId")
        .filter(|id| !id.is_empty())
        .ok_or_else(|| "channelRenderer without channelId".to_string())?;

    let name = text_from_object(renderer.obj("title"))
        .ok_or_else(|| format!("no title for channel {channel_id}"))?;

    let url = channel_url_from_navigation_endpoint(renderer.obj("navigationEndpoint"))
        .unwrap_or_else(|| crate::parsing::channel_url(channel_id));

    let subscriber_count = text_from_object(renderer.obj("subscriberCountText"))
        .as_deref()
        .map_or(-1, parse_abbreviated_count);

    let stream_count = text_from_object(renderer.obj("videoCountText"))
        .as_deref()
        .map_or(-1, parse_view_count);

    Ok(ChannelItem {
        url,
        name,
        description: text_from_object(renderer.obj("descriptionSnippet")),
        subscriber_count,
        stream_count,
        verified: is_verified(renderer.arr("ownerBadges")),
        thumbnails: thumbnails_from_array(renderer.obj("thumbnail").arr("thumbnails")),
    })
}

fn parse_playlist(renderer: &Value) -> std::result::Result<PlaylistItem, String> {
    let playlist_id = renderer
        .str("playlistId")
        .filter(|id| !id.is_empty())
        .ok_or_else(|| "playlistRenderer without playlistId".to_string())?;

    let name = text_from_object(renderer.obj("title"))
        .ok_or_else(|| format!("no title for playlist {playlist_id}"))?;

    Ok(PlaylistItem {
        url: playlist_url(playlist_id),
        name,
        uploader_name: text_from_object(renderer.obj("longBylineText"))
            .or_else(|| text_from_object(renderer.obj("shortBylineText"))),
        stream_count: renderer
            .str("videoCount")
            .and_then(|c| c.parse().ok())
            .unwrap_or(-1),
        thumbnails: thumbnails_from_info_item(renderer),
    })
}

/// The newer `lockupViewModel` shape YouTube is migrating results to.
fn parse_lockup(model: &Value) -> std::result::Result<SearchItem, String> {
    let content_type = model.str_or("contentType", "");
    let content_id = model.str_or("contentId", "");
    if content_id.is_empty() {
        return Err("lockupViewModel without contentId".into());
    }

    let title = model
        .obj("metadata")
        .obj("lockupMetadataViewModel")
        .obj("title")
        .str("content")
        .unwrap_or_default()
        .to_string();

    match content_type {
        "LOCKUP_CONTENT_TYPE_PLAYLIST" | "LOCKUP_CONTENT_TYPE_PODCAST" => {
            Ok(SearchItem::Playlist(PlaylistItem {
                url: playlist_url(content_id),
                name: title,
                uploader_name: None,
                stream_count: -1,
                thumbnails: Vec::new(),
            }))
        }
        "LOCKUP_CONTENT_TYPE_VIDEO" => Ok(SearchItem::Stream(StreamItem {
            url: stream_url(content_id),
            name: title,
            view_count: -1,
            ..Default::default()
        })),
        other => Err(format!("unhandled lockup content type: {other}")),
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn suggestion_absent_on_an_ordinary_search() {
        // Shape taken from a live response: contents[0] has only a videoRenderer.
        let sections = json!([{ "itemSectionRenderer": { "contents": [{ "videoRenderer": {} }] } }]);
        assert_eq!(extract_search_suggestion(sections.as_array().unwrap()), None);
        assert!(!is_corrected_search(sections.as_array().unwrap()));
    }

    #[test]
    fn suggestion_read_when_present() {
        let sections = json!([{
            "itemSectionRenderer": { "contents": [{
                "showingResultsForRenderer": { "correctedQuery": { "runs": [{ "text": "cats" }] } }
            }]}
        }]);
        assert_eq!(
            extract_search_suggestion(sections.as_array().unwrap()).as_deref(),
            Some("cats")
        );
        assert!(is_corrected_search(sections.as_array().unwrap()));
    }

    #[test]
    fn stream_without_owner_badges_parses_unverified() {
        // 7 of 19 live results had no ownerBadges; that must not be an error.
        let renderer = json!({
            "videoId": "dQw4w9WgXcQ",
            "title": { "runs": [{ "text": "Never Gonna Give You Up" }] },
            "lengthText": { "simpleText": "3:33" },
            "viewCountText": { "simpleText": "1,600,000,000 views" },
            "publishedTimeText": { "simpleText": "16 years ago" }
        });
        let item = parse_stream(&renderer).expect("should parse");
        assert_eq!(item.url, "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assert_eq!(item.duration_seconds, Some(213));
        assert_eq!(item.view_count, 1_600_000_000);
        assert!(!item.uploader_verified);
    }

    #[test]
    fn a_bad_item_is_recorded_but_does_not_drop_the_good_ones() {
        let contents = json!([
            { "videoRenderer": { "title": { "simpleText": "no id here" } } },
            { "videoRenderer": { "videoId": "abc", "title": { "simpleText": "fine" } } }
        ]);
        let mut result = SearchResult::default();
        collect_items(contents.as_array().unwrap(), &mut result);
        assert_eq!(result.items.len(), 1, "the valid item survives");
        assert_eq!(result.errors.len(), 1, "the invalid one is reported");
    }

    #[test]
    fn empty_response_yields_empty_result_not_an_error() {
        let mut result = SearchResult::default();
        collect_items(&[], &mut result);
        assert!(result.items.is_empty());
        assert!(result.errors.is_empty());
    }

    #[test]
    fn subscriber_counts() {
        assert_eq!(parse_abbreviated_count("1.2M subscribers"), 1_200_000);
        assert_eq!(parse_abbreviated_count("15K subscribers"), 15_000);
        assert_eq!(parse_abbreviated_count("842 subscribers"), 842);
        assert_eq!(parse_abbreviated_count("no digits"), -1);
    }

    #[test]
    fn continuation_token_absent_means_no_next_page() {
        assert_eq!(continuation_token(&json!({})), None);
        let renderer = json!({
            "continuationEndpoint": { "continuationCommand": { "token": "TOK" } }
        });
        assert_eq!(continuation_token(&renderer).as_deref(), Some("TOK"));
    }
}
