#[tokio::main]
async fn main() {
    if let Err(e) = agent_runner::run().await {
        eprintln!("agent-runner: fatal: {e}");
        std::process::exit(1);
    }
}
