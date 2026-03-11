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

NodePos* g_nodes = nullptr;
uint64_t* g_edge_index = nullptr;
Edge* g_edges = nullptr;
SpatialNode* g_spatial = nullptr; // Added spatial mapping
size_t g_node_count = 0;

// Mapping function for Z-Order Curve
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

// Binary search on the spatial index to find the nearest routable node
uint32_t find_nearest_node(double lat, double lon) {
    uint64_t target_spatial = latlng_to_spatial(lat, lon);

    // Find first element not less than target
    auto it = std::lower_bound(g_spatial, g_spatial + g_node_count, target_spatial,
                               [](const SpatialNode& a, uint64_t val) {
                                   return a.spatial_id < val;
                               });

    // Check neighbors to find the absolute closest spatial ID
    uint32_t best_id = 0;
    if (it == g_spatial + g_node_count) {
        best_id = (g_spatial + g_node_count - 1)->local_id;
    } else if (it == g_spatial) {
        best_id = it->local_id;
    } else {
        // Compare current 'it' and 'prev(it)' for closest match
        uint64_t diff1 = it->spatial_id - target_spatial;
        uint64_t diff2 = target_spatial - (it - 1)->spatial_id;
        best_id = (diff1 < diff2) ? it->local_id : (it - 1)->local_id;
    }
    return best_id;
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
    // 1. SNAPPING: Find actual start and end IDs from GPS
    uint32_t start_id = find_nearest_node(sLat, sLon);
    uint32_t end_id = find_nearest_node(eLat, eLon);

    LOGD("Snapped Start: %u (%.7f, %.7f) | Snapped End: %u (%.7f, %.7f)",
         start_id, g_nodes[start_id].lat_e7/1e7, g_nodes[start_id].lon_e7/1e7,
         end_id, g_nodes[end_id].lat_e7/1e7, g_nodes[end_id].lon_e7/1e7);

    // 2. A* SEARCH
    std::vector<uint32_t> g_score(g_node_count, 0xFFFFFFFF);
    std::vector<uint32_t> parent(g_node_count, 0xFFFFFFFF);
    using NodeDist = std::pair<uint32_t, uint32_t>;
    std::priority_queue<NodeDist, std::vector<NodeDist>, std::greater<NodeDist>> pq;

    g_score[start_id] = 0;
    uint32_t h_initial = haversine_mm(g_nodes[start_id].lat_e7, g_nodes[start_id].lon_e7,
                                      g_nodes[end_id].lat_e7, g_nodes[end_id].lon_e7);
    pq.push({h_initial, start_id});

    uint32_t nodes_visited = 0;
    uint32_t min_h = h_initial;

    while (!pq.empty()) {
        uint32_t u = pq.top().second;
        pq.pop();
        nodes_visited++;

        uint32_t current_h = haversine_mm(g_nodes[u].lat_e7, g_nodes[u].lon_e7,
                                          g_nodes[end_id].lat_e7, g_nodes[end_id].lon_e7);

        if (current_h < min_h) {
            min_h = current_h;
            LOGD("[Progress] Closer: %.7f, %.7f (%.2f m to goal)",
                 g_nodes[u].lat_e7 / 1e7, g_nodes[u].lon_e7 / 1e7, current_h / 1000.0);
        }

        if (nodes_visited % 100 == 0) { // Increased log interval for performance
            LOGD("Visited: %u | MinDist: %.2f m", nodes_visited, min_h / 1000.0);
        }

        if (u == end_id) {
            LOGD("Path found! Nodes searched: %u", nodes_visited);
            break;
        }

        uint64_t start_off = g_edge_index[u];
        uint64_t end_off = g_edge_index[u + 1];

        for (uint64_t i = start_off; i < end_off; ++i) {
            Edge& e = g_edges[i];
            uint32_t v = e.target;
            if (g_score[u] + e.dist_mm < g_score[v]) {
                g_score[v] = g_score[u] + e.dist_mm;
                parent[v] = u;
                uint32_t h = haversine_mm(g_nodes[v].lat_e7, g_nodes[v].lon_e7,
                                          g_nodes[end_id].lat_e7, g_nodes[end_id].lon_e7);
                pq.push({g_score[v] + h, v});
            }
        }
    }

    // 3. RECONSTRUCTION
    std::vector<double> flat_coords;
    if (g_score[end_id] != 0xFFFFFFFF) {
        for (uint32_t curr = end_id; curr != 0xFFFFFFFF; curr = parent[curr]) {
            flat_coords.push_back(g_nodes[curr].lat_e7 / 1e7);
            flat_coords.push_back(g_nodes[curr].lon_e7 / 1e7);
        }
        std::reverse(flat_coords.begin(), flat_coords.end());
    } else {
        LOGD("Route not found between requested points.");
    }

    jdoubleArray result = env->NewDoubleArray(flat_coords.size());
    env->SetDoubleArrayRegion(result, 0, flat_coords.size(), flat_coords.data());
    return result;
}