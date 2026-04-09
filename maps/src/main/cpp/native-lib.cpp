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
#include "bucket_queue.h"

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

struct SpatialNode {
    uint64_t spatial_id;
    uint32_t local_id;
};
#pragma pack(pop)

// Constants
const double WALK_SPEED_M_S = 4.5 / 3.6;
const double BICYCLE_SPEED_M_S = 16.0 / 3.6;
const uint64_t NO_EDGES_SENTINEL = (1ULL << 40) - 1;
const double EARTH_RADIUS_MM = 6371000.0 * 1000.0;
const double DEG_TO_RAD = M_PI / 180.0;

// Global Data Pointers
NodeMaster* g_nodes = nullptr;
Edge* g_edges = nullptr;
SpatialNode* g_spatial = nullptr;
char* g_road_names = nullptr;
size_t g_node_count = 0;

// Optimization: Cosine Lookup Table
float g_cos_lat_lookup[1801];

// Speed/Modifier Lookup Table: [Mode][RoadType]
// Modes: 2 (WALK), 3 (BICYCLE)
// RoadTypes: 0-15
float g_speed_modifiers[4][16];

inline float get_cos_lat(int32_t lat_e7) {
    int idx = (lat_e7 / 1000000) + 900;
    return g_cos_lat_lookup[idx];
}

// --- CORE UTILITIES ---

inline uint64_t get_ptr(uint32_t idx) {
    return ((uint64_t)g_nodes[idx].edge_ptr_high << 32) | g_nodes[idx].edge_ptr_low;
}

inline uint64_t get_end_ptr(uint32_t idx) {
    uint32_t next_idx = idx + 1;
    while (next_idx < g_node_count) {
        uint64_t ptr = get_ptr(next_idx);
        if (ptr != NO_EDGES_SENTINEL) return ptr;
        next_idx++;
    }
    return NO_EDGES_SENTINEL;
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

inline uint32_t fast_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    double dlat = (double)(lat2_e7 - lat1_e7) / 1e7 * DEG_TO_RAD;
    double dlon = (double)(lon2_e7 - lon1_e7) / 1e7 * DEG_TO_RAD;
    float cosLat = get_cos_lat(lat1_e7);
    double x = dlon * cosLat;
    double y = dlat;
    return (uint32_t)(sqrt(x * x + y * y) * EARTH_RADIUS_MM);
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
    double base_speed = (mode == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S;
    // modifier = 1.0 / actual_modifier. High cost = low speed.
    // Modifier from lookup table is essentially the penalty factor.
    return (uint32_t)((double)dist_mm / (10.0 * base_speed * g_speed_modifiers[mode][road_type & 0xF]));
}

uint32_t heuristic_time_10ms(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2, int mode) {
    uint32_t dist_mm = fast_dist_mm(lat1, lon1, lat2, lon2);
    double max_speed = (mode == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S;
    // Heuristic must be admissible: assume the best possible modifier (e.g. 1.5 for stairs in walk mode)
    // Actually, to be safe and admissible, use a speed higher than any modified speed.
    double h_speed = max_speed * 1.6;
    return (uint32_t)((double)dist_mm / (10.0 * h_speed));
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
    auto it = std::lower_bound(g_spatial, g_spatial + g_node_count, target_spatial,
                               [](const SpatialNode& a, uint64_t val) { return a.spatial_id < val; });
    intptr_t center = std::distance(g_spatial, it);

    SnappedEdge best = {0xFFFFFFFF, 0xFFFFFFFF, pLat, pLon, 0, 0, 0, 0xFFFFFFFF};
    uint32_t minSnapDist = 0xFFFFFFFF;

    intptr_t window = 800;
    for (intptr_t i = std::max((intptr_t)0, center - window); i <= std::min((intptr_t)g_node_count - 1, center + window); ++i) {
        uint32_t u = g_spatial[i].local_id;
        uint64_t s = get_ptr(u);
        if (s == NO_EDGES_SENTINEL) continue;
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
    g_spatial = (SpatialNode*)m_file(base + "/nodes_spatial.bin", s3);
    g_road_names = (char*)m_file(base + "/road_names.bin", s4);
    if (g_nodes) g_node_count = s1 / sizeof(NodeMaster);

    // Initialize Cosine Lookup Table
    for (int i = 0; i <= 1800; ++i) {
        double lat_deg = (i / 10.0) - 90.0;
        g_cos_lat_lookup[i] = (float)cos(lat_deg * DEG_TO_RAD);
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

    LOGD("[INIT] Loaded %zu nodes. Cosine and Speed lookup tables ready.", g_node_count);
    env->ReleaseStringUTFChars(base_path, path);
    return (g_nodes && g_edges && g_spatial && g_road_names);
}

static RoutingScratchpad g_scratchpad(25);
static BucketQueue g_bq;

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vayunmathur_maps_OfflineRouter_findRouteNative(JNIEnv* env, jobject thiz, jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode) {
    g_scratchpad.reset();
    g_bq.clear();

    SnappedEdge start = find_nearest_edge(sLat, sLon, mode, "START");
    SnappedEdge end = find_nearest_edge(eLat, eLon, mode, "END");
    if (start.nodeA == 0xFFFFFFFF || end.nodeA == 0xFFFFFFFF) return nullptr;

    uint32_t timeToA = get_edge_time_10ms(start.distA_mm, start.type, mode);
    uint32_t timeToB = get_edge_time_10ms(start.distB_mm, start.type, mode);
    g_scratchpad[start.nodeA].g_score = timeToA;
    g_scratchpad[start.nodeB].g_score = timeToB;

    g_bq.push(timeToA + heuristic_time_10ms(g_nodes[start.nodeA].lat_e7, g_nodes[start.nodeA].lon_e7, end.proj_lat, end.proj_lon, mode), start.nodeA);
    g_bq.push(timeToB + heuristic_time_10ms(g_nodes[start.nodeB].lat_e7, g_nodes[start.nodeB].lon_e7, end.proj_lat, end.proj_lon, mode), start.nodeB);

    uint32_t found_node = 0xFFFFFFFF;
    int iterations = 0;
    uint32_t min_dist_to_dest = 0xFFFFFFFF;

    while (!g_bq.empty() && iterations < 2500000) { // Increased iterations for larger search space
        uint32_t u = g_bq.pop();
        iterations++;

        uint32_t current_dist = fast_dist_mm(g_nodes[u].lat_e7, g_nodes[u].lon_e7, end.proj_lat, end.proj_lon);
        if (current_dist < min_dist_to_dest) min_dist_to_dest = current_dist;

        if (iterations % 50000 == 0) {
            LOGD("[A* BUCKET] Iter: %d. Node: %u. Approx Dist: %u mm. Min found: %u mm.", iterations, u, current_dist, min_dist_to_dest);
        }
        if (u == end.nodeA || u == end.nodeB) {
            found_node = u;
            LOGD("[A* SUCCESS] Destination edge reached at node %u after %d iterations", u, iterations);
            break;
        }

        uint64_t s_ptr = get_ptr(u);
        if (s_ptr == NO_EDGES_SENTINEL) continue;
        uint64_t e_ptr = get_end_ptr(u);
        if (e_ptr < s_ptr || (e_ptr - s_ptr) > 1000) continue;

        for (uint64_t i = s_ptr; i < e_ptr; ++i) {
            Edge& e = g_edges[i];
            // No longer skipping via is_accessible. All edges are relaxed.
            uint32_t travel_time = get_edge_time_10ms(e.dist_mm, e.type, mode);
            uint32_t new_g = g_scratchpad[u].g_score + travel_time;
            if (new_g < g_scratchpad[e.target].g_score) {
                g_scratchpad[e.target].g_score = new_g;
                g_scratchpad[e.target].parent_id = u;
                uint32_t h = heuristic_time_10ms(g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7, end.proj_lat, end.proj_lon, mode);
                g_bq.push(new_g + h, e.target);
            }
        }
    }

    if (found_node == 0xFFFFFFFF) {
        LOGE("[A* FAILURE] No route found after %d iterations", iterations);
        return nullptr;
    }

    // --- RECONSTRUCTION ---
    std::vector<uint32_t> path_nodes;
    for (uint32_t c = found_node; c != 0xFFFFFFFF; c = g_scratchpad[c].parent_id) {
        path_nodes.push_back(c);
        if (path_nodes.size() > 100000) break;
    }
    std::reverse(path_nodes.begin(), path_nodes.end());

    jclass stepClass = env->FindClass("com/vayunmathur/maps/OfflineRouter$RawStep");
    jmethodID stepCtor = env->GetMethodID(stepClass, "<init>", "(ILjava/lang/String;JJ[D)V");
    std::vector<jobject> jSteps;

    // Start Step
    {
        std::vector<double> geom = {sLon, sLat, (double)start.proj_lon / 1e7, (double)start.proj_lat / 1e7};
        uint32_t target_node = path_nodes[0];
        geom.push_back((double)g_nodes[target_node].lon_e7 / 1e7); geom.push_back((double)g_nodes[target_node].lat_e7 / 1e7);
        uint32_t d = (target_node == start.nodeA) ? start.distA_mm : start.distB_mm;
        uint32_t t = get_edge_time_10ms(d, start.type, mode);
        std::string name = (start.name_offset == 0xFFFFFFFF) ? "Unnamed Road" : (g_road_names + start.name_offset);
        jdoubleArray jGeom = env->NewDoubleArray(geom.size()); env->SetDoubleArrayRegion(jGeom, 0, geom.size(), geom.data());
        jstring jName = env->NewStringUTF(name.c_str());
        jSteps.push_back(env->NewObject(stepClass, stepCtor, 19, jName, (jlong)d, (jlong)t, jGeom));
        env->DeleteLocalRef(jName); env->DeleteLocalRef(jGeom);
    }

    // Path Aggregation
    double last_bearing = get_bearing(start.proj_lat, start.proj_lon, g_nodes[path_nodes[0]].lat_e7, g_nodes[path_nodes[0]].lon_e7);
    size_t idx = 0;
    while (idx < path_nodes.size() - 1) {
        uint32_t step_start_idx = idx, u = path_nodes[idx], v = path_nodes[idx+1];
        uint32_t current_name_off = 0xFFFFFFFF, current_type = 0;
        uint64_t sp = get_ptr(u); uint64_t ep = get_end_ptr(u);
        for (uint64_t k = sp; k < ep; ++k) { if (g_edges[k].target == v) { current_name_off = g_edges[k].name_offset; current_type = g_edges[k].type; break; } }
        std::string roadName = (current_name_off == 0xFFFFFFFF) ? "Unnamed Road" : (g_road_names + current_name_off);
        std::vector<double> geom; uint64_t step_dist = 0; uint64_t step_time = 0;
        while (idx < path_nodes.size() - 1) {
            uint32_t cu = path_nodes[idx], cv = path_nodes[idx+1];
            uint32_t name_off = 0xFFFFFFFF, d = 0, type = 0;
            uint64_t s = get_ptr(cu); uint64_t e = get_end_ptr(cu);
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
        std::vector<double> geom = { (double)g_nodes[last_node].lon_e7 / 1e7, (double)g_nodes[last_node].lat_e7 / 1e7, (double)end.proj_lon / 1e7, (double)end.proj_lat / 1e7, eLon, eLat };
        uint32_t d = (last_node == end.nodeA) ? end.distA_mm : end.distB_mm;
        uint32_t t = get_edge_time_10ms(d, end.type, mode);
        std::string name = (end.name_offset == 0xFFFFFFFF) ? "Destination Road" : (g_road_names + end.name_offset);
        double cur_bearing = get_bearing(g_nodes[last_node].lat_e7, g_nodes[last_node].lon_e7, end.proj_lat, end.proj_lon);
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