#include <jni.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <mutex>
#include <android/log.h>

#include "scratchpad.h"
#include "radix_heap.h"

#define LOG_TAG "OfflineRouterNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// User-specified Travel Mode mapping
enum TravelMode { DRIVING = 0, WALK = 2, BICYCLE = 3 };

// Road types based on OpenStreetMap tags
enum RoadType {
    MOTORWAY = 1, TRUNK = 2, PRIMARY = 3, SECONDARY = 4, TERTIARY = 5,
    UNCLASSIFIED = 6, RESIDENTIAL = 7, SERVICE = 8, LIVING_STREET = 9,
    PEDESTRIAN = 10, TRACK = 11, FOOTWAY = 12, CYCLEWAY = 13, PATH = 14, STEPS = 15
};

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
    uint8_t speed_limit;
};
#pragma pack(pop)

// --- GLOBALS ---
const int NUM_ZONES = 64;
NodeMaster* g_node_zones[NUM_ZONES] = {nullptr};
Edge* g_edge_zones[NUM_ZONES] = {nullptr};
size_t g_edge_count_in_zone[NUM_ZONES] = {0};

uint32_t g_zone_offsets[NUM_ZONES + 1] = {0};
char* g_road_names = nullptr;
size_t g_road_names_size = 0;

uint64_t g_time_scale_fixed[4]; // Heuristic scales (10ms units per mm)
uint64_t g_edge_time_multipliers[4][16]; // Speed-to-time conversion factors for WALK/BIKE/DRIVING(fallback)
const double WALK_SPEED_M_S = 4.5 / 3.6;
const double BICYCLE_SPEED_M_S = 16.0 / 3.6;
const double DEG_TO_RAD = M_PI / 180.0;

uint32_t g_lon_to_mm_scale[4096];

static RoutingScratchpad g_scratchpad;
static TrafficPageTable g_traffic_zones[NUM_ZONES];
static RadixHeap g_heap; // Only one heap needed for unidirectional

static std::vector<uint32_t> g_requested_squares; // Packed (lat_idx << 16 | lon_idx)
static std::mutex g_traffic_mutex;

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

bool is_square_requested(int32_t lat_idx, int32_t lon_idx) {
    uint32_t packed = ((uint32_t)(lat_idx + 360) << 16) | (uint32_t)(lon_idx + 720);
    std::lock_guard<std::mutex> lock(g_traffic_mutex);
    return std::find(g_requested_squares.begin(), g_requested_squares.end(), packed) != g_requested_squares.end();
}

void mark_square_requested(int32_t lat_idx, int32_t lon_idx) {
    uint32_t packed = ((uint32_t)(lat_idx + 360) << 16) | (uint32_t)(lon_idx + 720);
    std::lock_guard<std::mutex> lock(g_traffic_mutex);
    g_requested_squares.push_back(packed);
}

void ensure_traffic_loaded(JNIEnv* env, jobject thiz, int32_t lat_e7, int32_t lon_e7) {
    int32_t lat_idx = (int32_t)floor(lat_e7 * 1e-7);
    int32_t lon_idx = (int32_t)floor(lon_e7 * 1e-7);

    if (is_square_requested(lat_idx, lon_idx)) return;
    mark_square_requested(lat_idx, lon_idx);

    uint64_t spatial = latlng_to_spatial(lat_e7 * 1e-7, lon_e7 * 1e-7);
    int zone_id = (int)((spatial >> 58) & 0x3F);

    double min_lat = (double)lat_idx;
    double min_lon = (double)lon_idx;
    double max_lat = min_lat + 1.0;
    double max_lon = min_lon + 1.0;

    jclass clazz = env->GetObjectClass(thiz);
    jmethodID method = env->GetMethodID(clazz, "fetchTrafficData", "(DDDDI)V");
    if (method) {
        env->CallVoidMethod(thiz, method, min_lat, min_lon, max_lat, max_lon, zone_id);
    }
}

// --- ROAD PERMISSIONS ---

inline bool is_mode_allowed(uint8_t road_type, int mode) {
    if (mode == DRIVING) {
        return (road_type >= MOTORWAY && road_type <= LIVING_STREET);
    }
    return (road_type >= MOTORWAY && road_type <= STEPS);
}

inline int get_zone_for_id(uint32_t global_id) {
    for (int i = 0; i < 64; ++i) {
        if (global_id < g_zone_offsets[i+1]) return i;
    }
    return -1;
}

inline bool is_zone_mapped(int zone) {
    return zone >= 0 && zone < NUM_ZONES && g_node_zones[zone] != nullptr;
}

inline const NodeMaster& get_node(uint32_t global_id) {
    int zone = get_zone_for_id(global_id);
    if (zone < 0 || !g_node_zones[zone]) {
        static NodeMaster null_node = {0,0,0,0};
        return null_node;
    }
    return g_node_zones[zone][global_id - g_zone_offsets[zone]];
}

// --- GEOMETRY ---

__attribute__((always_inline)) inline uint32_t fast_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    int64_t dlat = std::abs((int64_t)lat1_e7 - lat2_e7);
    int64_t dlon = std::abs((int64_t)lon1_e7 - lon2_e7);
    int64_t dy_mm = (dlat * 111319) / 10;
    auto scale_idx = (uint32_t)(((lat1_e7 + lat2_e7) / 2 >> 19) + 2048);
    uint32_t scale = g_lon_to_mm_scale[scale_idx & 4095];
    int64_t dx_mm = (dlon * scale) >> 10;
    uint64_t max_v = (dx_mm > dy_mm) ? dx_mm : dy_mm;
    uint64_t min_v = (dx_mm > dy_mm) ? dy_mm : dx_mm;
    return (uint32_t)(max_v - (max_v >> 5) + (min_v >> 1) - (min_v >> 3));
}

inline uint32_t accurate_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    const double R = 6371000800.0;
    double phi1 = (lat1_e7 * 1e-7) * DEG_TO_RAD;
    double phi2 = (lat2_e7 * 1e-7) * DEG_TO_RAD;
    double delta_phi = (lat2_e7 - lat1_e7) * 1e-7 * DEG_TO_RAD;
    double delta_lambda = (lon2_e7 - lon1_e7) * 1e-7 * DEG_TO_RAD;
    double s_dphi = sin(delta_phi / 2.0);
    double s_dlamb = sin(delta_lambda / 2.0);
    double a = s_dphi * s_dphi + cos(phi1) * cos(phi2) * s_dlamb * s_dlamb;
    return (uint32_t)(R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a)));
}

inline uint32_t get_edge_time_10ms(int zone_id, uint32_t local_edge_id, uint32_t dist_mm, uint8_t type, uint8_t limit, int mode) {
    if (mode == DRIVING) {
        uint8_t traffic_speed = 0;
        if (zone_id >= 0 && zone_id < NUM_ZONES) {
            traffic_speed = g_traffic_zones[zone_id].get_speed(local_edge_id);
        }
        uint8_t effective_limit = (traffic_speed > 0) ? traffic_speed : limit;
        if (effective_limit > 0) {
            double speed_m_s = (double)effective_limit / 3.6;
            return (uint32_t)((double)dist_mm / (speed_m_s * 10.0));
        }
    }
    uint64_t multiplier = g_edge_time_multipliers[mode & 0x3][type & 0xF];
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

// --- CORE ROUTING ---

struct SnappedEdge {
    uint32_t nodeA, nodeB;
    int32_t proj_lat, proj_lon;
    uint32_t distA_mm, distB_mm;
    uint8_t type;
    uint8_t speed_limit;
    uint32_t name_offset;
};

struct RoutingContext {
    SnappedEdge start, end;
    uint32_t target_node = 0xFFFFFFFF;
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

SnappedEdge find_nearest_edge(double lat, double lon, int mode) {
    uint64_t target_spatial = latlng_to_spatial(lat, lon);
    int32_t pLat = lat * 1e7, pLon = lon * 1e7;
    SnappedEdge best = {0xFFFFFFFF, 0xFFFFFFFF, pLat, pLon, 0, 0, 0, 0, 0xFFFFFFFF};
    uint32_t minSnapDist = 0xFFFFFFFF;
    int target_zone = (int)((target_spatial >> 58) & 0x3F);

    for (int z = std::max(0, target_zone - 2); z <= std::min(NUM_ZONES - 1, target_zone + 2); ++z) {
        if (!is_zone_mapped(z)) continue;
        uint32_t zone_node_count = g_zone_offsets[z + 1] - g_zone_offsets[z];
        uint32_t low = 0, high = zone_node_count - 1, local_center = 0;
        while (low <= high) {
            uint32_t mid = low + (high - low) / 2;
            if (g_node_zones[z][mid].spatial_id < target_spatial) low = mid + 1;
            else { local_center = mid; if (mid == 0) break; high = mid - 1; }
        }
        int window = 4000;
        for (int i = std::max(0, (int)local_center - window); i <= std::min((int)zone_node_count - 1, (int)local_center + window); ++i) {
            uint32_t u_global = g_zone_offsets[z] + i;
            const auto& node_u = g_node_zones[z][i];
            uint32_t e_ptr = (i + 1 < zone_node_count) ? g_node_zones[z][i+1].edge_ptr : (uint32_t)g_edge_count_in_zone[z];
            for (uint32_t j = node_u.edge_ptr; j < e_ptr; ++j) {
                Edge& e = g_edge_zones[z][j];
                if (!is_mode_allowed(e.type, mode)) continue;
                int zone_v = get_zone_for_id(e.target);
                if (!is_zone_mapped(zone_v)) continue;
                const auto& node_v = g_node_zones[zone_v][e.target - g_zone_offsets[zone_v]];
                Projection p = get_projection(pLat, pLon, node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
                if (p.dist_mm < minSnapDist) {
                    minSnapDist = p.dist_mm;
                    best.nodeA = u_global; best.nodeB = e.target; best.proj_lat = p.lat_e7; best.proj_lon = p.lon_e7;
                    best.distA_mm = fast_dist_mm(p.lat_e7, p.lon_e7, node_u.lat_e7, node_u.lon_e7);
                    best.distB_mm = fast_dist_mm(p.lat_e7, p.lon_e7, node_v.lat_e7, node_v.lon_e7);
                    best.type = e.type; best.speed_limit = e.speed_limit; best.name_offset = e.name_offset;
                }
            }
        }
    }
    return best;
}

bool prepare_routing(JNIEnv* env, jobject thiz, double sLat, double sLon, double eLat, double eLon, int mode, RoutingContext& ctx) {
    g_scratchpad.reset(); g_heap.clear();
    ctx.iterations = 0; ctx.target_node = 0xFFFFFFFF;
    ctx.start = find_nearest_edge(sLat, sLon, mode);
    ctx.end = find_nearest_edge(eLat, eLon, mode);
    if (ctx.start.nodeA == 0xFFFFFFFF || ctx.end.nodeA == 0xFFFFFFFF) return false;
    if (mode == DRIVING) ensure_traffic_loaded(env, thiz, ctx.start.proj_lat, ctx.start.proj_lon);
    auto push = [&](uint32_t node, uint32_t travel_dist_mm) {
        uint32_t g = get_edge_time_10ms(-1, 0, travel_dist_mm, ctx.start.type, ctx.start.speed_limit, mode);
        auto& entry = g_scratchpad[node]; entry.g_fwd = g;
        const auto& n_data = get_node(node);
        uint32_t h = heuristic_time_10ms(n_data.lat_e7, n_data.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode);
        g_heap.push(g + h, node);
    };
    push(ctx.start.nodeA, ctx.start.distA_mm); push(ctx.start.nodeB, ctx.start.distB_mm);
    return true;
}

void perform_search_loop(JNIEnv* env, jobject thiz, int mode, RoutingContext& ctx) {
    while (!g_heap.empty() && ctx.iterations < 50000000) {
        ctx.iterations++;
        uint32_t u = g_heap.pop();
        if (u == ctx.end.nodeA || u == ctx.end.nodeB) { ctx.target_node = u; break; }
        auto& entry_u = g_scratchpad[u];
        uint32_t u_g = entry_u.g_fwd;
        int zone_u = get_zone_for_id(u);
        if (zone_u < 0 || !g_node_zones[zone_u]) continue;
        const auto& n_u = g_node_zones[zone_u][u - g_zone_offsets[zone_u]];
        if (mode == DRIVING) ensure_traffic_loaded(env, thiz, n_u.lat_e7, n_u.lon_e7);
        uint32_t s = n_u.edge_ptr;
        uint32_t node_idx = u - g_zone_offsets[zone_u];
        uint32_t node_count = g_zone_offsets[zone_u + 1] - g_zone_offsets[zone_u];
        uint32_t e_ptr = (node_idx + 1 < node_count) ? g_node_zones[zone_u][node_idx + 1].edge_ptr : (uint32_t)g_edge_count_in_zone[zone_u];
        for (uint32_t i = s; i < e_ptr; ++i) {
            Edge& edge = g_edge_zones[zone_u][i];
            if (!is_mode_allowed(edge.type, mode)) continue;
            uint32_t travel = get_edge_time_10ms(zone_u, i, edge.dist_mm, edge.type, edge.speed_limit, mode);
            uint32_t v = edge.target;
            uint32_t new_g = u_g + travel;
            auto& entry_v = g_scratchpad[v];
            if (new_g < entry_v.g_fwd) {
                entry_v.g_fwd = new_g; entry_v.p_fwd = u;
                const auto& n_v = get_node(v);
                uint32_t h = heuristic_time_10ms(n_v.lat_e7, n_v.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode);
                g_heap.push(new_g + h, v);
            }
        }
    }
}

jobjectArray reconstruct_path(JNIEnv* env, int mode, const RoutingContext& ctx) {
    std::vector<uint32_t> path;
    uint32_t curr = ctx.target_node;
    uint32_t safety = 0;
    while (curr != 0xFFFFFFFF && safety < 1000000) {
        path.push_back(curr); curr = g_scratchpad[curr].p_fwd; safety++;
    }
    std::reverse(path.begin(), path.end());
    if (path.empty()) return nullptr;
    struct StepData {
        uint32_t name_off; uint64_t dist_mm = 0; uint64_t time_10ms = 0;
        std::vector<double> coords; int maneuver = 0;
    };
    std::vector<StepData> steps; double last_bearing = 0;
    int total_edges = 0;
    int traffic_edges = 0;

    for (size_t i = 0; i < path.size() - 1; ++i) {
        uint32_t u = path[i], v = path[i+1];
        int z_u = get_zone_for_id(u); if (z_u < 0) continue;
        const auto& node_u = g_node_zones[z_u][u - g_zone_offsets[z_u]];
        const auto& node_v = get_node(v);
        uint32_t current_name_off = 0xFFFFFFFF; uint8_t edge_type = 7, edge_limit = 0; uint32_t edge_dist_mm = 0;
        uint32_t s = node_u.edge_ptr;
        uint32_t node_idx = u - g_zone_offsets[z_u];
        uint32_t node_count = g_zone_offsets[z_u + 1] - g_zone_offsets[z_u];
        uint32_t e_ptr = (node_idx + 1 < node_count) ? g_node_zones[z_u][node_idx + 1].edge_ptr : (uint32_t)g_edge_count_in_zone[z_u];
        uint32_t resolved_edge_idx = 0xFFFFFFFF;
        for (uint32_t k = s; k < e_ptr; ++k) {
            if (g_edge_zones[z_u][k].target == v) {
                edge_type = g_edge_zones[z_u][k].type; edge_limit = g_edge_zones[z_u][k].speed_limit;
                current_name_off = g_edge_zones[z_u][k].name_offset; edge_dist_mm = g_edge_zones[z_u][k].dist_mm;
                resolved_edge_idx = k; break;
            }
        }

        total_edges++;
        if (resolved_edge_idx != 0xFFFFFFFF && g_traffic_zones[z_u].get_speed(resolved_edge_idx) > 0) {
            traffic_edges++;
        }

        if (edge_dist_mm == 0) edge_dist_mm = accurate_dist_mm(node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
        uint32_t edge_time_10ms = get_edge_time_10ms(z_u, resolved_edge_idx, edge_dist_mm, edge_type, edge_limit, mode);
        double current_bearing = get_bearing(node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
        if (steps.empty() || current_name_off != steps.back().name_off) {
            int maneuver = steps.empty() ? 0 : get_maneuver(last_bearing, current_bearing);
            steps.push_back({current_name_off, 0, 0, {}, maneuver});
            steps.back().coords.push_back(node_u.lon_e7 / 1e7); steps.back().coords.push_back(node_u.lat_e7 / 1e7);
        }
        steps.back().dist_mm += edge_dist_mm; steps.back().time_10ms += edge_time_10ms;
        steps.back().coords.push_back(node_v.lon_e7 / 1e7); steps.back().coords.push_back(node_v.lat_e7 / 1e7);
        last_bearing = current_bearing;
    }

    if (total_edges > 0) {
        double percent = (double)traffic_edges / total_edges * 100.0;
        LOGD("Path traffic stats: %d/%d edges have traffic data (%.2f%%)", traffic_edges, total_edges, percent);
    }

    jclass stepClass = env->FindClass("com/vayunmathur/maps/util/OfflineRouter$RawStep");
    jmethodID stepCtor = env->GetMethodID(stepClass, "<init>", "(ILjava/lang/String;JJ[D)V");
    jobjectArray res = env->NewObjectArray(steps.size(), stepClass, nullptr);
    for (size_t i = 0; i < steps.size(); ++i) {
        const char* name_ptr = (steps[i].name_off < g_road_names_size) ? (g_road_names + steps[i].name_off) : "Unknown Road";
        jstring jName = env->NewStringUTF(name_ptr); jdoubleArray jGeom = env->NewDoubleArray(steps[i].coords.size());
        env->SetDoubleArrayRegion(jGeom, 0, steps[i].coords.size(), steps[i].coords.data());
        jobject stepObj = env->NewObject(stepClass, stepCtor, (jint)steps[i].maneuver, jName, (jlong)steps[i].dist_mm, (jlong)steps[i].time_10ms, jGeom);
        env->SetObjectArrayElement(res, i, stepObj);
        env->DeleteLocalRef(jName); env->DeleteLocalRef(jGeom); env->DeleteLocalRef(stepObj);
    }
    return res;
}

// --- JNI INTERFACE ---

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_init(JNIEnv* env, jobject thiz, jstring base_path) {
    const char* path_raw = env->GetStringUTFChars(base_path, nullptr); std::string base(path_raw);
    if (!base.empty() && base.back() != '/') base += "/";
    auto m_file = [&](const std::string& p, size_t& s) -> void* {
        s = 0; int fd = open(p.c_str(), O_RDONLY); if (fd < 0) return nullptr;
        s = lseek(fd, 0, SEEK_END); void* a = mmap(nullptr, s, PROT_READ, MAP_SHARED, fd, 0); close(fd);
        return (a == MAP_FAILED) ? nullptr : a;
    };
    size_t s_meta; uint32_t* meta = (uint32_t*)m_file(base + "metadata.bin", s_meta);
    if (!meta) { env->ReleaseStringUTFChars(base_path, path_raw); return false; }
    bool has_edge_metadata = (s_meta >= NUM_ZONES * 2 * sizeof(uint32_t));
    g_zone_offsets[0] = 0;
    if (has_edge_metadata) {
        for (int i = 0; i < NUM_ZONES; ++i) g_zone_offsets[i+1] = g_zone_offsets[i] + meta[i*2];
    } else {
        for (int i = 0; i < NUM_ZONES; ++i) g_zone_offsets[i+1] = g_zone_offsets[i] + meta[i];
    }
    for (int i = 0; i < NUM_ZONES; ++i) {
        uint32_t node_count = has_edge_metadata ? meta[i*2] : meta[i];
        if (node_count == 0) continue;
        size_t s_n, s_e;
        g_node_zones[i] = (NodeMaster*)m_file(base + "nodes_zone_" + std::to_string(i) + ".bin", s_n);
        g_edge_zones[i] = (Edge*)m_file(base + "edges_zone_" + std::to_string(i) + ".bin", s_e);
        g_edge_count_in_zone[i] = s_e / sizeof(Edge);
    }
    munmap(meta, s_meta);
    size_t s_r; g_road_names = (char*)m_file(base + "road_names.bin", s_r); g_road_names_size = s_r;
    for (int i = 0; i < 4096; ++i) {
        double lat_deg = ((double)((int64_t)(i - 2048) << 19)) / 1e7;
        g_lon_to_mm_scale[i] = (uint32_t)((111139000.0 / 1e7) * cos(lat_deg * DEG_TO_RAD) * 1024.0);
    }
    auto calc_scale = [](double speed_m_s) { return (uint64_t)((100.0 / (speed_m_s * 1000.0)) * 4294967296.0); };
    g_time_scale_fixed[WALK] = calc_scale(WALK_SPEED_M_S); g_time_scale_fixed[BICYCLE] = calc_scale(BICYCLE_SPEED_M_S);
    g_time_scale_fixed[DRIVING] = calc_scale(105.0 / 3.6);
    for (int m = 0; m < 4; ++m) {
        for (int r = 0; r < 16; ++r) {
            double speed_m_s = 1.0;
            if (m == DRIVING) {
                switch(r) {
                    case MOTORWAY: speed_m_s = 105.0/3.6; break; case TRUNK: speed_m_s = 85.0/3.6; break;
                    case PRIMARY: speed_m_s = 65.0/3.6; break; case SECONDARY: speed_m_s = 55.0/3.6; break;
                    case TERTIARY: speed_m_s = 45.0/3.6; break; default: speed_m_s = 30.0/3.6;
                }
            } else { speed_m_s = (m == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S; }
            g_edge_time_multipliers[m][r] = (uint64_t)((100.0 / (speed_m_s * 1000.0)) * 4294967296.0);
        }
    }
    env->ReleaseStringUTFChars(base_path, path_raw); return true;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_findRouteNative(JNIEnv* env, jobject thiz, jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode) {
    RoutingContext ctx; if (!prepare_routing(env, thiz, sLat, sLon, eLat, eLon, mode, ctx)) return nullptr;
    perform_search_loop(env, thiz, mode, ctx);
    if (ctx.target_node == 0xFFFFFFFF) return nullptr;
    return reconstruct_path(env, mode, ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_updateTrafficNative(JNIEnv* env, jobject thiz, jint zone_id, jintArray edge_ids, jbyteArray speeds) {
    jsize len = env->GetArrayLength(edge_ids); jint* ids_ptr = env->GetIntArrayElements(edge_ids, nullptr);
    jbyte* speeds_ptr = env->GetByteArrayElements(speeds, nullptr);
    if (zone_id >= 0 && zone_id < NUM_ZONES) {
        std::lock_guard<std::mutex> lock(g_traffic_mutex);
        for (jsize i = 0; i < len; i++) {
            uint32_t local_id = (uint32_t)ids_ptr[i]; uint8_t speed = (uint8_t)speeds_ptr[i];
            g_traffic_zones[zone_id].set_speed(local_id, speed);
        }
    }
    env->ReleaseIntArrayElements(edge_ids, ids_ptr, JNI_ABORT); env->ReleaseByteArrayElements(speeds, speeds_ptr, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_ensureTrafficLoadedNative(JNIEnv* env, jobject thiz, jdouble lat, jdouble lon) {
    ensure_traffic_loaded(env, thiz, (int32_t)(lat * 1e7), (int32_t)(lon * 1e7));
}
