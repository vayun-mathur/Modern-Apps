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
    uint32_t edge_ptr_low;
    uint8_t edge_ptr_high;
};

struct Edge {
    uint32_t target;
    uint32_t dist_mm;
    uint32_t name_offset;
    uint8_t type;
};
#pragma pack(pop)

// --- GLOBALS ---
uint64_t g_time_scale_fixed[4];
uint64_t g_edge_time_multipliers[4][16];
const double WALK_SPEED_M_S = 4.5 / 3.6;
const double BICYCLE_SPEED_M_S = 16.0 / 3.6;
const double EARTH_RADIUS_MM = 6371000.0 * 1000.0;
const double DEG_TO_RAD = M_PI / 180.0;

NodeMaster* g_nodes = nullptr;
Edge* g_edges = nullptr;
uint64_t* g_spatial = nullptr;
char* g_road_names = nullptr;
size_t g_node_count = 0;

uint32_t g_lon_to_mm_scale[4096];

static RoutingScratchpad g_scratchpad;
static RadixHeap g_fwd_heap;
static RadixHeap g_bwd_heap;

// --- GEOMETRY & TIME UTILITIES ---

inline uint64_t get_ptr(uint32_t idx) {
    return ((uint64_t)g_nodes[idx].edge_ptr_high << 32) | g_nodes[idx].edge_ptr_low;
}

inline uint64_t get_end_ptr(uint32_t idx) {
    return get_ptr(idx + 1);
}

__attribute__((always_inline)) inline uint32_t fast_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    int64_t dlat = std::abs((int64_t)lat1_e7 - lat2_e7);
    int64_t dlon = std::abs((int64_t)lon1_e7 - lon2_e7);
    int64_t dy_mm = (dlat << 3) + (dlat << 1) + dlat + (dlat >> 3);
    uint32_t scale_idx = (uint32_t)((lat1_e7 >> 19) + 2048);
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
    if (angle_diff > 155 || angle_diff < -155) return 3; // UTURN
    if (angle_diff < -100) return 2; // SHARP_LEFT
    if (angle_diff < -45) return 4;  // LEFT
    if (angle_diff < -10) return 1;  // SLIGHT_LEFT
    if (angle_diff < 10) return 9;   // STRAIGHT
    if (angle_diff < 45) return 5;   // SLIGHT_RIGHT
    if (angle_diff < 100) return 8;  // RIGHT
    return 6; // SHARP_RIGHT
}

uint64_t latlng_to_spatial(double lat, double lon) {
    double x = (lon + 180.0) / 360.0;
    double y = (lat + 90.0) / 180.0;
    uint32_t ix = (uint32_t)(x * 4294967295.0), iy = (uint32_t)(y * 4294967295.0);
    uint64_t res = 0;
    for (int i = 0; i < 32; i++) {
        res |= ((uint64_t)((ix >> i) & 1) << (2 * i));
        res |= ((uint64_t)((iy >> i) & 1) << (2 * i + 1));
    }
    return res;
}

// --- CORE ROUTING FUNCTIONS ---

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
    int32_t pLat = (int32_t)(lat * 1e7), pLon = (int32_t)(lon * 1e7);
    auto it = std::lower_bound(g_spatial, g_spatial + g_node_count, target_spatial);
    intptr_t center = std::distance(g_spatial, it);

    SnappedEdge best = {0xFFFFFFFF, 0xFFFFFFFF, pLat, pLon, 0, 0, 0, 0xFFFFFFFF};
    uint32_t minSnapDist = 0xFFFFFFFF;

    intptr_t window = 1200;
    for (intptr_t i = std::max((intptr_t)0, center - window); i <= std::min((intptr_t)g_node_count - 1, center + window); ++i) {
        uint32_t u = (uint32_t)i;
        uint64_t s = get_ptr(u), e_ptr = get_end_ptr(u);
        for (uint64_t j = s; j < e_ptr; ++j) {
            Edge& e = g_edges[j];
            Projection p = get_projection(pLat, pLon, g_nodes[u].lat_e7, g_nodes[u].lon_e7, g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7);
            if (p.dist_mm < minSnapDist) {
                minSnapDist = p.dist_mm;
                best.nodeA = u; best.nodeB = e.target; best.proj_lat = p.lat_e7; best.proj_lon = p.lon_e7;
                best.distA_mm = fast_dist_mm(p.lat_e7, p.lon_e7, g_nodes[u].lat_e7, g_nodes[u].lon_e7);
                best.distB_mm = fast_dist_mm(p.lat_e7, p.lon_e7, g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7);
                best.type = e.type; best.name_offset = e.name_offset;
            }
        }
    }

    if (best.nodeA != 0xFFFFFFFF) {
        LOGD("[%s SNAP] Success! Nearest Edge: %u -> %u. Snap Distance: %u mm", label, best.nodeA, best.nodeB, minSnapDist);
    } else {
        LOGE("[%s SNAP] Failed to find any edges near (%f, %f)", label, lat, lon);
    }
    return best;
}

/**
 * PHASE 1: PREPARE
 * Snaps start/end points and initializes both search frontiers.
 */
bool prepare_routing(double sLat, double sLon, double eLat, double eLon, int mode, RoutingContext& ctx) {
    g_scratchpad.reset();
    g_fwd_heap.clear();
    g_bwd_heap.clear();
    ctx.iterations = 0;
    ctx.meeting_node = 0xFFFFFFFF;
    ctx.best_total_time = 0xFFFFFFFF;

    ctx.start = find_nearest_edge(sLat, sLon, mode, "START");
    ctx.end = find_nearest_edge(eLat, eLon, mode, "END");

    if (ctx.start.nodeA == 0xFFFFFFFF || ctx.end.nodeA == 0xFFFFFFFF) return false;

    // Push Start Nodes (Forward)
    auto push_fwd = [&](uint32_t node, uint32_t g) {
        auto& entry = g_scratchpad[node];
        entry.g_fwd = g;
        uint32_t h = heuristic_time_10ms(g_nodes[node].lat_e7, g_nodes[node].lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode);
        g_fwd_heap.push(g + h, node);
    };
    push_fwd(ctx.start.nodeA, get_edge_time_10ms(ctx.start.distA_mm, ctx.start.type, mode));
    push_fwd(ctx.start.nodeB, get_edge_time_10ms(ctx.start.distB_mm, ctx.start.type, mode));

    // Push End Nodes (Backward)
    auto push_bwd = [&](uint32_t node, uint32_t g) {
        auto& entry = g_scratchpad[node];
        entry.g_bwd = g;
        uint32_t h = heuristic_time_10ms(g_nodes[node].lat_e7, g_nodes[node].lon_e7, ctx.start.proj_lat, ctx.start.proj_lon, mode);
        g_bwd_heap.push(g + h, node);
    };
    push_bwd(ctx.end.nodeA, get_edge_time_10ms(ctx.end.distA_mm, ctx.end.type, mode));
    push_bwd(ctx.end.nodeB, get_edge_time_10ms(ctx.end.distB_mm, ctx.end.type, mode));

    return true;
}

/**
 * PHASE 2: LOOP
 * The core search loop expanding both frontiers.
 */
void perform_search_loop(int mode, RoutingContext& ctx) {
    while (!g_fwd_heap.empty() && !g_bwd_heap.empty()) {
        ctx.iterations++;

        // Termination condition for Bidirectional A*
        if (g_fwd_heap.top_key() + g_bwd_heap.top_key() >= ctx.best_total_time) break;
        if (__builtin_expect(ctx.iterations > 1000000000, 0)) break;

        // Periodic logging
        if (__builtin_expect((ctx.iterations & 0xFFFFF) == 0, 0)) {
            LOGD("[BI-A*] Iter: %d. Fwd Keys: %u, Bwd Keys: %u. Best meeting: %u",
                 ctx.iterations, g_fwd_heap.top_key(), g_bwd_heap.top_key(), ctx.best_total_time);
        }

        // Expand the smaller front
        bool is_fwd = (g_fwd_heap.size() <= g_bwd_heap.size());
        RadixHeap& active_heap = is_fwd ? g_fwd_heap : g_bwd_heap;

        uint32_t u = active_heap.pop();
        auto& entry_u = g_scratchpad[u];
        uint32_t u_g = is_fwd ? entry_u.g_fwd : entry_u.g_bwd;

        uint64_t s = get_ptr(u), e_ptr = get_end_ptr(u);
        for (uint64_t i = s; i < e_ptr; ++i) {
            Edge& edge = g_edges[i];
            uint32_t travel = get_edge_time_10ms(edge.dist_mm, edge.type, mode);
            uint32_t v = edge.target;
            uint32_t new_g = u_g + travel;

            auto& entry_v = g_scratchpad[v];
            uint32_t& v_g_active = is_fwd ? entry_v.g_fwd : entry_v.g_bwd;
            uint32_t& v_p_active = is_fwd ? entry_v.p_fwd : entry_v.p_bwd;
            uint32_t v_g_other = is_fwd ? entry_v.g_bwd : entry_v.g_fwd;

            if (new_g < v_g_active) {
                v_g_active = new_g;
                v_p_active = u;

                uint32_t tLat = is_fwd ? ctx.end.proj_lat : ctx.start.proj_lat;
                uint32_t tLon = is_fwd ? ctx.end.proj_lon : ctx.start.proj_lon;
                active_heap.push(new_g + heuristic_time_10ms(g_nodes[v].lat_e7, g_nodes[v].lon_e7, tLat, tLon, mode), v);

                // Check for front intersection
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
}

/**
 * PHASE 3: RECONSTRUCT
 * Traces parents from the meeting node in both directions to build the final step array.
 */
jobjectArray reconstruct_path(JNIEnv* env, int mode, const RoutingContext& ctx) {
    LOGD("[RECONSTRUCT] Building path starting from meeting node %u", ctx.meeting_node);

    std::vector<uint32_t> path;
    // Trace Forward: meeting -> start
    for (uint32_t c = ctx.meeting_node; c != 0xFFFFFFFF; c = g_scratchpad[c].p_fwd) {
        path.push_back(c);
        if (path.size() > 500000) break;
    }
    std::reverse(path.begin(), path.end());
    // Trace Backward: meeting -> end
    for (uint32_t c = g_scratchpad[ctx.meeting_node].p_bwd; c != 0xFFFFFFFF; c = g_scratchpad[c].p_bwd) {
        path.push_back(c);
        if (path.size() > 1000000) break;
    }

    LOGD("[RECONSTRUCT] Path finalized with %zu raw nodes.", path.size());

    jclass stepClass = env->FindClass("com/vayunmathur/maps/OfflineRouter$RawStep");
    jmethodID stepCtor = env->GetMethodID(stepClass, "<init>", "(ILjava/lang/String;JJ[D)V");
    std::vector<jobject> steps;

    double last_bearing = get_bearing(ctx.start.proj_lat, ctx.start.proj_lon, g_nodes[path[0]].lat_e7, g_nodes[path[0]].lon_e7);

    size_t idx = 0;
    while (idx < path.size() - 1) {
        uint32_t u = path[idx], v = path[idx+1], current_name_off = 0xFFFFFFFF;
        uint64_t sp = get_ptr(u), ep = get_end_ptr(u);
        for (uint64_t k = sp; k < ep; ++k) if (g_edges[k].target == v) { current_name_off = g_edges[k].name_offset; break; }

        std::vector<double> geom;
        uint64_t dist = 0, time = 0;
        size_t step_start = idx;

        while (idx < path.size() - 1) {
            uint32_t cu = path[idx], cv = path[idx+1], name_off = 0xFFFFFFFF, d = 0, type = 0;
            uint64_t s = get_ptr(cu), e = get_end_ptr(cu);
            for (uint64_t k = s; k < e; ++k) if (g_edges[k].target == cv) { name_off = g_edges[k].name_offset; d = g_edges[k].dist_mm; type = g_edges[k].type; break; }
            if (name_off != current_name_off && step_start != idx) break;
            geom.push_back(g_nodes[cu].lon_e7 / 1e7); geom.push_back(g_nodes[cu].lat_e7 / 1e7);
            dist += d; time += get_edge_time_10ms(d, type, mode); idx++;
        }
        geom.push_back(g_nodes[path[idx]].lon_e7 / 1e7); geom.push_back(g_nodes[path[idx]].lat_e7 / 1e7);

        double cur_bearing = get_bearing(g_nodes[path[step_start]].lat_e7, g_nodes[path[step_start]].lon_e7, g_nodes[path[idx]].lat_e7, g_nodes[path[idx]].lon_e7);
        int maneuver = get_maneuver(last_bearing, cur_bearing);
        last_bearing = cur_bearing;

        jdoubleArray jGeom = env->NewDoubleArray(geom.size());
        env->SetDoubleArrayRegion(jGeom, 0, geom.size(), geom.data());
        jstring jName = env->NewStringUTF(current_name_off == 0xFFFFFFFF ? "Unnamed Road" : g_road_names + current_name_off);
        steps.push_back(env->NewObject(stepClass, stepCtor, maneuver, jName, (jlong)dist, (jlong)time, jGeom));
        env->DeleteLocalRef(jName); env->DeleteLocalRef(jGeom);
    }

    jobjectArray res = env->NewObjectArray(steps.size(), stepClass, nullptr);
    for (size_t i = 0; i < steps.size(); ++i) { env->SetObjectArrayElement(res, i, steps[i]); env->DeleteLocalRef(steps[i]); }

    LOGD("[RECONSTRUCT] Complete. Generated %zu turn-by-turn steps.", steps.size());
    return res;
}

// --- JNI INTERFACE ---

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vayunmathur_maps_OfflineRouter_init(JNIEnv* env, jobject thiz, jstring base_path) {
    const char* path = env->GetStringUTFChars(base_path, 0);
    std::string base(path);
    auto m_file = [&](std::string p, size_t& s) -> void* {
        int fd = open(p.c_str(), O_RDONLY); if (fd < 0) return nullptr;
        s = lseek(fd, 0, SEEK_END);
        void* a = mmap(NULL, s, PROT_READ, MAP_SHARED, fd, 0);
        close(fd); return (a == MAP_FAILED) ? nullptr : a;
    };
    size_t s1, s2, s3, s4;
    g_nodes = (NodeMaster*)m_file(base + "/nodes_master.bin", s1);
    g_edges = (Edge*)m_file(base + "/edges.bin", s2);
    g_spatial = (uint64_t*)m_file(base + "/nodes_spatial.bin", s3);
    g_road_names = (char*)m_file(base + "/road_names.bin", s4);
    if (g_nodes) g_node_count = s1 / sizeof(NodeMaster);

    for (int i = 0; i < 4096; ++i) {
        double lat_deg = ((double)((int64_t)(i - 2048) << 19)) / 1e7;
        g_lon_to_mm_scale[i] = (uint32_t)((111139000.0 / 1e7) * cos(lat_deg * DEG_TO_RAD) * 1024.0);
    }

    auto calc_scale = [](double speed_m_s) { return (uint64_t)((100.0 / (speed_m_s * 1000.0)) * 4294967296.0); };
    g_time_scale_fixed[WALK] = calc_scale(WALK_SPEED_M_S);
    g_time_scale_fixed[BICYCLE] = calc_scale(BICYCLE_SPEED_M_S);

    for (int m = 0; m < 4; ++m) {
        double base_speed = (m == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S;
        for (int r = 0; r < 16; ++r) {
            float mod = (r >= 3) ? 1.0f : 0.001f;
            g_edge_time_multipliers[m][r] = (uint64_t)((100.0 / (base_speed * 1000.0 * mod)) * 4294967296.0);
        }
    }

    LOGD("[INIT] Offline Router initialized with %zu nodes.", g_node_count);
    env->ReleaseStringUTFChars(base_path, path);
    return (g_nodes != nullptr);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vayunmathur_maps_OfflineRouter_findRouteNative(JNIEnv* env, jobject thiz, jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode) {
    RoutingContext ctx;

    LOGD("[ROUTE] Request: (%f, %f) -> (%f, %f), Mode: %d", sLat, sLon, eLat, eLon, mode);

    if (!prepare_routing(sLat, sLon, eLat, eLon, mode, ctx)) {
        LOGE("[ROUTE] Failed to snap start or end points.");
        return nullptr;
    }

    perform_search_loop(mode, ctx);

    if (ctx.meeting_node == 0xFFFFFFFF) {
        LOGE("[ROUTE] No route found after %d iterations.", ctx.iterations);
        return nullptr;
    }

    LOGD("[ROUTE] Path found! Iterations: %d, Meeting Node: %u, Time: %u units",
         ctx.iterations, ctx.meeting_node, ctx.best_total_time);

    return reconstruct_path(env, mode, ctx);
}