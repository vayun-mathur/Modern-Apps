#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# agents host setup: one-time, idempotent preparation of the maproj host for
# the `agents` compose project (infra/agents/compose.yml).
#
#   sudo bash infra/agents/host-setup.sh            # apply
#   sudo bash infra/agents/host-setup.sh --dry-run  # print actions only
#
# Steps:
#   (a) create /root/agent-world (0700),
#   (b) add an 8 GiB swapfile /swapfile-agents (only if no swap is active;
#       never touches existing swap),
#   (c) create a sparse 60 GiB ext4 image /root/agent-world/builds.img,
#       loop-mount it at /root/agent-world/builds via fstab, and create the
#       gradle-cache / cargo-home / android-sdk / slots subdirs,
#   (d) install a systemd oneshot (agents-firewall.service) that idempotently
#       blocks sandbox-egress abuse: drops 172.31.250.0/24 -> RFC1918/APIPA
#       in DOCKER-USER (established replies allowed first) and drops
#       172.31.250.0/24 -> host in INPUT (container->host services such as
#       the wg-mail tunnel's 10.66.0.2:25 hit INPUT, not FORWARD).
#
# It never runs a docker command that changes state, and never touches
# /root/location_share_server, existing swap, or snap Docker itself.
# ---------------------------------------------------------------------------
set -euo pipefail

AGENT_WORLD=/root/agent-world
BUILDS_DIR=$AGENT_WORLD/builds
BUILDS_IMG=$AGENT_WORLD/builds.img
SWAPFILE=/swapfile-agents
EGRESS_SUBNET=172.31.250.0/24
FIREWALL_SERVICE=/etc/systemd/system/agents-firewall.service
FIREWALL_SCRIPT=/usr/local/sbin/agents-firewall.sh

DRY_RUN=0
if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN=1
elif [[ -n "${1:-}" ]]; then
    echo "usage: $0 [--dry-run]" >&2
    exit 2
fi

log()  { echo "[host-setup] $*"; }

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "host-setup: refusing: not Linux ($(uname -s))" >&2
    exit 1
fi
if [[ $EUID -ne 0 ]]; then
    echo "host-setup: refusing: must run as root" >&2
    exit 1
fi
if [[ $DRY_RUN -eq 1 ]]; then
    log "dry-run mode: printing actions without changing state"
fi

# -- (a) agent world dir -----------------------------------------------------
log "ensuring $AGENT_WORLD (0700)"
if [[ $DRY_RUN -eq 1 ]]; then
    echo "[dry-run] would run: mkdir -p $AGENT_WORLD && chmod 0700 $AGENT_WORLD"
else
    mkdir -p "$AGENT_WORLD"
    chmod 0700 "$AGENT_WORLD"
fi

# -- (b) swap (only if the host has none active) -----------------------------
if swapon --show=NAME --noheadings 2>/dev/null | grep -q .; then
    log "swap already active; leaving existing swap untouched"
else
    log "no active swap: creating 8 GiB $SWAPFILE"
    if [[ $DRY_RUN -eq 1 ]]; then
        echo "[dry-run] would run: fallocate -l 8G $SWAPFILE && chmod 600 $SWAPFILE && mkswap $SWAPFILE && swapon $SWAPFILE"
        echo "[dry-run] would append to /etc/fstab: $SWAPFILE none swap sw 0 0"
    else
        fallocate -l 8G "$SWAPFILE"
        chmod 600 "$SWAPFILE"
        mkswap "$SWAPFILE"
        swapon "$SWAPFILE"
        grep -qs "^$SWAPFILE " /etc/fstab || echo "$SWAPFILE none swap sw 0 0" >> /etc/fstab
    fi
fi

# -- (c) size-bounded builds image -------------------------------------------
log "ensuring 60 GiB ext4 image $BUILDS_IMG mounted at $BUILDS_DIR"
if [[ $DRY_RUN -eq 1 ]]; then
    echo "[dry-run] would run: truncate -s 60G $BUILDS_IMG && mkfs.ext4 -F $BUILDS_IMG"
    echo "[dry-run] would append to /etc/fstab: $BUILDS_IMG $BUILDS_DIR ext4 loop,nofail 0 0"
    echo "[dry-run] would run: mkdir -p $BUILDS_DIR && mount $BUILDS_DIR"
    echo "[dry-run] would run: mkdir -p $BUILDS_DIR/{gradle-cache,cargo-home,android-sdk,slots}"
else
    if [[ ! -f "$BUILDS_IMG" ]]; then
        truncate -s 60G "$BUILDS_IMG"
        mkfs.ext4 -F "$BUILDS_IMG"
    fi
    FSTAB_LINE="$BUILDS_IMG $BUILDS_DIR ext4 loop,nofail 0 0"
    grep -qsF "$BUILDS_IMG $BUILDS_DIR" /etc/fstab || echo "$FSTAB_LINE" >> /etc/fstab
    mkdir -p "$BUILDS_DIR"
    mountpoint -q "$BUILDS_DIR" || mount "$BUILDS_DIR"
    mkdir -p "$BUILDS_DIR"/{gradle-cache,cargo-home,android-sdk,slots}
fi

# -- (d) egress firewall ------------------------------------------------------
log "installing $FIREWALL_SERVICE"
if [[ $DRY_RUN -eq 1 ]]; then
    echo "[dry-run] would write $FIREWALL_SCRIPT (idempotent iptables script)"
    echo "[dry-run] would write $FIREWALL_SERVICE (oneshot, After/Requires snap.docker.dockerd.service)"
    echo "[dry-run] would run: systemctl daemon-reload && systemctl enable --now agents-firewall.service"
else
    cat > "$FIREWALL_SCRIPT" <<EOF
#!/usr/bin/env bash
# agents-firewall: confine the agents-egress subnet ($EGRESS_SUBNET).
# Idempotent: rules carry a comment marker and are only added when absent.
set -euo pipefail
MARK="agents-egress-guard"
have() { iptables -C "\$@" -m comment --comment "\$MARK" 2>/dev/null; }
# Replies to connections the sandbox initiated must keep working.
have DOCKER-USER -s $EGRESS_SUBNET -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT \\
    || iptables -I DOCKER-USER 1 -s $EGRESS_SUBNET -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT -m comment --comment "\$MARK"
# No sandbox -> private-network traffic (lateral movement, metadata, proxies).
for NET in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16; do
    have DOCKER-USER -s $EGRESS_SUBNET -d "\$NET" -j DROP \\
        || iptables -I DOCKER-USER 2 -s $EGRESS_SUBNET -d "\$NET" -j DROP -m comment --comment "\$MARK"
done
# No sandbox -> host services (container-to-host packets traverse INPUT;
# e.g. the wg-mail tunnel's 10.66.0.2:25/:8081 must stay unreachable).
have INPUT -s $EGRESS_SUBNET -j DROP \\
    || iptables -I INPUT 1 -s $EGRESS_SUBNET -j DROP -m comment --comment "\$MARK"
EOF
    chmod 0755 "$FIREWALL_SCRIPT"
    cat > "$FIREWALL_SERVICE" <<EOF
[Unit]
Description=Confine agents sandbox egress (agents-egress subnet)
After=snap.docker.dockerd.service
Requires=snap.docker.dockerd.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=$FIREWALL_SCRIPT

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    systemctl enable --now agents-firewall.service
fi

# -- (e) next steps -----------------------------------------------------------
cat <<'NEXT'

host-setup done. Next steps (on the host, as root):
  1. Clone the deploy checkout:  git clone <Modern-Apps> /root/agent-world/Modern-Apps
  2. Create /root/agent-world/.env from infra/agents/.env.example (fill secrets).
  3. Ensure the base image exists:  docker compose -f /root/location_share_server/compose.yml --profile release build builder
     (produces location-share-builder:local; never touch the running project).
  4. Review, then start:  cd /root/agent-world/Modern-Apps && docker compose -p agents -f infra/agents/compose.yml up -d --build
  5. Verify: curl the runner via agentd; see infra/agents/README.md.
NEXT
