//! SQLite store: tasks, events, sessions. Migrations run on startup.
//!
//! `Store` is a thin synchronous wrapper around a `rusqlite::Connection`
//! guarded by a `Mutex`; the HTTP/agent code calls `Store::with` from
//! `spawn_blocking` so the async runtime never blocks. Schema:
//!
//! * `tasks(...)` — one row per agent task (see [`crate::models::Task`]).
//! * `events(task_id, seq, ...)` — append-only log; `seq` is per-task.
//! * `sessions(token_sha256_hex, expires_at)` — login sessions.

use std::path::Path;
use std::sync::{Arc, Mutex};

use rusqlite::{params, Connection, OptionalExtension};

use crate::models::{now_unix, Event, Task, TaskStatus};

#[derive(Clone)]
pub struct Store {
    inner: Arc<Mutex<Connection>>,
}

impl Store {
    pub fn open(dir: &Path) -> Result<Store, String> {
        std::fs::create_dir_all(dir).map_err(|e| format!("create state dir: {e}"))?;
        let db = dir.join("agentd.db");
        let conn =
            Connection::open(&db).map_err(|e| format!("open sqlite {}: {e}", db.display()))?;
        let store = Store {
            inner: Arc::new(Mutex::new(conn)),
        };
        store.migrate()?;
        Ok(store)
    }

    fn with<T>(&self, f: impl FnOnce(&Connection) -> rusqlite::Result<T>) -> Result<T, String> {
        let conn = self.inner.lock().map_err(|e| format!("db lock: {e}"))?;
        f(&conn).map_err(|e| format!("sqlite: {e}"))
    }

    fn migrate(&self) -> Result<(), String> {
        self.with(|c| {
            c.execute_batch(
                "PRAGMA journal_mode=WAL;
                 CREATE TABLE IF NOT EXISTS tasks (
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   title TEXT NOT NULL,
                   prompt TEXT NOT NULL,
                   module_scope TEXT NOT NULL DEFAULT '',
                   model TEXT NOT NULL,
                   status TEXT NOT NULL DEFAULT 'queued',
                   slot INTEGER,
                   branch TEXT NOT NULL DEFAULT '',
                   base_sha TEXT,
                   commit_sha TEXT,
                   pushed INTEGER NOT NULL DEFAULT 0,
                   created_at INTEGER NOT NULL,
                   updated_at INTEGER NOT NULL,
                   started_at INTEGER,
                   finished_at INTEGER,
                   steps INTEGER NOT NULL DEFAULT 0,
                   prompt_tokens INTEGER NOT NULL DEFAULT 0,
                   completion_tokens INTEGER NOT NULL DEFAULT 0,
                   cost_usd REAL NOT NULL DEFAULT 0,
                   budget_steps INTEGER NOT NULL DEFAULT 60,
                   budget_usd REAL NOT NULL DEFAULT 2.0,
                   error TEXT,
                   summary TEXT
                 );
                 CREATE TABLE IF NOT EXISTS events (
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   task_id INTEGER NOT NULL REFERENCES tasks(id),
                   seq INTEGER NOT NULL,
                   ts INTEGER NOT NULL,
                   kind TEXT NOT NULL,
                   body TEXT NOT NULL,
                   UNIQUE(task_id, seq)
                 );
                 CREATE INDEX IF NOT EXISTS idx_events_task ON events(task_id, seq);
                 CREATE TABLE IF NOT EXISTS sessions (
                   token_sha256_hex TEXT PRIMARY KEY,
                   expires_at INTEGER NOT NULL
                 );",
            )?;
            // Forward-compatible columns for DBs created by older migrates.
            for (table, col, ddl) in [
                ("tasks", "module_scope", "ALTER TABLE tasks ADD COLUMN module_scope TEXT NOT NULL DEFAULT ''"),
                ("tasks", "slot", "ALTER TABLE tasks ADD COLUMN slot INTEGER"),
                ("tasks", "base_sha", "ALTER TABLE tasks ADD COLUMN base_sha TEXT"),
                ("tasks", "commit_sha", "ALTER TABLE tasks ADD COLUMN commit_sha TEXT"),
            ] {
                let exists: i64 = c.query_row(
                    "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?",
                    params![table, col],
                    |r| r.get(0),
                )?;
                if exists == 0 {
                    c.execute_batch(ddl)?;
                }
            }
            Ok(())
        })
    }

    // ---- tasks ----

    pub fn create_task(
        &self,
        title: &str,
        prompt: &str,
        module_scope: &str,
        model: &str,
        budget_steps: i64,
        budget_usd: f64,
    ) -> Result<i64, String> {
        let now = now_unix();
        self.with(|c| {
            c.execute(
                "INSERT INTO tasks (title, prompt, module_scope, model, status, created_at, updated_at, budget_steps, budget_usd)
                 VALUES (?, ?, ?, ?, 'queued', ?, ?, ?, ?)",
                params![title, prompt, module_scope, model, now, now, budget_steps, budget_usd],
            )?;
            Ok(c.last_insert_rowid())
        })
    }

    pub fn set_branch(&self, id: i64, branch: &str) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET branch = ?, updated_at = ? WHERE id = ?",
                params![branch, now_unix(), id],
            )?;
            Ok(())
        })
    }

    fn row_to_task(r: &rusqlite::Row<'_>) -> rusqlite::Result<Task> {
        let status_s: String = r.get("status")?;
        Ok(Task {
            id: r.get("id")?,
            title: r.get("title")?,
            prompt: r.get("prompt")?,
            module_scope: r.get("module_scope")?,
            model: r.get("model")?,
            status: TaskStatus::parse(&status_s).unwrap_or(TaskStatus::Queued),
            slot: r.get::<_, Option<i64>>("slot")?.map(|v| v as u32),
            branch: r.get("branch")?,
            base_sha: r.get("base_sha")?,
            commit_sha: r.get("commit_sha")?,
            pushed: r.get::<_, i64>("pushed")? != 0,
            created_at: r.get("created_at")?,
            updated_at: r.get("updated_at")?,
            started_at: r.get("started_at")?,
            finished_at: r.get("finished_at")?,
            steps: r.get("steps")?,
            prompt_tokens: r.get("prompt_tokens")?,
            completion_tokens: r.get("completion_tokens")?,
            cost_usd: r.get("cost_usd")?,
            budget_steps: r.get("budget_steps")?,
            budget_usd: r.get("budget_usd")?,
            error: r.get("error")?,
            summary: r.get("summary")?,
        })
    }

    pub fn get_task(&self, id: i64) -> Result<Option<Task>, String> {
        self.with(|c| {
            c.query_row("SELECT * FROM tasks WHERE id = ?", params![id], Self::row_to_task)
                .optional()
        })
    }

    pub fn list_tasks(&self, limit: i64) -> Result<Vec<Task>, String> {
        self.with(|c| {
            let mut st = c.prepare("SELECT * FROM tasks ORDER BY id DESC LIMIT ?")?;
            let rows = st.query_map(params![limit], Self::row_to_task)?;
            rows.collect::<rusqlite::Result<Vec<_>>>()
        })
    }

    /// Oldest queued task (for the scheduler; `claim_queued` is the atomic
    /// variant used by workers).
    /// (`#[allow]` — kept as the non-atomic read for tests/debugging.)
    #[allow(dead_code)]
    pub fn next_queued(&self) -> Result<Option<Task>, String> {
        self.with(|c| {
            c.query_row(
                "SELECT * FROM tasks WHERE status = 'queued' ORDER BY id ASC LIMIT 1",
                [],
                Self::row_to_task,
            )
            .optional()
        })
    }

    /// Atomically claim the oldest queued task for `slot`: returns the task
    /// if the claim won, `None` if another worker claimed it first.
    pub fn claim_queued(&self, slot: u32) -> Result<Option<Task>, String> {
        let conn = self.inner.lock().map_err(|e| format!("db lock: {e}"))?;
        let now = now_unix();
        let claimed: Option<Task> = (|| -> rusqlite::Result<Option<Task>> {
            let tx_begin: Option<Task> = conn
                .query_row(
                    "SELECT * FROM tasks WHERE status = 'queued' ORDER BY id ASC LIMIT 1",
                    [],
                    Self::row_to_task,
                )
                .optional()?;
            let Some(task) = tx_begin else { return Ok(None) };
            let n = conn.execute(
                "UPDATE tasks SET status = 'running', slot = ?, started_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'queued'",
                params![slot as i64, now, now, task.id],
            )?;
            if n == 0 {
                return Ok(None);
            }
            Ok(Some(conn.query_row(
                "SELECT * FROM tasks WHERE id = ?",
                params![task.id],
                Self::row_to_task,
            )?))
        })()
        .map_err(|e| format!("sqlite: {e}"))?;
        Ok(claimed)
    }

    pub fn update_status(
        &self,
        id: i64,
        status: TaskStatus,
        error: Option<&str>,
        summary: Option<&str>,
    ) -> Result<(), String> {
        let finished = if status.is_terminal() {
            Some(now_unix())
        } else {
            None
        };
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET status = ?, error = COALESCE(?, error), summary = COALESCE(?, summary),
                 updated_at = ?, finished_at = COALESCE(?, finished_at) WHERE id = ?",
                params![status.as_str(), error, summary, now_unix(), finished, id],
            )?;
            Ok(())
        })
    }

    /// Startup recovery: every `running` task is a crash/restart orphan.
    /// Documented policy: mark them `failed` with "interrupted by restart".
    /// Returns the number of rows touched.
    pub fn mark_interrupted_running_failed(&self) -> Result<usize, String> {
        self.with(|c| {
            let n = c.execute(
                "UPDATE tasks SET status = 'failed', error = 'interrupted by restart',
                 finished_at = COALESCE(finished_at, ?), updated_at = ?
                 WHERE status = 'running'",
                params![now_unix(), now_unix()],
            )?;
            Ok(n)
        })
    }

    pub fn set_base_sha(&self, id: i64, sha: &str) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET base_sha = ?, updated_at = ? WHERE id = ?",
                params![sha, now_unix(), id],
            )?;
            Ok(())
        })
    }

    pub fn set_finished_commit(
        &self,
        id: i64,
        commit_sha: &str,
        pushed: bool,
        summary: &str,
    ) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET commit_sha = ?, pushed = ?, summary = ?, updated_at = ? WHERE id = ?",
                params![commit_sha, pushed as i64, summary, now_unix(), id],
            )?;
            Ok(())
        })
    }

    pub fn add_usage(
        &self,
        id: i64,
        steps: i64,
        prompt_tokens: i64,
        completion_tokens: i64,
        cost_usd: f64,
    ) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET steps = steps + ?, prompt_tokens = prompt_tokens + ?,
                 completion_tokens = completion_tokens + ?, cost_usd = cost_usd + ?, updated_at = ?
                 WHERE id = ?",
                params![steps, prompt_tokens, completion_tokens, cost_usd, now_unix(), id],
            )?;
            Ok(())
        })
    }

    // ---- events ----

    /// Append an event; assigns the next per-task `seq`.
    pub fn push_event(
        &self,
        task_id: i64,
        kind: &str,
        body: &serde_json::Value,
    ) -> Result<Event, String> {
        let conn = self.inner.lock().map_err(|e| format!("db lock: {e}"))?;
        (|| -> rusqlite::Result<Event> {
            let seq: i64 = conn.query_row(
                "SELECT COALESCE(MAX(seq), -1) + 1 FROM events WHERE task_id = ?",
                params![task_id],
                |r| r.get(0),
            )?;
            let body_s = serde_json::to_string(body).unwrap_or_else(|_| "{}".to_string());
            let ts = now_unix();
            conn.execute(
                "INSERT INTO events (task_id, seq, ts, kind, body) VALUES (?, ?, ?, ?, ?)",
                params![task_id, seq, ts, kind, body_s],
            )?;
            let id = conn.last_insert_rowid();
            Ok(Event {
                id,
                task_id,
                seq,
                ts,
                kind: kind.to_string(),
                body: body.clone(),
            })
        })()
        .map_err(|e| format!("sqlite: {e}"))
    }

    pub fn list_events(&self, task_id: i64, after_seq: Option<i64>) -> Result<Vec<Event>, String> {
        self.with(|c| {
            let mut st = c.prepare(
                "SELECT id, task_id, seq, ts, kind, body FROM events
                 WHERE task_id = ? AND seq > COALESCE(?, -1) ORDER BY seq ASC LIMIT 2000",
            )?;
            let rows = st.query_map(params![task_id, after_seq], |r| {
                let body_s: String = r.get("body")?;
                let body: serde_json::Value =
                    serde_json::from_str(&body_s).unwrap_or(serde_json::Value::Null);
                Ok(Event {
                    id: r.get("id")?,
                    task_id: r.get("task_id")?,
                    seq: r.get("seq")?,
                    ts: r.get("ts")?,
                    kind: r.get("kind")?,
                    body,
                })
            })?;
            rows.collect::<rusqlite::Result<Vec<_>>>()
        })
    }

    // ---- sessions ----

    pub fn put_session(&self, token_sha_hex: &str, expires_at: i64) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "INSERT OR REPLACE INTO sessions (token_sha256_hex, expires_at) VALUES (?, ?)",
                params![token_sha_hex, expires_at],
            )?;
            Ok(())
        })
    }

    pub fn session_valid(&self, token_sha_hex: &str, now: i64) -> Result<bool, String> {
        self.with(|c| {
            let exp: Option<i64> = c
                .query_row(
                    "SELECT expires_at FROM sessions WHERE token_sha256_hex = ?",
                    params![token_sha_hex],
                    |r| r.get(0),
                )
                .optional()?;
            match exp {
                Some(e) if e > now => Ok(true),
                _ => {
                    // Best-effort cleanup of the presented (dead) token.
                    let _ = c.execute(
                        "DELETE FROM sessions WHERE token_sha256_hex = ?",
                        params![token_sha_hex],
                    );
                    Ok(false)
                }
            }
        })
    }

    pub fn delete_session(&self, token_sha_hex: &str) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "DELETE FROM sessions WHERE token_sha256_hex = ?",
                params![token_sha_hex],
            )?;
            Ok(())
        })
    }

    pub fn purge_expired_sessions(&self, now: i64) -> Result<(), String> {
        self.with(|c| {
            c.execute("DELETE FROM sessions WHERE expires_at <= ?", params![now])?;
            Ok(())
        })
    }
}
