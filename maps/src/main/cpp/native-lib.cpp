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

// Must match Kotlin RouteService.TravelMode ordinal
enum TravelMode { WALK = 2, BICYCLE = 3 };

#pragma pack(push, 1)
struct NodePos { int32_t lat_e7, lon_e7; };
struct Edge { uint32_t source, target, dist_mm; uint8_t type; };
struct SpatialNode { uint64_t spatial_id; uint32_t local_id; };
#pragma pack(pop)

NodePos* g_nodes = nullptr;
uint64_t* g_edge_index = nullptr;
Edge* g_edges = nullptr;
SpatialNode* g_spatial = nullptr;
size_t g_node_count = 0;

// Constants for speeds (m/s)
const double WALK_SPEED_M_S = 4.5 / 3.6;    // ~1.25 m/s
const double BICYCLE_SPEED_M_S = 16.0 / 3.6; // ~4.44 m/s

// --- GEOMETRY HELPERS ---

uint32_t haversine_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    double lat1 = (lat1_e7 / 1e7) * (M_PI / 180.0);
    double lon1 = (lon1_e7 / 1e7) * (M_PI / 180.0);
    double lat2 = (lat2_e7 / 1e7) * (M_PI / 180.0);
    double lon2 = (lon2_e7 / 1e7) * (M_PI / 180.0);
    double a = sin((lat2 - lat1) / 2) * sin((lat2 - lat1) / 2) + cos(lat1) * cos(lat2) * sin((lon2 - lon1) / 2) * sin((lon2 - lon1) / 2);
    return (uint32_t)(2 * atan2(sqrt(a), sqrt(1 - a)) * 6371000.0 * 1000.0);
}

// --- ROUTING LOGIC HELPERS ---

bool is_accessible(uint8_t road_type, int mode) {
    if (mode == WALK) {
        return (road_type > 2); // No Motorway/Trunk
    } else if (mode == BICYCLE) {
        return (road_type > 2 && road_type != 15); // No Motorway/Trunk/Steps
    }
    return true;
}

/**
 * Calculates time in 10ms units to traverse an edge.
 * One unit = 10 milliseconds.
 */
uint32_t get_edge_time_10ms(uint32_t dist_mm, uint8_t road_type, int mode) {
    double base_speed = (mode == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S;

    // Weight modifiers for preference
    double modifier = 1.0;
    if (mode == WALK) {
        if (road_type == 12 || road_type == 10) modifier = 0.8; // Favor footways
        if (road_type == 15) modifier = 1.5; // Penalty for steps
    } else if (mode == BICYCLE) {
        if (road_type == 13) modifier = 0.7; // Favor cycleways
        if (road_type == 7 || road_type == 8) modifier = 1.1; // Penalty for residential
    }

    // Time (s) = dist_mm / 1000 / speed
    // Time (10ms units) = (dist_mm / 1000 / speed) * 100 = dist_mm / (10 * speed)
    return (uint32_t)((double)dist_mm / (10.0 * base_speed * modifier));
}

/**
 * Heuristic: Minimum time in 10ms units to reach target (assuming max speed)
 */
uint32_t heuristic_time_10ms(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2, int mode) {
    uint32_t dist_mm = haversine_mm(lat1, lon1, lat2, lon2);
    double max_speed = (mode == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S;

    // Use fastest possible speed across all types (lowest modifier) to ensure admissibility
    double heuristic_speed = (mode == BICYCLE) ? (max_speed / 0.7) : (max_speed / 0.8);
    return (uint32_t)((double)dist_mm / (10.0 * heuristic_speed));
}

uint64_t latlng_to_spatial(double lat, double lon) {
    double x = (lon + 180.0) / 360.0;
    double y = (lat + 90.0) / 180.0;
    uint32_t ix = (uint32_t)(x * 4294967295.0);
    uint32_t iy = (uint32_t)(y * 4294967295.0);
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
    uint32_t timeToA_10ms, timeToB_10ms;
};

SnappedEdge find_nearest_edge(double lat, double lon, int mode) {
    uint64_t target_spatial = latlng_to_spatial(lat, lon);
    int32_t pLat = (int32_t)(lat * 1e7), pLon = (int32_t)(lon * 1e7);
    auto it = std::lower_bound(g_spatial, g_spatial + g_node_count, target_spatial, [](const SpatialNode& a, uint64_t val) { return a.spatial_id < val; });
    intptr_t center = std::distance(g_spatial, it);
    uint32_t bestA = 0, bestB = 0, minDist = 0xFFFFFFFF;
    int32_t finalLat = pLat, finalLon = pLon;
    uint8_t bestType = 0;

    for (intptr_t i = std::max((intptr_t)0, center - 500); i <= std::min((intptr_t)g_node_count - 1, center + 500); ++i) {
        uint32_t u = g_spatial[i].local_id;
        for (uint64_t j = g_edge_index[u]; j < g_edge_index[u + 1]; ++j) {
            Edge& e = g_edges[j];
            if (!is_accessible(e.type, mode)) continue;
            uint32_t v = e.target;
            Projection p = get_projection(pLat, pLon, g_nodes[u].lat_e7, g_nodes[u].lon_e7, g_nodes[v].lat_e7, g_nodes[v].lon_e7);
            if (p.dist_mm < minDist) {
                minDist = p.dist_mm; bestA = u; bestB = v; finalLat = p.lat_e7; finalLon = p.lon_e7; bestType = e.type;
            }
        }
    }

    uint32_t distA = haversine_mm(finalLat, finalLon, g_nodes[bestA].lat_e7, g_nodes[bestA].lon_e7);
    uint32_t distB = haversine_mm(finalLat, finalLon, g_nodes[bestB].lat_e7, g_nodes[bestB].lon_e7);

    return {bestA, bestB, finalLat, finalLon,
            get_edge_time_10ms(distA, bestType, mode),
            get_edge_time_10ms(distB, bestType, mode)};
}

// --- JNI ---

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vayunmathur_maps_OfflineRouter_init(JNIEnv* env, jobject thiz, jstring base_path) {
    const char* path = env->GetStringUTFChars(base_path, 0);
    size_t s1, s2, s3, s4;
    std::string base(path);
    auto map_it = [&](std::string p, size_t& s) {
        int fd = open(p.c_str(), O_RDONLY); if(fd < 0) return (void*)nullptr;
        s = lseek(fd, 0, SEEK_END);
        void* a = mmap(NULL, s, PROT_READ, MAP_SHARED, fd, 0);
        close(fd); return a;
    };
    g_nodes = (NodePos*)map_it(base + "/nodes_lookup.bin", s1);
    g_edge_index = (uint64_t*)map_it(base + "/edge_index.bin", s2);
    g_edges = (Edge*)map_it(base + "/edges.bin", s3);
    g_spatial = (SpatialNode*)map_it(base + "/nodes_spatial.bin", s4);
    g_node_count = s1 / sizeof(NodePos);
    env->ReleaseStringUTFChars(base_path, path);
    return g_nodes && g_edge_index && g_edges && g_spatial;
}

static RoutingScratchpad g_scratchpad(20);

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_vayunmathur_maps_OfflineRouter_findShortestRouteNative(JNIEnv* env, jobject thiz,
                                                                jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode) {
    LOGD("Starting time-based route search (10ms units): (%f, %f) -> (%f, %f), mode: %d", sLat, sLon, eLat, eLon, mode);

    g_scratchpad.reset();

    SnappedEdge start = find_nearest_edge(sLat, sLon, mode);
    SnappedEdge end = find_nearest_edge(eLat, eLon, mode);

    // Score is total time in 10ms units
    using NodeScore = std::pair<uint32_t, uint32_t>;
    std::priority_queue<NodeScore, std::vector<NodeScore>, std::greater<NodeScore>> pq;

    g_scratchpad[start.nodeA].g_score = start.timeToA_10ms;
    g_scratchpad[start.nodeB].g_score = start.timeToB_10ms;

    uint32_t hA = heuristic_time_10ms(g_nodes[start.nodeA].lat_e7, g_nodes[start.nodeA].lon_e7, end.proj_lat, end.proj_lon, mode);
    uint32_t hB = heuristic_time_10ms(g_nodes[start.nodeB].lat_e7, g_nodes[start.nodeB].lon_e7, end.proj_lat, end.proj_lon, mode);

    pq.push({g_scratchpad[start.nodeA].g_score + hA, start.nodeA});
    pq.push({g_scratchpad[start.nodeB].g_score + hB, start.nodeB});

    uint32_t found_end_node = 0xFFFFFFFF;
    int iterations = 0;

    while (!pq.empty()) {
        NodeScore current = pq.top();
        uint32_t u = current.second;
        uint32_t f_score = current.first;
        pq.pop();

        iterations++;

        if (u == end.nodeA || u == end.nodeB) {
            found_end_node = u;
            LOGD("Target found! Time: %u (10ms units), Iterations: %d", g_scratchpad[u].g_score, iterations);
            break;
        }

        if (u + 1 >= g_node_count) continue;

        for (uint64_t i = g_edge_index[u]; i < g_edge_index[u + 1]; ++i) {
            Edge& e = g_edges[i];
            if (!is_accessible(e.type, mode)) continue;

            uint32_t travel_time = get_edge_time_10ms(e.dist_mm, e.type, mode);

            if (g_scratchpad[u].g_score + travel_time < g_scratchpad[e.target].g_score) {
                g_scratchpad[e.target].g_score = g_scratchpad[u].g_score + travel_time;
                g_scratchpad[e.target].parent_id = u;

                uint32_t h = heuristic_time_10ms(g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7, end.proj_lat, end.proj_lon, mode);
                pq.push({g_scratchpad[e.target].g_score + h, e.target});
            }
        }

        if (iterations > 1000000) {
            LOGE("Critical: Search timed out at 1M iterations.");
            break;
        }
    }

    std::vector<double> results;

    // 1. First element: Total time in seconds
    uint32_t total_time_10ms = 0;
    if (found_end_node != 0xFFFFFFFF) {
        uint32_t final_leg_time = (found_end_node == end.nodeA) ? end.timeToA_10ms : end.timeToB_10ms;
        total_time_10ms = g_scratchpad[found_end_node].g_score + final_leg_time;
    }
    // Convert 10ms units to seconds: units * 0.01
    results.push_back((double)total_time_10ms / 100.0);

    // 2. Coordinates: Start point
    results.push_back(start.proj_lon / 1e7);
    results.push_back(start.proj_lat / 1e7);

    if (found_end_node != 0xFFFFFFFF) {
        std::vector<uint32_t> nodes;
        for (uint32_t c = found_end_node; c != 0xFFFFFFFF; c = g_scratchpad[c].parent_id) {
            nodes.push_back(c);
        }
        std::reverse(nodes.begin(), nodes.end());
        for (uint32_t id : nodes) {
            results.push_back(g_nodes[id].lon_e7 / 1e7);
            results.push_back(g_nodes[id].lat_e7 / 1e7);
        }
    }

    // 3. Coordinates: End point
    results.push_back(end.proj_lon / 1e7);
    results.push_back(end.proj_lat / 1e7);

    jdoubleArray resultArr = env->NewDoubleArray(results.size());
    env->SetDoubleArrayRegion(resultArr, 0, results.size(), results.data());
    return resultArr;
}