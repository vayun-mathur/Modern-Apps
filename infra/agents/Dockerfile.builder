# ---------------------------------------------------------------------------
# agents builder — the sandbox image agent code runs in.
#
# FROM location-share-builder:local (Ubuntu 24.04, JDK 21, Android SDK +
# NDK 29.0.14206865 at /opt/android-sdk, Rust 1.97 in /cargo-home,
# GRADLE_USER_HOME=/gradle-cache, /root/.cargo -> /cargo-home). On top it
# adds the agent-runner HTTP server plus the sandbox conventions the agents
# harness expects:
#   - unprivileged `agent` user (uid/gid 10001); the `builder` compose
#     service runs as this user,
#   - /home/agent/.cargo -> /cargo-home (build-logic/RustNative.kt invokes
#     $HOME/.cargo/bin/cargo and ignores CARGO_HOME),
#   - baked /opt/agent/gradle.properties (copied into /gradle-cache by the
#     builder-init service at first boot),
#   - ripgrep for the runner's /search route.
#
# Build context is infra/agents (a Cargo workspace with proto, agentd,
# runner); only the runner is compiled in. The `location-share-builder:local`
# base must exist first (built from /root/location_share_server), so this
# image is built with `docker compose -p agents -f infra/agents/compose.yml
# build` on the host, never here.
#
# NOTE: the base image declares VOLUME ["/gradle-cache","/cargo-home",
# "/opt/android-sdk"]. A VOLUME path cannot be chowned in later RUN layers
# and becomes an anonymous volume if not mounted, so compose mounts all
# three explicitly (bind-backed named volumes seeded from the image).
# ---------------------------------------------------------------------------

# ---- stage 1: compile agent-runner ---------------------------------------
FROM rust:1-bookworm AS runner-build
WORKDIR /src
# Workspace layout: Cargo.toml + the three member crates. Copying only these
# keeps the build cache stable when unrelated tree files change.
COPY Cargo.toml Cargo.lock ./
COPY proto ./proto
COPY runner ./runner
# agentd has its own Dockerfile; only its manifest is needed here (plus an
# empty main) so the workspace resolves without compiling it.
COPY agentd/Cargo.toml ./agentd/Cargo.toml
RUN mkdir -p agentd/src && echo 'fn main() {}' > agentd/src/main.rs
RUN cargo build -p agent-runner --release --locked

# ---- stage 2: sandbox -------------------------------------------------------
FROM location-share-builder:local

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk

RUN apt-get update && apt-get install -y --no-install-recommends \
        ripgrep \
        tini \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged sandbox user. The compose file runs the builder service as
# 10001:10001 and bind-mounts the caches; /home/agent/.cargo mirrors the
# base image's /root/.cargo symlink so Gradle's RustNative.kt finds cargo.
RUN groupadd -g 10001 agent \
    && useradd -m -u 10001 -g 10001 -d /home/agent -s /bin/bash agent \
    && ln -sfn /cargo-home /home/agent/.cargo

COPY --from=runner-build /src/target/release/agent-runner /usr/local/bin/agent-runner

# Stand-in for no-new-privileges (unusable under snap Docker, see compose.yml):
# with no setuid/setgid binaries, the uid-10001 sandbox has no path to root.
RUN find / -xdev -type f -perm /6000 -exec chmod a-s {} + 2>/dev/null || true

# Gradle memory/concurrency profile for the 6-CPU / 14 GB sandbox (smaller
# than release builds: 4 workers / 12 GB). builder-init copies this into
# /gradle-cache/gradle.properties on first boot; a repo-level
# gradle.properties is left untouched.
COPY gradle.properties /opt/agent/gradle.properties

WORKDIR /builds/slots

# Undo the base image's release ENTRYPOINT: this container serves the runner
# API instead. tini reaps stray grandchildren so killpg'd builds don't leak
# zombies into pid 1.
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/agent-runner"]
CMD []
