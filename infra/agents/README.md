# Agents sandbox (compose project `agents`)

- Runner: `agent-runner` (Tokio+axum, `infra/agents/runner/`) serves the
  `proto` HTTP API from inside the sandbox: per-slot checkouts at
  `/builds/slots/slot-N`, `bash -lc` exec in its own process group (Build
  kind serialized on a single permit), path confinement, `git` ops.
- Image: `Dockerfile.builder` builds the runner, then extends
  `location-share-builder:local` with ripgrep, user `agent` (10001), and a
  small-Gradle profile; ENTRYPOINT runs the runner under tini.
- Isolation: `builder` runs as 10001, caps (6 CPU/14 GB/4k pids),
  `cap_drop: ALL`, no-new-privileges, only on `agents-internal` (sealed)
  and `agents-egress` (172.31.250.0/24, host-firewalled from RFC1918).
- Orchestrator: `agentd` (separate image) talks to `http://builder:7070`,
  serves the UI as `agents-ui` on prod net, pushes via the mirror volume.
- Release back-pressure: `release-signal` mirrors prod's
  `/srv/release_in_progress` into `/signal/release` for agentd to defer.

Deploy (once, on the host as root, from /root/agent-world/Modern-Apps):
1. `git clone <Modern-Apps> /root/agent-world/Modern-Apps`
2. Review `infra/agents/host-setup.sh`, then `sudo bash infra/agents/host-setup.sh`
3. Copy `infra/agents/.env.example` to `/root/agent-world/.env`, fill secrets
4. Ensure base exists: `docker compose -f /root/location_share_server/compose.yml --profile release build builder`
5. `docker compose -p agents -f infra/agents/compose.yml up -d --build`

Verify: `docker compose -p agents ps`; `docker exec agents-builder-1 curl -s localhost:7070/v1/health`;
agentd UI via Caddy; run one `agent/` task end to end, then check
`docker stats` (builder ≤ 6 CPU/14 GB) and `iptables -S DOCKER-USER`.

Stop/remove (never touches `location-share`):
`docker compose -p agents -f infra/agents/compose.yml down` (add `-v` to
drop agents-* volumes too; the builds image + mirror stay until deleted).
