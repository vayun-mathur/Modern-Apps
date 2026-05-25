#include <jni.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <mutex>
#include <set>
#include <map>
#include <android/log.h>
#include "sqlite3.h"
#include <zlib.h>

#include "scratchpad.h"
#include "radix_heap.h"

#define LOG_TAG "OfflineRouterNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// User-specified Travel Mode mapping
enum TravelMode { DRIVING = 0, PUBLIC_TRANSIT = 1, WALK = 2, BICYCLE = 3 };

// Road types based on OpenStreetMap tags
enum RoadType {
    MOTORWAY = 1, TRUNK = 2, PRIMARY = 3, SECONDARY = 4, TERTIARY = 5,
    UNCLASSIFIED = 6, RESIDENTIAL = 7, SERVICE = 8, LIVING_STREET = 9,
    PEDESTRIAN = 10, TRACK = 11, FOOTWAY = 12, CYCLEWAY = 13, PATH = 14, STEPS = 15
};

#define TRANSIT_FLAG 0x80

#pragma pack(push, 1)
struct NodeMaster {
    int32_t lat_e7, lon_e7;
    uint64_t spatial_id;
    uint32_t edge_ptr;
    uint32_t stop_code_off;
    uint32_t feed_name_off;
};

struct Edge {
    uint32_t target;
    uint32_t dist_mm;
    uint32_t name_offset;
    uint8_t type;
    uint8_t speed_limit;
};

struct TransitVoyage {
    uint32_t dep_10ms;
    uint32_t arr_10ms;
};
#pragma pack(pop)

// --- GLOBALS ---
const int NUM_ZONES = 64;
NodeMaster* g_node_zones[NUM_ZONES] = {nullptr};
Edge* g_edge_zones[NUM_ZONES] = {nullptr};
size_t g_edge_count_in_zone[NUM_ZONES] = {0};
TransitVoyage* g_transit_voyages[NUM_ZONES] = {nullptr};
size_t g_transit_voyage_count[NUM_ZONES] = {0};

uint32_t g_zone_offsets[NUM_ZONES + 1] = {0};
char* g_road_names = nullptr;
size_t g_road_names_size = 0;
std::string g_base_path;

uint64_t g_time_scale_fixed[4]; // Heuristic scales (10ms units per mm)
uint64_t g_edge_time_multipliers[4][16]; // Speed-to-time conversion factors for WALK/BIKE/DRIVING(fallback)
const double WALK_SPEED_M_S = 4.5 / 3.6;
const double BICYCLE_SPEED_M_S = 16.0 / 3.6;
const double DEG_TO_RAD = M_PI / 180.0;

uint32_t g_lon_to_mm_scale[4096];

static RoutingScratchpad g_scratchpad;
static TrafficPageTable g_traffic_zones[NUM_ZONES];
static RadixHeap g_heap; // Only one heap needed for unidirectional

static std::map<int, std::vector<double>> g_traffic_by_square;
static std::vector<uint32_t> g_requested_squares; // Packed (lat_idx << 16 | lon_idx)
static std::set<std::string> g_present_feeds;
static std::mutex g_traffic_mutex;
static sqlite3* g_db = nullptr;

// --- UTILS ---

static std::vector<uint8_t> compress_gzip(const std::vector<uint8_t>& input) {
    if (input.empty()) return {};
    z_stream zs;
    memset(&zs, 0, sizeof(zs));
    if (deflateInit2(&zs, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY) != Z_OK) return {};
    zs.next_in = (Bytef*)input.data();
    zs.avail_in = input.size();
    int ret;
    char buffer[32768];
    std::vector<uint8_t> output;
    do {
        zs.next_out = (Bytef*)buffer;
        zs.avail_out = sizeof(buffer);
        ret = deflate(&zs, Z_FINISH);
        if (output.size() < zs.total_out) {
            output.insert(output.end(), (uint8_t*)buffer, (uint8_t*)buffer + (zs.total_out - output.size()));
        }
    } while (ret == Z_OK);
    deflateEnd(&zs);
    if (ret != Z_STREAM_END) return {};
    return output;
}

inline bool is_zone_mapped(int zone) {
    return zone >= 0 && zone < NUM_ZONES && g_node_zones[zone] != nullptr;
}

inline uint32_t find_node_idx_for_edge(int zone, uint32_t local_edge_idx) {
    if (!is_zone_mapped(zone)) return 0;
    uint32_t node_count = g_zone_offsets[zone + 1] - g_zone_offsets[zone];
    int32_t low = 0, high = (int32_t)node_count - 1;
    uint32_t res = 0;
    while (low <= high) {
        int32_t mid = low + (high - low) / 2;
        if (g_node_zones[zone][mid].edge_ptr <= local_edge_idx) {
            res = (uint32_t)mid;
            low = mid + 1;
        } else {
            high = mid - 1;
        }
    }
    return res;
}

inline int get_zone_for_id(uint32_t global_id) {
    for (int i = 0; i < 64; ++i) {
        if (global_id < g_zone_offsets[i+1]) return i;
    }
    return -1;
}

static void load_traffic_from_db(int packed_square) {
    if (!g_db) return;
    sqlite3_stmt* stmt;
    sqlite3_prepare_v2(g_db, "SELECT zone_id, edge_id, speed FROM raw_traffic WHERE square_id = ?;", -1, &stmt, nullptr);
    sqlite3_bind_int(stmt, 1, packed_square);
    auto& segments = g_traffic_by_square[packed_square];
    segments.clear();
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        int zone_id = sqlite3_column_int(stmt, 0);
        uint32_t local_id = (uint32_t)sqlite3_column_int(stmt, 1);
        uint8_t speed = (uint8_t)sqlite3_column_int(stmt, 2);
        if (is_zone_mapped(zone_id) && local_id < g_edge_count_in_zone[zone_id]) {
            g_traffic_zones[zone_id].set_speed(local_id, speed);
            const Edge& edge = g_edge_zones[zone_id][local_id];
            const NodeMaster& node_u = g_node_zones[zone_id][find_node_idx_for_edge(zone_id, local_id)];
            int zone_v = get_zone_for_id(edge.target);
            if (is_zone_mapped(zone_v)) {
                const NodeMaster& node_v = g_node_zones[zone_v][edge.target - g_zone_offsets[zone_v]];
                double ratio = (edge.speed_limit > 0) ? (double)speed / edge.speed_limit : 1.0;
                segments.push_back(node_u.lat_e7 * 1e-7); segments.push_back(node_u.lon_e7 * 1e-7);
                segments.push_back(node_v.lat_e7 * 1e-7); segments.push_back(node_v.lon_e7 * 1e-7);
                segments.push_back(ratio);
            }
        }
    }
    sqlite3_finalize(stmt);
}

// --- MVT ENCODING UTILS ---
static void write_varint(std::vector<uint8_t>& buf, uint64_t value) {
    while (value >= 0x80) {
        buf.push_back((uint8_t)(value | 0x80));
        value >>= 7;
    }
    buf.push_back((uint8_t)value);
}

static void write_tag(std::vector<uint8_t>& buf, uint32_t field, uint8_t type) {
    write_varint(buf, (field << 3) | type);
}

static void write_string(std::vector<uint8_t>& buf, uint32_t field, const std::string& s) {
    write_tag(buf, field, 2);
    write_varint(buf, s.size());
    buf.insert(buf.end(), s.begin(), s.end());
}

static int32_t zigzag(int32_t n) { return (n << 1) ^ (n >> 31); }

static uint32_t mvt_command(uint32_t cmd, uint32_t count) { return (cmd & 0x7) | (count << 3); }

struct TileProj {
    double n;
    int x, y;
    void wgs84_to_tile_px(double lat, double lon, int32_t& px, int32_t& py) const {
        double lat_rad = lat * M_PI / 180.0;
        double tx = (lon + 180.0) / 360.0 * n;
        double ty = (1.0 - std::log(std::tan(lat_rad) + (1.0 / std::cos(lat_rad))) / M_PI) / 2.0 * n;
        px = (int32_t)((tx - x) * 4096.0);
        py = (int32_t)((ty - y) * 4096.0);
    }
};

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

inline const NodeMaster& get_node(uint32_t global_id) {
    int zone = 0;
    for (int i = 0; i < 64; ++i) {
        if (global_id < g_zone_offsets[i+1]) { zone = i; break; }
    }
    if (!g_node_zones[zone]) {
        static NodeMaster null_node = {0,0,0,0,0,0};
        return null_node;
    }
    return g_node_zones[zone][global_id - g_zone_offsets[zone]];
}

void ensure_traffic_loaded(JNIEnv* env, jobject thiz, int32_t lat_e7, int32_t lon_e7, bool force_async) {
    int32_t lat_idx = (int32_t)floor(lat_e7 * 1e-7);
    int32_t lon_idx = (int32_t)floor(lon_e7 * 1e-7);
    uint32_t packed = ((uint32_t)(lat_idx + 360) << 16) | (uint32_t)(lon_idx + 720);

    {
        std::lock_guard<std::mutex> lock(g_traffic_mutex);
        if (std::find(g_requested_squares.begin(), g_requested_squares.end(), packed) != g_requested_squares.end()) {
            return;
        }
        LOGD("ensure_traffic_loaded: REQUESTING square %u", packed);
        g_requested_squares.push_back(packed);
    }

    uint64_t spatial = latlng_to_spatial(lat_e7 * 1e-7, lon_e7 * 1e-7);
    int zone_id = (int)((spatial >> 58) & 0x3F);

    jclass clazz = env->GetObjectClass(thiz);
    jmethodID method = env->GetMethodID(clazz, "fetchTrafficData", "(DDDDIIZ)V");
    if (method) {
        LOGD("ensure_traffic_loaded: CALLING fetchTrafficData for square %u (zone %d), async=%d", packed, zone_id, force_async);
        env->CallVoidMethod(thiz, method, (double)lat_idx, (double)lon_idx, (double)lat_idx + 1.0, (double)lon_idx + 1.0, zone_id, (jint)packed, (jboolean)force_async);
        LOGD("ensure_traffic_loaded: RETURNED from fetchTrafficData for square %u", packed);
    }
}

// --- ROAD PERMISSIONS ---

inline bool is_mode_allowed(uint8_t road_type, int mode) {
    uint8_t type = road_type & 0x7F;
    if (mode == DRIVING) {
        return (type >= MOTORWAY && type <= LIVING_STREET);
    }
    if (mode == PUBLIC_TRANSIT) {
        return (road_type & TRANSIT_FLAG) || (type >= MOTORWAY && type <= STEPS);
    }
    return (type >= MOTORWAY && type <= STEPS);
}

// --- GEOMETRY ---

__attribute__((always_inline)) inline uint32_t fast_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    int64_t dlat = std::abs((int64_t)lat1_e7 - lat2_e7);
    int64_t dlon = std::abs((int64_t)lon1_e7 - lon2_e7);

    // 1 unit (1e-7 deg) latitude is ~11.1139 mm
    int64_t dy_mm = (dlat * 111139) / 10000;

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
        if (zone_id >= 0 && zone_id < NUM_ZONES) traffic_speed = g_traffic_zones[zone_id].get_speed(local_edge_id);
        uint8_t effective_limit = (traffic_speed > 0) ? traffic_speed : limit;
        if (effective_limit > 0) {
            double speed_m_s = (double)effective_limit / 3.6;
            return (uint32_t)((double)dist_mm / (speed_m_s * 10.0));
        }
    }
    uint64_t multiplier = g_edge_time_multipliers[mode & 0x3][(type & 0x7F) & 0xF];
    return (uint32_t)(((uint64_t)dist_mm * multiplier) >> 32);
}

inline uint32_t get_transit_edge_time_10ms(int zone, const Edge& edge, uint32_t current_time_from_start, uint32_t start_time_abs, uint32_t feed_off, bool is_boarding_or_transfer, uint32_t& wait_out, uint32_t& travel_out) {
    wait_out = 0; travel_out = 0;
    if (zone < 0 || !g_transit_voyages[zone]) return 0xFFFFFFFF;

    uint32_t voyage_offset = edge.dist_mm;
    uint32_t voyage_count = edge.speed_limit;
    if (voyage_count == 0) return 0xFFFFFFFF;

    const char* route_name = (edge.name_offset < g_road_names_size) ? (g_road_names + edge.name_offset) : "Unknown";

    if (feed_off != 0xFFFFFFFF && feed_off < g_road_names_size) {
        const char* feed_name = g_road_names + feed_off;
        if (g_present_feeds.find(feed_name) == g_present_feeds.end()) {
            TransitVoyage* base = g_transit_voyages[zone] + voyage_offset;
            wait_out = 0; travel_out = base[0].arr_10ms - base[0].dep_10ms;
            return travel_out;
        }
    }

    const uint32_t BOARDING_PENALTY = 6000; // 1 minute to platform
    uint32_t abs_now = (start_time_abs + current_time_from_start) % (24 * 3600 * 100);
    uint32_t arrival_at_platform = (abs_now + (is_boarding_or_transfer ? BOARDING_PENALTY : 0)) % (24 * 3600 * 100);
    const uint32_t DAY_10MS = 24 * 3600 * 100;

    TransitVoyage* base = g_transit_voyages[zone] + voyage_offset;
    uint32_t best_wait = 0xFFFFFFFF;
    uint32_t best_travel = 0;

    for (uint32_t i = 0; i < voyage_count; ++i) {
        uint32_t dep = base[i].dep_10ms;
        uint32_t wait = 0;
        if (dep >= arrival_at_platform) {
            wait = dep - arrival_at_platform;
        } else {
            wait = (DAY_10MS - arrival_at_platform) + dep;
        }
        if (wait < best_wait) {
            best_wait = wait;
            if (base[i].arr_10ms >= dep) {
                best_travel = base[i].arr_10ms - dep;
            } else {
                best_travel = (DAY_10MS - dep) + base[i].arr_10ms;
            }
        }
    }

    if (best_wait == 0xFFFFFFFF) return 0xFFFFFFFF;

    wait_out = best_wait; travel_out = best_travel;
    return (is_boarding_or_transfer ? BOARDING_PENALTY : 0) + best_wait + best_travel;
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
    uint32_t startTime = 0;
};

struct Projection { int32_t lat_e7, lon_e7; uint32_t dist_mm; };

Projection get_projection(int32_t px, int32_t py, int32_t x1, int32_t y1, int32_t x2, int32_t y2) {
    double lat_avg = (x1 + x2) / 2.0 * 1e-7;
    double lon_scale = cos(lat_avg * DEG_TO_RAD);

    double dx = (double)x2 - x1;
    double dy = ((double)y2 - y1) * lon_scale;
    double dpx = (double)px - x1;
    double dpy = ((double)py - y1) * lon_scale;

    double mag_sq = dx * dx + dy * dy;
    double t = (mag_sq == 0) ? 0 : (dpx * dx + dpy * dy) / mag_sq;
    t = std::max(0.0, std::min(1.0, t));

    int32_t proj_lat = (int32_t)(x1 + t * (x2 - x1));
    int32_t proj_lon = (int32_t)(y1 + t * (y2 - y1));
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
            if (g_node_zones[z][mid].spatial_id < target_spatial) {
                local_center = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        int window = 4000;
        for (int i = std::max(0, (int)local_center - window); i <= std::min((int)zone_node_count - 1, (int)local_center + window); ++i) {
            uint32_t u_global = g_zone_offsets[z] + i;
            const auto& node_u = g_node_zones[z][i];
            uint32_t e_ptr = (i + 1 < zone_node_count) ? g_node_zones[z][i+1].edge_ptr : (uint32_t)g_edge_count_in_zone[z];
            for (uint32_t j = node_u.edge_ptr; j < e_ptr; ++j) {
                Edge& e = g_edge_zones[z][j];
                // Snapping directly to transit lines is forbidden.
                if (e.type & TRANSIT_FLAG) continue;
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

bool prepare_routing(JNIEnv* env, jobject thiz, double sLat, double sLon, double eLat, double eLon, int mode, uint32_t startTime, RoutingContext& ctx) {
    LOGD("prepare_routing: mode=%d, start=(%f,%f), end=(%f,%f)", mode, sLat, sLon, eLat, eLon);
    g_scratchpad.reset(); g_heap.clear();
    ctx.iterations = 0; ctx.target_node = 0xFFFFFFFF; ctx.startTime = startTime;
    ctx.start = find_nearest_edge(sLat, sLon, mode);
    ctx.end = find_nearest_edge(eLat, eLon, mode);
    if (ctx.start.nodeA == 0xFFFFFFFF) {
        LOGE("prepare_routing: failed to find start edge");
        return false;
    }
    if (ctx.end.nodeA == 0xFFFFFFFF) {
        LOGE("prepare_routing: failed to find end edge");
        return false;
    }
    LOGD("prepare_routing: start snapped to nodeA=%u, nodeB=%u", ctx.start.nodeA, ctx.start.nodeB);
    if (mode == DRIVING) ensure_traffic_loaded(env, thiz, ctx.start.proj_lat, ctx.start.proj_lon, false);
    auto push = [&](uint32_t node, uint32_t travel_dist_mm) {
        uint32_t t_actual = get_edge_time_10ms(-1, 0, travel_dist_mm, ctx.start.type, ctx.start.speed_limit, mode);
        auto& entry = g_scratchpad.get_entry(node, 0);
        entry.g_fwd = t_actual;
        entry.g_bwd = t_actual;
        entry.last_type = ctx.start.type;
        const auto& n_data = get_node(node);
        uint32_t h = heuristic_time_10ms(n_data.lat_e7, n_data.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode);
        g_heap.push(t_actual + h, node << 1); // Start in state 0
    };
    push(ctx.start.nodeA, ctx.start.distA_mm); push(ctx.start.nodeB, ctx.start.distB_mm);
    return true;
}

void perform_search_loop(JNIEnv* env, jobject thiz, int mode, RoutingContext& ctx) {
    LOGD("perform_search_loop: starting search");
    while (!g_heap.empty() && ctx.iterations < 1000000) {
        ctx.iterations++;
        uint32_t u_idx = g_heap.pop();
        uint32_t u = u_idx >> 1;
        int u_state = u_idx & 1;

        if ((u == ctx.end.nodeA || u == ctx.end.nodeB) && (mode != PUBLIC_TRANSIT || u_state == 1)) {
            ctx.target_node = u_idx;
            LOGD("perform_search_loop: target found in %d iterations", ctx.iterations);
            break;
        }

        auto& entry_u = g_scratchpad.get_entry(u, u_state);
        uint32_t u_cost = entry_u.g_fwd;
        uint32_t u_actual_time = entry_u.g_bwd;
        int zone_u = get_zone_for_id(u);
        if (zone_u < 0 || !g_node_zones[zone_u]) continue;
        const auto& n_u = g_node_zones[zone_u][u - g_zone_offsets[zone_u]];

        if (mode == DRIVING) ensure_traffic_loaded(env, thiz, n_u.lat_e7, n_u.lon_e7, false);
        uint32_t s = n_u.edge_ptr;
        uint32_t node_idx = u - g_zone_offsets[zone_u];
        uint32_t node_count = g_zone_offsets[zone_u + 1] - g_zone_offsets[zone_u];
        uint32_t e_ptr = (node_idx + 1 < node_count) ? g_node_zones[zone_u][node_idx + 1].edge_ptr : (uint32_t)g_edge_count_in_zone[zone_u];

        for (uint32_t i = s; i < e_ptr; ++i) {
            Edge& edge = g_edge_zones[zone_u][i];
            if (!is_mode_allowed(edge.type, mode)) continue;

            uint32_t travel_time;
            int v_state = (mode == PUBLIC_TRANSIT && (edge.type & TRANSIT_FLAG)) ? 1 : 0;
            if (v_state == 1) {
                bool is_boarding = !(entry_u.last_type & TRANSIT_FLAG);
                bool is_transfer = !is_boarding && (edge.name_offset != entry_u.last_name_off);

                // FIX: Only allow boarding or transfers at recognized transit stops
                if ((is_boarding || is_transfer) && n_u.stop_code_off == 0xFFFFFFFF) continue;

                uint32_t wait, transit_move;
                travel_time = get_transit_edge_time_10ms(zone_u, edge, u_actual_time, ctx.startTime, n_u.feed_name_off, is_boarding || is_transfer, wait, transit_move);
                if (travel_time == 0xFFFFFFFF) continue;
            } else {
                // FIX: Only allow alighting from transit at recognized transit stops
                if (mode == PUBLIC_TRANSIT && (entry_u.last_type & TRANSIT_FLAG)) {
                    if (n_u.stop_code_off == 0xFFFFFFFF) continue;
                }
                travel_time = get_edge_time_10ms(zone_u, i, edge.dist_mm, edge.type, edge.speed_limit, mode);
            }

            uint32_t v = edge.target;
            uint32_t new_g = u_cost + travel_time;
            auto& entry_v = g_scratchpad.get_entry(v, v_state);
            if (new_g < entry_v.g_fwd) {
                entry_v.g_fwd = new_g;
                entry_v.g_bwd = new_g;
                entry_v.p_fwd = u_idx;
                entry_v.last_type = edge.type;
                entry_v.last_name_off = edge.name_offset;
                const auto& n_v = get_node(v);
                uint32_t h = heuristic_time_10ms(n_v.lat_e7, n_v.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode);
                g_heap.push(new_g + h, (v << 1) | v_state);
            }
        }
    }
}

jobjectArray reconstruct_path(JNIEnv* env, int mode, const RoutingContext& ctx) {
    LOGD("reconstruct_path: mode=%d, target_idx=%u", mode, ctx.target_node);
    std::vector<uint32_t> path_indices;
    uint32_t curr = ctx.target_node;
    uint32_t safety = 0;
    while (curr != 0xFFFFFFFF && safety < 1000000) {
        path_indices.push_back(curr);
        curr = g_scratchpad.get_entry(curr >> 1, curr & 1).p_fwd;
        safety++;
    }
    std::reverse(path_indices.begin(), path_indices.end());
    if (path_indices.empty()) return nullptr;

    std::vector<uint32_t> path_nodes;
    for (uint32_t idx : path_indices) path_nodes.push_back(idx >> 1);

    struct StepData {
        uint32_t name_off; uint64_t dist_mm = 0; uint64_t time_10ms = 0;
        std::vector<double> coords; int maneuver = 0;
        double speed_ratio = 1.0;
        bool is_transit = false;
        uint32_t feed_off = 0xFFFFFFFF;
        uint32_t code_off = 0xFFFFFFFF;
        uint32_t end_code_off = 0xFFFFFFFF;
        int stop_count = 0;
    };
    std::vector<StepData> steps; double last_bearing = 0;
    uint32_t current_elapsed_10ms = 0;
    bool last_was_transit = false;

    auto add_segment = [&](double lat1, double lon1, double lat2, double lon2, uint32_t name_off, uint8_t type, uint8_t limit, uint32_t dist_mm, int zone_id, uint32_t local_edge_idx, uint32_t feed_off, uint32_t code_off, uint32_t target_code_off) {
        double ratio = 1.0;
        uint8_t traffic_speed = 0;
        if (zone_id >= 0 && local_edge_idx != 0xFFFFFFFF) {
            traffic_speed = g_traffic_zones[zone_id].get_speed(local_edge_idx);
            if (traffic_speed > 0 && limit > 0) ratio = (double)traffic_speed / limit;
        }

        uint32_t time_10ms;
        bool is_transit = false;
        if (mode == PUBLIC_TRANSIT && (type & TRANSIT_FLAG)) {
            is_transit = true;
            if (local_edge_idx != 0xFFFFFFFF && zone_id != -1) {
                bool is_boarding = !last_was_transit;
                bool is_transfer = last_was_transit && (name_off != steps.back().name_off);
                uint32_t wait, transit_move;
                time_10ms = get_transit_edge_time_10ms(zone_id, g_edge_zones[zone_id][local_edge_idx], current_elapsed_10ms, ctx.startTime, feed_off, is_boarding || is_transfer, wait, transit_move);
                if (time_10ms == 0xFFFFFFFF) { time_10ms = 0; wait = 0; transit_move = 0; }

                if ((is_boarding || is_transfer)) {
                    uint32_t penalty = 6000;
                    steps.push_back({name_off, 0, wait + penalty, {lon1, lat1}, 21, 1.0, true, feed_off, code_off});
                    current_elapsed_10ms += (wait + penalty);
                    time_10ms = transit_move;
                } else {
                    time_10ms = transit_move;
                }
            } else {
                time_10ms = 0;
            }
        } else {
            time_10ms = get_edge_time_10ms(zone_id, local_edge_idx, dist_mm, type, limit, mode);
        }
        current_elapsed_10ms += time_10ms;
        last_was_transit = (type & TRANSIT_FLAG);
        double bearing = get_bearing((int32_t)(lat1 * 1e7), (int32_t)(lon1 * 1e7), (int32_t)(lat2 * 1e7), (int32_t)(lon2 * 1e7));

        auto get_ratio_cat = [](double r) {
            if (r < 0.5) return 0;
            if (r < 0.9) return 1;
            return 2;
        };

        int maneuver = steps.empty() ? 0 : get_maneuver(last_bearing, bearing);
        if (steps.empty() || name_off != steps.back().name_off || get_ratio_cat(ratio) != get_ratio_cat(steps.back().speed_ratio) || is_transit != steps.back().is_transit || (maneuver != 9 && maneuver != 0) || steps.back().maneuver == 21) {
            steps.push_back({name_off, 0, 0, {lon1, lat1}, maneuver, ratio, is_transit, feed_off, code_off, 0xFFFFFFFF, 0});
        }
        steps.back().dist_mm += dist_mm;
        steps.back().time_10ms += time_10ms;
        steps.back().coords.push_back(lon2);
        steps.back().coords.push_back(lat2);
        if (is_transit && steps.back().maneuver != 21) {
            steps.back().end_code_off = target_code_off;
            steps.back().stop_count++;
        }
        last_bearing = bearing;
    };

    // 1. Start stub: proj_s -> path_nodes[0]
    {
        uint32_t n0 = path_nodes[0];
        const auto& node0 = get_node(n0);
        uint32_t dist = (n0 == ctx.start.nodeA) ? ctx.start.distA_mm : ctx.start.distB_mm;
        add_segment(ctx.start.proj_lat * 1e-7, ctx.start.proj_lon * 1e-7, node0.lat_e7 * 1e-7, node0.lon_e7 * 1e-7,
                    ctx.start.name_offset, ctx.start.type, ctx.start.speed_limit, dist, -1, 0xFFFFFFFF, node0.feed_name_off, node0.stop_code_off, node0.stop_code_off);
    }

    // 2. Main path segments
    for (size_t i = 0; i < path_nodes.size() - 1; ++i) {
        uint32_t u = path_nodes[i], v = path_nodes[i+1];
        int z_u = get_zone_for_id(u);
        if (z_u < 0 || !g_node_zones[z_u]) continue;
        const auto& node_u = g_node_zones[z_u][u - g_zone_offsets[z_u]];
        const auto& node_v = get_node(v);

        uint32_t s = node_u.edge_ptr;
        uint32_t node_idx = u - g_zone_offsets[z_u];
        uint32_t node_count = g_zone_offsets[z_u + 1] - g_zone_offsets[z_u];
        uint32_t e_ptr = (node_idx + 1 < node_count) ? g_node_zones[z_u][node_idx + 1].edge_ptr : (uint32_t)g_edge_count_in_zone[z_u];

        uint32_t best_e_idx = 0xFFFFFFFF;
        for (uint32_t k = s; k < e_ptr; ++k) {
            if (g_edge_zones[z_u][k].target == v) {
                best_e_idx = k; break;
            }
        }
        if (best_e_idx == 0xFFFFFFFF) continue;

        const Edge& e = g_edge_zones[z_u][best_e_idx];
        uint32_t d = e.dist_mm;
        // Since e.dist_mm serves as the voyage offsets on transit edges,
        // it cannot be treated as physical distance. Calculate dynamically.
        if (d == 0 || (e.type & TRANSIT_FLAG)) {
            d = accurate_dist_mm(node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
        }

        add_segment(node_u.lat_e7 * 1e-7, node_u.lon_e7 * 1e-7, node_v.lat_e7 * 1e-7, node_v.lon_e7 * 1e-7,
                    e.name_offset, e.type, e.speed_limit, d, z_u, best_e_idx, node_u.feed_name_off, node_u.stop_code_off, node_v.stop_code_off);
    }

    // 3. End stub: path_nodes.back() -> proj_e
    {
        uint32_t nk = path_nodes.back();
        const auto& nodek = get_node(nk);
        uint32_t dist = (nk == ctx.end.nodeA) ? ctx.end.distA_mm : ctx.end.distB_mm;
        add_segment(nodek.lat_e7 * 1e-7, nodek.lon_e7 * 1e-7, ctx.end.proj_lat * 1e-7, ctx.end.proj_lon * 1e-7,
                    ctx.end.name_offset, ctx.end.type, ctx.end.speed_limit, dist, -1, 0xFFFFFFFF, nodek.feed_name_off, nodek.stop_code_off, 0xFFFFFFFF);
    }

    jclass stepClass = env->FindClass("com/vayunmathur/maps/util/OfflineRouter$RawStep");
    if (!stepClass) {
        LOGE("reconstruct_path: could not find RawStep class");
        return nullptr;
    }
    jmethodID stepCtor = env->GetMethodID(stepClass, "<init>", "(ILjava/lang/String;JJ[DDZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    if (!stepCtor) {
        LOGE("reconstruct_path: could not find RawStep constructor");
        return nullptr;
    }
    LOGD("reconstruct_path: creating array of size %zu", steps.size());
    jobjectArray res = env->NewObjectArray(steps.size(), stepClass, nullptr);
    for (size_t i = 0; i < steps.size(); ++i) {
        const char* name_ptr = (steps[i].name_off < g_road_names_size) ? (g_road_names + steps[i].name_off) : "Unknown Road";
        jstring jName = env->NewStringUTF(name_ptr); jdoubleArray jGeom = env->NewDoubleArray(steps[i].coords.size());
        env->SetDoubleArrayRegion(jGeom, 0, steps[i].coords.size(), steps[i].coords.data());

        jstring jFeed = nullptr;
        if (steps[i].feed_off != 0xFFFFFFFF && steps[i].feed_off < g_road_names_size) {
            jFeed = env->NewStringUTF(g_road_names + steps[i].feed_off);
        }
        jstring jCode = nullptr;
        if (steps[i].code_off != 0xFFFFFFFF && steps[i].code_off < g_road_names_size) {
            jCode = env->NewStringUTF(g_road_names + steps[i].code_off);
        }
        jstring jEndCode = nullptr;
        if (steps[i].end_code_off != 0xFFFFFFFF && steps[i].end_code_off < g_road_names_size) {
            jEndCode = env->NewStringUTF(g_road_names + steps[i].end_code_off);
        }

        jobject stepObj = env->NewObject(stepClass, stepCtor, (jint)steps[i].maneuver, jName, (jlong)steps[i].dist_mm, (jlong)steps[i].time_10ms, jGeom, (jdouble)steps[i].speed_ratio, (jboolean)steps[i].is_transit, jFeed, jCode, jEndCode, (jint)steps[i].stop_count);
        env->SetObjectArrayElement(res, i, stepObj);
        env->DeleteLocalRef(jName); env->DeleteLocalRef(jGeom); env->DeleteLocalRef(stepObj);
        if (jFeed) env->DeleteLocalRef(jFeed);
        if (jCode) env->DeleteLocalRef(jCode);
        if (jEndCode) env->DeleteLocalRef(jEndCode);
    }
    return res;
}

// --- JNI INTERFACE ---

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_init(JNIEnv* env, jobject thiz, jstring base_path, jobjectArray present_feeds) {
    const char* path_raw = env->GetStringUTFChars(base_path, nullptr); std::string base(path_raw);
    LOGD("Native init started with path: %s", path_raw);
    if (!base.empty() && base.back() != '/') base += "/";
    g_base_path = base;

    g_present_feeds.clear();
    if (present_feeds) {
        int len = env->GetArrayLength(present_feeds);
        for (int i = 0; i < len; ++i) {
            jstring s = (jstring)env->GetObjectArrayElement(present_feeds, i);
            const char* raw = env->GetStringUTFChars(s, nullptr);
            g_present_feeds.insert(raw);
            env->ReleaseStringUTFChars(s, raw);
            env->DeleteLocalRef(s);
        }
    }

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
        size_t s_n, s_e, s_t;
        g_node_zones[i] = (NodeMaster*)m_file(base + "nodes_zone_" + std::to_string(i) + ".bin", s_n);
        g_edge_zones[i] = (Edge*)m_file(base + "edges_zone_" + std::to_string(i) + ".bin", s_e);
        g_edge_count_in_zone[i] = s_e / sizeof(Edge);
        g_transit_voyages[i] = (TransitVoyage*)m_file(base + "transit_voyages_zone_" + std::to_string(i) + ".bin", s_t);
        g_transit_voyage_count[i] = s_t / sizeof(TransitVoyage);
    }
    munmap(meta, s_meta);
    size_t s_r; g_road_names = (char*)m_file(base + "road_names.bin", s_r); g_road_names_size = s_r;
    for (int i = 0; i < 4096; ++i) {
        double lat_deg = ((double)((int64_t)(i - 2048) << 19)) / 1e7;
        g_lon_to_mm_scale[i] = (uint32_t)((111139000.0 / 1e7) * cos(lat_deg * DEG_TO_RAD) * 1024.0);
    }
    auto calc_scale = [](double speed_m_s) { return (uint64_t)((100.0 / (speed_m_s * 1000.0)) * 4294967296.0); };

    // HEURISTIC SCALES FIX: We increase transit speed bounds (using a highly optimistic 80 km/h)
    // to ensure the heuristic remains optimistic/admissible, keeping search components directed
    // without over-penalizing transit extensions and yielding flat routes instead of jagged ones.
    g_time_scale_fixed[WALK] = calc_scale(WALK_SPEED_M_S);
    g_time_scale_fixed[BICYCLE] = calc_scale(BICYCLE_SPEED_M_S);
    g_time_scale_fixed[DRIVING] = calc_scale(105.0 / 3.6);
    g_time_scale_fixed[PUBLIC_TRANSIT] = calc_scale(80.0 / 3.6); // Optimistic 80 km/h transit velocity

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

    std::string db_path = base + "traffic_cache.mbtiles";
    if (sqlite3_open(db_path.c_str(), &g_db) == SQLITE_OK) {
        sqlite3_exec(g_db, "PRAGMA journal_mode=WAL;", nullptr, nullptr, nullptr);
        sqlite3_exec(g_db, "CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT, PRIMARY KEY(name));", nullptr, nullptr, nullptr);
        sqlite3_exec(g_db, "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row));", nullptr, nullptr, nullptr);
        sqlite3_exec(g_db, "CREATE TABLE IF NOT EXISTS raw_traffic (square_id INTEGER, zone_id INTEGER, edge_id INTEGER, speed INTEGER, PRIMARY KEY(square_id, edge_id));", nullptr, nullptr, nullptr);
        sqlite3_exec(g_db, "INSERT OR IGNORE INTO metadata VALUES ('name', 'Traffic');", nullptr, nullptr, nullptr);
        sqlite3_exec(g_db, "INSERT OR IGNORE INTO metadata VALUES ('format', 'pbf');", nullptr, nullptr, nullptr);
    }

    env->ReleaseStringUTFChars(base_path, path_raw); return true;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_findRouteNative(JNIEnv* env, jobject thiz, jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode, jlong startTime) {
    LOGD("findRouteNative: sLat=%f, sLon=%f, eLat=%f, eLon=%f, mode=%d", sLat, sLon, eLat, eLon, mode);
    RoutingContext ctx; if (!prepare_routing(env, thiz, sLat, sLon, eLat, eLon, mode, (uint32_t)startTime, ctx)) return nullptr;
    perform_search_loop(env, thiz, mode, ctx);
    if (ctx.target_node == 0xFFFFFFFF) {
        LOGE("findRouteNative: no path found after %d iterations", ctx.iterations);
        return nullptr;
    }
    LOGD("findRouteNative: path found, reconstructing...");
    return reconstruct_path(env, mode, ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_updateTrafficNative(JNIEnv* env, jobject thiz, jint zone_id, jintArray edge_ids, jbyteArray speeds, jint packed_square) {
    jsize len = env->GetArrayLength(edge_ids); jint* ids_ptr = env->GetIntArrayElements(edge_ids, nullptr);
    jbyte* speeds_ptr = env->GetByteArrayElements(speeds, nullptr);
    std::lock_guard<std::mutex> lock(g_traffic_mutex);

    if (g_db) {
        sqlite3_exec(g_db, "BEGIN TRANSACTION;", nullptr, nullptr, nullptr);
        sqlite3_stmt* stmt;
        sqlite3_prepare_v2(g_db, "INSERT OR REPLACE INTO raw_traffic (square_id, zone_id, edge_id, speed) VALUES (?, ?, ?, ?);", -1, &stmt, nullptr);
        for (jsize i = 0; i < len; i++) {
            sqlite3_bind_int(stmt, 1, packed_square);
            sqlite3_bind_int(stmt, 2, zone_id);
            sqlite3_bind_int(stmt, 3, ids_ptr[i]);
            sqlite3_bind_int(stmt, 4, (uint8_t)speeds_ptr[i]);
            sqlite3_step(stmt);
            sqlite3_reset(stmt);
        }
        sqlite3_finalize(stmt);
        sqlite3_exec(g_db, "DELETE FROM tiles;", nullptr, nullptr, nullptr); // Simple invalidation
        sqlite3_exec(g_db, "COMMIT;", nullptr, nullptr, nullptr);
    }

    auto& segments = g_traffic_by_square[packed_square];
    segments.clear();
    size_t total_edges_in_zone = g_edge_count_in_zone[zone_id];
    for (jsize i = 0; i < len; i++) {
        uint32_t local_id = (uint32_t)ids_ptr[i]; uint8_t speed = (uint8_t)speeds_ptr[i];
        if (is_zone_mapped(zone_id) && local_id < total_edges_in_zone) {
            const Edge& edge = g_edge_zones[zone_id][local_id];
            if (speed < 255) {
                g_traffic_zones[zone_id].set_speed(local_id, speed);
                const NodeMaster& node_u = g_node_zones[zone_id][find_node_idx_for_edge(zone_id, local_id)];
                int zone_v = get_zone_for_id(edge.target);
                if (is_zone_mapped(zone_v)) {
                    const NodeMaster& node_v = g_node_zones[zone_v][edge.target - g_zone_offsets[zone_v]];
                    double ratio = (edge.speed_limit > 0) ? (double)speed / edge.speed_limit : 1.0;
                    segments.push_back(node_u.lat_e7 * 1e-7); segments.push_back(node_u.lon_e7 * 1e-7);
                    segments.push_back(node_v.lat_e7 * 1e-7); segments.push_back(node_v.lon_e7 * 1e-7);
                    segments.push_back(ratio);
                }
            }
        }
    }
    env->ReleaseIntArrayElements(edge_ids, ids_ptr, JNI_ABORT); env->ReleaseByteArrayElements(speeds, speeds_ptr, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_notifyTrafficFetchFinishedNative(JNIEnv* env, jobject thiz, jint packed_square) {
    LOGD("notifyTrafficFetchFinishedNative: packed %u", (uint32_t)packed_square);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficSegmentsNative(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_traffic_mutex);
    std::vector<double> flattened;
    for (auto const& [square, segments] : g_traffic_by_square) {
        flattened.insert(flattened.end(), segments.begin(), segments.end());
        if (flattened.size() > 50000) break;
    }
    size_t count = flattened.size();
    if (count > 50000) count = 50000;
    jdoubleArray jRes = env->NewDoubleArray(count);
    if (count > 0) env->SetDoubleArrayRegion(jRes, 0, count, flattened.data());
    return jRes;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficTileNative(JNIEnv* env, jobject thiz, jint z, jint x, jint y) {
    std::lock_guard<std::mutex> lock(g_traffic_mutex);

    int tms_y = (1 << z) - 1 - y;
    if (g_db) {
        sqlite3_stmt* stmt;
        sqlite3_prepare_v2(g_db, "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?;", -1, &stmt, nullptr);
        sqlite3_bind_int(stmt, 1, z);
        sqlite3_bind_int(stmt, 2, x);
        sqlite3_bind_int(stmt, 3, tms_y);
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            const void* blob = sqlite3_column_blob(stmt, 0);
            int size = sqlite3_column_bytes(stmt, 0);
            jbyteArray res = env->NewByteArray(size);
            env->SetByteArrayRegion(res, 0, size, (jbyte*)blob);
            sqlite3_finalize(stmt);
            return res;
        }
        sqlite3_finalize(stmt);
    }

    double n = std::pow(2.0, z);
    double lon_min = (double)x / n * 360.0 - 180.0;
    double lon_max = (double)(x + 1) / n * 360.0 - 180.0;
    double lat_max = std::atan(std::sinh(M_PI * (1 - 2 * (double)y / n))) * 180.0 / M_PI;
    double lat_min = std::atan(std::sinh(M_PI * (1 - 2 * (double)(y + 1) / n))) * 180.0 / M_PI;

    TileProj proj{n, x, y};
    std::vector<uint8_t> layer_buf;
    write_string(layer_buf, 1, "traffic");
    write_tag(layer_buf, 15, 0); write_varint(layer_buf, 2);
    write_tag(layer_buf, 5, 0); write_varint(layer_buf, 4096);

    int min_lat_idx = (int)std::floor(lat_min); int max_lat_idx = (int)std::floor(lat_max);
    int min_lon_idx = (int)std::floor(lon_min); int max_lon_idx = (int)std::floor(lon_max);

    std::vector<std::string> keys = {"color"};
    std::vector<std::string> values;
    std::map<std::string, uint32_t> val_map;
    auto get_val_idx = [&](const std::string& v) {
        if (val_map.find(v) == val_map.end()) { val_map[v] = values.size(); values.push_back(v); }
        return val_map[v];
    };

    for (int lat_i = min_lat_idx; lat_i <= max_lat_idx; ++lat_i) {
        for (int lon_i = min_lon_idx; lon_i <= max_lon_idx; ++lon_i) {
            uint32_t square_id = ((uint32_t)(lat_i + 360) << 16) | (uint32_t)(lon_i + 720);
            if (g_traffic_by_square.find(square_id) == g_traffic_by_square.end()) load_traffic_from_db(square_id);
            auto it = g_traffic_by_square.find(square_id);
            if (it == g_traffic_by_square.end()) continue;

            const auto& data = it->second;
            for (size_t i = 0; i + 4 < data.size(); i += 5) {
                double lat1 = data[i], lon1 = data[i+1], lat2 = data[i+2], lon2 = data[i+3], speed_ratio = data[i+4];
                if (speed_ratio <= 0.0) continue;
                int32_t px1, py1, px2, py2;
                proj.wgs84_to_tile_px(lat1, lon1, px1, py1); proj.wgs84_to_tile_px(lat2, lon2, px2, py2);
                if (std::max(px1, px2) < -512 || std::min(px1, px2) > 4608 || std::max(py1, py2) < -512 || std::min(py1, py2) > 4608) continue;

                std::vector<uint8_t> feat_buf;
                write_varint(feat_buf, (3 << 3) | 0); write_varint(feat_buf, 2);
                std::vector<uint8_t> geom_buf;
                write_varint(geom_buf, mvt_command(1, 1)); write_varint(geom_buf, zigzag(px1)); write_varint(geom_buf, zigzag(py1));
                write_varint(geom_buf, mvt_command(2, 1)); write_varint(geom_buf, zigzag(px2 - px1)); write_varint(geom_buf, zigzag(py2 - py1));
                write_tag(feat_buf, 4, 2); write_varint(feat_buf, geom_buf.size());
                feat_buf.insert(feat_buf.end(), geom_buf.begin(), geom_buf.end());

                std::string color = "#1B5E20";
                if (speed_ratio < 0.5) color = "#B71C1C"; else if (speed_ratio < 0.9) color = "#E65100";
                write_tag(feat_buf, 2, 2); std::vector<uint8_t> tag_buf;
                write_varint(tag_buf, 0); write_varint(tag_buf, get_val_idx(color));
                write_varint(feat_buf, tag_buf.size()); feat_buf.insert(feat_buf.end(), tag_buf.begin(), tag_buf.end());
                write_tag(layer_buf, 2, 2); write_varint(layer_buf, feat_buf.size());
                layer_buf.insert(layer_buf.end(), feat_buf.begin(), feat_buf.end());
            }
        }
    }
    for (const auto& k : keys) write_string(layer_buf, 3, k);
    for (const auto& v : values) {
        write_tag(layer_buf, 4, 2); std::vector<uint8_t> v_buf; write_string(v_buf, 1, v);
        write_varint(layer_buf, v_buf.size()); layer_buf.insert(layer_buf.end(), v_buf.begin(), v_buf.end());
    }
    std::vector<uint8_t> tile_buf;
    write_tag(tile_buf, 3, 2); write_varint(tile_buf, layer_buf.size());
    tile_buf.insert(tile_buf.end(), layer_buf.begin(), layer_buf.end());

    std::vector<uint8_t> compressed = compress_gzip(tile_buf);
    if (g_db && !compressed.empty()) {
        sqlite3_stmt* stmt;
        sqlite3_prepare_v2(g_db, "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?);", -1, &stmt, nullptr);
        sqlite3_bind_int(stmt, 1, z); sqlite3_bind_int(stmt, 2, x); sqlite3_bind_int(stmt, 3, tms_y);
        sqlite3_bind_blob(stmt, 4, compressed.data(), compressed.size(), SQLITE_TRANSIENT);
        sqlite3_step(stmt); sqlite3_finalize(stmt);
    }

    const auto& final_data = compressed.empty() ? tile_buf : compressed;
    jbyteArray res = env->NewByteArray(final_data.size());
    env->SetByteArrayRegion(res, 0, final_data.size(), (jbyte*)final_data.data());
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_ensureTrafficLoadedNative(JNIEnv* env, jobject thiz, jdouble lat, jdouble lon, jboolean force_async) {
    ensure_traffic_loaded(env, thiz, (int32_t)(lat * 1e7), (int32_t)(lon * 1e7), force_async);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_ensureZoneLoadedNative(JNIEnv* env, jobject thiz, jint zone_id) {
    if (zone_id < 0 || zone_id >= NUM_ZONES) return false;
    if (g_node_zones[zone_id] != nullptr) return true;
    if (g_base_path.empty()) return false;

    auto m_file = [&](const std::string& p, size_t& s) -> void* {
        s = 0; int fd = open(p.c_str(), O_RDONLY); if (fd < 0) return nullptr;
        s = lseek(fd, 0, SEEK_END); void* a = mmap(nullptr, s, PROT_READ, MAP_SHARED, fd, 0); close(fd);
        return (a == MAP_FAILED) ? nullptr : a;
    };

    size_t s_n, s_e, s_t;
    void* nodes = m_file(g_base_path + "nodes_zone_" + std::to_string(zone_id) + ".bin", s_n);
    if (!nodes) return false;

    g_node_zones[zone_id] = (NodeMaster*)nodes;
    g_edge_zones[zone_id] = (Edge*)m_file(g_base_path + "edges_zone_" + std::to_string(zone_id) + ".bin", s_e);
    g_edge_count_in_zone[zone_id] = s_e / sizeof(Edge);
    g_transit_voyages[zone_id] = (TransitVoyage*)m_file(g_base_path + "transit_voyages_zone_" + std::to_string(zone_id) + ".bin", s_t);
    g_transit_voyage_count[zone_id] = s_t / sizeof(TransitVoyage);

    LOGD("Native ensureZoneLoadedNative: zone %d LOADED", zone_id);
    return true;
}