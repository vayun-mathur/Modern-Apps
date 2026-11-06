//! SQLite store: tasks, events, messages, sessions. Migrations run on startup.
//!
//! `Store` is a thin synchronous wrapper around a `rusqlite::Connection`
//! guarded by a `Mutex`; the HTTP/agent code calls `Store::with` from
//! `spawn_blocking` so the async runtime never blocks. Schema:
//!
//! * `tasks(...)` — one row per agent task (see [`crate::models::Task`]).
//! * `events(task_id, seq, ...)` — append-only log; `seq` is per-task.
//! * `messages(task_id, seq, body)` — persisted OpenRouter conversation
//!   (user / assistant / tool only; the system message is rebuilt per turn
//!   and never stored).
//! * `sessions(token_sha256_hex, expires_at)` — login sessions.

use std::path::Path;
use std::sync::{Arc, Mutex};

use rusqlite::{params, Connection, OptionalExtension};

use crate::models::{now_unix, Event, Pending, Task, TaskStatus};

/// One drained inbox row: user text plus the route's `kind` tag
/// (`plan_approve`, `plan_changes`, `review_changes`; `""` = plain message).
#[derive(Debug, Clone)]
pub struct InboxRow {
    pub text: String,
    pub kind: String,
}

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
                );
                CREATE TABLE IF NOT EXISTS messages (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  task_id INTEGER NOT NULL REFERENCES tasks(id),
                  seq INTEGER NOT NULL,
                  body TEXT NOT NULL,
                  UNIQUE(task_id, seq)
                );
                CREATE INDEX IF NOT EXISTS idx_messages_task ON messages(task_id, seq);
                CREATE TABLE IF NOT EXISTS inbox (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  task_id INTEGER NOT NULL REFERENCES tasks(id),
                  text TEXT NOT NULL,
                  kind TEXT NOT NULL DEFAULT ''
                );
                CREATE INDEX IF NOT EXISTS idx_inbox_task ON inbox(task_id, id);
                CREATE TABLE IF NOT EXISTS triage (
                  issue_number INTEGER PRIMARY KEY,
                  task_id INTEGER NOT NULL REFERENCES tasks(id),
                  status TEXT NOT NULL DEFAULT 'queued',
                  created_at INTEGER NOT NULL,
                  result_json TEXT
                );
                CREATE TABLE IF NOT EXISTS kv (
                  key TEXT PRIMARY KEY,
                  value TEXT NOT NULL
                );",
            )?;
            // Forward-compatible columns for DBs created by older migrates.
            for (table, col, ddl) in [
                ("tasks", "module_scope", "ALTER TABLE tasks ADD COLUMN module_scope TEXT NOT NULL DEFAULT ''"),
                ("tasks", "slot", "ALTER TABLE tasks ADD COLUMN slot INTEGER"),
                ("tasks", "base_sha", "ALTER TABLE tasks ADD COLUMN base_sha TEXT"),
                ("tasks", "commit_sha", "ALTER TABLE tasks ADD COLUMN commit_sha TEXT"),
                ("tasks", "pending_json", "ALTER TABLE tasks ADD COLUMN pending_json TEXT"),
                ("tasks", "todos_json", "ALTER TABLE tasks ADD COLUMN todos_json TEXT"),
                ("tasks", "rules_json", "ALTER TABLE tasks ADD COLUMN rules_json TEXT"),
                ("tasks", "require_plan", "ALTER TABLE tasks ADD COLUMN require_plan INTEGER NOT NULL DEFAULT 0"),
                ("tasks", "plan_approved", "ALTER TABLE tasks ADD COLUMN plan_approved INTEGER NOT NULL DEFAULT 0"),
                ("tasks", "wip_sha", "ALTER TABLE tasks ADD COLUMN wip_sha TEXT"),
                ("tasks", "pushed_sha", "ALTER TABLE tasks ADD COLUMN pushed_sha TEXT"),
                ("tasks", "final_diff", "ALTER TABLE tasks ADD COLUMN final_diff TEXT"),
                ("tasks", "commit_message", "ALTER TABLE tasks ADD COLUMN commit_message TEXT"),
                ("tasks", "issue_number", "ALTER TABLE tasks ADD COLUMN issue_number INTEGER"),
                ("tasks", "plan_md", "ALTER TABLE tasks ADD COLUMN plan_md TEXT"),
                ("inbox", "kind", "ALTER TABLE inbox ADD COLUMN kind TEXT NOT NULL DEFAULT ''"),
                ("tasks", "stop_requested", "ALTER TABLE tasks ADD COLUMN stop_requested INTEGER NOT NULL DEFAULT 0"),
                ("tasks", "approving", "ALTER TABLE tasks ADD COLUMN approving INTEGER NOT NULL DEFAULT 0"),
                ("tasks", "kind", "ALTER TABLE tasks ADD COLUMN kind TEXT NOT NULL DEFAULT 'task'"),
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

    /// Record the linked GitHub issue for start-from-issue tasks
    /// (the form stores it; `finish` then requires the ` (#<n>)` suffix).
    pub fn set_issue_number(&self, id: i64, issue_number: i64) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET issue_number = ?, updated_at = ? WHERE id = ?",
                params![issue_number, now_unix(), id],
            )?;
            Ok(())
        })
    }

    fn row_to_task(r: &rusqlite::Row<'_>) -> rusqlite::Result<Task> {
        let status_s: String = r.get("status")?;
        let kind: Option<String> = r
            .get::<_, Option<String>>("kind")
            .unwrap_or(None);
        let pending_s: Option<String> = r.get("pending_json")?;
        let pending = pending_s
            .as_deref()
            .map(serde_json::from_str::<Pending>)
            .transpose()
            .map_err(|e| {
                rusqlite::Error::FromSqlConversionFailure(
                    0,
                    rusqlite::types::Type::Text,
                    Box::new(e),
                )
            })?;
        Ok(Task {
            id: r.get("id")?,
            title: r.get("title")?,
            prompt: r.get("prompt")?,
            module_scope: r.get("module_scope")?,
            model: r.get("model")?,
            kind: kind.unwrap_or_else(crate::models::default_task_kind),
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
            pending,
            todos_json: r.get("todos_json")?,
            rules_json: r.get("rules_json")?,
            require_plan: r.get::<_, i64>("require_plan")? != 0,
            plan_approved: r.get::<_, i64>("plan_approved")? != 0,
            wip_sha: r.get("wip_sha")?,
            pushed_sha: r.get("pushed_sha")?,
            final_diff: r.get("final_diff")?,
            commit_message: r.get("commit_message")?,
            issue_number: r.get("issue_number")?,
            plan_md: r.get("plan_md")?,
            stop_requested: r.get::<_, i64>("stop_requested")? != 0,
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

    /// Ids of tasks left `running` by a previous process (restart orphans).
    pub fn interrupted_running_ids(&self) -> Result<Vec<i64>, String> {
        self.with(|c| {
            let mut st = c.prepare("SELECT id FROM tasks WHERE status = 'running' ORDER BY id ASC")?;
            let rows = st.query_map([], |r| r.get(0))?;
            rows.collect::<rusqlite::Result<Vec<i64>>>()
        })
    }

    /// Set the "plan first" requirement + approval flag on a task.
    pub fn set_plan_gate(&self, id: i64, require_plan: bool) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET require_plan = ?, updated_at = ? WHERE id = ?",
                params![require_plan as i64, now_unix(), id],
            )?;
            Ok(())
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

    /// Store the proposed plan text (phase 4) without approving it.
    pub fn set_plan_md(&self, id: i64, plan_md: &str) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET plan_md = ?, updated_at = ? WHERE id = ?",
                params![plan_md, now_unix(), id],
            )?;
            Ok(())
        })
    }

    /// Mark the plan approved (operator route).
    pub fn approve_plan(&self, id: i64) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET plan_approved = 1, updated_at = ? WHERE id = ?",
                params![now_unix(), id],
            )?;
            Ok(())
        })
    }

    /// Store the validated `update_todos` list (raw `todos_json`).
    pub fn set_todos(&self, id: i64, todos_json: &str) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET todos_json = ?, updated_at = ? WHERE id = ?",
                params![todos_json, now_unix(), id],
            )?;
            Ok(())
        })
    }

    /// Merge freshly loaded rules into `rules_json` (path → content, or null
    /// for "no file"). Existing entries win; returns nothing (callers read
    /// back via `get_task` when they need the merged map).
    pub fn merge_rules(
        &self,
        id: i64,
        fresh: &std::collections::BTreeMap<String, Option<String>>,
    ) -> Result<(), String> {
        let current: Option<String> = self.with(|c| {
            c.query_row(
                "SELECT rules_json FROM tasks WHERE id = ?",
                params![id],
                |r| r.get(0),
            )
        })?;
        let mut map: serde_json::Map<String, serde_json::Value> = current
            .as_deref()
            .map(serde_json::from_str)
            .transpose()
            .map_err(|e| format!("parse rules_json: {e}"))?
            .unwrap_or_default();
        for (path, content) in fresh {
            map.entry(path.clone()).or_insert_with(|| match content {
                Some(c) => serde_json::Value::String(c.clone()),
                None => serde_json::Value::Null,
            });
        }
        let merged = serde_json::Value::Object(map).to_string();
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET rules_json = ?, updated_at = ? WHERE id = ?",
                params![merged, now_unix(), id],
            )?;
            Ok(())
        })
    }

    /// Record the review snapshot at `finish` time: squashed-commit sha is
    /// kept in `wip_sha` (the WIP ref), plus `final_diff`, `commit_message`
    /// and `summary`.
    pub fn set_review(
        &self,
        id: i64,
        wip_sha: &str,
        final_diff: &str,
        commit_message: &str,
        summary: &str,
    ) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET wip_sha = ?, final_diff = ?, commit_message = ?,
                 summary = ?, updated_at = ? WHERE id = ?",
                params![wip_sha, final_diff, commit_message, summary, now_unix(), id],
            )?;
            Ok(())
        })
    }

    /// Record a successful push: `pushed=1`, `pushed_sha`, `commit_sha`, and
    /// `base_sha = pushed_sha` (follow-ups resume from the pushed tip with no
    /// WIP). Clears `pending_json` and the stop flag; leaves status changes to
    /// the caller.
    /// Atomically claim a task in `review` for approval. Returns false if it
    /// is not in review or another approve is already in flight, so double
    /// clicks can never push (or comment on the issue) twice.
    pub fn claim_approve(&self, id: i64) -> Result<bool, String> {
        self.with(|c| {
            let n = c.execute(
                "UPDATE tasks SET approving = 1, updated_at = ?
                 WHERE id = ? AND status = 'review' AND approving = 0",
                params![now_unix(), id],
            )?;
            Ok(n == 1)
        })
    }

    /// End an approve attempt. If messages arrived while approving, re-queue
    /// the task so they are handled (as review changes on failure, or as a
    /// follow-up after a successful push).
    pub fn finish_approve(&self, id: i64) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET approving = 0, updated_at = ? WHERE id = ?",
                params![now_unix(), id],
            )?;
            c.execute(
                "UPDATE tasks SET status = 'queued', updated_at = ?
                 WHERE id = ? AND status IN ('review', 'succeeded')
                 AND EXISTS (SELECT 1 FROM inbox WHERE task_id = ?)",
                params![now_unix(), id, id],
            )?;
            Ok(())
        })
    }

    /// Clear approve claims left by a crashed process (startup only).
    pub fn clear_stale_approving(&self) -> Result<usize, String> {
        self.with(|c| c.execute("UPDATE tasks SET approving = 0 WHERE approving = 1", []))
    }

    pub fn set_pushed(&self, id: i64, pushed_sha: &str) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET pushed = 1, pushed_sha = ?, commit_sha = ?,
                 base_sha = ?, wip_sha = NULL, pending_json = NULL, stop_requested = 0,
                 updated_at = ? WHERE id = ?",
                params![pushed_sha, pushed_sha, pushed_sha, now_unix(), id],
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

    /// Add headroom after a `budget` park is answered: +20 steps, +$1.00.
    pub fn extend_budget(&self, id: i64) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET budget_steps = budget_steps + 20, budget_usd = budget_usd + 1.0,
                 updated_at = ? WHERE id = ?",
                params![now_unix(), id],
            )?;
            Ok(())
        })
    }

    pub fn request_stop(&self, id: i64) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE tasks SET stop_requested = 1, updated_at = ? WHERE id = ?",
                params![now_unix(), id],
            )?;
            Ok(())
        })
    }

    /// Atomically park a task and release its slot.
    ///
    /// Sets `status`, `pending_json`, `wip_sha`, `slot = NULL` and clears the
    /// stop flag. Lost-wakeup fix: when the inbox already holds an unconsumed
    /// user message, the task is set to `queued` instead so the worker loop
    /// picks it up again (the message is the wakeup). Returns the status the
    /// task was set to.
    pub fn park_task(
        &self,
        id: i64,
        status: TaskStatus,
        pending: &Pending,
        wip_sha: Option<&str>,
    ) -> Result<TaskStatus, String> {
        let conn = self.inner.lock().map_err(|e| format!("db lock: {e}"))?;
        (|| -> rusqlite::Result<TaskStatus> {
            conn.execute_batch("BEGIN IMMEDIATE")?;
            let r = (|| -> rusqlite::Result<TaskStatus> {
                let pending_s = serde_json::to_string(pending).map_err(|e| {
                    rusqlite::Error::FromSqlConversionFailure(
                        0,
                        rusqlite::types::Type::Text,
                        Box::new(e),
                    )
                })?;
                let inbox_n: i64 = conn.query_row(
                    "SELECT COUNT(*) FROM inbox WHERE task_id = ?",
                    params![id],
                    |row| row.get(0),
                )?;
                let effective = if inbox_n > 0 {
                    TaskStatus::Queued
                } else {
                    status
                };
                conn.execute(
                    "UPDATE tasks SET status = ?, pending_json = ?, wip_sha = ?,
                     slot = NULL, stop_requested = 0, updated_at = ? WHERE id = ?",
                    params![
                        effective.as_str(),
                        pending_s,
                        wip_sha,
                        now_unix(),
                        id
                    ],
                )?;
                Ok(effective)
            })();
            match r {
                Ok(s) => {
                    conn.execute_batch("COMMIT")?;
                    Ok(s)
                }
                Err(e) => {
                    let _ = conn.execute_batch("ROLLBACK");
                    Err(e)
                }
            }
        })()
        .map_err(|e| format!("sqlite: {e}"))
    }

    // ---- messages (persisted conversation) ----

    /// Append one OpenRouter message (user / assistant / tool) to the stored
    /// history. Returns the assigned `seq`.
    pub fn append_message(
        &self,
        task_id: i64,
        msg: &crate::openrouter::Message,
    ) -> Result<i64, String> {
        let body = serde_json::to_string(msg).map_err(|e| format!("serialize message: {e}"))?;
        let conn = self.inner.lock().map_err(|e| format!("db lock: {e}"))?;
        (|| -> rusqlite::Result<i64> {
            let seq: i64 = conn.query_row(
                "SELECT COALESCE(MAX(seq), -1) + 1 FROM messages WHERE task_id = ?",
                params![task_id],
                |r| r.get(0),
            )?;
            conn.execute(
                "INSERT INTO messages (task_id, seq, body) VALUES (?, ?, ?)",
                params![task_id, seq, body],
            )?;
            Ok(seq)
        })()
        .map_err(|e| format!("sqlite: {e}"))
    }

    /// Load the stored conversation in order. The system message is never
    /// stored; the caller prepends the rebuilt one.
    pub fn load_messages(&self, task_id: i64) -> Result<Vec<crate::openrouter::Message>, String> {
        self.with(|c| {
            let mut st = c.prepare(
                "SELECT body FROM messages WHERE task_id = ? ORDER BY seq ASC LIMIT 10000",
            )?;
            let rows = st.query_map(params![task_id], |r| {
                let body_s: String = r.get("body")?;
                let msg: crate::openrouter::Message = serde_json::from_str(&body_s).map_err(|e| {
                    rusqlite::Error::FromSqlConversionFailure(
                        0,
                        rusqlite::types::Type::Text,
                        Box::new(e),
                    )
                })?;
                Ok(msg)
            })?;
            rows.collect::<rusqlite::Result<Vec<_>>>()
        })
    }

    // ---- inbox (steering / answers) ----

    /// Insert a user message and, in the SAME transaction, re-queue the task
    /// when it is parked (`needs_input`), in `review`, or already `succeeded`
    /// (follow-up). Returns `true` when the task was re-queued.
    ///
    /// Records `kind` on the inbox row (`plan_approve`, `plan_changes`,
    /// `review_changes`; `""` = plain message) so the resume path can format
    /// the pending tool-call answer.
    pub fn inbox_post_kind(
        &self,
        task_id: i64,
        text: &str,
        kind: &str,
    ) -> Result<bool, String> {
        let conn = self.inner.lock().map_err(|e| format!("db lock: {e}"))?;
        (|| -> rusqlite::Result<bool> {
            conn.execute_batch("BEGIN IMMEDIATE")?;
            let r = (|| -> rusqlite::Result<bool> {
                conn.execute(
                    "INSERT INTO inbox (task_id, text, kind) VALUES (?, ?, ?)",
                    params![task_id, text, kind],
                )?;
                let row: Option<(String, i64)> = conn
                    .query_row(
                        "SELECT status, approving FROM tasks WHERE id = ?",
                        params![task_id],
                        |row| Ok((row.get(0)?, row.get(1)?)),
                    )
                    .optional()?;
                // Parked tasks (needs_input/review) and succeeded tasks
                // (follow-ups) re-queue on a new message. A task mid-approve
                // keeps the message queued; `finish_approve` re-queues after.
                let requeue = row
                    .filter(|(_, approving)| *approving == 0)
                    .and_then(|(s, _)| TaskStatus::parse(&s))
                    .map(|s| s.is_parked() || s == TaskStatus::Succeeded)
                    .unwrap_or(false);
                if requeue {
                    conn.execute(
                        "UPDATE tasks SET status = 'queued', updated_at = ? WHERE id = ?",
                        params![now_unix(), task_id],
                    )?;
                }
                Ok(requeue)
            })();
            match r {
                Ok(v) => {
                    conn.execute_batch("COMMIT")?;
                    Ok(v)
                }
                Err(e) => {
                    let _ = conn.execute_batch("ROLLBACK");
                    Err(e)
                }
            }
        })()
        .map_err(|e| format!("sqlite: {e}"))
    }

    /// Atomically take all unconsumed inbox rows (ordered) and delete them,
    /// so concurrent consumers can never double-deliver. Returns untagged
    /// text; use [`Store::drain_inbox_tagged`] for the [`InboxRow`] kinds.
    pub fn drain_inbox(&self, task_id: i64) -> Result<Vec<String>, String> {
        Ok(self
            .drain_inbox_tagged(task_id)?
            .into_iter()
            .map(|r| r.text)
            .collect())
    }

    /// Tagged variant of [`Store::drain_inbox`].
    pub fn drain_inbox_tagged(&self, task_id: i64) -> Result<Vec<InboxRow>, String> {
        let conn = self.inner.lock().map_err(|e| format!("db lock: {e}"))?;
        (|| -> rusqlite::Result<Vec<InboxRow>> {
            conn.execute_batch("BEGIN IMMEDIATE")?;
            let r = (|| -> rusqlite::Result<Vec<InboxRow>> {
                let mut st = conn.prepare(
                    "SELECT id, text, COALESCE(kind, '') FROM inbox WHERE task_id = ? ORDER BY id ASC",
                )?;
                let rows: Vec<(i64, String, String)> = st
                    .query_map(params![task_id], |row| {
                        Ok((row.get(0)?, row.get(1)?, row.get(2)?))
                    })?
                    .collect::<rusqlite::Result<Vec<_>>>()?;
                if !rows.is_empty() {
                    let ids: Vec<String> = rows.iter().map(|(id, _, _)| id.to_string()).collect();
                    conn.execute(
                        &format!("DELETE FROM inbox WHERE id IN ({})", ids.join(",")),
                        [],
                    )?;
                }
                Ok(rows
                    .into_iter()
                    .map(|(_, text, kind)| InboxRow { text, kind })
                    .collect())
            })();
            match r {
                Ok(v) => {
                    conn.execute_batch("COMMIT")?;
                    Ok(v)
                }
                Err(e) => {
                    let _ = conn.execute_batch("ROLLBACK");
                    Err(e)
                }
            }
        })()
        .map_err(|e| format!("sqlite: {e}"))
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

    // ---- kv (small persistent key/value: triage watermark, ...) ----

    pub fn kv_get(&self, key: &str) -> Result<Option<String>, String> {
        self.with(|c| {
            c.query_row(
                "SELECT value FROM kv WHERE key = ?",
                params![key],
                |r| r.get(0),
            )
            .optional()
        })
    }

    pub fn kv_set(&self, key: &str, value: &str) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "INSERT INTO kv (key, value) VALUES (?, ?)
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                params![key, value],
            )?;
            Ok(())
        })
    }

    // ---- triage (issue → triage task idempotency + results) ----

    /// Record that `issue` is being triaged by `task_id`. Returns false when a
    /// row already exists (the caller then skips the issue).
    pub fn triage_insert(&self, issue: u64, task_id: i64) -> Result<bool, String> {
        self.with(|c| {
            let n = c.execute(
                "INSERT OR IGNORE INTO triage (issue_number, task_id, status, created_at)
                 VALUES (?, ?, 'queued', ?)",
                params![issue as i64, task_id, now_unix()],
            )?;
            Ok(n == 1)
        })
    }

    pub fn triage_get(&self, issue: u64) -> Result<Option<TriageRow>, String> {
        self.with(|c| {
            c.query_row(
                "SELECT issue_number, task_id, status, created_at, result_json
                 FROM triage WHERE issue_number = ?",
                params![issue as i64],
                |r| {
                    Ok(TriageRow {
                        issue_number: r.get::<_, i64>(0)? as u64,
                        task_id: r.get(1)?,
                        status: r.get(2)?,
                        created_at: r.get(3)?,
                        result_json: r.get(4)?,
                    })
                },
            )
            .optional()
        })
    }

    /// Store the triage outcome (`status`: e.g. `done` / `low_confidence` /
    /// `failed`; `result_json`: serialized [`crate::triage::TriageResult`]).
    pub fn triage_set_result(
        &self,
        issue: u64,
        status: &str,
        result_json: Option<&str>,
    ) -> Result<(), String> {
        self.with(|c| {
            c.execute(
                "UPDATE triage SET status = ?, result_json = ? WHERE issue_number = ?",
                params![status, result_json, issue as i64],
            )?;
            Ok(())
        })
    }

    /// Newest triage rows first (for the dashboard badge list; the driver
    /// worker's UI comes later).
    pub fn triage_recent(&self, limit: i64) -> Result<Vec<TriageRow>, String> {
        self.with(|c| {
            let mut st = c.prepare(
                "SELECT issue_number, task_id, status, created_at, result_json
                 FROM triage ORDER BY issue_number DESC LIMIT ?",
            )?;
            let rows = st.query_map(params![limit], |r| {
                Ok(TriageRow {
                    issue_number: r.get::<_, i64>(0)? as u64,
                    task_id: r.get(1)?,
                    status: r.get(2)?,
                    created_at: r.get(3)?,
                    result_json: r.get(4)?,
                })
            })?;
            rows.collect::<rusqlite::Result<Vec<_>>>()
        })
    }

    /// Create a `triage`-kind task for `issue_number`: read-only, 40 steps /
    /// $0.50, no plan gate, linked to the issue. Returns the task id.
    pub fn create_triage_task(
        &self,
        issue_number: u64,
        title: &str,
        prompt: &str,
        module_scope: &str,
        model: &str,
    ) -> Result<i64, String> {
        let now = now_unix();
        self.with(|c| {
            c.execute(
                "INSERT INTO tasks (title, prompt, module_scope, model, status, kind,
                  created_at, updated_at, budget_steps, budget_usd, require_plan, issue_number)
                 VALUES (?, ?, ?, ?, 'queued', 'triage', ?, ?, 40, 0.50, 0, ?)",
                params![title, prompt, module_scope, model, now, now, issue_number as i64],
            )?;
            Ok(c.last_insert_rowid())
        })
    }
}

/// One row of the `triage` table: which task triages an issue, its status,
/// and (once finished) the JSON result.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TriageRow {
    pub issue_number: u64,
    pub task_id: i64,
    pub status: String,
    pub created_at: i64,
    pub result_json: Option<String>,
}
