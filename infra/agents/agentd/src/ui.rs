//! Server-rendered HTML UI (maud; auto-escaped) plus SSE live events.
//!
//! Routes:
//! * `GET /healthz` — unauthenticated, body `ok`.
//! * `GET /login`, `POST /login`, `POST /logout`.
//! * `GET /` — task list.
//! * `GET /tasks/new`, `POST /tasks` — new-task form + creation.
//! * `GET /tasks/:id` — task page (live log via SSE, totals, cancel/retry).
//! * `GET /tasks/:id/events` — SSE stream of the task's events.
//! * `POST /tasks/:id/cancel`, `POST /tasks/:id/retry`.
//!
//! All state-changing routes are POST and additionally require an `Origin`
//! header exactly equal to `AGENTD_PUBLIC_ORIGIN`. Auth is a session cookie
//! (HttpOnly; Secure; SameSite=Strict). Security headers (CSP self-only,
//! nosniff, no-referrer, frame-ancestors none) are attached to every
//! response.

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
use crate::models::{branch_name, Event, TaskStatus};
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
        .route("/tasks/:id/cancel", post(cancel_route))
        .route("/tasks/:id/retry", post(retry_route))
        .with_state(state)
        .layer(axum::middleware::from_fn(security_headers))
}

async fn security_headers(
    req: axum::extract::Request,
    next: axum::middleware::Next,
) -> Response {
    let mut resp = next.run(req).await;
    let h = resp.headers_mut();
    h.insert(
        header::CONTENT_SECURITY_POLICY,
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
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
fn require_auth(state: &AppState, headers: &HeaderMap) -> Option<Response> {
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

fn require_origin(state: &AppState, headers: &HeaderMap) -> Option<Response> {
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

// ---- pages ----

fn layout(title: &str, release: bool, body: Markup) -> Markup {
    html! {
        (maud::DOCTYPE)
        html lang="en" {
            head {
                meta charset="utf-8";
                meta name="viewport" content="width=device-width, initial-scale=1";
                title { (title) " — agentd" }
                style { (PreEscaped(CSS)) }
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

const CSS: &str = r#"
:root { color-scheme: light dark; }
body { font-family: system-ui, sans-serif; max-width: 72rem; margin: 0 auto; padding: 0 1rem 4rem; }
.top { display: flex; gap: .75rem; align-items: center; padding: .75rem 0; border-bottom: 1px solid #8884; }
.top .sep { opacity: .5; }
.top .inline { display: inline; margin-left: auto; }
button.link { background: none; border: none; cursor: pointer; color: inherit; text-decoration: underline; font: inherit; }
.banner { background: #7a4d00; color: #fff; padding: .5rem .75rem; border-radius: .4rem; margin: .75rem 0; }
table.tasks { width: 100%; border-collapse: collapse; }
table.tasks th, table.tasks td { text-align: left; padding: .35rem .5rem; border-bottom: 1px solid #8883; }
.pill { display: inline-block; padding: .1rem .5rem; border-radius: 1rem; font-size: .8rem; border: 1px solid #8886; }
.pill.queued { background: #5555; } .pill.running { background: #14532d55; }
.pill.succeeded { background: #16653455; } .pill.failed { background: #7f1d1d55; }
.pill.cancelled { background: #4445; }
pre.log { white-space: pre-wrap; word-break: break-word; background: #0002; border: 1px solid #8883; border-radius: .4rem; padding: .5rem; max-height: 24rem; overflow: auto; }
form.card { display: grid; gap: .6rem; max-width: 44rem; }
label { display: grid; gap: .25rem; font-weight: 600; }
input, textarea, select { font: inherit; padding: .4rem .5rem; border: 1px solid #8886; border-radius: .35rem; }
.row { display: flex; gap: .5rem; align-items: center; flex-wrap: wrap; }
.muted { opacity: .7; } .small { font-size: .85rem; }
.ev { border-bottom: 1px solid #8882; padding: .35rem 0; }
.ev .kind { font-size: .75rem; text-transform: uppercase; letter-spacing: .04em; opacity: .7; }
"#;

fn release_banner(state: &AppState) -> bool {
    crate::agent::release_active(&state.config)
}

async fn login_page() -> Html<String> {
    Html(
        layout(
            "login",
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
                    "login",
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

async fn task_list(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Some(reject) = require_auth(&state, &headers) {
        return reject;
    }
    let tasks = state.store.list_tasks(100).unwrap_or_default();
    let banner = release_banner(&state);
    Html(
        layout(
            "tasks",
            banner,
            html! {
                h1 { "Tasks" }
                p { a href="/tasks/new" { "+ New task" } }
                table class="tasks" {
                    thead { tr { th { "ID" } th { "Title" } th { "Status" } th { "Model" } th { "Cost" } th { "Branch" } } }
                    tbody {
                        @for t in &tasks {
                            tr {
                                td { a href=(format!("/tasks/{}", t.id)) { "#" (t.id) } }
                                td { (&t.title) }
                                td { (status_pill(t.status)) }
                                td class="small muted" { (&t.model) }
                                td { (format!("${:.4}", t.cost_usd)) }
                                td class="small muted" { (&t.branch) }
                            }
                        }
                        @if tasks.is_empty() {
                            tr { td colspan="6" class="muted" { "No tasks yet." } }
                        }
                    }
                }
            },
        )
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
            "new task",
            banner,
            html! {
                h1 { "New task" }
                form method="post" action="/tasks" class="card" {
                    label { "Title" input type="text" name="title" required maxlength="120"; }
                    label { "Module scope (e.g. notes, games/logicgate)"
                        input type="text" name="module_scope" value="notes" required maxlength="80"; }
                    label { "Model"
                        input type="text" name="model" value=(default_model) maxlength="120"; }
                    label { "Prompt" textarea name="prompt" rows="12" required {} }
                    div class="row" {
                        label { "Budget steps" input type="number" name="budget_steps" value="60" min="1" max="500"; }
                        label { "Budget USD" input type="number" name="budget_usd" value="2.00" min="0.1" max="50" step="0.5"; }
                    }
                    button type="submit" { "Queue task" }
                }
            },
        )
        .into_string(),
    )
    .into_response()
}

#[derive(Deserialize)]
struct NewTaskForm {
    title: String,
    prompt: String,
    module_scope: String,
    model: String,
    budget_steps: i64,
    budget_usd: f64,
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
    if title.is_empty() || prompt.is_empty() {
        return (StatusCode::BAD_REQUEST, "title and prompt are required").into_response();
    }
    let scope: String = form
        .module_scope
        .trim()
        .chars()
        .filter(|c| {
            c.is_ascii_alphanumeric() || *c == '/' || *c == ':' || *c == '-' || *c == '_'
        })
        .collect();
    if scope.is_empty() {
        return (StatusCode::BAD_REQUEST, "module scope is required").into_response();
    }
    let model = if form.model.trim().is_empty() {
        state.config.default_model.clone()
    } else {
        form.model.trim().to_string()
    };
    let steps = form.budget_steps.clamp(1, 500);
    let usd = form.budget_usd.clamp(0.1, 50.0);
    let id = match state
        .store
        .create_task(&title, &prompt, &scope, &model, steps, usd)
    {
        Ok(id) => id,
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, e).into_response(),
    };
    let branch = branch_name(id, &title);
    let _ = state.store.set_branch(id, &branch);
    state
        .driver()
        .emit(id, "status", serde_json::json!({"message": "task queued"}));
    (
        StatusCode::FOUND,
        [(header::LOCATION, format!("/tasks/{id}"))],
        "queued",
    )
        .into_response()
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
    let task_id = task.id;
    let body = html! {
        h1 { "#" (task.id) " " (&task.title) }
        div class="row" {
            (status_pill(task.status))
            span class="small muted" { (&task.model) }
            span class="small muted" { "branch " code { (&task.branch) } }
            @if let Some(sha) = &task.commit_sha {
                span class="small muted" {
                    "commit " code { (sha) } " " (if task.pushed { "(pushed)" } else { "(not pushed)" })
                }
            }
        }
        p class="small" {
            "Scope " code { (&task.module_scope) }
            " · steps " (task.steps) "/" (task.budget_steps)
            " · cost $" (format!("{:.4}", task.cost_usd)) "/$" (format!("{:.2}", task.budget_usd))
            " · tokens " (task.prompt_tokens) "+" (task.completion_tokens)
        }
        @if let Some(err) = &task.error {
            p class="pill failed" { "Error: " (err) }
        }
        @if let Some(sum) = &task.summary {
            h2 { "Summary" }
            p { (sum) }
        }
        div class="row" {
            @if !task.status.is_terminal() {
                form method="post" action=(format!("/tasks/{task_id}/cancel")) class="inline" {
                    button type="submit" { "Cancel" }
                }
            } @else {
                form method="post" action=(format!("/tasks/{task_id}/retry")) class="inline" {
                    button type="submit" { "Retry as new task" }
                }
            }
        }
        h2 { "Live log" }
        div id="log" {
            @for ev in &events {
                (render_event(ev))
            }
        }
        h2 { "Final diff" }
        div id="diff" class="muted small" {
            "The final diff is shown in the event log (git_diff tool result / committed sha)."
        }
        script { (PreEscaped(format!(r#"
const log = document.getElementById('log');
const src = new EventSource('/tasks/{task_id}/events');
src.onmessage = (m) => {{
  try {{
    const ev = JSON.parse(m.data);
    const div = document.createElement('div');
    div.className = 'ev';
    const kind = document.createElement('div');
    kind.className = 'kind';
    kind.textContent = ev.kind + ' #' + ev.seq;
    div.appendChild(kind);
    const pre = document.createElement('pre');
    pre.className = 'log';
    pre.textContent = JSON.stringify(ev.body, null, 1).slice(0, 8000);
    div.appendChild(pre);
    log.appendChild(div);
    window.scrollTo(0, document.body.scrollHeight);
  }} catch (e) {{}}
}};
"#))) }
    };
    Html(
        layout(&format!("task #{task_id}"), banner, body).into_string(),
    )
    .into_response()
}

fn render_event(ev: &Event) -> Markup {
    let preview = match ev.kind.as_str() {
        "assistant" => ev
            .body
            .get("text")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .chars()
            .take(2000)
            .collect::<String>(),
        "tool_call" => format!(
            "{} {}",
            ev.body.get("name").and_then(|v| v.as_str()).unwrap_or("?"),
            ev.body.get("args").and_then(|v| v.as_str()).unwrap_or("")
        ),
        "tool_result" => ev
            .body
            .get("result")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .chars()
            .take(2000)
            .collect::<String>(),
        "status" => ev
            .body
            .get("message")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        _ => ev.body.to_string().chars().take(1000).collect(),
    };
    html! {
        div class="ev" {
            div class="kind" { (&ev.kind) " #" (ev.seq) }
            pre class="log" { (preview) }
        }
    }
}

#[derive(Deserialize)]
struct EventsQuery {
    after: Option<i64>,
}

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

async fn cancel_route(
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
    match crate::scheduler::cancel_task(&state.driver(), id).await {
        Ok(_) => (
            StatusCode::FOUND,
            [(header::LOCATION, format!("/tasks/{id}"))],
            "cancelled",
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
    state.driver().emit(
        new_id,
        "status",
        serde_json::json!({"message": format!("retried from task #{id}")}),
    );
    (
        StatusCode::FOUND,
        [(header::LOCATION, format!("/tasks/{new_id}"))],
        "queued",
    )
        .into_response()
}
