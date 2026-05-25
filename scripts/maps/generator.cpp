#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <algorithm>
#include <cstdint>
#include <unordered_map>
#include <iomanip>
#include <cmath>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <chrono>
#include <sys/stat.h>
#include <bitset>
#include <thread>
#include <atomic>
#include <mutex>
#include <future>
#include <queue>
#include <condition_variable>
#include <functional>
#include <sstream>

using namespace std;

// Libosmium Headers
#include <osmium/io/any_input.hpp>
#include <osmium/handler.hpp>
#include <osmium/visitor.hpp>

// --- CONFIGURATION ---
const uint64_t BITSET_SIZE = 20000000000ULL;
const int NUM_ZONES = 64;
const string DATA_DIR = "map_data/";
const double DEG_TO_RAD = M_PI / 180.0;
const int ID_TILE_BITS = 16;
const size_t NUM_ID_TILES = 1 << ID_TILE_BITS;
const uint64_t ID_TILE_SHIFT = 64 - ID_TILE_BITS;

#pragma pack(push, 1)
struct NodeMaster {
    int32_t lat_e7;
    int32_t lon_e7;
    uint64_t spatial_id;
    uint32_t edge_ptr;
    uint32_t stop_code_off;
    uint32_t feed_name_off;
};

struct FinalEdge {
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

#define TRANSIT_FLAG 0x80
#define TRANSIT_NODE_FLAG (1ULL << 63)

struct NodeTemp {
    uint64_t osm_id;
    uint64_t spatial_value;
    int32_t lat_e7;
    int32_t lon_e7;
    uint32_t stop_code_off;
    uint32_t feed_name_off;
    uint64_t sort_key() const { return spatial_value; }
};

struct IDMapping {
    uint64_t osm_id;
    uint32_t local_id;
    uint64_t sort_key() const { return osm_id; }
};

struct TmpEdge {
    uint32_t source_lid;
    uint32_t target_lid;
    uint32_t dist_mm;
    uint32_t name_offset;
    uint8_t type;
    uint8_t speed_limit;
    uint32_t sort_key() const { return source_lid; }
};

struct CachedWay {
    uint32_t name_offset;
    uint32_t first_node_idx;
    uint16_t node_count;
    uint8_t type;
    uint8_t speed_limit;
    bool oneway;
};
#pragma pack(pop)

// ==========================================
// THREAD-SAFE CONCURRENT ATOMIC BITSET
// Prevents OOM crashes from allocating 20GB.
// ==========================================
class AtomicBitset {
    std::atomic<uint8_t>* bits;
    uint64_t num_bytes;
public:
    AtomicBitset(uint64_t size) {
        num_bytes = size / 8 + 1;
        bits = new std::atomic<uint8_t>[num_bytes];
        for (uint64_t i = 0; i < num_bytes; ++i) {
            bits[i].store(0, std::memory_order_relaxed);
        }
    }
    ~AtomicBitset() {
        delete[] bits;
    }
    bool set(uint64_t idx) {
        uint64_t byte_idx = idx / 8;
        uint8_t bit_mask = 1 << (idx % 8);
        uint8_t current = bits[byte_idx].load(std::memory_order_relaxed);
        while (!(current & bit_mask)) {
            uint8_t new_val = current | bit_mask;
            if (bits[byte_idx].compare_exchange_weak(current, new_val, std::memory_order_relaxed)) {
                return true;
            }
        }
        return false;
    }
    bool get(uint64_t idx) const {
        uint64_t byte_idx = idx / 8;
        uint8_t bit_mask = 1 << (idx % 8);
        return (bits[byte_idx].load(std::memory_order_relaxed) & bit_mask) != 0;
    }
};

// ==========================================
// THREAD POOL & WORKER UTILITIES
// ==========================================

class MoveTask {
    struct Base {
        virtual ~Base() {}
        virtual void run() = 0;
    };
    template<typename F>
    struct Impl : Base {
        F f;
        Impl(F&& f) : f(std::move(f)) {}
        void run() override { f(); }
    };
    unique_ptr<Base> ptr;
public:
    MoveTask() = default;
    template<typename F>
    MoveTask(F&& f) : ptr(new Impl<F>(std::move(f))) {}
    void operator()() { if (ptr) ptr->run(); }
    explicit operator bool() const { return !!ptr; }
};

class ThreadPool {
public:
    ThreadPool(size_t threads) : stop(false) {
        for(size_t i = 0; i < threads; ++i)
            workers.emplace_back([this] {
                for(;;) {
                    MoveTask task;
                    {
                        unique_lock<mutex> lock(this->queue_mutex);
                        this->condition.wait(lock, [this]{ return this->stop || !this->tasks.empty(); });
                        if(this->stop && this->tasks.empty()) return;
                        task = move(this->tasks.front());
                        this->tasks.pop();
                    }
                    task();
                }
            });
    }
    template<typename F>
    void enqueue(F&& f) {
        {
            unique_lock<mutex> lock(queue_mutex);
            condition_throttle.wait(lock, [this] { return tasks.size() < 100; });
            tasks.emplace(forward<F>(f));
        }
        condition.notify_one();
    }
    void wait_finished() {
        for(;;) {
            {
                unique_lock<mutex> lock(queue_mutex);
                if (tasks.empty()) break;
            }
            this_thread::sleep_for(chrono::milliseconds(10));
        }
    }
    ~ThreadPool() {
        { unique_lock<mutex> lock(queue_mutex); stop = true; }
        condition.notify_all();
        for(thread &worker: workers) worker.join();
    }
    void notify_worker_done() {
        unique_lock<mutex> lock(queue_mutex);
        condition_throttle.notify_one();
    }
private:
    vector<thread> workers;
    queue<MoveTask> tasks;
    mutex queue_mutex;
    condition_variable condition;
    condition_variable condition_throttle;
    bool stop;
};

// ==========================================
// PARALLEL RADIX SORT
// ==========================================

template<typename T>
void parallel_radix_sort(vector<T>& data, int total_bits) {
    if (data.empty()) return;
    size_t n = data.size();
    vector<T> buffer(n);
    T* src = data.data();
    T* dst = buffer.data();
    int num_threads = thread::hardware_concurrency();

    for (int shift = 0; shift < total_bits; shift += 8) {
        size_t global_counts[256] = {0};
        vector<vector<size_t>> local_counts(num_threads, vector<size_t>(256, 0));

        auto count_func = [&](int tid, size_t start, size_t end) {
            for (size_t i = start; i < end; ++i) {
                local_counts[tid][(src[i].sort_key() >> shift) & 0xFF]++;
            }
        };

        vector<thread> threads;
        size_t chunk = n / num_threads;
        for(int t=0; t<num_threads; ++t)
            threads.emplace_back(count_func, t, t*chunk, (t==num_threads-1) ? n : (t+1)*chunk);
        for(auto& t : threads) t.join();

        size_t offsets[256];
        offsets[0] = 0;
        for (int i = 0; i < 256; ++i) {
            for(int t=0; t<num_threads; ++t) global_counts[i] += local_counts[t][i];
            if (i > 0) offsets[i] = offsets[i-1] + global_counts[i-1];
        }

        for (size_t i = 0; i < n; ++i) {
            dst[offsets[(src[i].sort_key() >> shift) & 0xFF]++] = src[i];
        }
        swap(src, dst);
    }
}

// ==========================================
// UTILITIES
// ==========================================

uint32_t g_lon_to_mm_scale[4096];
uint32_t g_id_tile_offsets[NUM_ID_TILES + 1];

void init_lookup_table() {
    for (int i = 0; i < 4096; ++i) {
        double lat_deg = (double)((i - 2048) << 19) * 1e-7;
        lat_deg = std::max(-90.0, std::min(90.0, lat_deg));
        double scale = 11.131949 * cos(lat_deg * DEG_TO_RAD) * 1024.0;
        g_lon_to_mm_scale[i] = (uint32_t)scale;
    }
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
    double c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a));
    return (uint32_t)(R * c);
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

void build_id_tile_index(const vector<IDMapping>& mapping) {
    memset(g_id_tile_offsets, 0, sizeof(g_id_tile_offsets));
    for (const auto& entry : mapping) {
        uint32_t tile_idx = entry.osm_id >> ID_TILE_SHIFT;
        g_id_tile_offsets[tile_idx + 1]++;
    }
    for (size_t i = 1; i <= NUM_ID_TILES; ++i) {
        g_id_tile_offsets[i] += g_id_tile_offsets[i - 1];
    }
}

inline uint32_t get_local_id_by_osm(uint64_t osm_id, const vector<IDMapping>& mapping) {
    uint32_t tile_idx = osm_id >> ID_TILE_SHIFT;
    uint32_t start = g_id_tile_offsets[tile_idx];
    uint32_t end = g_id_tile_offsets[tile_idx + 1];
    auto it = lower_bound(mapping.begin() + start, mapping.begin() + end, IDMapping{osm_id, 0},
                          [](const IDMapping& a, const IDMapping& b) { return a.osm_id < b.osm_id; });
    if (it != mapping.begin() + end && it->osm_id == osm_id) return it->local_id;
    return 0xFFFFFFFF;
}

uint8_t parse_maxspeed(const char* str) {
    if (!str) return 0;
    char* endptr;
    double val = strtod(str, &endptr);
    if (val <= 0) return 0;
    while (*endptr == ' ') endptr++;
    if (strcmp(endptr, "mph") == 0) return (uint8_t)round(val * 1.60934);
    if (strcmp(endptr, "knots") == 0) return (uint8_t)round(val * 1.852);
    return (uint8_t)val;
}

uint8_t get_hw_id(const char* type) {
    if (!type) return 0;
    static const unordered_map<string, uint8_t> m = {
            {"motorway", 1}, {"trunk", 2}, {"primary", 3}, {"secondary", 4},
            {"tertiary", 5}, {"unclassified", 6}, {"residential", 7}, {"service", 8},
            {"living_street", 9}, {"pedestrian", 10}, {"track", 11}, {"footway", 12},
            {"cycleway", 13}, {"path", 14}, {"steps", 15}
    };
    auto it = m.find(type);
    return it != m.end() ? it->second : 0;
}

bool is_transit_way(const osmium::TagList& tags) {
    const char* railway = tags.get_value_by_key("railway");
    if (railway && (strcmp(railway, "rail") == 0 || strcmp(railway, "subway") == 0 ||
                    strcmp(railway, "tram") == 0 || strcmp(railway, "light_rail") == 0)) return true;
    const char* route = tags.get_value_by_key("route");
    if (route && (strcmp(route, "bus") == 0 || strcmp(route, "train") == 0)) return true;
    return false;
}

void print_progress(const string& label, uint64_t current, uint64_t total) {
    static auto last_update = chrono::steady_clock::now();
    auto now = chrono::steady_clock::now();
    if (current < total && chrono::duration_cast<chrono::milliseconds>(now - last_update).count() < 500) return;
    last_update = now;
    float progress = (total == 0) ? 1.0f : (float)current / total;
    cout << "\r" << left << setw(20) << label << " [" << int(progress * 100.0) << "%] " << flush;
    if (current >= total) cout << endl;
}

uint32_t parse_time_10ms(const string& s) {
    int h, m, sec;
    if (sscanf(s.c_str(), "%d:%d:%d", &h, &m, &sec) == 3) {
        return (h * 3600 + m * 60 + sec) * 100;
    }
    return 0;
}

struct TransitInput {
    uint64_t u_osm, v_osm;
    string line_name;
    uint32_t dep, arr;
};

int main(int argc, char* argv[]) {
    if (argc < 2) { cerr << "Usage: " << argv[0] << " map.osm.pbf [transit.csv]" << endl; return 1; }

    mkdir(DATA_DIR.c_str(), 0777);
    init_lookup_table();
    ThreadPool pool(thread::hardware_concurrency());

    vector<TransitInput> transit_inputs;
    if (argc >= 3) {
        ifstream transit_in(argv[2]);
        string line;
        while (getline(transit_in, line)) {
            stringstream ss(line);
            string u_s, v_s, name, dep_s, arr_s;
            if (getline(ss, u_s, ',') && getline(ss, v_s, ',') && getline(ss, name, ',') &&
                getline(ss, dep_s, ',') && getline(ss, arr_s, ',')) {
                try {
                    transit_inputs.push_back({stoull(u_s), stoull(v_s), name, parse_time_10ms(dep_s), parse_time_10ms(arr_s)});
                } catch (...) {
                    continue;
                }
            }
        }
    }

    AtomicBitset useful_nodes_mask(BITSET_SIZE);
    AtomicBitset road_nodes_mask(BITSET_SIZE);
    AtomicBitset transit_nodes_mask(BITSET_SIZE);
    atomic<uint64_t> total_local_nodes{0};
    atomic<uint64_t> total_edges{0};

    vector<CachedWay> cached_ways;
    vector<uint64_t> way_nodes_topology;
    mutex way_cache_mtx;

    // STEP 1: Discovery & Caching (Pass 1 - Ways)
    unordered_map<string, uint32_t> name_pool;
    mutex name_pool_mtx;
    uint32_t name_offset = 0;
    ofstream name_out(DATA_DIR + "road_names.bin", ios::binary);

    {
        osmium::io::Reader reader{argv[1], osmium::osm_entity_bits::node | osmium::osm_entity_bits::way};
        uint64_t total_size = reader.file_size();

        while (auto buf = reader.read()) {
            pool.enqueue([&, buf = move(buf)]() mutable {
                vector<CachedWay> local_ways;
                vector<uint64_t> local_topology;

                for (const auto& node : buf.select<osmium::Node>()) {
                    const char* hw = node.tags().get_value_by_key("highway");
                    const char* rw = node.tags().get_value_by_key("railway");
                    const char* pt = node.tags().get_value_by_key("public_transport");
                    if ((hw && (strcmp(hw, "bus_stop") == 0 || strcmp(hw, "bus_station") == 0 || strcmp(hw, "tram_stop") == 0)) ||
                        (rw && (strcmp(rw, "station") == 0 || strcmp(rw, "halt") == 0 || strcmp(rw, "tram_stop") == 0 || strcmp(rw, "stop") == 0)) ||
                        (pt && (strcmp(pt, "stop_position") == 0 || strcmp(pt, "platform") == 0 || strcmp(pt, "station") == 0))) {
                        if (node.id() < BITSET_SIZE) {
                            useful_nodes_mask.set(node.id());
                            if (road_nodes_mask.set(node.id())) total_local_nodes++;
                            // Stations/stops are transit points by definition
                            if (transit_nodes_mask.set(node.id())) total_local_nodes++;
                        }
                    }
                }

                for (const auto& way : buf.select<osmium::Way>()) {
                    uint8_t hw = get_hw_id(way.tags().get_value_by_key("highway"));
                    bool is_transit = is_transit_way(way.tags());
                    if (hw == 0 && !is_transit) continue;

                    uint32_t n_off = 0xFFFFFFFF;
                    const char* name_str = way.tags().get_value_by_key("name");
                    if (!name_str && is_transit) name_str = way.tags().get_value_by_key("ref");
                    if (name_str) {
                        lock_guard<mutex> lock(name_pool_mtx);
                        auto it = name_pool.find(name_str);
                        if (it != name_pool.end()) n_off = it->second;
                        else {
                            n_off = name_offset;
                            name_pool[string(name_str)] = n_off;
                            name_out.write(name_str, strlen(name_str) + 1);
                            name_offset += (strlen(name_str) + 1);
                        }
                    }

                    uint8_t speed = parse_maxspeed(way.tags().get_value_by_key("maxspeed"));
                    bool oneway = (strcmp(way.tags().get_value_by_key("oneway", ""), "yes") == 0);
                    const auto& nodes = way.nodes();

                    uint32_t topology_start = local_topology.size();
                    for (const auto& n : nodes) {
                        if (n.ref() < BITSET_SIZE) {
                            useful_nodes_mask.set(n.ref());
                            if (hw > 0) {
                                if (road_nodes_mask.set(n.ref())) total_local_nodes++;
                            }
                            if (is_transit) {
                                if (transit_nodes_mask.set(n.ref())) total_local_nodes++;
                            }
                        }
                        local_topology.push_back(n.ref());
                    }

                    local_ways.push_back({ n_off, topology_start, (uint16_t)nodes.size(), (uint8_t)(is_transit ? (hw | TRANSIT_FLAG) : hw), speed, oneway });
                    if (hw > 0) {
                        total_edges += (nodes.size() - 1) * (oneway ? 1 : 2);
                    }
                }

                if (!local_ways.empty()) {
                    lock_guard<mutex> lock(way_cache_mtx);
                    uint32_t global_topo_base = way_nodes_topology.size();
                    for (auto& cw : local_ways) cw.first_node_idx += global_topo_base;
                    way_nodes_topology.insert(way_nodes_topology.end(), local_topology.begin(), local_topology.end());
                    cached_ways.insert(cached_ways.end(), local_ways.begin(), local_ways.end());
                }
                pool.notify_worker_done();
            });
            print_progress("Step 1: Way Caching", reader.offset(), total_size);
        }
        pool.wait_finished();
    }

    // STEP 2: Node Collection (Pass 2 - Nodes)
    for (auto& ti : transit_inputs) {
        if (ti.u_osm < BITSET_SIZE) {
            useful_nodes_mask.set(ti.u_osm);
            if (transit_nodes_mask.set(ti.u_osm)) total_local_nodes++;
        }
        if (ti.v_osm < BITSET_SIZE) {
            useful_nodes_mask.set(ti.v_osm);
            if (transit_nodes_mask.set(ti.v_osm)) total_local_nodes++;
        }
    }

    vector<NodeTemp> nodes_raw(total_local_nodes);
    {
        osmium::io::Reader reader{argv[1], osmium::osm_entity_bits::node};
        uint64_t total_size = reader.file_size();
        atomic<uint64_t> idx{0};
        while (auto buf = reader.read()) {
            pool.enqueue([&nodes_raw, &idx, &useful_nodes_mask, &road_nodes_mask, &transit_nodes_mask, buf = move(buf), &pool, &name_pool, &name_pool_mtx, &name_offset, &name_out]() mutable {
                for (const auto& n : buf.select<osmium::Node>()) {
                    if (n.id() < BITSET_SIZE && useful_nodes_mask.get(n.id())) {
                        double lat = n.location().lat();
                        double lon = n.location().lon();
                        uint64_t spatial = latlng_to_spatial(lat, lon);
                        int32_t lat_e7 = (int32_t)(lat * 1e7);
                        int32_t lon_e7 = (int32_t)(lon * 1e7);

                        uint32_t stop_code_off = 0xFFFFFFFF;
                        uint32_t feed_name_off = 0xFFFFFFFF;

                        for (const auto& tag : n.tags()) {
                            if (strncmp(tag.key(), "gtfs:stop_code:", 15) == 0) {
                                string feed = tag.key() + 15;
                                const char* stop_name = n.tags().get_value_by_key("name");
                                string code = stop_name ? stop_name : tag.value();

                                lock_guard<mutex> lock(name_pool_mtx);
                                auto it_f = name_pool.find(feed);
                                if (it_f != name_pool.end()) feed_name_off = it_f->second;
                                else {
                                    feed_name_off = name_offset;
                                    name_pool[feed] = feed_name_off;
                                    name_out.write(feed.c_str(), feed.size() + 1);
                                    name_offset += (feed.size() + 1);
                                }

                                auto it_c = name_pool.find(code);
                                if (it_c != name_pool.end()) stop_code_off = it_c->second;
                                else {
                                    stop_code_off = name_offset;
                                    name_pool[code] = stop_code_off;
                                    name_out.write(code.c_str(), code.size() + 1);
                                    name_offset += (code.size() + 1);
                                }
                                break;
                            }
                        }

                        if (stop_code_off == 0xFFFFFFFF) {
                            const char* hw_tag = n.tags().get_value_by_key("highway");
                            const char* rw_tag = n.tags().get_value_by_key("railway");
                            const char* pt_tag = n.tags().get_value_by_key("public_transport");
                            if ((hw_tag && (strcmp(hw_tag, "bus_stop") == 0 || strcmp(hw_tag, "bus_station") == 0 || strcmp(hw_tag, "tram_stop") == 0)) ||
                                (rw_tag && (strcmp(rw_tag, "station") == 0 || strcmp(rw_tag, "halt") == 0 || strcmp(rw_tag, "tram_stop") == 0 || strcmp(rw_tag, "stop") == 0)) ||
                                (pt_tag && (strcmp(pt_tag, "stop_position") == 0 || strcmp(pt_tag, "platform") == 0 || strcmp(pt_tag, "station") == 0))) {
                                lock_guard<mutex> lock(name_pool_mtx);
                                const char* name_tag = n.tags().get_value_by_key("name");
                                string code = name_tag ? name_tag : "OSM_STOP";
                                auto it_c = name_pool.find(code);
                                if (it_c != name_pool.end()) stop_code_off = it_c->second;
                                else {
                                    stop_code_off = name_offset;
                                    name_pool[code] = stop_code_off;
                                    name_out.write(code.c_str(), code.size() + 1);
                                    name_offset += (code.size() + 1);
                                }
                            }
                        }

                        if (road_nodes_mask.get(n.id())) {
                            uint64_t c_idx = idx.fetch_add(1);
                            nodes_raw[c_idx] = { (uint64_t)n.id(), spatial, lat_e7, lon_e7, stop_code_off, feed_name_off };
                        }
                        if (transit_nodes_mask.get(n.id())) {
                            uint64_t c_idx = idx.fetch_add(1);
                            nodes_raw[c_idx] = { (uint64_t)n.id() | TRANSIT_NODE_FLAG, spatial, lat_e7, lon_e7, stop_code_off, feed_name_off };
                        }
                    }
                }
                pool.notify_worker_done();
            });
            print_progress("Step 2: Node Scan", reader.offset(), total_size);
        }
        pool.wait_finished();

        // COMPACTION FIX: Safely resize nodes_raw to discard unvisited/missing elements
        // to avoid shifting valid nodes with spatial coordinate 0 (Null Island) values.
        uint64_t final_nodes_count = idx.load();
        nodes_raw.resize(final_nodes_count);
        total_local_nodes = final_nodes_count;
    }

    cout << "Parallel Radix Sorting Nodes..." << endl;
    parallel_radix_sort(nodes_raw, 64);
    vector<NodeMaster> node_masters(total_local_nodes);
    vector<IDMapping> id_to_local(total_local_nodes);
    vector<uint32_t> zone_node_counts(NUM_ZONES, 0);

    for (uint32_t i = 0; i < total_local_nodes; ++i) {
        node_masters[i] = { nodes_raw[i].lat_e7, nodes_raw[i].lon_e7, nodes_raw[i].spatial_value, 0, nodes_raw[i].stop_code_off, nodes_raw[i].feed_name_off };
        id_to_local[i] = { nodes_raw[i].osm_id, i };
        zone_node_counts[(int)((nodes_raw[i].spatial_value >> 58) & 0x3F)]++;
    }
    vector<NodeTemp>().swap(nodes_raw);

    // CRITICAL SORT ORDER FIX: Sort and index id_to_local mappings IMMEDIATELY
    // before we run get_local_id_by_osm for the transit groups! Calling binary searches
    // on unsorted lists was generating garbage indices and wild overlapping sawtooth route maps.
    cout << "Parallel Radix Sorting Mapping..." << endl;
    parallel_radix_sort(id_to_local, 64);
    build_id_tile_index(id_to_local);

    vector<bool> is_road_node(total_local_nodes, false);
    for (uint32_t i = 0; i < total_local_nodes; ++i) {
        if (!(id_to_local[i].osm_id & TRANSIT_NODE_FLAG)) {
            is_road_node[id_to_local[i].local_id] = true;
        }
    }

    // Group transit inputs by (u, v)
    struct TransitEdgeGroup {
        vector<TransitVoyage> voyages;
        uint32_t name_off;
    };
    map<pair<uint32_t, uint32_t>, TransitEdgeGroup> transit_groups;

    if (transit_inputs.empty()) {
        cout << "Synthesizing transit voyages (15-min frequency)..." << endl;
        lock_guard<mutex> lock(name_pool_mtx);
        for (const auto& cw : cached_ways) {
            if (cw.type & TRANSIT_FLAG) {
                for (size_t i = 0; i < (size_t)cw.node_count - 1; ++i) {
                    uint64_t u_osm = way_nodes_topology[cw.first_node_idx + i];
                    uint64_t v_osm = way_nodes_topology[cw.first_node_idx + i + 1];
                    uint32_t u_lid = get_local_id_by_osm(u_osm | TRANSIT_NODE_FLAG, id_to_local);
                    uint32_t v_lid = get_local_id_by_osm(v_osm | TRANSIT_NODE_FLAG, id_to_local);
                    if (u_lid == 0xFFFFFFFF || v_lid == 0xFFFFFFFF) continue;

                    uint32_t dist = accurate_dist_mm(node_masters[u_lid].lat_e7, node_masters[u_lid].lon_e7,
                                                     node_masters[v_lid].lat_e7, node_masters[v_lid].lon_e7);
                    double speed_m_s = (cw.type & 0x7F) == 0 ? 11.1 : 5.5;
                    uint32_t travel_time_10ms = (uint32_t)((dist / 1000.0) / speed_m_s * 100.0);

                    auto& group = transit_groups[{u_lid, v_lid}];
                    group.name_off = cw.name_offset;
                    for (int h = 0; h < 24; ++h) {
                        for (int m = 0; m < 60; m += 15) {
                            uint32_t dep = (h * 3600 + m * 60) * 100;
                            group.voyages.push_back({dep, dep + travel_time_10ms});
                        }
                    }
                }
            }
        }
    } else {
        lock_guard<mutex> lock(name_pool_mtx);
        for (auto& ti : transit_inputs) {
            uint32_t u_lid = get_local_id_by_osm(ti.u_osm | TRANSIT_NODE_FLAG, id_to_local);
            uint32_t v_lid = get_local_id_by_osm(ti.v_osm | TRANSIT_NODE_FLAG, id_to_local);
            if (u_lid != 0xFFFFFFFF && v_lid != 0xFFFFFFFF) {
                uint32_t n_off = 0xFFFFFFFF;
                auto it = name_pool.find(ti.line_name);
                if (it != name_pool.end()) n_off = it->second;
                else {
                    n_off = name_offset;
                    name_pool[ti.line_name] = n_off;
                    name_out.write(ti.line_name.c_str(), ti.line_name.size() + 1);
                    name_offset += (ti.line_name.size() + 1);
                }
                transit_groups[{u_lid, v_lid}].voyages.push_back({ti.dep, ti.arr});
                transit_groups[{u_lid, v_lid}].name_off = n_off;
            }
        }
    }

    for (auto& entry : transit_groups) {
        sort(entry.second.voyages.begin(), entry.second.voyages.end(), [](const TransitVoyage& a, const TransitVoyage& b) {
            return a.dep_10ms < b.dep_10ms;
        });
    }

    total_edges += transit_groups.size();
    total_edges += total_local_nodes * 2; // Upper bound for transfer edges

    vector<TmpEdge> tmp_edges(total_edges);
    atomic<uint64_t> global_edge_idx{0};
    {
        size_t n_ways = cached_ways.size();
        size_t chunk = n_ways / thread::hardware_concurrency();
        vector<future<void>> construction_tasks;
        for (int t = 0; t < (int)thread::hardware_concurrency(); ++t) {
            construction_tasks.push_back(async(launch::async, [&, t, chunk, n_ways]() {
                size_t start_way = t * chunk;
                size_t end_way = (t == (int)thread::hardware_concurrency() - 1) ? n_ways : (t + 1) * chunk;
                vector<TmpEdge> local_buffer;
                local_buffer.reserve(10000);
                for (size_t w = start_way; w < end_way; ++w) {
                    const auto& cw = cached_ways[w];
                    // Skip ways that are dedicated transit infrastructure (no highway type)
                    if ((cw.type & TRANSIT_FLAG) && (cw.type & 0x7F) == 0) continue;

                    for (size_t i = 0; i < (size_t)cw.node_count - 1; ++i) {
                        uint64_t u_osm = way_nodes_topology[cw.first_node_idx + i];
                        uint64_t v_osm = way_nodes_topology[cw.first_node_idx + i + 1];
                        uint32_t u_lid = get_local_id_by_osm(u_osm, id_to_local);
                        uint32_t v_lid = get_local_id_by_osm(v_osm, id_to_local);
                        if (u_lid == 0xFFFFFFFF || v_lid == 0xFFFFFFFF) continue;
                        uint32_t dist = accurate_dist_mm(node_masters[u_lid].lat_e7, node_masters[u_lid].lon_e7,
                                                         node_masters[v_lid].lat_e7, node_masters[v_lid].lon_e7);
                        local_buffer.push_back({ u_lid, v_lid, dist, cw.name_offset, cw.type, cw.speed_limit });
                        if (!cw.oneway) local_buffer.push_back({ v_lid, u_lid, dist, cw.name_offset, cw.type, cw.speed_limit });
                        if (local_buffer.size() >= 9000) {
                            uint64_t start = global_edge_idx.fetch_add(local_buffer.size());
                            memcpy(&tmp_edges[start], local_buffer.data(), local_buffer.size() * sizeof(TmpEdge));
                            local_buffer.clear();
                        }
                    }
                }
                if (!local_buffer.empty()) {
                    uint64_t start = global_edge_idx.fetch_add(local_buffer.size());
                    memcpy(&tmp_edges[start], local_buffer.data(), local_buffer.size() * sizeof(TmpEdge));
                }
            }));
        }
        for (auto& task : construction_tasks) task.wait();

        for (auto& entry : transit_groups) {
            uint32_t u_lid = entry.first.first;
            uint32_t v_lid = entry.first.second;
            tmp_edges[global_edge_idx.fetch_add(1)] = { u_lid, v_lid, 0, entry.second.name_off, TRANSIT_FLAG, (uint8_t)min((size_t)255, entry.second.voyages.size()) };
        }

        // NEW: Add transfer edges for shared stop nodes
        for (uint32_t i = 0; i < total_local_nodes; ++i) {
            uint64_t osm_u = id_to_local[i].osm_id;
            if (!(osm_u & TRANSIT_NODE_FLAG)) { // it's a road node
                uint32_t transit_lid = get_local_id_by_osm(osm_u | TRANSIT_NODE_FLAG, id_to_local);
                if (transit_lid != 0xFFFFFFFF) {
                    if (node_masters[i].stop_code_off != 0xFFFFFFFF) {
                        uint32_t road_lid = i;
                        // 15 meter transfer distance (15000mm)
                        tmp_edges[global_edge_idx.fetch_add(1)] = { road_lid, transit_lid, 15000, 0xFFFFFFFF, 12, 5 };
                        tmp_edges[global_edge_idx.fetch_add(1)] = { transit_lid, road_lid, 15000, 0xFFFFFFFF, 12, 5 };
                    }
                }
            }
        }

        tmp_edges.resize(global_edge_idx.load());
        cout << "Step 3: Graph Construction Complete. Real Edges Count: " << tmp_edges.size() << endl;
    }

    vector<IDMapping>().swap(id_to_local);
    vector<uint64_t>().swap(way_nodes_topology);
    vector<CachedWay>().swap(cached_ways);

    cout << "Parallel Radix Sorting Edges..." << endl;
    parallel_radix_sort(tmp_edges, 32);

    // --- Connect Isolated Bus Stops ---
    {
        cout << "Identifying Connected Components..." << endl;
        vector<int32_t> component_id(total_local_nodes, -1);
        uint32_t lcc_id = 0;
        uint32_t max_size = 0;

        vector<uint32_t> node_to_edge(total_local_nodes + 1);
        uint32_t cur_edge = 0;
        for (uint32_t i = 0; i < total_local_nodes; ++i) {
            node_to_edge[i] = cur_edge;
            while (cur_edge < tmp_edges.size() && tmp_edges[cur_edge].source_lid == i) cur_edge++;
        }
        node_to_edge[total_local_nodes] = cur_edge;

        vector<uint32_t> q;
        q.reserve(total_local_nodes);

        int32_t current_comp = 0;
        for (uint32_t i = 0; i < total_local_nodes; ++i) {
            if (component_id[i] == -1) {
                uint32_t start_idx = q.size();
                q.push_back(i);
                component_id[i] = current_comp;
                uint32_t head = start_idx;
                while (head < q.size()) {
                    uint32_t u = q[head++];
                    for (uint32_t e = node_to_edge[u]; e < node_to_edge[u + 1]; ++e) {
                        uint32_t v = tmp_edges[e].target_lid;
                        if (component_id[v] == -1) {
                            component_id[v] = current_comp;
                            q.push_back(v);
                        }
                    }
                }
                uint32_t comp_size = q.size() - start_idx;
                if (comp_size > max_size) {
                    max_size = comp_size;
                    lcc_id = current_comp;
                }
                current_comp++;
            }
        }
        cout << "LCC ID: " << lcc_id << ", Size: " << max_size << " / " << total_local_nodes << endl;

        vector<TmpEdge> new_edges;
        for (uint32_t i = 0; i < total_local_nodes; ++i) {
            if (node_masters[i].stop_code_off != 0xFFFFFFFF && component_id[i] != (int32_t)lcc_id && is_road_node[i]) {
                uint32_t best_node = 0xFFFFFFFF;
                uint32_t best_dist = 0xFFFFFFFF;

                for (int radius = 1000; radius <= 1000000; radius *= 10) {
                    int start = max(0, (int)i - radius);
                    int end = min((int)total_local_nodes, (int)i + radius);
                    for (int j = start; j < end; ++j) {
                        if (component_id[j] == (int32_t)lcc_id && is_road_node[j]) {
                            uint32_t d = accurate_dist_mm(node_masters[i].lat_e7, node_masters[i].lon_e7,
                                                          node_masters[j].lat_e7, node_masters[j].lon_e7);
                            if (d < best_dist) {
                                best_dist = d;
                                best_node = j;
                            }
                        }
                    }
                    if (best_node != 0xFFFFFFFF) break;
                }

                if (best_node != 0xFFFFFFFF) {
                    new_edges.push_back({ i, (uint32_t)best_node, best_dist, 0xFFFFFFFF, 12, 5 });
                    new_edges.push_back({ (uint32_t)best_node, i, best_dist, 0xFFFFFFFF, 12, 5 });
                }
            }
        }

        if (!new_edges.empty()) {
            cout << "Adding " << new_edges.size() << " synthetic connections for isolated bus stops." << endl;
            tmp_edges.insert(tmp_edges.end(), new_edges.begin(), new_edges.end());
            parallel_radix_sort(tmp_edges, 32);
        }
    }

    // STEP 4: Finalizing & Writing
    cout << "Parallel Finalization..." << endl;
    vector<uint32_t> current_zone_edge_starts(NUM_ZONES);
    vector<uint32_t> current_zone_node_starts(NUM_ZONES);
    uint32_t g_node_ptr = 0, g_edge_ptr = 0;
    for (int zid = 0; zid < NUM_ZONES; ++zid) {
        current_zone_node_starts[zid] = g_node_ptr;
        current_zone_edge_starts[zid] = g_edge_ptr;
        uint32_t node_count = zone_node_counts[zid];
        uint32_t edge_count = 0;
        for (uint32_t i = 0; i < node_count; ++i) {
            uint32_t lid = g_node_ptr + i;
            while (g_edge_ptr + edge_count < tmp_edges.size() && tmp_edges[g_edge_ptr + edge_count].source_lid == lid) edge_count++;
        }
        g_node_ptr += node_count; g_edge_ptr += edge_count;
    }

    vector<future<void>> zone_tasks;
    for (int zid = 0; zid < NUM_ZONES; ++zid) {
        zone_tasks.push_back(async(launch::async, [=, &node_masters, &tmp_edges, &zone_node_counts, &current_zone_node_starts, &current_zone_edge_starts, &transit_groups]() {
            uint32_t node_count = zone_node_counts[zid];
            if (node_count == 0) return;
            uint32_t edge_start = current_zone_edge_starts[zid];
            uint32_t node_start = current_zone_node_starts[zid];
            uint32_t local_edge_ptr = 0;
            uint32_t local_transit_voyage_ptr = 0;
            ofstream node_out(DATA_DIR + "nodes_zone_" + to_string(zid) + ".bin", ios::binary);
            ofstream edge_out(DATA_DIR + "edges_zone_" + to_string(zid) + ".bin", ios::binary);
            ofstream transit_out(DATA_DIR + "transit_voyages_zone_" + to_string(zid) + ".bin", ios::binary);
            for (uint32_t i = 0; i < node_count; ++i) {
                uint32_t lid = node_start + i;
                node_masters[lid].edge_ptr = local_edge_ptr;
                while (edge_start + local_edge_ptr < tmp_edges.size() && tmp_edges[edge_start + local_edge_ptr].source_lid == lid) {
                    auto te = tmp_edges[edge_start + local_edge_ptr];
                    if (te.type & TRANSIT_FLAG) {
                        auto it = transit_groups.find({te.source_lid, te.target_lid});
                        if (it != transit_groups.end()) {
                            te.dist_mm = local_transit_voyage_ptr;
                            te.speed_limit = (uint8_t)min((size_t)255, it->second.voyages.size());
                            for (size_t v = 0; v < te.speed_limit; ++v) {
                                transit_out.write((char*)&it->second.voyages[v], sizeof(TransitVoyage));
                            }
                            local_transit_voyage_ptr += te.speed_limit;
                        }
                    }
                    FinalEdge fe = { te.target_lid, te.dist_mm, te.name_offset, te.type, te.speed_limit };
                    edge_out.write((char*)&fe, sizeof(FinalEdge));
                    local_edge_ptr++;
                }
            }
            node_out.write((char*)&node_masters[node_start], sizeof(NodeMaster) * node_count);
            NodeMaster sentinel = {0, 0, 0, local_edge_ptr};
            node_out.write((char*)&sentinel, sizeof(NodeMaster));
        }));
    }
    for (auto& task : zone_tasks) task.wait();

    ofstream meta_out(DATA_DIR + "metadata.bin", ios::binary);
    meta_out.write((char*)zone_node_counts.data(), sizeof(uint32_t) * NUM_ZONES);
    cout << "Processing Complete." << endl;
    return 0;
}