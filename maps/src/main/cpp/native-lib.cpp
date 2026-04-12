#include <jni.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <android/log.h>

#include "scratchpad.h"
#include "radix_heap.h"

#define LOG_TAG "OfflineRouterNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

enum TravelMode { WALK = 2, BICYCLE = 3 };

#pragma pack(push, 1)
struct NodeMaster {
    int32_t lat_e7, lon_e7;
    uint64_t spatial_id;
    uint32_t edge_ptr;
};

struct Edge {
    uint32_t target;
    uint32_t dist_mm;
    uint32_t name_offset;
    uint8_t type;
};
#pragma pack(pop)

// --- GLOBALS ---
const int NUM_ZONES = 64;
NodeMaster* g_node_zones[NUM_ZONES] = {nullptr};
Edge* g_edge_zones[NUM_ZONES] = {nullptr};
size_t g_node_zone_sizes[NUM_ZONES] = {0};
size_t g_edge_zone_sizes[NUM_ZONES] = {0};

uint32_t g_zone_offsets[NUM_ZONES + 1] = {0};
char* g_road_names = nullptr;
size_t g_road_names_size = 0;
size_t g_total_node_count = 0;

uint64_t g_time_scale_fixed[4];
uint64_t g_edge_time_multipliers[4][16];
const double WALK_SPEED_M_S = 4.5 / 3.6;
const double BICYCLE_SPEED_M_S = 16.0 / 3.6;
const double DEG_TO_RAD = M_PI / 180.0;

uint32_t g_lon_to_mm_scale[4096];

static RoutingScratchpad g_scratchpad;
static RadixHeap g_fwd_heap;
static RadixHeap g_bwd_heap;

// --- OPTIMIZATION: CACHED ZONE LOOKUP ---
thread_local int g_last_zone_id = 0;

inline int get_zone_for_id(uint32_t global_id) {
    if (__builtin_expect(global_id >= g_total_node_count, 0)) return -1;
    if (__builtin_expect(global_id >= g_zone_offsets[g_last_zone_id] && global_id < g_zone_offsets[g_last_zone_id + 1], 1)) {
        return g_last_zone_id;
    }
    auto it = std::upper_bound(g_zone_offsets, g_zone_offsets + NUM_ZONES + 1, global_id);
    g_last_zone_id = (int)std::distance(g_zone_offsets, it) - 1;
    return g_last_zone_id;
}

inline bool is_zone_mapped(int zone) {
    return zone >= 0 && zone < NUM_ZONES && g_node_zones[zone] != nullptr;
}

inline const NodeMaster& get_node(uint32_t global_id) {
    int zone = get_zone_for_id(global_id);
    if (!g_node_zones[zone]) {
        static NodeMaster null_node = {0,0,0,0};
        return null_node;
    }
    return g_node_zones[zone][global_id - g_zone_offsets[zone]];
}

// --- GEOMETRY ---

__attribute__((always_inline)) inline uint32_t fast_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    int64_t dlat = std::abs((int64_t)lat1_e7 - lat2_e7);
    int64_t dlon = std::abs((int64_t)lon1_e7 - lon2_e7);
    int64_t dy_mm = (dlat << 3) + (dlat << 1) + dlat + (dlat >> 3);
    auto scale_idx = (uint32_t)((lat1_e7 >> 19) + 2048);
    uint32_t scale = g_lon_to_mm_scale[scale_idx & 4095];
    int64_t dx_mm = (dlon * scale) >> 10;
    uint64_t max_v = (dx_mm > dy_mm) ? dx_mm : dy_mm;
    uint64_t min_v = (dx_mm > dy_mm) ? dy_mm : dx_mm;
    return (uint32_t)(max_v - (max_v >> 5) + (min_v >> 1) - (min_v >> 3));
}

inline uint32_t get_edge_time_10ms(uint32_t dist_mm, uint8_t road_type, int mode) {
    uint64_t multiplier = g_edge_time_multipliers[mode & 0x3][road_type & 0xF];
    return (uint32_t)(((uint64_t)dist_mm * multiplier) >> 32);
}

inline uint32_t heuristic_time_10ms(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2, int mode) {
    uint32_t dist_mm = fast_dist_mm(lat1, lon1, lat2, lon2);
    uint64_t scaled_time = (uint64_t)dist_mm * g_time_scale_fixed[mode & 0x3];
    return (uint32_t)(scaled_time >> 32);
}

double get_bearing(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2) {
    double f1 = (lat1 / 1e7) * DEG_TO_RAD;
    double f2 = (lat2 / 1e7) * DEG_TO_RAD;
    double dl = ((lon2 - lon1) / 1e7) * DEG_TO_RAD;
    double y = sin(dl) * cos(f2);
    double x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl);
    return atan2(y, x) * (180.0 / M_PI);
}

int get_maneuver(double prev_bearing, double next_bearing) {
    double angle_diff = next_bearing - prev_bearing;
    while (angle_diff < -180) angle_diff += 360;
    while (angle_diff > 180) angle_diff -= 360;
    if (angle_diff > 155 || angle_diff < -155) return 3;
    if (angle_diff < -100) return 2;
    if (angle_diff < -45) return 4;
    if (angle_diff < -10) return 1;
    if (angle_diff < 10) return 9;
    if (angle_diff < 45) return 5;
    if (angle_diff < 100) return 8;
    return 6;
}

uint64_t latlng_to_spatial(double lat, double lon) {
    double x = (lon + 180.0) / 360.0;
    double y = (lat + 90.0) / 180.0;
    auto ix = (uint32_t)(x * 4294967295.0), iy = (uint32_t)(y * 4294967295.0);
    uint64_t res = 0;
    for (int i = 0; i < 32; i++) {
        res |= ((uint64_t)((ix >> i) & 1) << (2 * i));
        res |= ((uint64_t)((iy >> i) & 1) << (2 * i + 1));
    }
    return res;
}

// --- CORE ROUTING ---

struct SnappedEdge {
    uint32_t nodeA, nodeB;
    int32_t proj_lat, proj_lon;
    uint32_t distA_mm, distB_mm;
    uint8_t type;
    uint32_t name_offset;
};

struct RoutingContext {
    SnappedEdge start, end;
    uint32_t meeting_node = 0xFFFFFFFF;
    uint32_t best_total_time = 0xFFFFFFFF;
    int iterations = 0;
};

struct Projection { int32_t lat_e7, lon_e7; uint32_t dist_mm; };

Projection get_projection(int32_t px, int32_t py, int32_t x1, int32_t y1, int32_t x2, int32_t y2) {
    double dx = (double)x2 - x1, dy = (double)y2 - y1;
    double mag_sq = dx * dx + dy * dy;
    double t = (mag_sq == 0) ? 0 : ((double)(px - x1) * dx + (double)(py - y1) * dy) / mag_sq;
    t = std::max(0.0, std::min(1.0, t));
    int32_t proj_lat = (int32_t)(x1 + t * dx), proj_lon = (int32_t)(y1 + t * dy);
    return {proj_lat, proj_lon, fast_dist_mm(px, py, proj_lat, proj_lon)};
}

SnappedEdge find_nearest_edge(double lat, double lon, int mode, const char* label) {
    uint64_t target_spatial = latlng_to_spatial(lat, lon);
    int32_t pLat = lat * 10'000'000, pLon = lon * 10'000'000;
    SnappedEdge best = {0xFFFFFFFF, 0xFFFFFFFF, pLat, pLon, 0, 0, 0, 0xFFFFFFFF};
    uint32_t minSnapDist = 0xFFFFFFFF;
    const uint32_t SNAP_LIMIT_MM = 10000000;
    int target_zone = (int)((target_spatial >> 58) & 0x3F);
    for (int z = std::max(0, target_zone - 2); z <= std::min(NUM_ZONES - 1, target_zone + 2); ++z) {
        if (!is_zone_mapped(z)) continue;
        uint32_t zone_node_count = g_zone_offsets[z + 1] - g_zone_offsets[z];
        if (zone_node_count == 0) continue;
        uint32_t low = 0, high = zone_node_count - 1, local_center = 0;
        while (low <= high) {
            uint32_t mid = low + (high - low) / 2;
            if (g_node_zones[z][mid].spatial_id < target_spatial) low = mid + 1;
            else { local_center = mid; if (mid == 0) break; high = mid - 1; }
        }
        int window = 2000;
        for (int i = std::max(0, (int)local_center - window); i <= std::min((int)zone_node_count - 1, (int)local_center + window); ++i) {
            uint32_t u_global = g_zone_offsets[z] + i;
            const auto& node_u = g_node_zones[z][i];
            uint32_t s_ptr = node_u.edge_ptr, e_ptr = g_node_zones[z][i + 1].edge_ptr;
            for (uint32_t j = s_ptr; j < e_ptr; ++j) {
                if (j * sizeof(Edge) >= g_edge_zone_sizes[z]) break;
                Edge& e = g_edge_zones[z][j];
                int zone_v = get_zone_for_id(e.target);
                if (!is_zone_mapped(zone_v)) continue;
                const auto& node_v = g_node_zones[zone_v][e.target - g_zone_offsets[zone_v]];
                Projection p = get_projection(pLat, pLon, node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
                if (p.dist_mm < minSnapDist) {
                    minSnapDist = p.dist_mm;
                    best.nodeA = u_global; best.nodeB = e.target; best.proj_lat = p.lat_e7; best.proj_lon = p.lon_e7;
                    best.distA_mm = fast_dist_mm(p.lat_e7, p.lon_e7, node_u.lat_e7, node_u.lon_e7);
                    best.distB_mm = fast_dist_mm(p.lat_e7, p.lon_e7, node_v.lat_e7, node_v.lon_e7);
                    best.type = e.type; best.name_offset = e.name_offset;
                }
            }
        }
    }
    return best;
}

bool prepare_routing(double sLat, double sLon, double eLat, double eLon, int mode, RoutingContext& ctx) {
    LOGD("[PREPARE] Starting routing preparation. Mode: %d", mode);
    g_scratchpad.reset();
    g_fwd_heap.clear();
    g_bwd_heap.clear();
    ctx.iterations = 0;
    ctx.meeting_node = 0xFFFFFFFF;
    ctx.best_total_time = 0xFFFFFFFF;

    ctx.start = find_nearest_edge(sLat, sLon, mode, "START");
    ctx.end = find_nearest_edge(eLat, eLon, mode, "END");

    if (ctx.start.nodeA == 0xFFFFFFFF || ctx.end.nodeA == 0xFFFFFFFF) {
        LOGE("[PREPARE] Failed to snap start or end points.");
        return false;
    }

    LOGD("[PREPARE] Snapped START to Edge: %u -> %u, END to Edge: %u -> %u", ctx.start.nodeA, ctx.start.nodeB, ctx.end.nodeA, ctx.end.nodeB);

    auto push_fwd = [&](uint32_t node, uint32_t g) {
        auto& entry = g_scratchpad[node];
        entry.g_fwd = g;
        const auto& n_data = get_node(node);
        uint32_t h = heuristic_time_10ms(n_data.lat_e7, n_data.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode);
        g_fwd_heap.push(g + h, node);
    };
    push_fwd(ctx.start.nodeA, get_edge_time_10ms(ctx.start.distA_mm, ctx.start.type, mode));
    push_fwd(ctx.start.nodeB, get_edge_time_10ms(ctx.start.distB_mm, ctx.start.type, mode));

    auto push_bwd = [&](uint32_t node, uint32_t g) {
        auto& entry = g_scratchpad[node];
        entry.g_bwd = g;
        const auto& n_data = get_node(node);
        uint32_t h = heuristic_time_10ms(n_data.lat_e7, n_data.lon_e7, ctx.start.proj_lat, ctx.start.proj_lon, mode);
        g_bwd_heap.push(g + h, node);
    };
    push_bwd(ctx.end.nodeA, get_edge_time_10ms(ctx.end.distA_mm, ctx.end.type, mode));
    push_bwd(ctx.end.nodeB, get_edge_time_10ms(ctx.end.distB_mm, ctx.end.type, mode));

    LOGD("[PREPARE] Heaps ready. Fwd: %zu, Bwd: %zu", g_fwd_heap.size(), g_bwd_heap.size());
    return true;
}

void perform_search_loop(int mode, RoutingContext& ctx) {
    LOGD("[SEARCH] Starting Bi-A* search loop.");
    while (!g_fwd_heap.empty() && !g_bwd_heap.empty()) {
        ctx.iterations++;

        if (__builtin_expect((ctx.iterations % 200000) == 0, 0)) {
            LOGD("[SEARCH] Iter: %d | FwdHeap: %u | BwdHeap: %u | BestMeeting: %u",
                 ctx.iterations, g_fwd_heap.size(), g_bwd_heap.size(), ctx.best_total_time);
        }

        if (g_fwd_heap.top_key() + g_bwd_heap.top_key() >= ctx.best_total_time) {
            LOGD("[SEARCH] Stopping: frontiers met at iter %d.", ctx.iterations);
            break;
        }

        bool is_fwd = (g_fwd_heap.size() <= g_bwd_heap.size());
        RadixHeap& active_heap = is_fwd ? g_fwd_heap : g_bwd_heap;

        uint32_t u = active_heap.pop();
        auto& entry_u = g_scratchpad[u];
        uint32_t u_g = is_fwd ? entry_u.g_fwd : entry_u.g_bwd;

        int zone_u = get_zone_for_id(u);
        if (zone_u < 0) continue;

        uint32_t s = g_node_zones[zone_u][u - g_zone_offsets[zone_u]].edge_ptr;
        uint32_t e_ptr = g_node_zones[zone_u][u - g_zone_offsets[zone_u] + 1].edge_ptr;

        for (uint32_t i = s; i < e_ptr; ++i) {
            Edge& edge = g_edge_zones[zone_u][i];
            uint32_t travel = get_edge_time_10ms(edge.dist_mm, edge.type, mode);
            uint32_t v = edge.target;
            int zone_v = get_zone_for_id(v);

            // CRITICAL FIX: Ignore edges pointing into unmapped zones.
            if (!is_zone_mapped(zone_v)) continue;

            uint32_t new_g = u_g + travel;

            auto& entry_v = g_scratchpad[v];
            uint32_t& v_g_active = is_fwd ? entry_v.g_fwd : entry_v.g_bwd;
            uint32_t& v_p_active = is_fwd ? entry_v.p_fwd : entry_v.p_bwd;
            uint32_t v_g_other = is_fwd ? entry_v.g_bwd : entry_v.g_fwd;

            if (__builtin_expect(new_g < v_g_active, 1)) {
                v_g_active = new_g;
                v_p_active = u;
                const auto& n_v = g_node_zones[zone_v][v - g_zone_offsets[zone_v]];
                int32_t tLat = is_fwd ? ctx.end.proj_lat : ctx.start.proj_lat;
                int32_t tLon = is_fwd ? ctx.end.proj_lon : ctx.start.proj_lon;
                active_heap.push(new_g + heuristic_time_10ms(n_v.lat_e7, n_v.lon_e7, tLat, tLon, mode), v);

                if (v_g_other != 0xFFFFFFFF) {
                    uint32_t total = new_g + v_g_other;
                    if (total < ctx.best_total_time) {
                        ctx.best_total_time = total;
                        ctx.meeting_node = v;
                    }
                }
            }
        }
    }

    if (ctx.meeting_node == 0xFFFFFFFF) {
        LOGE("[SEARCH] Search failed. No path exists within loaded zones.");
    } else {
        LOGD("[SEARCH] Success: Meeting point at node %u", ctx.meeting_node);
    }
}

jobjectArray reconstruct_path(JNIEnv* env, int mode, const RoutingContext& ctx) {
    LOGD("[RECONSTRUCT] Rebuilding path from meeting node %u", ctx.meeting_node);

    std::vector<uint32_t> path;
    if (ctx.start.nodeA == ctx.end.nodeA && ctx.start.nodeB == ctx.end.nodeB) {
        path.push_back(ctx.start.nodeA);
        path.push_back(ctx.start.nodeB);
    } else {
        uint32_t safety = 0; const uint32_t LIMIT = 500000;
        for (uint32_t c = ctx.meeting_node; c != 0xFFFFFFFF && safety < LIMIT; c = g_scratchpad[c].p_fwd) {
            path.push_back(c); safety++;
        }
        std::reverse(path.begin(), path.end());
        for (uint32_t c = g_scratchpad[ctx.meeting_node].p_bwd; c != 0xFFFFFFFF && safety < LIMIT; c = g_scratchpad[c].p_bwd) {
            path.push_back(c); safety++;
        }
    }

    if (path.size() < 2) return nullptr;

    struct StepData {
        uint32_t name_off;
        uint64_t dist = 0;
        uint64_t time = 0;
        std::vector<double> coords;
        int maneuver = 0;
    };

    std::vector<StepData> steps;
    double last_bearing = 0;

    for (size_t i = 0; i < path.size() - 1; ++i) {
        uint32_t u = path[i], v = path[i+1];
        int z_u = get_zone_for_id(u);
        int z_v = get_zone_for_id(v);
        const auto& node_u = g_node_zones[z_u][u - g_zone_offsets[z_u]];
        const auto& node_v = g_node_zones[z_v][v - g_zone_offsets[z_v]];

        // Find the edge to get name and distance
        uint32_t current_name_off = 0xFFFFFFFF;
        uint32_t edge_dist = 0;
        uint32_t edge_time = 0;

        uint32_t s = node_u.edge_ptr, e = g_node_zones[z_u][u - g_zone_offsets[z_u] + 1].edge_ptr;
        for (uint32_t k = s; k < e; ++k) {
            if (g_edge_zones[z_u][k].target == v) {
                current_name_off = g_edge_zones[z_u][k].name_offset;
                edge_dist = g_edge_zones[z_u][k].dist_mm;
                edge_time = get_edge_time_10ms(edge_dist, g_edge_zones[z_u][k].type, mode);
                break;
            }
        }

        double current_bearing = get_bearing(node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);

        // Logic: Start new step if road name changes OR if it's the first node
        if (steps.empty() || current_name_off != steps.back().name_off) {
            int maneuver = 0; // Default: Proceed/Start
            if (!steps.empty()) {
                maneuver = get_maneuver(last_bearing, current_bearing);
            }
            steps.push_back({current_name_off, 0, 0, {}, maneuver});
            // Add the start node of the new road
            steps.back().coords.push_back(node_u.lon_e7 / 1e7);
            steps.back().coords.push_back(node_u.lat_e7 / 1e7);
        }

        steps.back().dist += edge_dist;
        steps.back().time += edge_time;
        steps.back().coords.push_back(node_v.lon_e7 / 1e7);
        steps.back().coords.push_back(node_v.lat_e7 / 1e7);
        last_bearing = current_bearing;
    }

    // Convert C++ steps to Java OfflineRouter$RawStep array
    jclass stepClass = env->FindClass("com/vayunmathur/maps/OfflineRouter$RawStep");
    jmethodID stepCtor = env->GetMethodID(stepClass, "<init>", "(ILjava/lang/String;JJ[D)V");
    jobjectArray res = env->NewObjectArray(steps.size(), stepClass, nullptr);

    for (size_t i = 0; i < steps.size(); ++i) {
        const char* name_ptr = (steps[i].name_off < g_road_names_size) ? (g_road_names + steps[i].name_off) : "Unknown Road";
        jstring jName = env->NewStringUTF(name_ptr);
        jdoubleArray jGeom = env->NewDoubleArray(steps[i].coords.size());
        env->SetDoubleArrayRegion(jGeom, 0, steps[i].coords.size(), steps[i].coords.data());

        jobject stepObj = env->NewObject(stepClass, stepCtor,
                                         (jint)steps[i].maneuver,
                                         jName,
                                         (jlong)steps[i].dist,
                                         (jlong)steps[i].time,
                                         jGeom);

        env->SetObjectArrayElement(res, i, stepObj);
        env->DeleteLocalRef(jName);
        env->DeleteLocalRef(jGeom);
        env->DeleteLocalRef(stepObj);
    }

    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vayunmathur_maps_OfflineRouter_init(JNIEnv* env, jobject thiz, jstring base_path) {
    const char* path_raw = env->GetStringUTFChars(base_path, nullptr); std::string base(path_raw);
    if (!base.empty() && base.back() != '/') base += "/";
    auto m_file = [&](const std::string& p, size_t& s) -> void* {
        int fd = open(p.c_str(), O_RDONLY); if (fd < 0) return nullptr;
        s = lseek(fd, 0, SEEK_END); if (s == 0) { close(fd); return nullptr; }
        void* a = mmap(nullptr, s, PROT_READ, MAP_SHARED, fd, 0); close(fd); return (a == MAP_FAILED) ? nullptr : a;
    };
    size_t s_meta; uint32_t* meta = (uint32_t*)m_file(base + "metadata.bin", s_meta);
    if (!meta) { env->ReleaseStringUTFChars(base_path, path_raw); return false; }
    g_zone_offsets[0] = 0;
    for (int i = 0; i < NUM_ZONES; ++i) g_zone_offsets[i+1] = g_zone_offsets[i] + meta[i];
    g_total_node_count = g_zone_offsets[NUM_ZONES];
    for (int i = 0; i < NUM_ZONES; ++i) {
        uint32_t count = meta[i]; if (count == 0) continue;
        size_t s_n, s_e;
        void* n_ptr = m_file(base + "nodes_zone_" + std::to_string(i) + ".bin", s_n);
        void* e_ptr = m_file(base + "edges_zone_" + std::to_string(i) + ".bin", s_e);
        if (!n_ptr || !e_ptr) { if (n_ptr) munmap(n_ptr, s_n); if (e_ptr) munmap(e_ptr, s_e); continue; }
        g_node_zones[i] = (NodeMaster*)n_ptr; g_edge_zones[i] = (Edge*)e_ptr;
        g_node_zone_sizes[i] = s_n; g_edge_zone_sizes[i] = s_e;
    }
    munmap(meta, s_meta);
    size_t s_r; g_road_names = (char*)m_file(base + "road_names.bin", s_r); g_road_names_size = s_r;
    for (int i = 0; i < 4096; ++i) {
        double lat_deg = ((double)((int64_t)(i - 2048) << 19)) / 1e7;
        g_lon_to_mm_scale[i] = (uint32_t)((111139000.0 / 1e7) * cos(lat_deg * DEG_TO_RAD) * 1024.0);
    }
    auto calc_scale = [](double speed_m_s) { return (uint64_t)((100.0 / (speed_m_s * 1000.0)) * 4294967296.0); };
    g_time_scale_fixed[WALK] = calc_scale(WALK_SPEED_M_S); g_time_scale_fixed[BICYCLE] = calc_scale(BICYCLE_SPEED_M_S);
    for (int m = 0; m < 4; ++m) {
        double base_speed = (m == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S;
        for (int r = 0; r < 16; ++r) {
            // FIX: Removed 0.001f penalty for motorways/trunks to prevent overflow and allow walking/biking
            float mod = 1.0f;
            g_edge_time_multipliers[m][r] = (uint64_t)((100.0 / (base_speed * 1000.0 * mod)) * 4294967296.0);
        }
    }
    env->ReleaseStringUTFChars(base_path, path_raw); return true;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vayunmathur_maps_OfflineRouter_findRouteNative(JNIEnv* env, jobject thiz, jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode) {
    RoutingContext ctx; if (!prepare_routing(sLat, sLon, eLat, eLon, mode, ctx)) return nullptr;
    perform_search_loop(mode, ctx);
    if (ctx.meeting_node == 0xFFFFFFFF) {
        LOGE("[ROUTE] Search failed. No path exists within loaded zones.");
        return nullptr;
    }
    LOGD("[ROUTE] Meeting node found: %u. Iterations: %d", ctx.meeting_node, ctx.iterations);
    return reconstruct_path(env, mode, ctx);
}