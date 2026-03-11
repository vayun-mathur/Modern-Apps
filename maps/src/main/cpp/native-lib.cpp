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

#define TAG "OfflineRouterNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

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

// --- ROUTING LOGIC HELPERS ---

bool is_accessible(uint8_t road_type, int mode) {
    if (mode == WALK) {
        return (road_type > 2); // No Motorway/Trunk
    } else if (mode == BICYCLE) {
        return (road_type > 2 && road_type != 15); // No Motorway/Trunk/Steps
    }
    return true;
}

double get_weight_modifier(uint8_t road_type, int mode) {
    if (mode == WALK) {
        if (road_type == 12 || road_type == 10) return 0.8; // Favor footways
        if (road_type == 15) return 1.5; // Penalty for steps
    } else if (mode == BICYCLE) {
        if (road_type == 13) return 0.7; // Favor cycleways
        if (road_type == 7 || road_type == 8) return 1.1; // Slight penalty for residential
    }
    return 1.0;
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

uint32_t haversine_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    double lat1 = (lat1_e7 / 1e7) * (M_PI / 180.0);
    double lon1 = (lon1_e7 / 1e7) * (M_PI / 180.0);
    double lat2 = (lat2_e7 / 1e7) * (M_PI / 180.0);
    double lon2 = (lon2_e7 / 1e7) * (M_PI / 180.0);
    double a = sin((lat2 - lat1) / 2) * sin((lat2 - lat1) / 2) + cos(lat1) * cos(lat2) * sin((lon2 - lon1) / 2) * sin((lon2 - lon1) / 2);
    return (uint32_t)(2 * atan2(sqrt(a), sqrt(1 - a)) * 6371000.0 * 1000.0);
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
    uint32_t distToA, distToB;
};

SnappedEdge find_nearest_edge(double lat, double lon, int mode) {
    uint64_t target_spatial = latlng_to_spatial(lat, lon);
    int32_t pLat = (int32_t)(lat * 1e7), pLon = (int32_t)(lon * 1e7);
    auto it = std::lower_bound(g_spatial, g_spatial + g_node_count, target_spatial, [](const SpatialNode& a, uint64_t val) { return a.spatial_id < val; });
    intptr_t center = std::distance(g_spatial, it);
    uint32_t bestA = 0, bestB = 0, minDist = 0xFFFFFFFF;
    int32_t finalLat = pLat, finalLon = pLon;

    for (intptr_t i = std::max((intptr_t)0, center - 500); i <= std::min((intptr_t)g_node_count - 1, center + 500); ++i) {
        uint32_t u = g_spatial[i].local_id;
        for (uint64_t j = g_edge_index[u]; j < g_edge_index[u + 1]; ++j) {
            Edge& e = g_edges[j];
            if (!is_accessible(e.type, mode)) continue;
            uint32_t v = e.target;
            Projection p = get_projection(pLat, pLon, g_nodes[u].lat_e7, g_nodes[u].lon_e7, g_nodes[v].lat_e7, g_nodes[v].lon_e7);
            if (p.dist_mm < minDist) {
                minDist = p.dist_mm; bestA = u; bestB = v; finalLat = p.lat_e7; finalLon = p.lon_e7;
            }
        }
    }
    return {bestA, bestB, finalLat, finalLon, haversine_mm(finalLat, finalLon, g_nodes[bestA].lat_e7, g_nodes[bestA].lon_e7), haversine_mm(finalLat, finalLon, g_nodes[bestB].lat_e7, g_nodes[bestB].lon_e7)};
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

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_vayunmathur_maps_OfflineRouter_findShortestRouteNative(JNIEnv* env, jobject thiz,
                                                                jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode) {
    SnappedEdge start = find_nearest_edge(sLat, sLon, mode);
    SnappedEdge end = find_nearest_edge(eLat, eLon, mode);

    std::vector<uint32_t> g_score(g_node_count, 0xFFFFFFFF);
    std::vector<uint32_t> parent(g_node_count, 0xFFFFFFFF);
    using NodeDist = std::pair<uint32_t, uint32_t>;
    std::priority_queue<NodeDist, std::vector<NodeDist>, std::greater<NodeDist>> pq;

    g_score[start.nodeA] = start.distToA;
    g_score[start.nodeB] = start.distToB;

    uint32_t hA = haversine_mm(g_nodes[start.nodeA].lat_e7, g_nodes[start.nodeA].lon_e7, end.proj_lat, end.proj_lon);
    uint32_t hB = haversine_mm(g_nodes[start.nodeB].lat_e7, g_nodes[start.nodeB].lon_e7, end.proj_lat, end.proj_lon);

    pq.push({g_score[start.nodeA] + hA, start.nodeA});
    pq.push({g_score[start.nodeB] + hB, start.nodeB});

    uint32_t found_end_node = 0xFFFFFFFF;

    while (!pq.empty()) {
        uint32_t u = pq.top().second; pq.pop();
        if (u == end.nodeA || u == end.nodeB) { found_end_node = u; break; }

        if (u + 1 >= g_node_count) continue;
        for (uint64_t i = g_edge_index[u]; i < g_edge_index[u + 1]; ++i) {
            Edge& e = g_edges[i];
            if (!is_accessible(e.type, mode)) continue;

            uint32_t mod_dist = (uint32_t)(e.dist_mm * get_weight_modifier(e.type, mode));
            if (g_score[u] + mod_dist < g_score[e.target]) {
                g_score[e.target] = g_score[u] + mod_dist;
                parent[e.target] = u;
                uint32_t h = haversine_mm(g_nodes[e.target].lat_e7, g_nodes[e.target].lon_e7, end.proj_lat, end.proj_lon);
                pq.push({g_score[e.target] + h, e.target});
            }
        }
    }

    std::vector<double> path;
    // 1. Output Start Snapped Point (LON, LAT)
    path.push_back(start.proj_lon / 1e7);
    path.push_back(start.proj_lat / 1e7);

    if (found_end_node != 0xFFFFFFFF) {
        std::vector<uint32_t> nodes;
        for (uint32_t c = found_end_node; c != 0xFFFFFFFF; c = parent[c]) nodes.push_back(c);
        std::reverse(nodes.begin(), nodes.end());
        for (uint32_t id : nodes) {
            // 2. Output Intermediate Nodes (LON, LAT)
            path.push_back(g_nodes[id].lon_e7 / 1e7);
            path.push_back(g_nodes[id].lat_e7 / 1e7);
        }
    }

    // 3. Output End Snapped Point (LON, LAT)
    path.push_back(end.proj_lon / 1e7);
    path.push_back(end.proj_lat / 1e7);

    jdoubleArray result = env->NewDoubleArray(path.size());
    env->SetDoubleArrayRegion(result, 0, path.size(), path.data());
    return result;
}