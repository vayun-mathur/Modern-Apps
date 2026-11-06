//! Triage UI: dashboard section, task-page result box, and the
//! "Start fix task" route that turns a triaged issue into a normal task.

use axum::{
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Redirect, Response},
};
use maud::{html, Markup};

use crate::models::{branch_name, Task};
use crate::state::AppState;
use crate::store::TriageRow;

fn issue_url(repo: &str, n: u64) -> String {
    format!("https://github.com/{repo}/issues/{n}")
}

fn result_field<'a>(v: &'a serde_json::Value, k: &str) -> Option<&'a str> {
    v.get(k).and_then(|x| x.as_str())
}

fn labels_line(v: &serde_json::Value) -> String {
    let list = |k: &str, sign: &str| -> Vec<String> {
        v.get(k)
            .and_then(|x| x.as_array())
            .map(|a| a.iter().filter_map(|l| l.as_str()).map(|l| format!("{sign}{l}")).collect())
            .unwrap_or_default()
    };
    let mut all = list("labels_added", "+");
    all.extend(list("labels_removed", "−"));
    if all.is_empty() {
        "labels unchanged".to_string()
    } else {
        all.join(" ")
    }
}

fn fix_button(issue: u64) -> Markup {
    html! {
        form method="post" action=(format!("/triage/{issue}/fix")) class="inline" {
            button type="submit" { "Start fix task" }
        }
    }
}

fn result_summary(raw: Option<&str>, repo: &str) -> Markup {
    let v: serde_json::Value = raw.and_then(|r| serde_json::from_str(r).ok()).unwrap_or_default();
    if v.is_null() {
        return html! { span class="muted" { "no result yet" } };
    }
    html! {
        span { (result_field(&v, "type").unwrap_or("?")) " · " (result_field(&v, "app").unwrap_or("?"))
            " · " (result_field(&v, "confidence").unwrap_or("?")) " confidence · " (labels_line(&v)) }
        @if let Some(url) = result_field(&v, "comment_url") {
            @if url.starts_with("https://github.com/") {
                " · " a href=(url) { "comment" }
            }
        } @else if v.get("skipped_writes").and_then(|x| x.as_bool()) == Some(true) {
            " · " span class="muted" { "GitHub not updated (no token)" }
        }
        @if let Some(dup) = v.get("duplicate_of").and_then(|x| x.as_u64()) {
            " · possible duplicate of " a href=(issue_url(repo, dup)) { "#" (dup) }
        }
    }
}

/// Dashboard "Triage" section: the most recent automatic triages.
pub fn dashboard_section(rows: &[TriageRow], repo: &str) -> Markup {
    html! {
        @if !rows.is_empty() {
            section class="dash-section" {
                h2 { "Triage" }
                @for r in rows {
                    div class="task-row" {
                        span class="badge" { "triage" }
                        a href=(issue_url(repo, r.issue_number)) { "#" (r.issue_number) }
                        a href=(format!("/tasks/{}", r.task_id)) { "task " (r.task_id) }
                        span class="reason" { (r.status) }
                        span class="small" { (result_summary(r.result_json.as_deref(), repo)) }
                        (fix_button(r.issue_number))
                    }
                }
            }
        }
    }
}

/// Side-panel block for a triage task: badge, issue link, outcome, fix button.
pub fn task_panel_block(task: &Task, repo: &str) -> Markup {
    let Some(n) = task.issue_number.map(|n| n as u64) else {
        return html! {};
    };
    html! {
        h3 { span class="badge" { "triage" } " Issue " a href=(issue_url(repo, n)) { "#" (n) } }
        @if let Some(s) = &task.summary {
            div class="p-row" { (s) }
        }
        (fix_button(n))
    }
}

/// `POST /triage/:issue/fix`: create a normal interactive task from the issue
/// (Plan first on, issue linked) and open it.
pub async fn fix_route(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(issue): Path<u64>,
) -> Response {
    if let Some(reject) = crate::ui::require_auth(&state, &headers) {
        return reject;
    }
    if let Some(reject) = crate::ui::require_origin(&state, &headers) {
        return reject;
    }
    let found = match crate::issues::fetch_issue(&state.config, issue).await {
        Ok(i) => i,
        Err(e) => return (StatusCode::BAD_GATEWAY, format!("could not fetch issue #{issue}: {e}")).into_response(),
    };
    let (title, prompt, scope) = crate::issues::prefill_from_issue(&found);
    let id = match state.store.create_task(
        &title,
        &prompt,
        &scope.unwrap_or_default(),
        &state.config.default_model,
        60,
        2.0,
    ) {
        Ok(id) => id,
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, e).into_response(),
    };
    let _ = state.store.set_branch(id, &branch_name(id, &title));
    let _ = state.store.set_plan_gate(id, true);
    let _ = state.store.set_issue_number(id, issue as i64);
    Redirect::to(&format!("/tasks/{id}")).into_response()
}
