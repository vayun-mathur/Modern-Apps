//! agentd: trusted half of the on-server coding-agent harness.
//!
//! Startup: load env config (fail fast), open SQLite (migrate), mark
//! orphaned `running` tasks failed ("interrupted by restart"), ensure the
//! bare git mirror, bind the axum server, spawn scheduler workers.

mod agent;
mod auth;
mod config;
mod git;
mod models;
mod openrouter;
mod prompt;
mod runner;
mod scheduler;
mod state;
mod store;
mod tools;
mod ui;

#[cfg(test)]
mod tests;

#[cfg(test)]
mod tests_integration;

use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .with_writer(std::io::stderr)
        .init();

    if let Err(e) = run().await {
        eprintln!("agentd: fatal: {e}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), String> {
    let config = config::Config::from_env()?;
    tracing::info!("agentd starting: {}", config.redacted_debug());

    let store = store::Store::open(&config.state_dir)?;
    let interrupted = store.mark_interrupted_running_failed()?;
    if interrupted > 0 {
        tracing::warn!("{interrupted} orphaned running task(s) marked failed (interrupted by restart)");
    }
    store.purge_expired_sessions(models::now_unix())?;

    git::ensure_mirror(&config.mirror_dir, &config.repo_url)
        .await
        .map_err(|e| format!("mirror: {e}"))?;

    let state = state::AppState::new(config.clone(), store);
    let _workers = scheduler::spawn_workers(state.driver(), config.slots);

    let app = ui::router(state);
    let listener = tokio::net::TcpListener::bind(&config.listen)
        .await
        .map_err(|e| format!("bind {}: {e}", config.listen))?;
    tracing::info!("agentd listening on {}", config.listen);
    axum::serve(listener, app)
        .await
        .map_err(|e| format!("serve: {e}"))?;
    Ok(())
}
