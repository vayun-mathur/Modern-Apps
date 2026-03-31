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

NodeMaster* g_nodes = nullptr;
Edge* g_edges = nullptr;
SpatialNode* g_spatial = nullptr;
char* g_road_names = nullptr;
size_t g_node_count = 0;

const double WALK_SPEED_M_S = 4.5 / 3.6;
const double BICYCLE_SPEED_M_S = 16.0 / 3.6;

// Reconstruct 40-bit pointer
inline uint64_t get_ptr(uint32_t idx) {
    if (idx >= g_node_count) return 0;
    return ((uint64_t)g_nodes[idx].edge_ptr_high << 32) | g_nodes[idx].edge_ptr_low;
}

// --- GEOMETRY & MANEUVER HELPERS ---

uint32_t haversine_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    double lat1 = (lat1_e7 / 1e7) * (M_PI / 180.0);
    double lon1 = (lon1_e7 / 1e7) * (M_PI / 180.0);
    double lat2 = (lat2_e7 / 1e7) * (M_PI / 180.0);
    double lon2 = (lon2_e7 / 1e7) * (M_PI / 180.0);
    double a = sin((lat2 - lat1) / 2) * sin((lat2 - lat1) / 2) + cos(lat1) * cos(lat2) * sin((lon2 - lon1) / 2) * sin((lon2 - lon1) / 2);
    return (uint32_t)(2 * atan2(sqrt(a), sqrt(1 - a)) * 6371000.0 * 1000.0);
}

double get_bearing(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2) {
    double f1 = (lat1 / 1e7) * (M_PI / 180.0);
    double f2 = (lat2 / 1e7) * (M_PI / 180.0);
    double dl = ((lon2 - lon1) / 1e7) * (M_PI / 180.0);
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

bool is_accessible(uint8_t road_type, int mode) {
    if (mode == WALK) return (road_type > 2);
    if (mode == BICYCLE) return (road_type > 2 && road_type != 15);
    return true;
}

uint32_t get_edge_time_10ms(uint32_t dist_mm, uint8_t road_type, int mode) {
    double base_speed = (mode == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S;
    double modifier = 1.0;
    if (mode == WALK) {
        if (road_type == 12 || road_type == 10) modifier = 0.8;
        if (road_type == 15) modifier = 1.5;
    } else if (mode == BICYCLE) {
        if (road_type == 13) modifier = 0.7;
        if (road_type == 7 || road_type == 8) modifier = 1.1;
    }
    return (uint32_t)((double)dist_mm / (10.0 * base_speed * modifier));
}

uint32_t heuristic_time_10ms(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2, int mode) {
    uint32_t dist_mm = haversine_mm(lat1, lon1, lat2, lon2);
    double max_speed = (mode == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S;
    double h_speed = (mode == BICYCLE) ? (max_speed / 0.7) : (max_speed / 0.8);
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
};

SnappedEdge find_nearest_edge(double lat, double lon, int mode, const char* label) {
    uint64_t target_spatial = latlng_to_spatial(lat, lon);
    int32_t pLat = (int32_t)(lat * 1e7), pLon = (int32_t)(lon * 1e7);
    auto it = std::lower_bound(g_spatial, g_spatial + g_node_count, target_spatial,
                               [](const SpatialNode& a, uint64_t val) { return a.spatial_id < val; });
    intptr_t center = std::distance(g_spatial, it);
    uint32_t bestA = 0xFFFFFFFF, bestB = 0xFFFFFFFF, minDist = 0xFFFFFFFF;
    int32_t finalLat = pLat, finalLon = pLon;

    intptr_t window = 500;
    for (intptr_t i = std::max((intptr_t)0, center - window); i <= std::min((intptr_t)g_node_count - 1, center + window); ++i) {
        uint32_t u = g_spatial[i].local_id;
        uint64_t s = get_ptr(u), e_ptr = get_ptr(u + 1);
        for (uint64_t j = s; j < e_ptr; ++j) {
            Edge& e = g_edges[j];
            if (!is_accessible(e.type, mode)) continue;
            Projection p = get_projection(pLat, pLon, g_nodes[u].lat_e7, g_nodes[u].lon_e7, g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7);
            if (p.dist_mm < minDist) { minDist = p.dist_mm; bestA = u; bestB = e.target; finalLat = p.lat_e7; finalLon = p.lon_e7; }
        }
    }
    return {bestA, bestB, finalLat, finalLon};
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
    env->ReleaseStringUTFChars(base_path, path);
    return g_nodes && g_edges && g_spatial && g_road_names;
}

static RoutingScratchpad g_scratchpad(20);

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vayunmathur_maps_OfflineRouter_findRouteNative(JNIEnv* env, jobject thiz, jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode) {
    g_scratchpad.reset();
    SnappedEdge start = find_nearest_edge(sLat, sLon, mode, "START");
    SnappedEdge end = find_nearest_edge(eLat, eLon, mode, "END");

    if (start.nodeA == 0xFFFFFFFF || end.nodeA == 0xFFFFFFFF) return nullptr;

    using NodeScore = std::pair<uint32_t, uint32_t>;
    std::priority_queue<NodeScore, std::vector<NodeScore>, std::greater<NodeScore>> pq;
    g_scratchpad[start.nodeA].g_score = 0;
    pq.push({heuristic_time_10ms(g_nodes[start.nodeA].lat_e7, g_nodes[start.nodeA].lon_e7, end.proj_lat, end.proj_lon, mode), start.nodeA});

    uint32_t found_node = 0xFFFFFFFF;
    int iterations = 0;
    while (!pq.empty() && iterations < 1500000) {
        uint32_t u = pq.top().second; pq.pop();
        iterations++;
        if (u == end.nodeA) { found_node = u; break; }
        uint64_t s_ptr = get_ptr(u), e_ptr = get_ptr(u + 1);
        for (uint64_t i = s_ptr; i < e_ptr; ++i) {
            Edge& e = g_edges[i];
            if (!is_accessible(e.type, mode)) continue;
            uint32_t travel_time = get_edge_time_10ms(e.dist_mm, e.type, mode);
            uint32_t new_g = g_scratchpad[u].g_score + travel_time;
            if (new_g < g_scratchpad[e.target].g_score) {
                g_scratchpad[e.target].g_score = new_g;
                g_scratchpad[e.target].parent_id = u;
                pq.push({new_g + heuristic_time_10ms(g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7, end.proj_lat, end.proj_lon, mode), e.target});
            }
        }
    }

    if (found_node == 0xFFFFFFFF) return nullptr;

    std::vector<uint32_t> path;
    for (uint32_t c = found_node; c != 0xFFFFFFFF; c = g_scratchpad[c].parent_id) { path.push_back(c); if (path.size() > 50000) break; }
    std::reverse(path.begin(), path.end());

    jclass stepClass = env->FindClass("com/vayunmathur/maps/OfflineRouter$RawStep");
    jmethodID stepCtor = env->GetMethodID(stepClass, "<init>", "(ILjava/lang/String;JJ[D)V");
    std::vector<jobject> jSteps;
    size_t idx = 0;
    double last_bearing = 0;

    while (idx < path.size() - 1) {
        uint32_t step_start_idx = idx, u = path[idx], v = path[idx+1];
        uint32_t current_name_off = 0xFFFFFFFF;
        uint8_t current_type = 0;
        uint64_t sp = get_ptr(u), ep = get_ptr(u + 1);
        for (uint64_t k = sp; k < ep; ++k) {
            if (g_edges[k].target == v) { current_name_off = g_edges[k].name_offset; current_type = g_edges[k].type; break; }
        }

        std::string roadName = (current_name_off == 0xFFFFFFFF) ? "Unnamed Road" : (g_road_names + current_name_off);
        std::vector<double> geom;
        uint64_t step_dist = 0;
        while (idx < path.size() - 1) {
            uint32_t cu = path[idx], cv = path[idx+1];
            uint32_t name_off = 0xFFFFFFFF, d = 0;
            uint64_t s = get_ptr(cu), e = get_ptr(cu+1);
            for (uint64_t k = s; k < e; ++k) { if (g_edges[k].target == cv) { name_off = g_edges[k].name_offset; d = g_edges[k].dist_mm; break; } }
            if (name_off != current_name_off && step_start_idx != idx) break;
            geom.push_back(g_nodes[cu].lon_e7 / 1e7); geom.push_back(g_nodes[cu].lat_e7 / 1e7);
            step_dist += d; idx++;
        }
        geom.push_back(g_nodes[path[idx]].lon_e7 / 1e7); geom.push_back(g_nodes[path[idx]].lat_e7 / 1e7);

        double cur_bearing = get_bearing(g_nodes[path[step_start_idx]].lat_e7, g_nodes[path[step_start_idx]].lon_e7, g_nodes[path[idx]].lat_e7, g_nodes[path[idx]].lon_e7);
        int maneuver = (step_start_idx == 0) ? 19 : get_maneuver(last_bearing, cur_bearing);
        last_bearing = cur_bearing;

        jdoubleArray jGeom = env->NewDoubleArray(geom.size());
        env->SetDoubleArrayRegion(jGeom, 0, geom.size(), geom.data());
        jstring jName = env->NewStringUTF(roadName.c_str());
        uint64_t step_time = get_edge_time_10ms((uint32_t)step_dist, current_type, mode);
        jSteps.push_back(env->NewObject(stepClass, stepCtor, maneuver, jName, (jlong)step_dist, (jlong)step_time, jGeom));
        env->DeleteLocalRef(jName); env->DeleteLocalRef(jGeom);
    }

    jobjectArray res = env->NewObjectArray(jSteps.size(), stepClass, nullptr);
    for (size_t s = 0; s < jSteps.size(); ++s) { env->SetObjectArrayElement(res, s, jSteps[s]); env->DeleteLocalRef(jSteps[s]); }
    return res;
}