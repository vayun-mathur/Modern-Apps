//! Typed client for the runner HTTP API defined in `proto`.
//!
//! Every call is slot-scoped (`/v1/slots/{slot}/...`) plus the global
//! `/v1/builds/kill`. Request paths sent to the runner are validated to be
//! repo-relative (no `..`, no absolute paths) — defense in depth on top of
//! the runner's own checks.

use proto::{
    CommitRequest, CommitResponse, DiffResponse, EditRequest, ExecKind, ExecRequest, ExecResponse,
    HealthResponse, KillResponse, ListRequest, ListResponse, PrepareRequest, PrepareResponse,
    ReadRequest, ReadResponse, SearchRequest, SearchResponse, WriteRequest,
};

#[derive(Clone)]
pub struct Runner {
    client: reqwest::Client,
    base: String,
}

impl Runner {
    pub fn new(base: String) -> Runner {
        Runner {
            client: reqwest::Client::new(),
            base: base.trim_end_matches('/').to_string(),
        }
    }

    fn url(&self, path: &str) -> String {
        format!("{}{}", self.base, path)
    }

    async fn post<B: serde::Serialize, R: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        body: &B,
    ) -> Result<R, String> {
        let resp = self
            .client
            .post(self.url(path))
            .json(body)
            .send()
            .await
            .map_err(|e| format!("runner POST {path}: {e}"))?;
        Self::read_json(path, resp).await
    }

    async fn post_empty<R: serde::de::DeserializeOwned>(&self, path: &str) -> Result<R, String> {
        let resp = self
            .client
            .post(self.url(path))
            .header("Content-Length", "0")
            .send()
            .await
            .map_err(|e| format!("runner POST {path}: {e}"))?;
        Self::read_json(path, resp).await
    }

    async fn read_json<R: serde::de::DeserializeOwned>(
        path: &str,
        resp: reqwest::Response,
    ) -> Result<R, String> {
        let status = resp.status();
        let bytes = resp
            .bytes()
            .await
            .map_err(|e| format!("runner {path} read body: {e}"))?;
        if !status.is_success() {
            let msg = serde_json::from_slice::<serde_json::Value>(&bytes)
                .ok()
                .and_then(|v| {
                    v.get("error")
                        .and_then(|e| e.as_str())
                        .map(|s| s.to_string())
                })
                .unwrap_or_else(|| format!("HTTP {status}"));
            return Err(format!("runner {path}: {msg}"));
        }
        serde_json::from_slice(&bytes).map_err(|e| format!("runner {path} bad JSON: {e}"))
    }

    pub async fn prepare(&self, slot: u32, branch: &str) -> Result<PrepareResponse, String> {
        self.post(
            &format!("/v1/slots/{slot}/prepare"),
            &PrepareRequest {
                branch: branch.to_string(),
                resume: None,
            },
        )
        .await
    }

    /// Resume a parked task (possibly on a different slot): restore the WIP
    /// ref's work over `base_sha`, or start clean from `base_sha` when there
    /// is no parked work.
    pub async fn prepare_resume(
        &self,
        slot: u32,
        branch: &str,
        resume: proto::ResumeSpec,
    ) -> Result<PrepareResponse, String> {
        self.post(
            &format!("/v1/slots/{slot}/prepare"),
            &PrepareRequest {
                branch: branch.to_string(),
                resume: Some(resume),
            },
        )
        .await
    }

    pub async fn read(
        &self,
        slot: u32,
        path: &str,
        start_line: Option<usize>,
        max_lines: Option<usize>,
    ) -> Result<ReadResponse, String> {
        check_rel_path(path)?;
        self.post(
            &format!("/v1/slots/{slot}/read"),
            &ReadRequest {
                path: path.to_string(),
                start_line,
                max_lines,
            },
        )
        .await
    }

    pub async fn write(&self, slot: u32, path: &str, content: &str) -> Result<(), String> {
        check_rel_path(path)?;
        let v: serde_json::Value = self
            .post(
                &format!("/v1/slots/{slot}/write"),
                &WriteRequest {
                    path: path.to_string(),
                    content: content.to_string(),
                },
            )
            .await?;
        let _ = v;
        Ok(())
    }

    pub async fn edit(&self, slot: u32, path: &str, old: &str, new: &str) -> Result<(), String> {
        check_rel_path(path)?;
        let v: serde_json::Value = self
            .post(
                &format!("/v1/slots/{slot}/edit"),
                &EditRequest {
                    path: path.to_string(),
                    old: old.to_string(),
                    new: new.to_string(),
                },
            )
            .await?;
        let _ = v;
        Ok(())
    }

    pub async fn list(&self, slot: u32, path: &str) -> Result<ListResponse, String> {
        // `list` of the repo root is allowed (single level) — the prompt
        // tells the model not to; empty path means root.
        if !path.is_empty() {
            check_rel_path(path)?;
        }
        self.post(
            &format!("/v1/slots/{slot}/list"),
            &ListRequest {
                path: path.to_string(),
            },
        )
        .await
    }

    pub async fn search(
        &self,
        slot: u32,
        pattern: &str,
        path: Option<&str>,
        glob: Option<&str>,
        fixed_string: bool,
    ) -> Result<SearchResponse, String> {
        if let Some(p) = path {
            if !p.is_empty() {
                check_rel_path(p)?;
            }
        }
        self.post(
            &format!("/v1/slots/{slot}/search"),
            &SearchRequest {
                pattern: pattern.to_string(),
                path: path.map(|s| s.to_string()),
                glob: glob.map(|s| s.to_string()),
                fixed_string,
            },
        )
        .await
    }

    pub async fn diff(&self, slot: u32) -> Result<DiffResponse, String> {
        let resp = self
            .client
            .post(self.url(&format!("/v1/slots/{slot}/diff")))
            .json(&serde_json::json!({}))
            .send()
            .await
            .map_err(|e| format!("runner diff: {e}"))?;
        Self::read_json(&format!("/v1/slots/{slot}/diff"), resp).await
    }

    pub async fn exec(
        &self,
        slot: u32,
        command: &str,
        kind: ExecKind,
        timeout_secs: u64,
    ) -> Result<ExecResponse, String> {
        self.post(
            &format!("/v1/slots/{slot}/exec"),
            &ExecRequest {
                command: command.to_string(),
                kind,
                timeout_secs,
            },
        )
        .await
    }

    pub async fn commit(&self, slot: u32, message: &str) -> Result<CommitResponse, String> {
        self.post(
            &format!("/v1/slots/{slot}/commit"),
            &CommitRequest {
                message: message.to_string(),
            },
        )
        .await
    }

    /// Download the slot's git bundle (`application/octet-stream`) to memory.
    pub async fn bundle(&self, slot: u32) -> Result<Vec<u8>, String> {
        let resp = self
            .client
            .get(self.url(&format!("/v1/slots/{slot}/bundle")))
            .send()
            .await
            .map_err(|e| format!("runner bundle: {e}"))?;
        let status = resp.status();
        if !status.is_success() {
            return Err(format!("runner bundle: HTTP {status}"));
        }
        resp.bytes()
            .await
            .map(|b| b.to_vec())
            .map_err(|e| format!("runner bundle read: {e}"))
    }

    pub async fn cancel_slot(&self, slot: u32) -> Result<bool, String> {
        let r: KillResponse = self.post_empty(&format!("/v1/slots/{slot}/cancel")).await?;
        Ok(r.killed)
    }

    pub async fn kill_build(&self) -> Result<bool, String> {
        let r: KillResponse = self.post_empty("/v1/builds/kill").await?;
        Ok(r.killed)
    }

    /// Readiness probe helper: the runner's build-slot state.
    /// (`#[allow]` — exercised by the integration test and useful for ops.)
    #[allow(dead_code)]
    pub async fn health(&self) -> Result<HealthResponse, String> {
        let resp = self
            .client
            .get(self.url("/v1/health"))
            .send()
            .await
            .map_err(|e| format!("runner health: {e}"))?;
        Self::read_json("/v1/health", resp).await
    }
}

/// Reject absolute paths and `..` escapes before they reach the runner.
fn check_rel_path(path: &str) -> Result<(), String> {
    if path.is_empty() {
        return Err("empty path".to_string());
    }
    if path.starts_with('/') {
        return Err(format!("absolute paths are not allowed: {path:?}"));
    }
    for seg in path.split('/') {
        if seg == ".." {
            return Err(format!("`..` is not allowed in paths: {path:?}"));
        }
    }
    Ok(())
}
