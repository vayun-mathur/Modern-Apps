//! Server-rendered HTML UI (maud; auto-escaped) plus SSE live events.
//!
//! Routes:
//! * `GET /healthz` — unauthenticated, body `ok`.
//! * `GET /login`, `POST /login`, `POST /logout`.
//! * `GET /` — dashboard ("Needs you" first, then running, then recent).
//! * `GET /tasks/new`, `POST /tasks` — new-task form + creation.
//! * `GET /tasks/:id` — task page (chat timeline + side panel + cards).
//! * `GET /tasks/:id/events` — SSE stream of the task's events.
//! * `GET /tasks/:id/timeline?after=<seq>` — fragment: new timeline events.
//! * `GET /tasks/:id/panel` — fragment: side panel (status, todos, budget…).
//! * `GET /tasks/:id/cards` — fragment: pending/review cards.
//! * `GET /fragments/dashboard` — fragment: dashboard sections + title.
//! * `GET /static/app.js`, `GET /static/app.css` — bundled assets.
//! * `POST /tasks/:id/messages` — inbox a user message (answer or steering).
//! * `POST /tasks/:id/stop` (alias `/cancel`) — park with work kept.
//! * `POST /tasks/:id/discard` — terminal discard.
//! * `POST /tasks/:id/retry`.
//! * `POST /tasks/:id/plan/approve`, `POST /tasks/:id/plan/changes`.
//! * `POST /tasks/:id/approve` — approve the review (push).
//!
//! All state-changing routes are POST and additionally require an `Origin`
//! header exactly equal to `AGENTD_PUBLIC_ORIGIN`. Auth is a session cookie
//! (HttpOnly; Secure; SameSite=Strict). Security headers (CSP self-only,
//! nosniff, no-referrer, frame-ancestors none) are attached to every
//! response.
//!
//! Rendering contract: every page is server-rendered maud (auto-escaped).
//! Agent-authored text goes through [`crate::markdown::render_markdown`]
//! (safe subset) or plain escaped text — never raw HTML. There is no inline
//! `<script>` and no inline `style=` anywhere; `/static/app.js` wires live
//! updates by fetching the fragment routes above and swapping them in (it
//! never builds HTML from event JSON). Every action is a plain `<form>`
//! POST, so the UI works without JS.

use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::sse::{Event as SseEvent, KeepAlive, Sse};
use axum::response::{Html, IntoResponse, Response};
use axum::{Form, Router};
use maud::{html, Markup, PreEscaped};
use serde::Deserialize;
use tokio_stream::wrappers::BroadcastStream;
use tokio_stream::StreamExt;

use crate::auth::{client_ip, origin_allowed, session_token_from_cookie, SESSION_COOKIE};
use crate::markdown::render_markdown;
use crate::models::{
    branch_name, Event, Task, TaskStatus, EVENT_ASSISTANT, EVENT_PLAN, EVENT_QUESTION,
    EVENT_REVIEW, EVENT_STATUS, EVENT_TODOS, EVENT_TOOL_CALL, EVENT_TOOL_RESULT,
    EVENT_USER_MESSAGE, INBOX_PLAN_CHANGES, INBOX_REVIEW_CHANGES, PENDING_BUDGET, PENDING_CHAT,
    PENDING_ERROR, PENDING_PLAN, PENDING_QUESTION, PENDING_STOPPED,
};
use crate::state::AppState;

pub fn router(state: AppState) -> Router {
    use axum::routing::{get, post};
    Router::new()
        .route("/healthz", get(healthz))
        .route("/login", get(login_page).post(login_submit))
        .route("/logout", post(logout))
        .route("/", get(task_list))
        .route("/tasks/new", get(new_task_page))
        .route("/tasks", post(create_task))
        .route("/tasks/:id", get(task_page))
        .route("/tasks/:id/events", get(task_events_sse))
        .route("/tasks/:id/timeline", get(task_timeline_fragment))
        .route("/tasks/:id/panel", get(task_panel_fragment))
        .route("/tasks/:id/cards", get(task_cards_fragment))
        .route("/fragments/dashboard", get(dashboard_fragment))
        .route("/static/app.js", get(static_js))
        .route("/static/app.css", get(static_css))
        .route("/tasks/:id/messages", post(messages_route))
        .route("/tasks/:id/stop", post(stop_route))
        .route("/tasks/:id/cancel", post(stop_route))
        .route("/tasks/:id/discard", post(discard_route))
        .route("/tasks/:id/retry", post(retry_route))
        .route("/tasks/:id/plan/approve", post(plan_approve_route))
        .route("/tasks/:id/plan/changes", post(plan_changes_route))
        .route("/tasks/:id/approve", post(review_approve_route))
        .route("/triage/:issue/fix", post(crate::triage_ui::fix_route))
        .with_state(state)
        .layer(axum::middleware::from_fn(security_headers))
}

async fn security_headers(
    req: axum::extract::Request,
    next: axum::middleware::Next,
) -> Response {
    let mut resp = next.run(req).await;
    let h = resp.headers_mut();
    // No 'unsafe-inline' anywhere: styling lives in /static/app.css and
    // behavior in /static/app.js. `img-src data:` covers no remote images
    // (markdown images render as alt text); favicon-less pages need nothing.
    h.insert(
        header::CONTENT_SECURITY_POLICY,
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
            .parse()
            .unwrap(),
    );
    h.insert(header::X_CONTENT_TYPE_OPTIONS, "nosniff".parse().unwrap());
    h.insert(header::REFERRER_POLICY, "no-referrer".parse().unwrap());
    h.insert(header::X_FRAME_OPTIONS, "DENY".parse().unwrap());
    resp
}

async fn healthz() -> &'static str {
    "ok"
}

// ---- static assets (bundled via include_str!) ----

const APP_JS: &str = include_str!("static/app.js");
const APP_CSS: &str = include_str!("static/app.css");

async fn static_js() -> Response {
    (
        [
            (
                header::CONTENT_TYPE,
                "application/javascript; charset=utf-8",
            ),
            (header::CACHE_CONTROL, "public, max-age=60"),
        ],
        APP_JS,
    )
        .into_response()
}

async fn static_css() -> Response {
    (
        [
            (header::CONTENT_TYPE, "text/css; charset=utf-8"),
            (header::CACHE_CONTROL, "public, max-age=60"),
        ],
        APP_CSS,
    )
        .into_response()
}

// ---- auth plumbing ----

fn session_token(headers: &HeaderMap) -> Option<String> {
    headers
        .get(header::COOKIE)
        .and_then(|v| v.to_str().ok())
        .and_then(session_token_from_cookie)
}

fn authed(state: &AppState, headers: &HeaderMap) -> bool {
    session_token(headers)
        .map(|t| state.auth.check_token(&t))
        .unwrap_or(false)
}

/// `None` = OK; `Some(response)` = reject (login redirect or 401 for SSE).
pub(crate) fn require_auth(state: &AppState, headers: &HeaderMap) -> Option<Response> {
    if authed(state, headers) {
        return None;
    }
    let accept: &str = headers
        .get(header::ACCEPT)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if accept.contains("text/event-stream") {
        Some((StatusCode::UNAUTHORIZED, "login required").into_response())
    } else {
        Some(
            (
                StatusCode::FOUND,
                [(header::LOCATION, "/login")],
                "redirect to /login",
            )
                .into_response(),
        )
    }
}

/// Fragment routes (`/tasks/:id/timeline|panel|cards`, `/fragments/dashboard`)
/// are only ever fetched by `app.js`: reject unauthenticated callers with a
/// plain 401 (never a login-page redirect, which the script would swap into
/// the page).
fn require_auth_fragment(state: &AppState, headers: &HeaderMap) -> Option<Response> {
    if authed(state, headers) {
        None
    } else {
        Some((StatusCode::UNAUTHORIZED, "login required").into_response())
    }
}

pub(crate) fn require_origin(state: &AppState, headers: &HeaderMap) -> Option<Response> {
    let origin = headers.get(header::ORIGIN).and_then(|v| v.to_str().ok());
    if origin_allowed(origin, &state.config.public_origin) {
        None
    } else {
        Some((StatusCode::FORBIDDEN, "bad or missing Origin").into_response())
    }
}

fn peer_ip(headers: &HeaderMap) -> String {
    // Cloudflare sets CF-Connecting-IP to the real client (Caddy forwards it
    // untouched); Caddy rewrites X-Forwarded-For to the CF edge address. Direct
    // hits that forge it are still bounded by the limiter's global cap.
    if let Some(ip) = headers
        .get("cf-connecting-ip")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        return ip.to_string();
    }
    let ff = headers.get("x-forwarded-for").and_then(|v| v.to_str().ok());
    client_ip(ff, None)
}

/// Sanitize a module-scope form value (same charset rule as before).
fn scope_from_form(raw: &str) -> Result<String, String> {
    let scope: String = raw
        .trim()
        .chars()
        .filter(|c| {
            c.is_ascii_alphanumeric() || *c == '/' || *c == ':' || *c == '-' || *c == '_'
        })
        .collect();
    if scope.is_empty() {
        return Err("module scope is required".to_string());
    }
    Ok(scope)
}

// ---- small pure helpers (unit-tested via the render fns below) ----

/// Escape `&<>"` for embedding a pre-escaped fragment (diff lines). Agent
/// text rendered through maud `(…)` or `render_markdown` never needs this.
fn esc_html(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            _ => out.push(c),
        }
    }
    out
}

/// Cap to `n` chars (char-boundary safe). Callers add their own truncation
/// marker so inline chips (`…`) and blocks (`[truncated]`) read naturally.
fn cap_chars(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.to_string()
    } else {
        s.chars().take(n).collect()
    }
}

fn ev_str<'a>(body: &'a serde_json::Value, key: &str) -> &'a str {
    body.get(key).and_then(|v| v.as_str()).unwrap_or("")
}

// ---- page shell ----

/// Full-page shell. `doc_title` is the complete `<title>` text (dashboard
/// passes `"(N) agentd"`). Styling is `/static/app.css`, behavior is
/// `/static/app.js` (deferred); no inline `<script>` or `<style>` anywhere.
pub(crate) fn layout(doc_title: &str, release: bool, body: Markup) -> Markup {
    html! {
        (maud::DOCTYPE)
        html lang="en" {
            head {
                meta charset="utf-8";
                meta name="viewport" content="width=device-width, initial-scale=1";
                title { (doc_title) }
                link rel="stylesheet" href="/static/app.css";
                script src="/static/app.js" defer {}
            }
            body {
                header class="top" {
                    a href="/" { "agentd" }
                    span class="sep" { "·" }
                    a href="/tasks/new" { "new task" }
                    form method="post" action="/logout" class="inline" {
                        button type="submit" class="link" { "logout" }
                    }
                }
                @if release {
                    div class="banner" { "A production release build is running — new builds will wait (up to 60 min) or be preempted." }
                }
                main { (body) }
            }
        }
    }
}

fn release_banner(state: &AppState) -> bool {
    crate::agent::release_active(&state.config)
}

async fn login_page() -> Html<String> {
    Html(
        layout(
            "login — agentd",
            false,
            html! {
                h1 { "agentd login" }
                form method="post" action="/login" class="card" {
                    label { "Password" input type="password" name="password" autofocus autocomplete="current-password"; }
                    button type="submit" { "Sign in" }
                }
            },
        )
        .into_string(),
    )
}

#[derive(Deserialize)]
struct LoginForm {
    password: String,
}

async fn login_submit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<LoginForm>,
) -> Response {
    let ip = peer_ip(&headers);
    if let Some(retry) = state.auth.login_gate(&ip) {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            [("Retry-After", retry.as_secs().to_string())],
            "too many login attempts; slow down",
        )
            .into_response();
    }
    if !state.auth.verify_password(&form.password) {
        state.auth.login_failed(&ip);
        return (
            StatusCode::UNAUTHORIZED,
            Html(
                layout(
                    "login — agentd",
                    false,
                    html! {
                        h1 { "agentd login" }
                        p class="pill failed" { "Wrong password." }
                        form method="post" action="/login" class="card" {
                            label { "Password" input type="password" name="password" autofocus autocomplete="current-password"; }
                            button type="submit" { "Sign in" }
                        }
                    },
                )
                .into_string(),
            ),
        )
            .into_response();
    }
    state.auth.login_ok(&ip);
    let token = match state.auth.issue_session() {
        Ok(t) => t,
        Err(e) => {
            tracing::error!("issue session: {e}");
            return (StatusCode::INTERNAL_SERVER_ERROR, "login failed").into_response();
        }
    };
    (
        StatusCode::FOUND,
        [
            (header::LOCATION, "/".to_string()),
            (
                header::SET_COOKIE,
                format!(
                    "{SESSION_COOKIE}={token}; Path=/; Max-Age=604800; HttpOnly; Secure; SameSite=Strict"
                ),
            ),
        ],
        "ok",
    )
        .into_response()
}

async fn logout(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Some(reject) = require_origin(&state, &headers) {
        return reject;
    }
    if let Some(t) = session_token(&headers) {
        state.auth.revoke_token(&t);
    }
    (
        StatusCode::FOUND,
        [
            (header::LOCATION, "/login".to_string()),
            (
                header::SET_COOKIE,
                format!("{SESSION_COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict"),
            ),
        ],
        "logged out",
    )
        .into_response()
}

fn status_pill(s: TaskStatus) -> Markup {
    html! { span class=(format!("pill {}", s.as_str())) { (s.as_str()) } }
}

// ---- dashboard ----

/// Split tasks into "needs you" (needs_input of any kind + review),
/// running/queued, and recent finished (capped at 20, newest first).
fn split_dashboard(tasks: &[Task]) -> (Vec<&Task>, Vec<&Task>, Vec<&Task>) {
    let mut needs = Vec::new();
    let mut active = Vec::new();
    let mut done = Vec::new();
    for t in tasks {
        match t.status {
            TaskStatus::NeedsInput | TaskStatus::Review => needs.push(t),
            TaskStatus::Queued | TaskStatus::Running => active.push(t),
            _ => done.push(t),
        }
    }
    done.truncate(20);
    (needs, active, done)
}

/// `<title>` text: `"(N) agentd"` when N tasks need the operator.
pub(crate) fn dashboard_title(needs: usize) -> String {
    if needs == 0 {
        "agentd".to_string()
    } else {
        format!("({needs}) agentd")
    }
}

/// One-line reason a task needs the operator (pending kind + excerpt).
fn need_reason(t: &Task) -> String {
    if t.status == TaskStatus::Review {
        let s = t.summary.as_deref().unwrap_or("").trim();
        return if s.is_empty() {
            "review: awaiting approval".to_string()
        } else {
            format!("review: {}", cap_chars(s, 80))
        };
    }
    let pending = match t.pending.as_ref() {
        Some(p) => p,
        None => return "waiting for you".to_string(),
    };
    match pending.kind.as_str() {
        PENDING_QUESTION => {
            let q = pending
                .payload
                .get("question")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim();
            if q.is_empty() {
                "question: awaiting answer".to_string()
            } else {
                format!("question: {}", cap_chars(q, 80))
            }
        }
        PENDING_PLAN => "plan: awaiting approval".to_string(),
        PENDING_CHAT => "replied — waiting for you".to_string(),
        PENDING_BUDGET => {
            let m = pending
                .payload
                .get("message")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim();
            if m.is_empty() {
                "budget exhausted".to_string()
            } else {
                format!("budget: {}", cap_chars(m, 100))
            }
        }
        PENDING_ERROR => {
            let m = pending
                .payload
                .get("message")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim();
            if m.is_empty() {
                "error — parked".to_string()
            } else {
                format!("error: {}", cap_chars(m, 100))
            }
        }
        PENDING_STOPPED => "stopped — work kept".to_string(),
        _ => format!("waiting ({})", pending.kind),
    }
}

fn dashboard_task_row(t: &Task, reason: Option<String>) -> Markup {
    html! {
        div class="task-row" {
            @if t.kind == crate::models::TASK_KIND_TRIAGE {
                span class="badge" { "triage" }
            }
            a href=(format!("/tasks/{}", t.id)) { "#" (t.id) " " (&t.title) }
            (status_pill(t.status))
            @if let Some(r) = reason {
                span class="reason" { (r) }
            }
            span class="cost" { "$" (format!("{:.4}", t.cost_usd)) " · " (&t.model) }
        }
    }
}

/// Dashboard body sections; `triage` is the pre-rendered "Triage" section.
fn render_dashboard_sections(tasks: &[Task], triage: &Markup) -> Markup {
    let (needs, active, done) = split_dashboard(tasks);
    html! {
        (triage)
        @if !needs.is_empty() {
            section class="dash-section" {
                h2 { "Needs you (" (needs.len()) ")" }
                @for t in &needs {
                    (dashboard_task_row(t, Some(need_reason(t))))
                }
            }
        }
        @if !active.is_empty() {
            section class="dash-section" {
                h2 { "Running" }
                @for t in &active {
                    (dashboard_task_row(t, None))
                }
            }
        }
        section class="dash-section" {
            h2 { "Recent" }
            @for t in &done {
                (dashboard_task_row(t, None))
            }
            @if tasks.is_empty() {
                p class="muted" { "No tasks yet." }
            }
        }
    }
}

/// Extension anchor for the parallel triage worker's dashboard "Triage"
/// section (separate `triage_ui.rs` + small hooks here). No-op until that
/// lands, so the dashboard stays structured around one extra section call.
fn triage_section(state: &AppState) -> Markup {
    let rows = state.store.triage_recent(10).unwrap_or_default();
    crate::triage_ui::dashboard_section(&rows, &state.config.github_repo)
}

/// Full dashboard page (pure; the handler only loads tasks). Returns the
/// complete document markup; the `<title>` carries the needs-you count.
#[cfg(test)]
pub(crate) fn render_dashboard_page(tasks: &[Task], release: bool) -> Markup {
    render_dashboard_page_with(tasks, release, &html! {})
}

fn render_dashboard_page_with(tasks: &[Task], release: bool, triage: &Markup) -> Markup {
    let (needs, _, _) = split_dashboard(tasks);
    let title = dashboard_title(needs.len());
    layout(
        &title,
        release,
        html! {
            h1 { "Tasks" }
            p { a href="/tasks/new" { "+ New task" } }
            div id="dashboard" {
                (render_dashboard_sections(tasks, triage))
            }
        },
    )
}

async fn task_list(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    let tasks = state.store.list_tasks(100).unwrap_or_default();
    let banner = release_banner(&state);
    Html(render_dashboard_page_with(&tasks, banner, &triage_section(&state)).into_string()).into_response()
}

/// Dashboard fragment for `app.js` polling: the sections plus the document
/// title (carried as an attribute; the script applies it to
/// `document.title`). Auth is a plain 401 (see `require_auth_fragment`).
async fn dashboard_fragment(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Some(reject) = require_auth_fragment(&state, &headers) {
        return reject;
    }
    let tasks = state.store.list_tasks(100).unwrap_or_default();
    let (needs, _, _) = split_dashboard(&tasks);
    let title = dashboard_title(needs.len());
    Html(
        html! {
            div data-dash-body="1" data-doc-title=(title) {
                (render_dashboard_sections(&tasks, &triage_section(&state)))
            }
        }
        .into_string(),
    )
    .into_response()
}

async fn new_task_page(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    let banner = release_banner(&state);
    let default_model = state.config.default_model.clone();
    Html(
        layout(
            "new task — agentd",
            banner,
            html! {
                h1 { "New task" }
                form method="post" action="/tasks" class="card newtask" {
                    label { "Title (or leave blank with a GitHub issue # below)" input type="text" name="title" maxlength="120"; }
                    label { "Module scope (e.g. notes, games/logicgate)"
                        input type="text" name="module_scope" value="notes" maxlength="80"; }
                    label { "Model"
                        input type="text" name="model" value=(default_model) maxlength="120"; }
                    label { "Prompt (or leave blank with a GitHub issue # below)" textarea name="prompt" rows="12" {} }
                    label { "GitHub issue # (optional — prefills title/prompt/module from the issue)"
                        input type="number" name="issue_number" min="1" max="1000000"; }
                    div class="row" {
                        label { "Budget steps" input type="number" name="budget_steps" value="60" min="1" max="500"; }
                        label { "Budget USD" input type="number" name="budget_usd" value="2.00" min="0.1" max="50" step="0.5"; }
                    }
                    label class="row" {
                        input type="checkbox" name="require_plan" value="1" checked;
                        "Plan first (agent proposes a plan and waits for approval before editing)"
                    }
                    button type="submit" class="primary" { "Queue task" }
                }
            },
        )
        .into_string(),
    )
    .into_response()
}

#[derive(Deserialize, Default)]
struct NewTaskForm {
    #[serde(default)]
    title: String,
    #[serde(default)]
    prompt: String,
    #[serde(default)]
    module_scope: String,
    #[serde(default)]
    model: String,
    #[serde(default)]
    budget_steps: Option<i64>,
    #[serde(default)]
    budget_usd: Option<f64>,
    /// Optional GitHub issue number: prefills title/prompt/module from the
    /// issue (typed form values win) and stores `issue_number` so `finish`
    /// requires the ` (#<n>)` commit suffix.
    #[serde(default)]
    issue_number: Option<i64>,
    /// "Plan first" checkbox (default checked in the form). `Option` because
    /// unchecked boxes are omitted from the form body entirely.
    #[serde(default)]
    require_plan: Option<String>,
}

async fn create_task(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<NewTaskForm>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    if let Some(reject) = require_origin(&state, &headers) {
        return reject;
    }
    let title = form.title.trim().to_string();
    let prompt = form.prompt.trim().to_string();
    let issue_number = form.issue_number.filter(|n| *n > 0);
    // Start-from-issue: fetch the issue and prefill title/prompt/module from
    // it — but values the user typed win. The issue link is stored so
    // `finish` requires the ` (#<n>)` commit suffix.
    let (title, prompt, scope) = if let Some(n) = issue_number {
        if title.is_empty() && prompt.is_empty() {
            // Both blank: require the issue lookup up front so the task is
            // never created with empty content.
            match crate::issues::fetch_issue(&state.config, n as u64).await {
                Ok(issue) => {
                    let (t, p, m) = crate::issues::prefill_from_issue(&issue);
                    let scope = scope_from_form(&form.module_scope).unwrap_or_else(|_| {
                        m.unwrap_or_else(|| scope_from_form(&form.module_scope).unwrap_or_default())
                    });
                    (t, p, scope)
                }
                Err(e) => {
                    return (
                        StatusCode::BAD_GATEWAY,
                        format!("could not fetch GitHub issue #{n}: {e}"),
                    )
                        .into_response();
                }
            }
        } else {
            // User typed something: still link the issue when it exists.
            let mut title = title;
            let mut prompt = prompt;
            let mut scope = scope_from_form(&form.module_scope).unwrap_or_default();
            if let Ok(issue) = crate::issues::fetch_issue(&state.config, n as u64).await {
                let (it, ip, im) = crate::issues::prefill_from_issue(&issue);
                if title.is_empty() {
                    title = it;
                }
                if prompt.is_empty() {
                    prompt = ip;
                }
                if form.module_scope.trim().is_empty() {
                    if let Some(m) = im {
                        scope = m;
                    }
                }
            }
            (title, prompt, scope)
        }
    } else {
        if title.is_empty() || prompt.is_empty() {
            return (StatusCode::BAD_REQUEST, "title and prompt are required").into_response();
        }
        match scope_from_form(&form.module_scope) {
            Ok(scope) => (title, prompt, scope),
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        }
    };
    if title.is_empty() || prompt.is_empty() {
        return (StatusCode::BAD_REQUEST, "title and prompt are required").into_response();
    }
    if scope.is_empty() {
        return (StatusCode::BAD_REQUEST, "module scope is required").into_response();
    }
    let model = if form.model.trim().is_empty() {
        state.config.default_model.clone()
    } else {
        form.model.trim().to_string()
    };
    let steps = form.budget_steps.unwrap_or(60).clamp(1, 500);
    let usd = form.budget_usd.unwrap_or(2.0).clamp(0.1, 50.0);
    let id = match state
        .store
        .create_task(&title, &prompt, &scope, &model, steps, usd)
    {
        Ok(id) => id,
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, e).into_response(),
    };
    let branch = branch_name(id, &title);
    let _ = state.store.set_branch(id, &branch);
    // "Plan first" checkbox: the form renders it checked by default.
    // Browsers omit unchecked boxes from the body, so present ("1") means
    // on and absent means off.
    let require_plan = form.require_plan.as_deref() == Some("1");
    let _ = state.store.set_plan_gate(id, require_plan);
    if let Some(n) = issue_number {
        let _ = state.store.set_issue_number(id, n);
    }
    state
        .driver()
        .emit(id, EVENT_STATUS, serde_json::json!({"message": "task queued"}));
    (
        StatusCode::FOUND,
        [(header::LOCATION, format!("/tasks/{id}"))],
        "queued",
    )
        .into_response()
}

// ---- task page: chat timeline + cards + side panel ----

/// Full task page (pure; the handler only loads the task + events). The
/// timeline carries `data-task-id` + `data-after` (max rendered seq) for
/// `app.js`, which appends `/timeline?after=` fragments and swaps `/panel`
/// and `/cards` in place.
pub(crate) fn render_task_page(
    task: &Task,
    events: &[Event],
    release: bool,
    github_repo: &str,
) -> Markup {
    let task_id = task.id;
    let after = events.last().map(|e| e.seq).unwrap_or(-1);
    layout(
        &format!("task #{task_id} — agentd"),
        release,
        html! {
            h1 { "#" (task_id) " " (&task.title) }
            div class="meta-row" {
                (status_pill(task.status))
                span class="small muted" { (&task.model) }
            }
            @if let Some(err) = &task.error {
                p class="pill failed" { "Error: " (err) }
            }
            div id="cards" {
                (render_cards(task))
            }
            div class="task-grid" {
                div class="main" {
                    @if task.status != TaskStatus::Review {
                        @if let Some(sum) = &task.summary {
                            @if task.status != TaskStatus::NeedsInput {
                                h2 { "Summary" }
                                p { (sum) }
                            }
                        }
                    }
                    h2 { "Timeline" }
                    div id="timeline" data-task-id=(task_id) data-after=(after) {
                        (render_timeline_events(events))
                    }
                    (render_composer(task))
                    (render_stop_row(task))
                    p class="small muted" {
                        "Live updates stream automatically when JavaScript is on; otherwise reload to refresh."
                    }
                }
                aside id="panel" {
                    (render_panel(task, github_repo))
                }
            }
        },
    )
}

async fn task_page(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    let Some(task) = state.store.get_task(id).unwrap_or(None) else {
        return (StatusCode::NOT_FOUND, "task not found").into_response();
    };
    let events = state.store.list_events(id, None).unwrap_or_default();
    let banner = release_banner(&state);
    Html(render_task_page(&task, &events, banner, &state.config.github_repo).into_string())
        .into_response()
}

// ---- timeline ----

/// Chat timeline. `todos` events render only in the side panel (skipped
/// here); question/plan/review events become short note lines (the live
/// pending/review cards above carry the full UI). A `tool_call` merges with
/// the directly following `tool_result` for the same tool into one collapsed
/// `<details>` chip — an orphan result (fragment boundary, rejected mixed
/// turn) renders on its own.
pub(crate) fn render_timeline_events(events: &[Event]) -> Markup {
    let mut items: Vec<Markup> = Vec::new();
    let mut skip_next_result = false;
    for (i, ev) in events.iter().enumerate() {
        if skip_next_result && ev.kind == EVENT_TOOL_RESULT {
            skip_next_result = false;
            continue;
        }
        skip_next_result = false;
        match ev.kind.as_str() {
            EVENT_USER_MESSAGE => items.push(render_user_msg(ev)),
            EVENT_ASSISTANT => {
                if let Some(m) = render_assistant_msg(ev) {
                    items.push(m);
                }
            }
            EVENT_TOOL_CALL => {
                let name = ev_str(&ev.body, "name");
                let args = ev_str(&ev.body, "args");
                // Merge only with the directly following result for the SAME
                // tool (dispatch emits call,result,… in order, so adjacency
                // plus the name check attributes results correctly).
                let merged = events.get(i + 1).filter(|n| {
                    n.kind == EVENT_TOOL_RESULT && ev_str(&n.body, "name") == name
                });
                match merged {
                    Some(res) => {
                        items.push(render_tool_pair(
                            name,
                            args,
                            Some((ev_str(&res.body, "result"), res_truncated(res))),
                            ev.seq,
                        ));
                        skip_next_result = true;
                    }
                    None => items.push(render_tool_pair(name, args, None, ev.seq)),
                }
            }
            EVENT_TOOL_RESULT => items.push(render_tool_pair(
                ev_str(&ev.body, "name"),
                "",
                Some((ev_str(&ev.body, "result"), res_truncated(ev))),
                ev.seq,
            )),
            EVENT_STATUS => {
                let msg = ev_str(&ev.body, "message");
                if !msg.trim().is_empty() {
                    items.push(html! {
                        div class="ev status-line" data-seq=(ev.seq) { "· " (cap_chars(msg.trim(), 500)) }
                    });
                }
            }
            EVENT_QUESTION => {
                let q = ev_str(&ev.body, "question");
                items.push(html! {
                    div class="ev note-line" data-seq=(ev.seq) {
                        "asked: " @if q.trim().is_empty() { "(question)" } @else { (cap_chars(q.trim(), 140)) }
                    }
                });
            }
            EVENT_PLAN => {
                items.push(html! {
                    div class="ev note-line" data-seq=(ev.seq) { "plan proposed — see the card above." }
                });
            }
            EVENT_REVIEW => {
                let s = ev_str(&ev.body, "summary");
                items.push(html! {
                    div class="ev note-line" data-seq=(ev.seq) {
                        "review ready" @if !s.trim().is_empty() { ": " (cap_chars(s.trim(), 140)) }
                    }
                });
            }
            EVENT_TODOS => {
                // Panel only (the checklist is live state, not history).
            }
            _ => {
                let raw = ev.body.to_string();
                items.push(html! {
                    div class="ev note-line" data-seq=(ev.seq) {
                        (&ev.kind) ": " (cap_chars(raw.trim(), 300))
                    }
                });
            }
        }
    }
    html! {
        @for m in items {
            (m)
        }
    }
}

/// User message: right-aligned bubble, plain escaped text (never markdown —
/// the operator's own words need no formatting, and pre-wrap keeps layout).
fn render_user_msg(ev: &Event) -> Markup {
    let text = cap_chars(ev_str(&ev.body, "text").trim(), 8000);
    html! {
        div class="ev msg-user" data-seq=(ev.seq) {
            div class="bubble user" { (text) }
        }
    }
}

/// Assistant text: left bubble through the safe markdown subset (raw HTML
/// escaped, `javascript:` links dropped). Empty text (tool-only turn) renders
/// nothing — the tool chips carry those turns.
fn render_assistant_msg(ev: &Event) -> Option<Markup> {
    let text = ev_str(&ev.body, "text");
    if text.trim().is_empty() {
        return None;
    }
    Some(html! {
        div class="ev msg-assistant" data-seq=(ev.seq) {
            div class="bubble assistant" { (PreEscaped(render_markdown(text))) }
        }
    })
}

fn res_truncated(ev: &Event) -> bool {
    ev.body
        .get("truncated")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
}

/// Collapsed tool chip: `▸ <name> <args…>`, escaped and capped, with the
/// (capped) result inside. `result=None` means the call is still running.
fn render_tool_pair(name: &str, args: &str, result: Option<(&str, bool)>, seq: i64) -> Markup {
    let name = if name.is_empty() { "tool" } else { name };
    let chip_args = cap_chars(args.trim(), 80);
    let chip = if chip_args.is_empty() {
        format!("▸ {name}")
    } else {
        format!("▸ {name} {chip_args}")
    };
    html! {
        div class="ev" data-seq=(seq) {
            details class="tool" {
                summary class="tool-chip" { (chip) }
                div class="tool-body" {
                    @if !args.trim().is_empty() {
                        p class="small muted" { "args: " code { (cap_chars(args.trim(), 400)) } }
                    }
                    @match result {
                        Some((text, truncated)) => {
                            (render_capped_result(text, truncated))
                        }
                        None => {
                            p class="small muted" { "(running…)" }
                        }
                    }
                }
            }
        }
    }
}

fn render_capped_result(text: &str, flagged: bool) -> Markup {
    let shown = cap_chars(text.trim(), 2000);
    let cut = flagged || shown.len() < text.trim().len();
    html! {
        pre { (shown) }
        @if cut {
            p class="small muted" { "…[result truncated]" }
        }
    }
}

// ---- cards (pending question / plan / review + parked explanations) ----

/// Pending + review cards (pure; also served as the `/cards` fragment).
/// Only one card shows at a time: review wins, then the active pending kind,
/// then a short explanation for the parked-but-textless kinds
/// (chat/budget/error/stopped — the composer below is their answer path).
pub(crate) fn render_cards(task: &Task) -> Markup {
    if task.status == TaskStatus::Review {
        return render_review_card(task);
    }
    let Some(pending) = task.pending.as_ref() else {
        return html! {};
    };
    if task.status.is_terminal() {
        return html! {};
    }
    match pending.kind.as_str() {
        PENDING_QUESTION => render_question_card(task.id, pending),
        PENDING_PLAN => render_plan_card(task.id, pending),
        PENDING_CHAT => html! {
            div class="card" {
                p class="small muted" { "Waiting for you (chat)" }
                p { "The agent replied and is waiting for you. Send a message below to continue." }
            }
        },
        PENDING_BUDGET => {
            let m = pending
                .payload
                .get("message")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            html! {
                div class="card" {
                    h3 { "Budget exhausted" }
                    p class="small muted" { "Waiting for you (budget)" }
                    @if !m.is_empty() {
                        p class="small" { (m) }
                    }
                    p { "Reply below to add 20 steps and $1.00 and resume — or Discard the task." }
                }
            }
        }
        PENDING_ERROR => {
            let m = pending
                .payload
                .get("message")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            html! {
                div class="card" {
                    h3 { "Parked on error" }
                    @if !m.is_empty() {
                        p class="small" { (m) }
                    }
                    p { "Reply below to retry — or Discard the task." }
                }
            }
        }
        PENDING_STOPPED => html! {
            div class="card" {
                h3 { "Stopped" }
                p { "Stopped by the operator; work is kept. Reply below to resume — or Discard the task." }
            }
        },
        _ => html! {
            div class="card" {
                p { "Waiting for you (" (pending.kind.clone()) "). Send a message below." }
            }
        },
    }
}

/// Question card: one form per option (posts the option text to
/// `/messages`), plus — for `multi_select` — a checkboxes form whose values
/// the server joins, plus a free-text box. Question text goes through the
/// safe markdown subset.
fn render_question_card(task_id: i64, pending: &crate::models::Pending) -> Markup {
    let action = format!("/tasks/{task_id}/messages");
    let q = pending
        .payload
        .get("question")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let options: Vec<String> = pending
        .payload
        .get("options")
        .and_then(|v| v.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|v| v.as_str().map(|s| s.to_string()))
                .collect()
        })
        .unwrap_or_default();
    let multi = pending
        .payload
        .get("multi_select")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    html! {
        div class="card" {
            h3 { "Question — waiting for your answer" }
            p class="small muted" { "Waiting for you (question)" }
            div class="q-text" { (PreEscaped(render_markdown(q))) }
            @if !options.is_empty() {
                div class="options" {
                    @for opt in &options {
                        form method="post" action=(action.clone()) class="inline" {
                            input type="hidden" name="text" value=(opt);
                            button type="submit" { (opt) }
                        }
                    }
                }
            }
            @if multi && !options.is_empty() {
                form method="post" action=(action.clone()) {
                    fieldset class="optlist" {
                        legend { "Or select all that apply" }
                        @for opt in &options {
                            label class="check" {
                                input type="checkbox" name="options" value=(opt);
                                (opt)
                            }
                        }
                    }
                    button type="submit" { "Send selected" }
                }
            }
            form method="post" action=(action) {
                label { "Or answer in your own words"
                    textarea name="text" rows="3" required maxlength="8000" {} }
                button type="submit" class="primary" { "Send answer" }
            }
        }
    }
}

/// Plan card: rendered markdown + Approve form + changes textarea (posts to
/// `/plan/changes`, which resumes the pending `propose_plan` call with the
/// feedback and keeps the plan gate shut).
fn render_plan_card(task_id: i64, pending: &crate::models::Pending) -> Markup {
    let plan = pending
        .payload
        .get("plan_markdown")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    html! {
        div class="card" {
            h3 { "Proposed plan — waiting for approval" }
            p class="small muted" { "Waiting for you (plan)" }
            div class="plan-md" { (PreEscaped(render_markdown(plan))) }
            div class="row" {
                form method="post" action=(format!("/tasks/{task_id}/plan/approve")) class="inline" {
                    button type="submit" class="primary" { "Approve plan" }
                }
            }
            form method="post" action=(format!("/tasks/{task_id}/plan/changes")) {
                label { "Request changes"
                    textarea name="text" rows="3" required maxlength="8000" {} }
                button type="submit" { "Request changes" }
            }
        }
    }
}

/// Review card (only in `review`): summary, commit message `<pre>`, capped
/// diff in an open `<details>` with +/- line classes, Approve & push /
/// Discard buttons, and a changes box (a normal message while in review →
/// the task resumes and the pending `finish` call gets "User reviewed the
/// change and requested: …").
fn render_review_card(task: &Task) -> Markup {
    let task_id = task.id;
    let diff = task.final_diff.as_deref().unwrap_or("(no diff stored)");
    html! {
        div class="card" {
            h3 { "Review — waiting for approval" }
            @if let Some(sum) = &task.summary {
                p { strong { "Summary: " } (sum) }
            }
            @if let Some(cm) = &task.commit_message {
                p { strong { "Commit message:" } pre class="log" { (cm) } }
            }
            details class="diff" open="open" {
                summary { "Diff (" (diff.len()) " bytes)" }
                (render_diff_lines(diff))
            }
            div class="row" {
                form method="post" action=(format!("/tasks/{task_id}/approve")) class="inline" {
                    button type="submit" class="primary" { "Approve & push" }
                }
                form method="post" action=(format!("/tasks/{task_id}/discard")) class="inline" {
                    button type="submit" { "Discard" }
                }
            }
            form method="post" action=(format!("/tasks/{task_id}/messages")) {
                label { "Request changes"
                    textarea name="text" rows="3" required maxlength="8000" {} }
                button type="submit" { "Request changes" }
            }
        }
    }
}

/// Diff viewer: each line escaped individually, `+`/`-` lines classed for
/// coloring, hunk headers dimmed. Capped (~2000 lines / 20k chars) so a big
/// diff can't blow up the page; the full diff lives in the branch.
pub(crate) fn render_diff_lines(diff: &str) -> Markup {
    const MAX_LINES: usize = 2000;
    const MAX_CHARS: usize = 20_000;
    let mut out = String::new();
    let mut chars = 0usize;
    let mut clamped = false;
    for (i, line) in diff.lines().enumerate() {
        if i >= MAX_LINES || chars + line.len() + 1 > MAX_CHARS {
            clamped = true;
            break;
        }
        let class = if line.starts_with('+') && !line.starts_with("+++") {
            "add"
        } else if line.starts_with('-') && !line.starts_with("---") {
            "del"
        } else if line.starts_with("@@") {
            "hunk"
        } else {
            ""
        };
        let e = esc_html(line);
        if class.is_empty() {
            out.push_str(&e);
        } else {
            out.push_str(&format!("<span class=\"{class}\">{e}</span>"));
        }
        out.push('\n');
        chars += line.len() + 1;
    }
    if clamped {
        out.push_str("… [diff view clamped; full diff in the branch]");
    }
    html! {
        pre class="difflines" { (PreEscaped(out)) }
    }
}

// ---- composer + stop/discard ----

/// Message box for non-terminal tasks (hidden while a question/plan/review
/// card owns answering) and for `succeeded` tasks (follow-up placeholder).
fn render_composer(task: &Task) -> Markup {
    let task_id = task.id;
    let pending_kind = task.pending.as_ref().map(|p| p.kind.as_str());
    let show = match task.status {
        TaskStatus::Succeeded => true,
        s if s.is_terminal() => false,
        TaskStatus::Review => false,
        _ => !matches!(pending_kind, Some(PENDING_QUESTION) | Some(PENDING_PLAN)),
    };
    if !show {
        return html! {};
    }
    let placeholder = if task.status == TaskStatus::Succeeded {
        "Follow-up (re-queues the task from the pushed tip)"
    } else {
        "Message the agent (steering, or the answer when the task waits)"
    };
    html! {
        h2 { "Message the agent" }
        form method="post" action=(format!("/tasks/{task_id}/messages")) class="card" {
            label { (placeholder)
                textarea name="text" rows="3" required maxlength="8000" {} }
            button type="submit" class="primary" { "Send" }
        }
    }
}

/// Stop while running/queued; Discard for any non-terminal task.
fn render_stop_row(task: &Task) -> Markup {
    if task.status.is_terminal() {
        let task_id = task.id;
        return html! {
            div class="row" {
                form method="post" action=(format!("/tasks/{task_id}/retry")) class="inline" {
                    button type="submit" { "Retry as new task" }
                }
            }
        };
    }
    let task_id = task.id;
    let running = matches!(task.status, TaskStatus::Running | TaskStatus::Queued);
    html! {
        div class="row" {
            @if running {
                form method="post" action=(format!("/tasks/{task_id}/stop")) class="inline" {
                    button type="submit" { "Stop" }
                }
            }
            form method="post" action=(format!("/tasks/{task_id}/discard")) class="inline" {
                button type="submit" { "Discard" }
            }
            @if task.pushed {
                p class="small muted" {
                    "Note: this task was pushed before — discarding removes the local work, but the remote branch "
                    code { (&task.branch) } " remains on git.maproj.org; the agent token can't delete it."
                }
            }
        }
    }
}

// ---- side panel ----

/// Side panel: status, todo checklist (todos events render ONLY here),
/// model, scope, branch + copyable `git fetch` line, issue link, plan-first
/// flag, steps/cost vs budget, tokens.
pub(crate) fn render_panel(task: &Task, github_repo: &str) -> Markup {
    let todos: Vec<crate::models::TodoItem> = task
        .todos_json
        .as_deref()
        .and_then(|raw| serde_json::from_str(raw).ok())
        .unwrap_or_default();
    let fetch_cmd = format!("git fetch origin {}", task.branch);
    html! {
        @if task.kind == crate::models::TASK_KIND_TRIAGE {
            (crate::triage_ui::task_panel_block(task, github_repo))
        }
        h3 { "Status" }
        div class="p-row" {
            span class="k" { "state" }
            span class="v" {
                (status_pill(task.status))
                @if let Some(p) = &task.pending {
                    " " span class="small muted" { "waiting: " (p.kind.clone()) }
                }
            }
        }
        @if task.pushed {
            div class="p-row" {
                span class="k" { "pushed" }
                span class="v" { code { (&task.branch) } " on the remote" }
            }
        }
        h3 { "Todos" }
        @if todos.is_empty() {
            p class="small muted" { "No todos yet." }
        } @else {
            ul class="todos" {
                @for t in &todos {
                    @match t.status.as_str() {
                        "done" => { li class="done" { "☑ " (&t.text) } }
                        "in_progress" => { li class="doing" { "▶ " (&t.text) } }
                        _ => { li { "☐ " (&t.text) } }
                    }
                }
            }
        }
        h3 { "Run" }
        div class="p-row" { span class="k" { "model" } span class="v small" { (&task.model) } }
        div class="p-row" { span class="k" { "scope" } span class="v" { code { (&task.module_scope) } } }
        div class="p-row" { span class="k" { "branch" } span class="v" { code { (&task.branch) } } }
        div class="p-row" {
            span class="k" { "fetch" }
            span class="v fetchline" {
                input readonly value=(fetch_cmd) aria-label="copyable git fetch command";
            }
        }
        @if let Some(n) = &task.issue_number {
            div class="p-row" {
                span class="k" { "issue" }
                span class="v" {
                    a href=(format!("https://github.com/{github_repo}/issues/{n}")) { "#" (n) }
                }
            }
        }
        @if let Some(sha) = &task.commit_sha {
            div class="p-row" {
                span class="k" { "commit" }
                span class="v small" {
                    code { (sha) } " " (if task.pushed { "(pushed)" } else { "(not pushed)" })
                }
            }
        }
        @if let Some(wip) = &task.wip_sha {
            div class="p-row" {
                span class="k" { "wip" }
                span class="v small" { code { (wip) } }
            }
        }
        div class="p-row" {
            span class="k" { "plan" }
            span class="v small" {
                @if task.require_plan {
                    "plan-first" @if task.plan_approved { " (approved)" } @else { " (awaiting approval)" }
                } @else {
                    "plan-first off"
                }
            }
        }
        h3 { "Budget" }
        div class="p-row" {
            span class="k" { "steps" }
            span class="v" { (task.steps) "/" (task.budget_steps) }
        }
        div class="p-row" {
            span class="k" { "cost" }
            span class="v" { "$" (format!("{:.4}", task.cost_usd)) "/$" (format!("{:.2}", task.budget_usd)) }
        }
        div class="p-row" {
            span class="k" { "tokens" }
            span class="v" { (task.prompt_tokens) "+" (task.completion_tokens) }
        }
    }
}

// ---- fragments (auth-protected, fetched by app.js) ----

#[derive(Deserialize)]
struct EventsQuery {
    after: Option<i64>,
}

async fn task_timeline_fragment(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<EventsQuery>,
) -> Response {
    if let Some(reject) = require_auth_fragment(&state, &headers) {
        return reject;
    }
    if state.store.get_task(id).unwrap_or(None).is_none() {
        return (StatusCode::NOT_FOUND, "task not found").into_response();
    }
    let events = state.store.list_events(id, q.after).unwrap_or_default();
    Html(render_timeline_events(&events).into_string()).into_response()
}

async fn task_panel_fragment(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if let Some(reject) = require_auth_fragment(&state, &headers) {
        return reject;
    }
    let Some(task) = state.store.get_task(id).unwrap_or(None) else {
        return (StatusCode::NOT_FOUND, "task not found").into_response();
    };
    Html(render_panel(&task, &state.config.github_repo).into_string()).into_response()
}

async fn task_cards_fragment(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if let Some(reject) = require_auth_fragment(&state, &headers) {
        return reject;
    }
    let Some(task) = state.store.get_task(id).unwrap_or(None) else {
        return (StatusCode::NOT_FOUND, "task not found").into_response();
    };
    Html(render_cards(&task).into_string()).into_response()
}

// ---- SSE ----

async fn task_events_sse(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<EventsQuery>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    if state.store.get_task(id).unwrap_or(None).is_none() {
        return (StatusCode::NOT_FOUND, "task not found").into_response();
    }
    // Replay backlog, then stream new events.
    let backlog = state.store.list_events(id, q.after).unwrap_or_default();
    let rx = state.events_tx.subscribe();
    let stream = BroadcastStream::new(rx).filter_map(move |res| {
        let (tid, ev) = res.ok()?;
        if tid == id {
            Some(Ok::<_, axum::Error>(SseEvent::default().data(
                serde_json::to_string(&ev).unwrap_or_else(|_| "{}".to_string()),
            )))
        } else {
            None
        }
    });
    let backlog_stream = tokio_stream::iter(
        backlog
            .into_iter()
            .map(|ev| {
                Ok::<_, axum::Error>(SseEvent::default().data(
                    serde_json::to_string(&ev).unwrap_or_else(|_| "{}".to_string()),
                ))
            })
            .collect::<Vec<_>>(),
    );
    Sse::new(backlog_stream.chain(stream))
        .keep_alive(KeepAlive::default())
        .into_response()
}

/// `POST /tasks/:id/messages` — inbox a user message (an answer while
/// parked, or steering while running). Re-queues parked / review / succeeded
/// tasks in the same transaction.
///
/// While in `review`, a normal message is request-changes: the row is tagged
/// so the resume formats the pending `finish` result as "User reviewed the
/// change and requested: …".
async fn messages_route(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Form(pairs): Form<Vec<(String, String)>>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    if let Some(reject) = require_origin(&state, &headers) {
        return reject;
    }
    // Fold the raw pairs by hand: checkboxes post repeated `options` keys
    // (which struct deserialization rejects as duplicates), and the
    // checkboxes-only form omits `text` entirely.
    let mut text = String::new();
    let mut options: Vec<String> = Vec::new();
    for (k, v) in pairs {
        match k.as_str() {
            "text" => text = v,
            "options" => options.push(v),
            _ => {}
        }
    }
    let text = if text.trim().is_empty() {
        options.join(", ").trim().to_string()
    } else {
        text.trim().to_string()
    };
    if text.is_empty() {
        return (StatusCode::BAD_REQUEST, "message text is required").into_response();
    }
    if state.store.get_task(id).unwrap_or(None).is_none() {
        return (StatusCode::NOT_FOUND, "task not found").into_response();
    }
    // Tag request-changes while in review so the resume formats the pending
    // `finish` tool result ("User reviewed the change and requested: …").
    let kind = match state.store.get_task(id).unwrap_or(None) {
        Some(t) if t.status == TaskStatus::Review => INBOX_REVIEW_CHANGES,
        _ => "",
    };
    match state.store.inbox_post_kind(id, &text, kind) {
        Ok(requeued) => {
            state.driver().emit(id, EVENT_STATUS,
                serde_json::json!({"message": format!("user message received{}", if requeued { " (task re-queued)" } else { "" })}),
            );
            (
                StatusCode::FOUND,
                [(header::LOCATION, format!("/tasks/{id}"))],
                "message received",
            )
                .into_response()
        }
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, e).into_response(),
    }
}

async fn stop_route(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    if let Some(reject) = require_origin(&state, &headers) {
        return reject;
    }
    match crate::scheduler::stop_task(&state.driver(), id).await {
        Ok(_) => (
            StatusCode::FOUND,
            [(header::LOCATION, format!("/tasks/{id}"))],
            "stop requested",
        )
            .into_response(),
        Err(e) => (StatusCode::NOT_FOUND, e).into_response(),
    }
}

async fn discard_route(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    if let Some(reject) = require_origin(&state, &headers) {
        return reject;
    }
    match crate::scheduler::discard_task(&state.driver(), id).await {
        Ok(_) => (
            StatusCode::FOUND,
            [(header::LOCATION, format!("/tasks/{id}"))],
            "discarded",
        )
            .into_response(),
        Err(e) => (StatusCode::NOT_FOUND, e).into_response(),
    }
}

async fn retry_route(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    if let Some(reject) = require_origin(&state, &headers) {
        return reject;
    }
    let Some(old) = state.store.get_task(id).unwrap_or(None) else {
        return (StatusCode::NOT_FOUND, "task not found").into_response();
    };
    if !old.status.is_terminal() {
        return (StatusCode::BAD_REQUEST, "task is still running").into_response();
    }
    let new_id = match state.store.create_task(
        &old.title,
        &old.prompt,
        &old.module_scope,
        &old.model,
        old.budget_steps,
        old.budget_usd,
    ) {
        Ok(v) => v,
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, e).into_response(),
    };
    let branch = branch_name(new_id, &old.title);
    let _ = state.store.set_branch(new_id, &branch);
    state.driver().emit(new_id, EVENT_STATUS,
        serde_json::json!({"message": format!("retried from task #{id}")}),
    );
    (
        StatusCode::FOUND,
        [(header::LOCATION, format!("/tasks/{new_id}"))],
        "queued",
    )
        .into_response()
}

/// `POST /tasks/:id/plan/approve` — approve the parked plan (only in
/// `needs_input(plan)`). Sets `plan_approved=1` and posts "approved" via the
/// tagged inbox/requeue path, so races behave like other messages and the
/// pending `propose_plan` call resumes with "Plan approved. Proceed.".
async fn plan_approve_route(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    if let Some(reject) = require_origin(&state, &headers) {
        return reject;
    }
    match crate::scheduler::approve_task(&state.driver(), id, "plan").await {
        Ok(_) => (
            StatusCode::FOUND,
            [(header::LOCATION, format!("/tasks/{id}"))],
            "plan approved",
        )
            .into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, e).into_response(),
    }
}

#[derive(Deserialize)]
struct PlanChangesForm {
    text: String,
}

/// `POST /tasks/:id/plan/changes` — request plan changes (only in
/// `needs_input(plan)`). The text goes through the tagged inbox/requeue path;
/// the pending `propose_plan` call resumes with "User requested changes to
/// the plan: …" and `plan_approved` stays 0.
async fn plan_changes_route(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Form(form): Form<PlanChangesForm>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    if let Some(reject) = require_origin(&state, &headers) {
        return reject;
    }
    let text = form.text.trim().to_string();
    if text.is_empty() {
        return (StatusCode::BAD_REQUEST, "changes text is required").into_response();
    }
    let Some(task) = state.store.get_task(id).unwrap_or(None) else {
        return (StatusCode::NOT_FOUND, "task not found").into_response();
    };
    if task.status != TaskStatus::NeedsInput
        || task.pending.as_ref().map(|p| p.kind.as_str()) != Some(crate::models::PENDING_PLAN)
    {
        return (StatusCode::BAD_REQUEST, "no plan awaiting review").into_response();
    }
    match state.store.inbox_post_kind(id, &text, INBOX_PLAN_CHANGES) {
        Ok(_) => (
            StatusCode::FOUND,
            [(header::LOCATION, format!("/tasks/{id}"))],
            "changes requested",
        )
            .into_response(),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, e).into_response(),
    }
}

/// `POST /tasks/:id/approve` — approve the review (only in `review`): push
/// `refs/agent-wip/<id>` → `refs/heads/<branch>`, mark `succeeded`. On push
/// failure the task stays in `review` and the error is shown.
async fn review_approve_route(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    if let Some(reject) = require_origin(&state, &headers) {
        return reject;
    }
    match crate::scheduler::approve_task(&state.driver(), id, "review").await {
        Ok(_) => (
            StatusCode::FOUND,
            [(header::LOCATION, format!("/tasks/{id}"))],
            "approved and pushed",
        )
            .into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, e).into_response(),
    }
}
