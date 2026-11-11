#!/bin/bash
# planet_build.sh — Linux port of scripts/maps/build_graph.ps1 (world region).
# Faithful phase order/flags; Windows-only bits (Tee-Object bars, RAM sampler)
# replaced with plain per-phase logs. Survives session end: run as a
# systemd --user unit (see launch notes in ~/planet-build-logs/).
#
# LOCATION + ENVIRONMENT (2026-11-10): moved here from ~/planet-build/ on
# devvm24805 (which keeps a thin wrapper that execs this file). Defaults are
# exactly the devvm behaviour; a rented build box (location_share_server
# scripts/vast/jobs/planet.sh) overrides them:
#   PB_REPO       Modern-Apps checkout     (default: the tree this file is in)
#   PB_GO_FILE    POI go-file              (default: $HOME/planet-build/poi_go)
#   PB_LOGDIR     master/phase logs        (default: $HOME/planet-build-logs)
#   PB_DISK_PATH  filesystem the disk gates check (default: /)
# Memory admission reads MemAvailable, capped by the cgroup's own headroom when
# the process runs under a memory limit below the host's RAM (containers, vast
# slices); with no such limit (devvm) it is plain MemAvailable as before.
#
# POI HOLD: poi_extract waits for the go-file $GO before it runs.
# A sibling agent is stamping each POI-index record with its containing admin
# boundary id; do NOT create $GO until that fix has landed in
# scripts/maps/osm_ingest/src (or the sibling confirms). Everything else runs.
#
# OVERLAP + MEMORY BUDGET (2026-11-10, round 3)
# ---------------------------------------------
# Only road_graph (phase 2) feeds mamaps_build's graph-reading stages, so
# everything else may run beside it: transit shaping (needs world.transit),
# the POI sidecars (planet.osm.pbf + reviews), and -- when mamaps_build has
# --graph-ready-file -- mamaps_build's own passes 1-3 / lane / materialise,
# which then block on $GRAPH/.ready until road_graph + road_overlay succeed.
#
# Overlap is memory-admitted, never assumed (fb-oomd killed a 169 GB
# container on Nov 10 while road_graph + poi_extract ran). Every tool runs in
# its own `systemd-run --user --scope` with MemoryMax (~1.3x its peak) and
# MemorySwapMax=0 (the box has a 254 GB swapfile; never lean on it), and a
# background tool starts only when
#     MemAvailable - sum(budget - RSS of tools already running)
#         >= its budget + MEM_HEADROOM_GB
# checked under one lock, so two admissions cannot both spend the same
# headroom. A tool that does not fit waits (polls) instead. Budgets are the
# measured planet peaks (GiB, Nov 10):
#     road_graph       107.0  Peak RSS in graph.log (192 threads)
#     transit_shapes    16.4  sampled; its input is world.transit for any region
#     poi_extract       40.3  sampled planet run, all anonymous, at the write stage
#     mamaps_build      ~95   lane/materialise (~83 of it the file-backed nodeloc
#                             map); passes 1-3 + coords-resolve <= 56 (sampled)
# mamaps_build overlapping road_graph is admitted against its pass-1..coords
# budget (60), because that is what runs during road_graph's ~3 minutes; its
# scope gets MemoryHigh = what admission left it, so if it reaches the
# 95 GB stages early it is throttled (reclaims its own nodeloc pages) instead
# of pressuring road_graph or the box. MemoryHigh is lifted when the graph is
# ready. On a ~235 GB box that is otherwise idle (~215 GB available):
# road_graph + early mamaps_build (110 + 60 + 30) fits; adding poi_extract
# (+44) or transit_shapes (+18) at the same time does not, so they start as
# soon as road_graph's memory comes back (beside mamaps_build, niced).
#
# DISK: phase 4's measured peak is ~202 GB (tiles.features.tmp 98.5 +
# tilechunks 72.8 + bodies 31); road_graph's out dir peaks at ~3.6x its final
# 19 GB (~68 GB) while it runs. Overlapped, the worst case is graph (19) +
# phase-4 peak (202) + POI (7.2) ~= 228 GB, so early mamaps_build also needs
# >= 270 GiB free at phase-2 start; otherwise the build runs phase 4 after the
# graph as before, behind the unchanged 240 GiB phase-4 gate.
set -u
REPO=${PB_REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
IN=$REPO/scripts/maps/inputs
OUT=$IN/world.mamaps
TMP=$OUT.buildtmp
GRAPH=$TMP/graph
ROUTES=$TMP/transit-routes.geojsonseq
TILES=$TMP/tiles.mamaps
POI=$TMP/poi
WT=$IN/world.transit
GO=${PB_GO_FILE:-$HOME/planet-build/poi_go}
LOGDIR=${PB_LOGDIR:-$HOME/planet-build-logs}
DISK_PATH=${PB_DISK_PATH:-/}
LOG=$LOGDIR/planet-build.log
# Inline archive hash written by `mamaps_pack --sha256-out` (moved beside the
# archive only after the phase-7 header gate passes).
PACK_SHA=$TMP/world.mamaps.sha256
# mamaps_build --graph-ready-file: created only after road_graph + road_overlay.
READY=$GRAPH/.ready
# Running-tool registry for memory admission (one "pid budget_gib" per file).
JOBS=$TMP/.jobs

# Memory budget (GiB). Override from the unit's environment if a box differs.
MEM_HEADROOM_GB=${MEM_HEADROOM_GB:-30}
PEAK_RG_GB=${PEAK_RG_GB:-110}
PEAK_TS_GB=${PEAK_TS_GB:-18}
PEAK_POI_GB=${PEAK_POI_GB:-44}
PEAK_MB_EARLY_GB=${PEAK_MB_EARLY_GB:-60}
PEAK_MB_GB=${PEAK_MB_GB:-100}
PEAK_OVL_GB=${PEAK_OVL_GB:-16}
ADMIT_POLL_S=${ADMIT_POLL_S:-10}
# Disk gates (KiB): phase 4 alone after the graph, and phase 2+4 overlapped.
DISK_GATE_P4_KB=251658240        # 240 GiB
DISK_GATE_OVERLAP_KB=283115520   # 270 GiB

export PATH=$HOME/.cargo/bin:$PATH
export RUSTUP_TOOLCHAIN=1.97.0-x86_64-unknown-linux-gnu
unset MAPS_THREADS RAYON_NUM_THREADS MAPS_PREFETCH_LANES
export MAPS_ANON_SPILL=1

ts(){ date '+%F %T'; }
log(){ echo "[$(ts)] $*" | tee -a "$LOG"; }
BG_PIDS=()
# Fail fast: also stop any background job (and the tool it is running), so a
# failed phase never leaves a 192-thread tool behind.
die(){
    log "FATAL: $*"
    for p in ${BG_PIDS[@]+"${BG_PIDS[@]}"}; do pkill -TERM -P "$p" 2>/dev/null; kill "$p" 2>/dev/null; done
    exit 1
}
# Inside a background job: report and fail that job only (the main line's
# `wait` turns it into a FATAL there).
bgdie(){ log "FATAL: $*"; exit 1; }

mem_avail_gb(){
    if [ -n "${PB_TEST_MEMAVAIL_GB:-}" ]; then echo "$PB_TEST_MEMAVAIL_GB"; return; fi
    local host lim cur inact total cg
    host=$(awk '/^MemAvailable:/{print int($2/1048576)}' /proc/meminfo)
    lim=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || echo max)
    total=$(awk '/^MemTotal:/{print $2 * 1024}' /proc/meminfo)
    # No cgroup limit, or one at/above the host's RAM: plain MemAvailable.
    if [ "$lim" = max ] || [ "$lim" -ge "$total" ] 2>/dev/null; then echo "$host"; return; fi
    # A real limit (container / vast slice; /proc/meminfo shows the HOST):
    # headroom = limit - usage + reclaimable page cache, never above the host's.
    cur=$(cat /sys/fs/cgroup/memory.current 2>/dev/null || echo 0)
    inact=$(awk '/^inactive_file /{print $2}' /sys/fs/cgroup/memory.stat 2>/dev/null || echo 0)
    cg=$(( (lim - cur + inact) / 1073741824 ))
    echo $(( cg < host ? cg : host ))
}
# Memory the running tools may still grow into: sum(budget - current RSS).
outstanding_gb(){
    local f pid budget rss sum=0
    for f in "$JOBS"/*; do
        [ -f "$f" ] || continue
        read -r pid budget < "$f" || continue
        [ -d "/proc/$pid" ] || continue
        rss=$(awk '/^VmRSS:/{print int($2/1048576)}' "/proc/$pid/status" 2>/dev/null)
        [ "${budget:-0}" -gt "${rss:-0}" ] && sum=$((sum + budget - ${rss:-0}))
    done
    echo "$sum"
}
# GiB a new tool could have now without eating the headroom (may be negative).
spare_gb(){ echo $(( $(mem_avail_gb) - $(outstanding_gb) - MEM_HEADROOM_GB )); }

SCOPES=0
if systemd-run --user --scope --quiet -p MemoryMax=1G -p MemorySwapMax=0 -- true 2>/dev/null; then
    SCOPES=1
fi

# run_tool NAME BUDGET_GB MAX_GB HIGH ADMIT -- cmd...
#   Runs cmd (in a memory-capped scope when available) and waits for it.
#   ADMIT=1: first wait until BUDGET_GB fits (see header). HIGH: "-" for no
#   MemoryHigh, "auto" for whatever admission left (overlap throttle), or GiB.
#   The tool's PID and budget are registered for later admissions while it runs.
run_tool(){
    local name=$1 budget=$2 max=$3 high=$4 admit=$5; shift 6
    local fd p rc spare props=()
    exec {fd}>"$JOBS/.lock"
    local waited=0
    while :; do
        flock "$fd"
        spare=$(spare_gb)
        if [ "$admit" = 0 ] || [ "$spare" -ge "$budget" ]; then break; fi
        # Nothing of ours left to wait for: the shortfall is other tenants, and
        # running alone is what the sequential build always did.
        if [ "$(outstanding_gb)" -eq 0 ]; then
            log "  WARN: $name needs ${budget}G + ${MEM_HEADROOM_GB}G headroom, only $((spare + MEM_HEADROOM_GB))G available and no other build tool running; starting it alone"
            break
        fi
        [ "$waited" = 0 ] && log "  $name waits for memory (needs ${budget}G, ${spare}G spare after headroom)"
        waited=1
        flock -u "$fd"
        sleep "$ADMIT_POLL_S"
    done
    [ "$high" = auto ] && high=$spare
    if [ "$SCOPES" = 1 ]; then
        props=(-p "MemoryMax=${max}G" -p MemorySwapMax=0)
        [ "$high" != - ] && props+=(-p "MemoryHigh=${high}G")
        systemd-run --user --scope --quiet --collect --unit="pb-$name-$$" "${props[@]}" -- "$@" {fd}>&- &
    else
        "$@" {fd}>&- &
    fi
    p=$!
    echo "$p $budget" > "$JOBS/$name"
    flock -u "$fd"; exec {fd}>&-
    log "  started $name (pid $p; budget ${budget}G, spare was ${spare}G, MemoryMax ${max}G, MemoryHigh ${high}G, scopes=$SCOPES)"
    wait "$p"; rc=$?
    rm -f "$JOBS/$name"
    return "$rc"
}
cap(){ echo $(( ($1 * 13 + 9) / 10 )); }   # MemoryMax = ~1.3x the budget

mkdir -p "$TMP" "$POI" "$LOGDIR" "$JOBS"
rm -f "$JOBS"/* 2>/dev/null
BIN=$REPO/target/release
cd "$REPO" || die "cannot cd $REPO"

log "preflight: required static inputs"
for f in "$IN/planet.osm.pbf" "$IN/coastline.shp" "$IN/world.mdem" "$IN/reviews_archive.tsv"; do
    [ -f "$f" ] || die "missing input: $f"
    log "  ok $(basename $f) ($(stat -c%s "$f") B)"
done

log "[1/7] tools: cargo build --release (all phase 2-7 bins, poi_extract included)"
# Whether the go-file was already there when poi_extract/mamaps_pack were
# built: if not, the POI job rebuilds them once it appears (fix must be in tree).
GO_AT_BUILD=0; [ -f "$GO" ] && GO_AT_BUILD=1
cargo build --release \
    --bin road_graph --bin road_overlay \
    --bin transit_shapes \
    --bin mamaps_build --bin mamaps_pack --bin mamaps_dump \
    --bin poi_extract \
    >>"$LOGDIR/cargo-build.log" 2>&1 || die "cargo build failed (see cargo-build.log)"
for b in road_graph road_overlay transit_shapes mamaps_build mamaps_pack mamaps_dump poi_extract; do
    [ -x "$BIN/$b" ] || die "no $b after build"
done
log "  all binaries present (go-file at build time: $GO_AT_BUILD)"
# `--sha256-out` (inline archive hash) exists only in a mamaps_pack new enough
# to have it; an older one says "unknown flag". Probe once, fall back to
# sha256sum in phase 7 if absent.
if "$BIN/mamaps_pack" --sha256-out /dev/null 2>&1 | grep -q 'unknown flag'; then
    PACK_SHA_ARGS=()
    log "  mamaps_pack has no --sha256-out; phase 7 will run sha256sum"
else
    PACK_SHA_ARGS=(--sha256-out "$PACK_SHA")
    log "  mamaps_pack hashes inline (--sha256-out)"
fi
GRAPH_READY_FLAG=0
if "$BIN/mamaps_build" --help 2>&1 | grep -q -- '--graph-ready-file'; then GRAPH_READY_FLAG=1; fi
log "  memory: MemAvailable $(mem_avail_gb)G, headroom ${MEM_HEADROOM_GB}G, scopes=$SCOPES; mamaps_build --graph-ready-file: $GRAPH_READY_FLAG"

MB_ARGS=(--input "$IN/planet.osm.pbf" --out "$TILES"
    --coastline "$IN/coastline.shp" --graph "$GRAPH"
    --transit-routes "$ROUTES" --dem "$IN/world.mdem" --region world)

# Phase-4 footprint cut (2026-11-08, ENOSPC at 13:23 after 289G stage-A file
# spill): free what mamaps_build does not need BEFORE it starts -- now at the
# start of phase 2, since it may start then. cop90 (~67G) is the DEM source
# for $IN/world.mdem (mamaps_build reads world.mdem). TILES partials from a
# prior crashed run must not co-reside with the new spill. The transit mirror
# $IN/world_transit_work is freed only once transit-phase.service is done.
free_for_phase4(){
    if [ -d "$TILES" ]; then
        rm -rf "$TILES" && log "  freed stale partial TILES ($TILES)" \
            || die "cannot clear stale $TILES -- disk too tight to retry phase 4"
    fi
    rm -f "$TMP"/tiles.features.tmp "$TMP"/tiles.features.ways.tmp 2>/dev/null
    if [ -d "$IN/cop90" ]; then
        rm -rf "$IN/cop90" && log "  freed DEM source cop90 pre-phase-4" \
            || log "  WARN: cop90 delete failed, disk may be tight"
    fi
}
free_transit_work(){
    if [ -d "$IN/world_transit_work" ]; then
        rm -rf "$IN/world_transit_work" && log "  freed transit workdir pre-phase-4" \
            || log "  WARN: world_transit_work delete failed"
    fi
}
free_kb(){
    if [ -n "${PB_TEST_FREE_KB:-}" ]; then echo "$PB_TEST_FREE_KB"; return; fi
    df -k "$DISK_PATH" | awk 'NR==2{print $4}'
}

# --- background job: transit shapes (old phase 3) ---------------------------
transit_job(){
    BG_PIDS=()
    log "[3/7] transit shapes (background): waiting for $WT (transit-phase.service merges it)"
    while :; do
        if [ -s "$WT" ] && ! systemctl --user is-active -q transit-phase.service; then break; fi
        if systemctl --user is-failed -q transit-phase.service && [ ! -s "$WT" ]; then
            bgdie "transit-phase.service failed and $WT never appeared"
        fi
        sleep 300
    done
    log "  world.transit present ($(stat -c%s "$WT") B); shaping routes when memory admits"
    # Written aside and renamed, so mamaps_build can never read a partial file.
    run_tool ts "$PEAK_TS_GB" "$(cap "$PEAK_TS_GB")" - 1 -- \
        "$BIN/transit_shapes" "$ROUTES.tmp" --pack "$WT" >"$TMP/transit.log" 2>&1 \
        || bgdie "transit_shapes failed (see $TMP/transit.log)"
    mv -f "$ROUTES.tmp" "$ROUTES" || bgdie "cannot place $ROUTES"
    log "  routes shaped"
}

# --- background job: POI sidecars (old phase 5) -----------------------------
poi_job(){
    BG_PIDS=()
    log "[5/7] POI sidecars (background): HOLDING for sibling admin-boundary fix (go-file $GO)"
    while [ ! -f "$GO" ]; do sleep 60; done
    if [ "$GO_AT_BUILD" = 0 ]; then
        log "  go-file appeared after phase 1; rebuilding poi_extract + mamaps_pack fresh (fix must be in tree)"
        nice -n 10 cargo build --release --bin poi_extract --bin mamaps_pack >>"$LOGDIR/cargo-build.log" 2>&1 \
            || bgdie "cargo build poi_extract/mamaps_pack failed"
    else
        log "  go-file present; poi_extract (built in phase 1) starts when memory admits"
    fi
    # Niced: it may run beside mamaps_build, which is the critical path.
    run_tool poi "$PEAK_POI_GB" "$(cap "$PEAK_POI_GB")" - 1 -- \
        nice -n 10 "$BIN/poi_extract" "$IN/planet.osm.pbf" \
        --geojson "$POI/poi.geojsonseq" --names "$POI/poi_names.bin" \
        --index "$POI/poi_index.bin" --region world --reviews "$IN/reviews_archive.tsv" \
        >"$TMP/poi.log" 2>&1 || bgdie "poi_extract failed (see $TMP/poi.log)"
    # Sibling admin-boundary fix (982f7b92): poi_region.bin defaults beside
    # --index; mamaps_pack auto-stages it as kind 15. No new flags either end.
    [ -f "$POI/poi_region.bin" ] || bgdie "poi_extract wrote no $POI/poi_region.bin -- fix regressed?"
    log "  poi sidecars done ($(stat -c%s "$POI/poi_region.bin") B region sidecar)"
}

# --- background job: early mamaps_build (phase 4 started in phase 2) --------
mb_early_job(){
    BG_PIDS=()
    # Reserved at its full peak from the start, so nothing else is admitted into
    # memory it may grow into; admitted (in the main line) against its early
    # budget, and throttled by MemoryHigh to what admission left it.
    run_tool mb "$PEAK_MB_GB" "$(cap "$PEAK_MB_GB")" auto 0 -- \
        "$BIN/mamaps_build" "${MB_ARGS[@]}" --graph-ready-file "$READY" \
        >"$TMP/build.log" 2>&1 || bgdie "mamaps_build failed (see $TMP/build.log)"
}

log "[2/7] road graph: road_graph --rounds 3 (planet pbf is >10GB); transit, POI and early tiles beside it when they fit"
rm -f "$READY" "$ROUTES.tmp"
free_for_phase4
run_tool rg "$PEAK_RG_GB" "$(cap "$PEAK_RG_GB")" - 0 -- \
    "$BIN/road_graph" "$IN/planet.osm.pbf" --out "$GRAPH" --region world --rounds 3 \
    >"$TMP/graph.log" 2>&1 &
RG_PID=$!; BG_PIDS+=("$RG_PID")
# Admissions below must see road_graph's reservation.
for _ in $(seq 1 300); do [ -f "$JOBS/rg" ] && break; sleep 0.1; done
[ -f "$JOBS/rg" ] || die "road_graph did not start"

# Early mamaps_build: only with the flag, the disk for both, and the memory
# for road_graph + its pass-1..coords stages (+ headroom) right now.
MB_EARLY=0
if [ "$GRAPH_READY_FLAG" = 1 ]; then
    spare=$(spare_gb); fkb=$(free_kb)
    if [ "$fkb" -lt "$DISK_GATE_OVERLAP_KB" ]; then
        log "  early mamaps_build: NO -- $((fkb / 1048576))G free < 270G needed for graph + phase-4 spill together"
    elif [ "$spare" -lt "$PEAK_MB_EARLY_GB" ]; then
        log "  early mamaps_build: NO -- ${spare}G spare beside road_graph < ${PEAK_MB_EARLY_GB}G (MemAvailable $(mem_avail_gb)G)"
    else
        log "  early mamaps_build: YES (${spare}G spare beside road_graph, $((fkb / 1048576))G free); blocks on $READY"
        MB_EARLY=1
        mb_early_job & MB_PID=$!; BG_PIDS+=("$MB_PID")
        for _ in $(seq 1 300); do [ -f "$JOBS/mb" ] && break; sleep 0.1; done
    fi
fi
transit_job & TRANSIT_PID=$!; BG_PIDS+=("$TRANSIT_PID")
poi_job & POI_PID=$!; BG_PIDS+=("$POI_PID")
log "  background: transit shapes (pid $TRANSIT_PID), POI sidecars (pid $POI_PID)"

wait "$RG_PID" || die "road_graph failed (see $TMP/graph.log)"
# road_graph's memory is back: early mamaps_build may use its full peak now.
if [ "$MB_EARLY" = 1 ] && [ "$SCOPES" = 1 ]; then
    systemctl --user set-property --runtime "pb-mb-$$.scope" MemoryHigh=infinity 2>/dev/null \
        && log "  road_graph done: lifted early mamaps_build MemoryHigh"
fi
[ -f "$GRAPH/metadata.bin" ] || die "road_graph produced no $GRAPH/metadata.bin"
log "  metadata.bin present; running road_overlay --graph"
run_tool ovl "$PEAK_OVL_GB" "$(cap "$PEAK_OVL_GB")" - 0 -- \
    "$BIN/road_overlay" --graph "$GRAPH" >>"$TMP/graph.log" 2>&1 \
    || die "road_overlay failed (see $TMP/graph.log)"
log "  graph done"

log "[3/7] transit shapes: joining background job (pid $TRANSIT_PID)"
wait "$TRANSIT_PID" || die "transit shapes job failed (see $LOG / $TMP/transit.log)"
[ -s "$ROUTES" ] || die "transit shapes job wrote no $ROUTES"
log "  routes ready ($(stat -c%s "$ROUTES") B)"
free_transit_work

log "[4/7] tiles: mamaps_build (this is the long pole)"
if [ "$MB_EARLY" = 1 ]; then
    # Graph and routes are final: release mamaps_build past its graph gate.
    touch "$READY" || die "cannot create $READY"
    log "  graph ready -> early mamaps_build continues (pid $MB_PID); pre-phase-4 free: $(df -h "$DISK_PATH" | awk 'NR==2{print $4}')"
    wait "$MB_PID" || die "mamaps_build failed (see $TMP/build.log)"
else
    # Disk gate (2026-11-10): measured phase-4 peak on two full planet runs was
    # ~202 GB (tiles.features.tmp 98.5 + tilechunks 72.8 + bodies 31), far below
    # mamaps_build's own 289 GB estimate, so gate at 240 GiB (~40 GB margin).
    free_for_phase4
    log "  pre-phase-4 free: $(df -h "$DISK_PATH" | awk 'NR==2{print $4}') avail; commit: $(grep -E '^(CommitLimit|Committed_AS):' /proc/meminfo | tr '\n' ' ')"
    [ "$(free_kb)" -ge "$DISK_GATE_P4_KB" ] \
        || die "phase 4 needs ~240G free for the stage-A feature spill; free space or shrink staging first (see build.log WARN)"
    # Admitted like the others: waits for a running poi_extract if both would not fit.
    run_tool mb "$PEAK_MB_GB" "$(cap "$PEAK_MB_GB")" - 1 -- \
        "$BIN/mamaps_build" "${MB_ARGS[@]}" \
        >"$TMP/build.log" 2>&1 || die "mamaps_build failed (see $TMP/build.log)"
fi
log "  tiles done; dem consumed -> freeing COP90 source tiles (~67G)"
rm -rf "$IN/cop90" && log "  inputs/cop90 deleted" || log "  WARN: cop90 delete failed, disk may be tight"

log "[5/7] POI sidecars: joining background job (pid $POI_PID; holds for go-file $GO)"
wait "$POI_PID" || die "POI job failed (see $LOG / $TMP/poi.log)"
[ -f "$POI/poi_region.bin" ] || die "poi_extract wrote no $POI/poi_region.bin -- fix regressed?"
log "  poi sidecars ready"

log "[6/7] pack: mamaps_pack -> world.mamaps"
# A stale inline hash from an earlier run must never be taken for this one.
rm -f "$PACK_SHA"
"$BIN/mamaps_pack" --tiles "$TILES" --graph "$GRAPH" --poi "$POI" \
    --transit "$WT" --out "$OUT" ${PACK_SHA_ARGS[@]+"${PACK_SHA_ARGS[@]}"} >"$TMP/pack.log" 2>&1 \
    || die "mamaps_pack failed (see $TMP/pack.log)"
log "  packed $(stat -c%s "$OUT") B; freeing transit workdir"
rm -rf "$IN/world_transit_work" && log "  inputs/world_transit_work deleted" \
    || log "  WARN: world_transit_work delete failed"

log "[7/7] verify: header dump gates + sha256"
# mamaps_dump maps the archive (ecea9ca8f): the header gate reads ~9 KB, not 81 GB.
"$BIN/mamaps_dump" "$OUT" --mode header >"$TMP/header.tsv" 2>"$TMP/dump.err" \
    || die "mamaps_dump failed (see $TMP/dump.err)"
# The pack's inline SHA-256 (same `sha256sum` line, computed over the bytes as
# written) when it is present, well-formed and newer than the archive's own
# rename; otherwise hash the file as before.
if [ -s "$PACK_SHA" ] && [ "$(wc -l < "$PACK_SHA")" -eq 1 ] \
    && grep -Eq '^[0-9a-f]{64}  world\.mamaps$' "$PACK_SHA" && ! [ "$PACK_SHA" -ot "$OUT" ]; then
    mv -f "$PACK_SHA" "$IN/world.mamaps.sha256" || die "cannot place $IN/world.mamaps.sha256"
    log "  sha256 (inline, from mamaps_pack): $(cut -d' ' -f1 "$IN/world.mamaps.sha256")"
else
    log "  no usable inline hash; running sha256sum"
    ( cd "$IN" && sha256sum world.mamaps > world.mamaps.sha256 ) || die "sha256sum failed"
    log "  sha256: $(cut -d' ' -f1 "$IN/world.mamaps.sha256")"
fi
log "BUILD COMPLETE: $OUT"
