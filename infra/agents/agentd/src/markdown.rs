//! Minimal safe Markdown renderer for agent-authored text.
//!
//! Agent output (`assistant` / `plan` / `question` events, pending payloads)
//! is untrusted: it may contain raw HTML or `javascript:` links. This module
//! parses with `pulldown-cmark` but renders its own HTML subset, so nothing
//! reaches the page unescaped:
//!
//! * emitted tags: `p`, `strong` (also for headings), `em`, `code`,
//!   `pre>code`, `ul`/`ol`/`li`, `br`, `hr`, `div.md-quote`, and `a[href]`
//!   for `http(s)` URLs only;
//! * raw HTML blocks and inline HTML are ESCAPED and shown as text;
//! * links with any other scheme render as plain text (no `<a>`);
//! * images render as their alt text (no external fetch under CSP `self`);
//! * everything else (tables, footnotes, metadata, …) renders as plain
//!   escaped text without its wrapper tags.

use pulldown_cmark::{Event, Options, Parser, Tag, TagEnd};

/// Escape `&<>"` for HTML text/attribute contexts.
fn esc(out: &mut String, s: &str) {
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            _ => out.push(c),
        }
    }
}

/// Only `http://` and `https://` links become `<a>`; anything else
/// (`javascript:`, `data:`, relative, …) renders as plain text.
fn is_safe_url(url: &str) -> bool {
    let u = url.trim_start();
    if u.chars().any(|c| c.is_control()) {
        return false;
    }
    let lower = u.to_ascii_lowercase();
    lower.starts_with("http://") || lower.starts_with("https://")
}

/// Render the safe subset of `md` to an HTML fragment (already escaped —
/// embed with [`maud::PreEscaped`]).
pub fn render_markdown(md: &str) -> String {
    let mut out = String::new();
    let parser = Parser::new_ext(md, Options::empty());
    // Whether each open `Link` emitted an `<a>` (matters only at `End`).
    let mut link_stack: Vec<bool> = Vec::new();
    for ev in parser {
        match ev {
            Event::Start(tag) => match tag {
                Tag::Paragraph => out.push_str("<p>"),
                Tag::Heading { .. } => out.push_str("<p><strong>"),
                Tag::BlockQuote(_) => out.push_str("<div class=\"md-quote\">"),
                Tag::CodeBlock(_) => out.push_str("<pre class=\"md-code\"><code>"),
                Tag::List(ordered) => {
                    if ordered.is_some() {
                        out.push_str("<ol>");
                    } else {
                        out.push_str("<ul>");
                    }
                }
                Tag::Item => out.push_str("<li>"),
                Tag::Emphasis => out.push_str("<em>"),
                Tag::Strong => out.push_str("<strong>"),
                Tag::Strikethrough => {}
                Tag::Link { dest_url, .. } => {
                    if is_safe_url(&dest_url) {
                        out.push_str("<a href=\"");
                        esc(&mut out, &dest_url);
                        out.push_str("\" rel=\"noopener\">");
                        link_stack.push(true);
                    } else {
                        link_stack.push(false);
                    }
                }
                // Images: alt-text children render as text; no `<img>`.
                // Tables/footnotes/metadata/definition lists/HTML blocks:
                // wrappers dropped, text children still render (escaped).
                _ => {}
            },
            Event::End(tag) => match tag {
                TagEnd::Paragraph => out.push_str("</p>"),
                TagEnd::Heading(_) => out.push_str("</strong></p>"),
                TagEnd::BlockQuote(_) => out.push_str("</div>"),
                TagEnd::CodeBlock => out.push_str("</code></pre>"),
                TagEnd::List(ordered) => {
                    if ordered {
                        out.push_str("</ol>");
                    } else {
                        out.push_str("</ul>");
                    }
                }
                TagEnd::Item => out.push_str("</li>"),
                TagEnd::Emphasis => out.push_str("</em>"),
                TagEnd::Strong => out.push_str("</strong>"),
                TagEnd::Link if link_stack.pop().unwrap_or(false) => {
                    out.push_str("</a>");
                }
                _ => {}
            },
            Event::Text(t) => esc(&mut out, &t),
            Event::Code(t) => {
                out.push_str("<code>");
                esc(&mut out, &t);
                out.push_str("</code>");
            }
            Event::Html(t) | Event::InlineHtml(t) => esc(&mut out, &t),
            Event::InlineMath(t) | Event::DisplayMath(t) => esc(&mut out, &t),
            Event::FootnoteReference(name) => {
                out.push_str("[^");
                esc(&mut out, &name);
                out.push(']');
            }
            Event::SoftBreak => out.push('\n'),
            Event::HardBreak => out.push_str("<br>"),
            Event::Rule => out.push_str("<hr>"),
            Event::TaskListMarker(checked) => {
                out.push_str(if checked { "☑ " } else { "☐ " });
            }
        }
    }
    out
}
