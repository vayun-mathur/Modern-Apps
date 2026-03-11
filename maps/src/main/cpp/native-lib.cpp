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

#pragma pack(push, 1)
struct NodePos { int32_t lat_e7, lon_e7; };
struct Edge { uint32_t source, target, dist_mm; uint8_t type; };
struct SpatialNode { uint64_t spatial_id; uint32_t local_id; };
#pragma pack(pop)

// Global Mapped Memory Pointers
NodePos* g_nodes = nullptr;
uint64_t* g_edge_index = nullptr;
Edge* g_edges = nullptr;
SpatialNode* g_spatial = nullptr;
size_t g_node_count = 0;

// --- MATH HELPERS ---

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
    double dlat = lat2 - lat1;
    double dlon = lon2 - lon1;
    double a = sin(dlat / 2) * sin(dlat / 2) + cos(lat1) * cos(lat2) * sin(dlon / 2) * sin(dlon / 2);
    return (uint32_t)(2 * atan2(sqrt(a), sqrt(1 - a)) * 6371000.0 * 1000.0);
}

struct Projection {
    int32_t lat_e7, lon_e7;
    uint32_t dist_mm;
};

// Calculates the closest point on segment (x1,y1)-(x2,y2) to point (px, py)
Projection get_projection(int32_t px, int32_t py, int32_t x1, int32_t y1, int32_t x2, int32_t y2) {
    double dx = (double)x2 - x1;
    double dy = (double)y2 - y1;
    double mag_sq = dx * dx + dy * dy;

    double t = (mag_sq == 0) ? 0 : ((double)(px - x1) * dx + (double)(py - y1) * dy) / mag_sq;
    t = std::max(0.0, std::min(1.0, t)); // Clamp to segment boundaries

    int32_t proj_lat = (int32_t)(x1 + t * dx);
    int32_t proj_lon = (int32_t)(y1 + t * dy);

    return {proj_lat, proj_lon, haversine_mm(px, py, proj_lat, proj_lon)};
}

// --- SNAPPING LOGIC ---

struct SnappedEdge {
    uint32_t nodeA, nodeB;
    int32_t proj_lat, proj_lon;
    uint32_t distToA, distToB;
};

SnappedEdge find_nearest_edge(double lat, double lon) {
    uint64_t target_spatial = latlng_to_spatial(lat, lon);
    int32_t pLat = (int32_t)(lat * 1e7), pLon = (int32_t)(lon * 1e7);

    // Binary search for the starting point in the Z-Order array
    auto it = std::lower_bound(g_spatial, g_spatial + g_node_count, target_spatial,
                               [](const SpatialNode& a, uint64_t val) { return a.spatial_id < val; });

    intptr_t center = std::distance(g_spatial, it);
    uint32_t bestA = 0, bestB = 0, minDist = 0xFFFFFFFF;
    int32_t finalLat = pLat, finalLon = pLon;

    // Windowed search (500 neighbors each way) to find the physically closest edge
    for (intptr_t i = std::max((intptr_t)0, center - 500); i <= std::min((intptr_t)g_node_count - 1, center + 500); ++i) {
        uint32_t u = g_spatial[i].local_id;
        for (uint64_t j = g_edge_index[u]; j < g_edge_index[u + 1]; ++j) {
            uint32_t v = g_edges[j].target;
            Projection p = get_projection(pLat, pLon, g_nodes[u].lat_e7, g_nodes[u].lon_e7, g_nodes[v].lat_e7, g_nodes[v].lon_e7);
            if (p.dist_mm < minDist) {
                minDist = p.dist_mm;
                bestA = u; bestB = v;
                finalLat = p.lat_e7; finalLon = p.lon_e7;
            }
        }
    }

    return {bestA, bestB, finalLat, finalLon,
            haversine_mm(finalLat, finalLon, g_nodes[bestA].lat_e7, g_nodes[bestA].lon_e7),
            haversine_mm(finalLat, finalLon, g_nodes[bestB].lat_e7, g_nodes[bestB].lon_e7)};
}

// --- JNI INTERFACE ---

void* map_file(const char* path, size_t& size) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return nullptr;
    size = lseek(fd, 0, SEEK_END);
    void* addr = mmap(NULL, size, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    return addr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vayunmathur_maps_OfflineRouter_init(JNIEnv* env, jobject thiz, jstring base_path) {
    const char* path = env->GetStringUTFChars(base_path, 0);
    size_t s1, s2, s3, s4;
    std::string base(path);
    g_nodes = (NodePos*)map_file((base + "/nodes_lookup.bin").c_str(), s1);
    g_edge_index = (uint64_t*)map_file((base + "/edge_index.bin").c_str(), s2);
    g_edges = (Edge*)map_file((base + "/edges.bin").c_str(), s3);
    g_spatial = (SpatialNode*)map_file((base + "/nodes_spatial.bin").c_str(), s4);

    g_node_count = s1 / sizeof(NodePos);
    env->ReleaseStringUTFChars(base_path, path);
    return g_nodes && g_edge_index && g_edges && g_spatial;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_vayunmathur_maps_OfflineRouter_findShortestRouteNative(JNIEnv* env, jobject thiz,
                                                                jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon) {
    // 1. SNAPPING TO ROAD EDGES
    SnappedEdge start = find_nearest_edge(sLat, sLon);
    SnappedEdge end = find_nearest_edge(eLat, eLon);

    LOGD("Snapped Start to Road: %.7f, %.7f | Snapped End to Road: %.7f, %.7f",
         start.proj_lat/1e7, start.proj_lon/1e7, end.proj_lat/1e7, end.proj_lon/1e7);

    // 2. A* PREPARATION
    std::vector<uint32_t> g_score(g_node_count, 0xFFFFFFFF);
    std::vector<uint32_t> parent(g_node_count, 0xFFFFFFFF);
    using NodeDist = std::pair<uint32_t, uint32_t>;
    std::priority_queue<NodeDist, std::vector<NodeDist>, std::greater<NodeDist>> pq;

    // Seed the priority queue with BOTH endpoints of the starting road segment
    // Seed the priority queue with BOTH endpoints of the starting road segment
    g_score[start.nodeA] = start.distToA;
    g_score[start.nodeB] = start.distToB;

    // Use the actual snapped destination coordinates for the heuristic
    uint32_t hA = haversine_mm(g_nodes[start.nodeA].lat_e7, g_nodes[start.nodeA].lon_e7, end.proj_lat, end.proj_lon);
    uint32_t hB = haversine_mm(g_nodes[start.nodeB].lat_e7, g_nodes[start.nodeB].lon_e7, end.proj_lat, end.proj_lon);

    pq.push({g_score[start.nodeA] + hA, start.nodeA});
    pq.push({g_score[start.nodeB] + hB, start.nodeB});

    uint32_t nodes_visited = 0;
    uint32_t min_h = 0xFFFFFFFF;
    uint32_t found_end_node = 0xFFFFFFFF;

    // 3. A* SEARCH LOOP
    while (!pq.empty()) {
        uint32_t u = pq.top().second;
        pq.pop();
        nodes_visited++;

        // Heuristic distance to the snapped destination point
        uint32_t current_h = haversine_mm(g_nodes[u].lat_e7, g_nodes[u].lon_e7, end.proj_lat, end.proj_lon);

        if (current_h < min_h) {
            min_h = current_h;
            if (nodes_visited % 10 == 0) {
                LOGD("[Progress] Closer: %.7f, %.7f (%.2f m to goal)",
                     g_nodes[u].lat_e7 / 1e7, g_nodes[u].lon_e7 / 1e7, current_h / 1000.0);
            }
        }

        // Target condition: Did we reach either endpoint of the destination's road?
        if (u == end.nodeA || u == end.nodeB) {
            found_end_node = u;
            LOGD("Path found! Nodes searched: %u", nodes_visited);
            break;
        }

        if (u + 1 >= g_node_count) continue; // Safety bounds check for index
        uint64_t start_off = g_edge_index[u];
        uint64_t end_off = g_edge_index[u + 1];

        for (uint64_t i = start_off; i < end_off; ++i) {
            Edge& e = g_edges[i];
            uint32_t v = e.target;
            if (g_score[u] + e.dist_mm < g_score[v]) {
                g_score[v] = g_score[u] + e.dist_mm;
                parent[v] = u;
                // Cost = dist_traveled + estimated_remaining_to_snapped_goal
                uint32_t h = haversine_mm(g_nodes[v].lat_e7, g_nodes[v].lon_e7, end.proj_lat, end.proj_lon);
                pq.push({g_score[v] + h, v});
            }
        }
    }

    // 4. PATH RECONSTRUCTION
    std::vector<double> final_path;

    // Add the snapped start coordinate first
    final_path.push_back(start.proj_lon / 1e7);
    final_path.push_back(start.proj_lat / 1e7);

    if (found_end_node != 0xFFFFFFFF) {
        std::vector<uint32_t> path_nodes;
        for (uint32_t curr = found_end_node; curr != 0xFFFFFFFF; curr = parent[curr]) {
            path_nodes.push_back(curr);
        }
        std::reverse(path_nodes.begin(), path_nodes.end());
        for (uint32_t id : path_nodes) {
            final_path.push_back(g_nodes[id].lon_e7 / 1e7);
            final_path.push_back(g_nodes[id].lat_e7 / 1e7);
        }
    } else {
        LOGD("Route not found between snapped points.");
    }

    // Add the snapped end coordinate last
    final_path.push_back(end.proj_lon / 1e7);
    final_path.push_back(end.proj_lat / 1e7);

    jdoubleArray result = env->NewDoubleArray(final_path.size());
    env->SetDoubleArrayRegion(result, 0, final_path.size(), final_path.data());
    return result;
}