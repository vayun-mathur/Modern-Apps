//! OpenRouter chat-completions client (OpenAI tool-calling format).
//!
//! Only the chat-completions endpoint is used. `usage: {include: true}` is
//! always sent so token counts and (when the provider supplies it) cost come
//! back on every response; missing cost is tolerated as 0. In production the
//! key comes from `OPENROUTER_API_KEY`; tests point `OPENROUTER_BASE_URL` at
//! a local mock.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize)]
struct UsageOpt {
    include: bool,
}

#[derive(Debug, Clone, Serialize)]
struct ChatRequest<'a> {
    model: &'a str,
    messages: &'a [Message],
    tools: &'a [ToolDef],
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_choice: Option<&'a str>,
    usage: UsageOpt,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<ToolCall>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

impl Message {
    pub fn system(content: String) -> Message {
        Message {
            role: "system".into(),
            content: Some(content),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }
    pub fn user(content: String) -> Message {
        Message {
            role: "user".into(),
            content: Some(content),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }
    /// Plain assistant-text constructor.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn assistant_text(content: String) -> Message {
        Message {
            role: "assistant".into(),
            content: Some(content),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }
    pub fn assistant_calls(content: Option<String>, calls: Vec<ToolCall>) -> Message {
        Message {
            role: "assistant".into(),
            content,
            tool_calls: Some(calls),
            tool_call_id: None,
            name: None,
        }
    }
    pub fn tool_result(call_id: String, content: String) -> Message {
        Message {
            role: "tool".into(),
            content: Some(content),
            tool_calls: None,
            tool_call_id: Some(call_id),
            name: None,
        }
    }

    /// Rough char length for context-budget accounting.
    pub fn chars(&self) -> usize {
        self.content.as_ref().map(|c| c.len()).unwrap_or(0)
            + self
                .tool_calls
                .as_ref()
                .map(|cs| cs.iter().map(|c| c.function.name.len() + c.function.arguments.len()).sum())
                .unwrap_or(0)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub function: FunctionCall,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FunctionCall {
    pub name: String,
    pub arguments: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ToolDef {
    #[serde(rename = "type")]
    pub kind: &'static str,
    pub function: FunctionDef,
}

#[derive(Debug, Clone, Serialize)]
pub struct FunctionDef {
    pub name: &'static str,
    pub description: &'static str,
    pub parameters: serde_json::Value,
}

fn schema(props: &[(&str, &str, &str)], required: &[&str]) -> serde_json::Value {
    let mut map = serde_json::Map::new();
    for (name, ty, _desc) in props {
        map.insert(
            name.to_string(),
            serde_json::json!({"type": ty, "description": _desc}),
        );
    }
    serde_json::json!({
        "type": "object",
        "properties": serde_json::Value::Object(map),
        "required": required,
        "additionalProperties": false,
    })
}

/// The eight tools the model may call (mirrors the runner proto routes).
pub fn tool_defs() -> Vec<ToolDef> {
    vec![
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "read_file",
                description: "Read a file relative to the repo root. Returns content with line numbers.",
                parameters: schema(
                    &[
                        ("path", "string", "Repo-relative path, e.g. notes/.../MainActivity.kt"),
                        ("start_line", "integer", "1-based first line (optional)"),
                        ("max_lines", "integer", "Max lines (optional)"),
                    ],
                    &["path"],
                ),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "list_dir",
                description: "List one directory level relative to the repo root.",
                parameters: schema(
                    &[("path", "string", "Repo-relative directory, e.g. notes/")],
                    &["path"],
                ),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "search",
                description: "Search code with ripgrep. Stays inside the module scope.",
                parameters: schema(
                    &[
                        ("pattern", "string", "Search pattern"),
                        ("path", "string", "Subdirectory (optional)"),
                        ("glob", "string", "e.g. *.kt (optional)"),
                        ("fixed_string", "boolean", "Literal match (optional)"),
                    ],
                    &["pattern"],
                ),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "edit_file",
                description: "Replace exactly one occurrence of `old` with `new` in a file.",
                parameters: schema(
                    &[
                        ("path", "string", "Repo-relative path"),
                        ("old", "string", "Exact text to replace (must occur exactly once)"),
                        ("new", "string", "Replacement text"),
                    ],
                    &["path", "old", "new"],
                ),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "write_file",
                description: "Create or overwrite a file (parents created).",
                parameters: schema(
                    &[
                        ("path", "string", "Repo-relative path"),
                        ("content", "string", "Full file content"),
                    ],
                    &["path", "content"],
                ),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "git_diff",
                description: "Show the working-tree diff vs the branch base.",
                parameters: schema(&[], &[]),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "run_shell",
                description: "Run a short shell command (<=60s). No builds, installs, adb, or cargo build/test — use `build` for those.",
                parameters: schema(
                    &[("command", "string", "Shell command")],
                    &["command"],
                ),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "build",
                description: "Run an allowlisted build: ./gradlew :<path>:<task> or ./check <module>. Blocks while a production release runs.",
                parameters: schema(
                    &[("command", "string", "Build command, e.g. ./gradlew :notes:compileDevKotlin")],
                    &["command"],
                ),
            },
        },
        ToolDef {
            kind: "function",
            function: FunctionDef {
                name: "finish",
                description: "Finish the task: requires a non-empty diff and a `<module>: <desc>` commit message with a prose body.",
                parameters: schema(
                    &[
                        ("summary", "string", "One-paragraph summary of what changed"),
                        ("commit_message", "string", "Commit message: `<module>: <desc>` line <=80 chars, blank line, prose body"),
                    ],
                    &["summary", "commit_message"],
                ),
            },
        },
    ]
}

#[derive(Debug, Clone, Deserialize)]
pub struct Usage {
    #[serde(default)]
    pub prompt_tokens: i64,
    #[serde(default)]
    pub completion_tokens: i64,
    #[serde(default)]
    #[allow(dead_code)]
    pub total_tokens: i64,
    /// Provider-supplied cost in USD; absent on many models → 0.
    #[serde(default)]
    pub cost: f64,
    #[serde(default)]
    pub total_cost: f64,
}

impl Usage {
    pub fn cost_usd(&self) -> f64 {
        if self.cost != 0.0 {
            self.cost
        } else {
            self.total_cost
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
struct ChoiceMessage {
    #[serde(default)]
    content: Option<serde_json::Value>,
    #[serde(default)]
    tool_calls: Option<Vec<ToolCall>>,
}

#[derive(Debug, Clone, Deserialize)]
struct Choice {
    message: ChoiceMessage,
}

#[derive(Debug, Clone, Deserialize)]
struct ChatResponse {
    #[serde(default)]
    choices: Vec<Choice>,
    #[serde(default)]
    usage: Option<Usage>,
    #[serde(default)]
    error: Option<ProviderError>,
}

#[derive(Debug, Clone, Deserialize)]
struct ProviderError {
    #[serde(default)]
    message: String,
    #[serde(default)]
    code: Option<serde_json::Value>,
}

/// A parsed assistant turn.
#[derive(Debug, Clone)]
pub struct AssistantTurn {
    /// Plain text, if any (content may be a string or a content-parts array).
    pub text: String,
    pub tool_calls: Vec<ToolCall>,
    pub usage: Usage,
}

fn content_to_string(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Array(parts) => parts
            .iter()
            .filter_map(|p| {
                p.get("text")
                    .and_then(|t| t.as_str())
                    .map(|s| s.to_string())
                    .or_else(|| {
                        p.get("content")
                            .and_then(|c| c.as_str())
                            .map(|s| s.to_string())
                    })
            })
            .collect::<Vec<_>>()
            .join(""),
        serde_json::Value::Null => String::new(),
        other => other.to_string(),
    }
}

/// Parse a raw chat-completions body into an [`AssistantTurn`]. Pure;
/// unit-tested against JSON fixtures.
pub fn parse_chat_response(body: &[u8]) -> Result<AssistantTurn, String> {
    let resp: ChatResponse =
        serde_json::from_slice(body).map_err(|e| format!("bad OpenRouter JSON: {e}"))?;
    if let Some(err) = resp.error {
        let code = err
            .code
            .map(|c| c.to_string())
            .unwrap_or_else(|| "unknown".to_string());
        return Err(format!("OpenRouter error {code}: {}", err.message));
    }
    let choice = resp
        .choices
        .into_iter()
        .next()
        .ok_or_else(|| "OpenRouter returned no choices".to_string())?;
    let text = choice
        .message
        .content
        .as_ref()
        .map(content_to_string)
        .unwrap_or_default();
    Ok(AssistantTurn {
        text,
        tool_calls: choice.message.tool_calls.unwrap_or_default(),
        usage: resp.usage.unwrap_or(Usage {
            prompt_tokens: 0,
            completion_tokens: 0,
            total_tokens: 0,
            cost: 0.0,
            total_cost: 0.0,
        }),
    })
}

#[derive(Clone)]
pub struct OpenRouter {
    client: reqwest::Client,
    base_url: String,
    api_key: String,
}

impl OpenRouter {
    pub fn new(base_url: String, api_key: String) -> OpenRouter {
        OpenRouter {
            client: reqwest::Client::new(),
            base_url,
            api_key,
        }
    }

    pub async fn chat(
        &self,
        model: &str,
        messages: &[Message],
        tools: &[ToolDef],
    ) -> Result<AssistantTurn, String> {
        let url = format!("{}/chat/completions", self.base_url.trim_end_matches('/'));
        let req = ChatRequest {
            model,
            messages,
            tools,
            tool_choice: None,
            usage: UsageOpt { include: true },
        };
        let resp = self
            .client
            .post(&url)
            .header("Authorization", format!("Bearer {}", self.api_key))
            .header("Content-Type", "application/json")
            .json(&req)
            .send()
            .await
            .map_err(|e| format!("OpenRouter request failed: {e}"))?;
        let status = resp.status();
        let body = resp
            .bytes()
            .await
            .map_err(|e| format!("OpenRouter read body: {e}"))?;
        if !status.is_success() {
            // Try to surface a provider message; never include the key
            // (it is only in the request header, never in the body).
            if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&body) {
                let msg = v
                    .get("error")
                    .and_then(|e| e.get("message").and_then(|m| m.as_str()))
                    .unwrap_or("unknown error");
                return Err(format!("OpenRouter HTTP {status}: {msg}"));
            }
            return Err(format!("OpenRouter HTTP {status}"));
        }
        parse_chat_response(&body)
    }
}
