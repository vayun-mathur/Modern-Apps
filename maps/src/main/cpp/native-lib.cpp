#include <jni.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <vector>
#include <queue>
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

// Constants
uint64_t g_time_scale_fixed[4];
uint64_t g_edge_time_multipliers[4][16];
const double WALK_SPEED_M_S = 4.5 / 3.6;
const double BICYCLE_SPEED_M_S = 16.0 / 3.6;
const uint64_t NO_EDGES_SENTINEL = (1ULL << 40) - 1;
const double EARTH_RADIUS_MM = 6371000.0 * 1000.0;
const double DEG_TO_RAD = M_PI / 180.0;

// Global Data Pointers
NodeMaster* g_nodes = nullptr;
Edge* g_edges = nullptr;
uint64_t* g_spatial = nullptr;
char* g_road_names = nullptr;
size_t g_node_count = 0;

// Optimization: Cosine Lookup Table
uint32_t g_lon_to_mm_scale[4096];

// Speed/Modifier Lookup Table: [Mode][RoadType]
// Modes: 2 (WALK), 3 (BICYCLE)
// RoadTypes: 0-15
float g_speed_modifiers[4][16];

// --- CORE UTILITIES ---

inline uint64_t get_ptr(uint32_t idx) {
    return ((uint64_t)g_nodes[idx].edge_ptr_high << 32) | g_nodes[idx].edge_ptr_low;
}

inline uint64_t get_end_ptr(uint32_t idx) {
    return get_ptr(idx+1);
}

// --- GEOMETRY HELPERS ---

uint32_t haversine_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    double lat1 = (lat1_e7 / 1e7) * DEG_TO_RAD;
    double lon1 = (lon1_e7 / 1e7) * DEG_TO_RAD;
    double lat2 = (lat2_e7 / 1e7) * DEG_TO_RAD;
    double lon2 = (lon2_e7 / 1e7) * DEG_TO_RAD;
    double a = sin((lat2 - lat1) / 2) * sin((lat2 - lat1) / 2) + cos(lat1) * cos(lat2) * sin((lon2 - lon1) / 2) * sin((lon2 - lon1) / 2);
    return (uint32_t)(2 * atan2(sqrt(a), sqrt(1 - a)) * EARTH_RADIUS_MM);
}

__attribute__((always_inline)) inline uint32_t fast_dist_approx(int64_t dx, int64_t dy) {
    // Branchless absolute: std::abs is fine, but compiler usually emits 'abs' instruction
    uint64_t x = (dx < 0) ? -dx : dx;
    uint64_t y = (dy < 0) ? -dy : dy;

    uint64_t max_v = (x > y) ? x : y;
    uint64_t min_v = (x > y) ? y : x;

    // 0.96875 * max + 0.375 * min
    // (max - max/32) + (min/2 - min/8)
    return (uint32_t)(max_v - (max_v >> 5) + (min_v >> 1) - (min_v >> 3));
}

__attribute__((always_inline)) inline uint32_t fast_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    // Branchless absolute difference
    int64_t dlat = (lat1_e7 > lat2_e7) ? (lat1_e7 - lat2_e7) : (lat2_e7 - lat1_e7);
    int64_t dlon = (lon1_e7 > lon2_e7) ? (lon1_e7 - lon2_e7) : (lon2_e7 - lon1_e7);

    // Latitude scaling (dy = dlat * 11.125)
    // Error is ~0.1%, completely safe for routing
    int64_t dy_mm = (dlat << 3) + (dlat << 1) + dlat + (dlat >> 3);

    // Longitude scaling: Shift instead of Magic Division
    // Add 2048 to offset the negative latitude range
    uint32_t scale_idx = (uint32_t)((lat1_e7 >> 19) + 2048);
    uint32_t scale = g_lon_to_mm_scale[scale_idx & 4095]; // & 4095 is a safety mask

    int64_t dx_mm = (dlon * scale) >> 10;

    return fast_dist_approx(dx_mm, dy_mm);
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

inline uint32_t get_edge_time_10ms(uint32_t dist_mm, uint8_t road_type, int mode) {
    // mode & 0x3 and road_type & 0xF ensure we stay within bounds for the CPU
    uint64_t multiplier = g_edge_time_multipliers[mode & 0x3][road_type & 0xF];

    // Perform 64-bit multiplication and shift down by 32 bits
    return (uint32_t)(((uint64_t)dist_mm * multiplier) >> 32);
}

inline uint32_t heuristic_time_10ms(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2, int mode) {
    uint32_t dist_mm = fast_dist_mm(lat1, lon1, lat2, lon2);

    // Time = (dist_mm * scale) >> 32
    // This is essentially: dist_mm * (100 / speed)
    uint64_t scaled_time = (uint64_t)dist_mm * g_time_scale_fixed[mode & 0x3];

    return (uint32_t)(scaled_time >> 32);
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

struct Projection { int32_t lat_e7, lon_e7; uint32_t dist_mm; };

Projection get_projection(int32_t px, int32_t py, int32_t x1, int32_t y1, int32_t x2, int32_t y2) {
    double dx = (double)x2 - x1, dy = (double)y2 - y1;
    double mag_sq = dx * dx + dy * dy;
    double t = (mag_sq == 0) ? 0 : ((double)(px - x1) * dx + (double)(py - y1) * dy) / mag_sq;
    t = std::max(0.0, std::min(1.0, t));
    int32_t proj_lat = (int32_t)(x1 + t * dx), proj_lon = (int32_t)(y1 + t * dy);
    return {proj_lat, proj_lon, haversine_mm(px, py, proj_lat, proj_lon)};
}

struct SnappedEdge {
    uint32_t nodeA, nodeB;
    int32_t proj_lat, proj_lon;
    uint32_t distA_mm, distB_mm;
    uint8_t type;
    uint32_t name_offset;
};

SnappedEdge find_nearest_edge(double lat, double lon, int mode, const char* label) {
    uint64_t target_spatial = latlng_to_spatial(lat, lon);
    int32_t pLat = (int32_t)(lat * 1e7), pLon = (int32_t)(lon * 1e7);
    auto it = std::lower_bound(g_spatial, g_spatial + g_node_count, target_spatial);
    intptr_t center = std::distance(g_spatial, it);

    SnappedEdge best = {0xFFFFFFFF, 0xFFFFFFFF, pLat, pLon, 0, 0, 0, 0xFFFFFFFF};
    uint32_t minSnapDist = 0xFFFFFFFF;

    intptr_t window = 800;
    for (intptr_t i = std::max((intptr_t)0, center - window); i <= std::min((intptr_t)g_node_count - 1, center + window); ++i) {
        uint32_t u = i;
        uint64_t s = get_ptr(u);
        uint64_t e_ptr = get_end_ptr(u);
        for (uint64_t j = s; j < e_ptr; ++j) {
            Edge& e = g_edges[j];
            // No longer checking is_accessible here; we want to find the nearest edge regardless of type penalty
            Projection p = get_projection(pLat, pLon, g_nodes[u].lat_e7, g_nodes[u].lon_e7, g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7);
            if (p.dist_mm < minSnapDist) {
                minSnapDist = p.dist_mm;
                best.nodeA = u; best.nodeB = e.target; best.proj_lat = p.lat_e7; best.proj_lon = p.lon_e7;
                best.distA_mm = haversine_mm(p.lat_e7, p.lon_e7, g_nodes[u].lat_e7, g_nodes[u].lon_e7);
                best.distB_mm = haversine_mm(p.lat_e7, p.lon_e7, g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7);
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

// --- JNI ---

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

    // Initialize Cosine Lookup Table
    for (int i = 0; i < 4096; ++i) {
        // Reverse the index to get latitude
        // (index << 19) gives the approximate E7 value
        double lat_e7 = (double)((int64_t)(i - 2048) << 19);
        double lat_deg = lat_e7 / 1e7;

        if (lat_deg < -90.0) lat_deg = -90.0;
        if (lat_deg > 90.0) lat_deg = 90.0;

        double cos_val = cos(lat_deg * (M_PI / 180.0));
        // Keep the 10-bit fractional precision
        g_lon_to_mm_scale[i] = (uint32_t)((111139000.0 / 1e7) * cos_val * 1024.0);
    }

    // Initialize Speed Modifier 2D Array
    // Default penalty for "inaccessible" is 0.001x speed (1000x time)
    for (int m = 0; m < 4; ++m) {
        for (int r = 0; r < 16; ++r) g_speed_modifiers[m][r] = 0.001f;
    }

    // WALK MODIFIERS (Mode 2)
    // Road types 3-15 are generally walking friendly. 0-2 (Motorways etc) are penalized.
    for (int r = 3; r <= 15; ++r) g_speed_modifiers[WALK][r] = 1.0f;
    g_speed_modifiers[WALK][12] = 0.8f; // Path/Grass
    g_speed_modifiers[WALK][10] = 0.8f; // Living Street
    g_speed_modifiers[WALK][15] = 1.5f; // Stairs

    // BICYCLE MODIFIERS (Mode 3)
    // Road types 3-14 are generally biking friendly. 15 (Stairs) and 0-2 penalized.
    for (int r = 3; r <= 14; ++r) g_speed_modifiers[BICYCLE][r] = 1.0f;
    g_speed_modifiers[BICYCLE][13] = 0.7f; // Trail
    g_speed_modifiers[BICYCLE][7] = 1.1f;  // Tertiary
    g_speed_modifiers[BICYCLE][8] = 1.1f;  // Secondary

    auto calc_scale = [](double speed_m_s) {
        double speed_mm_s = speed_m_s * 1000.0;
        // We want: Time = (Dist * 100) / speed_mm_s
        // We'll use a 32-bit fractional part for high precision
        return (uint64_t)((100.0 / speed_mm_s) * 4294967296.0);
    };

    g_time_scale_fixed[WALK] = calc_scale(WALK_SPEED_M_S);
    g_time_scale_fixed[BICYCLE] = calc_scale(BICYCLE_SPEED_M_S);

    for (int m = 0; m < 4; ++m) {
        double base_speed_m_s = (m == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S;
        for (int r = 0; r < 16; ++r) {
            // formula: Time_10ms = dist_mm * (1 / (10 * speed_mm_s_with_modifier))
            // Actual speed in mm/s = (base_speed_m_s * 1000) * modifier
            double speed_mm_s = (base_speed_m_s * 1000.0) * g_speed_modifiers[m][r];

            // We want Time_10ms = (dist_mm * 100) / speed_mm_s
            // Pre-calculate (100.0 / speed_mm_s) as a 32-bit fixed point
            double val = 100.0 / speed_mm_s;
            g_edge_time_multipliers[m][r] = (uint64_t)(val * 4294967296.0);
        }
    }

    LOGD("[INIT] Loaded %zu nodes. Cosine and Speed lookup tables ready.", g_node_count);
    env->ReleaseStringUTFChars(base_path, path);
    return (g_nodes && g_edges && g_spatial && g_road_names);
}

static RoutingScratchpad g_scratchpad;
static RadixHeap g_radix_heap;

struct RoutingContext {
    SnappedEdge start, end;
    uint32_t found_node = 0xFFFFFFFF;
    int iterations = 0;
};

bool prepare_routing(double sLat, double sLon, double eLat, double eLon, int mode, RoutingContext& ctx) {
    g_scratchpad.reset();
    g_radix_heap.clear(); // Using our new RadixHeap

    ctx.start = find_nearest_edge(sLat, sLon, mode, "START");
    ctx.end = find_nearest_edge(eLat, eLon, mode, "END");

    if (ctx.start.nodeA == 0xFFFFFFFF || ctx.end.nodeA == 0xFFFFFFFF) return false;

    uint32_t timeToA = get_edge_time_10ms(ctx.start.distA_mm, ctx.start.type, mode);
    uint32_t timeToB = get_edge_time_10ms(ctx.start.distB_mm, ctx.start.type, mode);

    g_scratchpad[ctx.start.nodeA].g_score = timeToA;
    g_scratchpad[ctx.start.nodeB].g_score = timeToB;

    auto push_initial = [&](uint32_t node, uint32_t g) {
        uint32_t h = heuristic_time_10ms(g_nodes[node].lat_e7, g_nodes[node].lon_e7,
                                         ctx.end.proj_lat, ctx.end.proj_lon, mode);
        g_radix_heap.push(g + h, node);
    };

    push_initial(ctx.start.nodeA, timeToA);
    push_initial(ctx.start.nodeB, timeToB);
    return true;
}

void perform_search_loop(int mode, RoutingContext& ctx) {
    const uint32_t targetA = ctx.end.nodeA;
    const uint32_t targetB = ctx.end.nodeB;
    uint32_t min_dist_to_dest = 0xFFFFFFFF;

    while (!g_radix_heap.empty()) {
        if (__builtin_expect(ctx.iterations >= 25000000, 0)) break;

        uint32_t u = g_radix_heap.pop();
        uint32_t old_g = g_scratchpad.get_g_score(u);

        if (__builtin_expect(u == targetA || u == targetB, 0)) {
            ctx.found_node = u;
            return;
        }

        // Periodic logging
//        ctx.iterations++;
//        if (__builtin_expect((ctx.iterations & 0x3FFFF) == 0, 0)) {
//            uint32_t d = fast_dist_mm(g_nodes[u].lat_e7, g_nodes[u].lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
//            LOGD("[A*] Iter: %d. Approx Dist: %u mm", ctx.iterations, d);
//        }

        uint64_t s_ptr = get_ptr(u), e_ptr = get_end_ptr(u);
        for (uint64_t i = s_ptr; i < e_ptr; ++i) {
            Edge& e = g_edges[i];
            uint32_t travel_time = get_edge_time_10ms(e.dist_mm, e.type, mode);
            uint32_t new_g = old_g + travel_time;

            auto target_entry = g_scratchpad[e.target];

            if (new_g < target_entry.g_score) {
                target_entry.g_score = new_g;
                target_entry.parent_id = u;
                uint32_t h = heuristic_time_10ms(g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7,
                                                 ctx.end.proj_lat, ctx.end.proj_lon, mode);
                g_radix_heap.push(new_g + h, e.target);
            }
        }
    }
}

jobjectArray reconstruct_path(JNIEnv* env, double sLat, double sLon, double eLat, double eLon, int mode, const RoutingContext& ctx) {
    std::vector<uint32_t> path_nodes;
    for (uint32_t c = ctx.found_node; c != 0xFFFFFFFF; c = g_scratchpad[c].parent_id) {
        path_nodes.push_back(c);
        if (path_nodes.size() > 100000) break;
    }
    std::reverse(path_nodes.begin(), path_nodes.end());

    jclass stepClass = env->FindClass("com/vayunmathur/maps/OfflineRouter$RawStep");
    jmethodID stepCtor = env->GetMethodID(stepClass, "<init>", "(ILjava/lang/String;JJ[D)V");
    std::vector<jobject> jSteps;

    // Start Step
    {
        std::vector<double> geom = {sLon, sLat, (double)ctx.start.proj_lon / 1e7, (double)ctx.start.proj_lat / 1e7};
        uint32_t target_node = path_nodes[0];
        geom.push_back((double)g_nodes[target_node].lon_e7 / 1e7); geom.push_back((double)g_nodes[target_node].lat_e7 / 1e7);
        uint32_t d = (target_node == ctx.start.nodeA) ? ctx.start.distA_mm : ctx.start.distB_mm;
        uint32_t t = get_edge_time_10ms(d, ctx.start.type, mode);
        std::string name = (ctx.start.name_offset == 0xFFFFFFFF) ? "Unnamed Road" : (g_road_names + ctx.start.name_offset);
        jdoubleArray jGeom = env->NewDoubleArray(geom.size()); env->SetDoubleArrayRegion(jGeom, 0, geom.size(), geom.data());
        jstring jName = env->NewStringUTF(name.c_str());
        jSteps.push_back(env->NewObject(stepClass, stepCtor, 19, jName, (jlong)d, (jlong)t, jGeom));
        env->DeleteLocalRef(jName); env->DeleteLocalRef(jGeom);
    }

    // Path Aggregation
    double last_bearing = get_bearing(ctx.start.proj_lat, ctx.start.proj_lon, g_nodes[path_nodes[0]].lat_e7, g_nodes[path_nodes[0]].lon_e7);
    size_t idx = 0;
    while (idx < path_nodes.size() - 1) {
        uint32_t step_start_idx = idx, u = path_nodes[idx], v = path_nodes[idx+1];
        uint32_t current_name_off = 0xFFFFFFFF, current_type = 0;
        uint64_t sp = get_ptr(u);
        uint64_t ep = get_end_ptr(u);
        for (uint64_t k = sp; k < ep; ++k) { if (g_edges[k].target == v) { current_name_off = g_edges[k].name_offset; current_type = g_edges[k].type; break; } }
        std::string roadName = (current_name_off == 0xFFFFFFFF) ? "Unnamed Road" : (g_road_names + current_name_off);
        std::vector<double> geom; uint64_t step_dist = 0; uint64_t step_time = 0;
        while (idx < path_nodes.size() - 1) {
            uint32_t cu = path_nodes[idx], cv = path_nodes[idx+1];
            uint32_t name_off = 0xFFFFFFFF, d = 0, type = 0;
            uint64_t s = get_ptr(cu);
            uint64_t e = get_end_ptr(cu);
            for (uint64_t k = s; k < e; ++k) { if (g_edges[k].target == cv) { name_off = g_edges[k].name_offset; d = g_edges[k].dist_mm; type = g_edges[k].type; break; } }
            if (name_off != current_name_off && step_start_idx != idx) break;
            geom.push_back(g_nodes[cu].lon_e7 / 1e7); geom.push_back(g_nodes[cu].lat_e7 / 1e7);
            step_dist += d; step_time += get_edge_time_10ms(d, type, mode); idx++;
        }
        geom.push_back(g_nodes[path_nodes[idx]].lon_e7 / 1e7); geom.push_back(g_nodes[path_nodes[idx]].lat_e7 / 1e7);
        double cur_bearing = get_bearing(g_nodes[path_nodes[step_start_idx]].lat_e7, g_nodes[path_nodes[step_start_idx]].lon_e7, g_nodes[path_nodes[idx]].lat_e7, g_nodes[path_nodes[idx]].lon_e7);
        int maneuver = get_maneuver(last_bearing, cur_bearing); last_bearing = cur_bearing;
        jdoubleArray jGeom = env->NewDoubleArray(geom.size()); env->SetDoubleArrayRegion(jGeom, 0, geom.size(), geom.data());
        jstring jName = env->NewStringUTF(roadName.c_str());
        jSteps.push_back(env->NewObject(stepClass, stepCtor, maneuver, jName, (jlong)step_dist, (jlong)step_time, jGeom));
        env->DeleteLocalRef(jName); env->DeleteLocalRef(jGeom);
    }

    // Arrival Step
    {
        uint32_t last_node = path_nodes.back();
        std::vector<double> geom = { (double)g_nodes[last_node].lon_e7 / 1e7, (double)g_nodes[last_node].lat_e7 / 1e7, (double)ctx.end.proj_lon / 1e7, (double)ctx.end.proj_lat / 1e7, eLon, eLat };
        uint32_t d = (last_node == ctx.end.nodeA) ? ctx.end.distA_mm : ctx.end.distB_mm;
        uint32_t t = get_edge_time_10ms(d, ctx.end.type, mode);
        std::string name = (ctx.end.name_offset == 0xFFFFFFFF) ? "Destination Road" : (g_road_names + ctx.end.name_offset);
        double cur_bearing = get_bearing(g_nodes[last_node].lat_e7, g_nodes[last_node].lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
        int maneuver = get_maneuver(last_bearing, cur_bearing);
        jdoubleArray jGeom = env->NewDoubleArray(geom.size()); env->SetDoubleArrayRegion(jGeom, 0, geom.size(), geom.data());
        jstring jName = env->NewStringUTF(name.c_str());
        jSteps.push_back(env->NewObject(stepClass, stepCtor, maneuver, jName, (jlong)d, (jlong)t, jGeom));
        env->DeleteLocalRef(jName); env->DeleteLocalRef(jGeom);
    }
    jobjectArray resArray = env->NewObjectArray(jSteps.size(), stepClass, nullptr);
    for (size_t s = 0; s < jSteps.size(); ++s) { env->SetObjectArrayElement(resArray, s, jSteps[s]); env->DeleteLocalRef(jSteps[s]); }
    return resArray;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vayunmathur_maps_OfflineRouter_findRouteNative(JNIEnv* env, jobject thiz, jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode) {
    RoutingContext ctx;

    if (!prepare_routing(sLat, sLon, eLat, eLon, mode, ctx)) {
        return nullptr;
    }

    perform_search_loop(mode, ctx);

    if (ctx.found_node == 0xFFFFFFFF) {
        LOGE("[A* FAILURE] No route found after %d iterations", ctx.iterations);
        return nullptr;
    }
    LOGD("[A* SUCCESS] Route found! Total Iterations: %d. Final Node: %u", ctx.iterations, ctx.found_node);

    return reconstruct_path(env, sLat, sLon, eLat, eLon, mode, ctx);
}