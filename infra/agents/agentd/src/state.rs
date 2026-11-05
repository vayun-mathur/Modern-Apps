//! Shared application state: config + store + auth + runner + OpenRouter.

use std::sync::Arc;

use tokio::sync::broadcast;

use crate::agent::DriverCtx;
use crate::auth::Auth;
use crate::config::Config;
use crate::models::Event;
use crate::openrouter::OpenRouter;
use crate::runner::Runner;
use crate::store::Store;

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<Config>,
    pub store: Store,
    pub auth: Auth,
    pub runner: Runner,
    pub openrouter: OpenRouter,
    pub events_tx: broadcast::Sender<(i64, Event)>,
}

impl AppState {
    pub fn new(config: Config, store: Store) -> AppState {
        let auth = Auth::new(store.clone(), config.password_hash.clone());
        let runner = Runner::new(config.runner_url.clone());
        let openrouter = OpenRouter::new(
            config.openrouter_base_url.clone(),
            config.openrouter_api_key.clone(),
        );
        let (events_tx, _) = broadcast::channel(1024);
        AppState {
            config: Arc::new(config),
            store,
            auth,
            runner,
            openrouter,
            events_tx,
        }
    }

    pub fn driver(&self) -> DriverCtx {
        DriverCtx {
            config: self.config.clone(),
            store: self.store.clone(),
            runner: self.runner.clone(),
            openrouter: self.openrouter.clone(),
            events_tx: self.events_tx.clone(),
        }
    }
}
