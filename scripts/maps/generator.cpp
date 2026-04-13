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
};

struct FinalEdge {
    uint32_t target;
    uint32_t dist_mm;
    uint32_t name_offset;
    uint8_t type;
};

struct NodeTemp {
    uint64_t osm_id;
    uint64_t spatial_value;
    int32_t lat_e7;
    int32_t lon_e7;
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
    uint32_t sort_key() const { return source_lid; }
};

// Structure for caching road topology in RAM to avoid a 3rd PBF pass
struct CachedWay {
    uint32_t name_offset;
    uint32_t first_node_idx; // Offset into global topology vector
    uint16_t node_count;
    uint8_t type;
    bool oneway;
};
#pragma pack(pop)

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

inline uint32_t fast_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    int64_t dlat = std::abs((int64_t)lat1_e7 - lat2_e7);
    int64_t dlon = std::abs((int64_t)lon1_e7 - lon2_e7);
    int64_t dy_mm = (dlat * 11131949LL) / 100000LL;
    uint32_t scale_idx = (uint32_t)((lat1_e7 >> 19) + 2048);
    uint32_t scale = g_lon_to_mm_scale[scale_idx & 4095];
    int64_t dx_mm = (dlon * scale) >> 10;
    return (uint32_t)sqrt(dx_mm * dx_mm + dy_mm * dy_mm);
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

void print_progress(const string& label, uint64_t current, uint64_t total) {
    static auto last_update = chrono::steady_clock::now();
    auto now = chrono::steady_clock::now();
    if (current < total && chrono::duration_cast<chrono::milliseconds>(now - last_update).count() < 500) return;
    last_update = now;
    float progress = (total == 0) ? 1.0f : (float)current / total;
    cout << "\r" << left << setw(20) << label << " [" << int(progress * 100.0) << "%] " << flush;
    if (current >= total) cout << endl;
}

// ==========================================
// MAIN PROCESSOR
// ==========================================

int main(int argc, char* argv[]) {
    if (argc < 2) { cerr << "Usage: " << argv[0] << " map.osm.pbf" << endl; return 1; }

    mkdir(DATA_DIR.c_str(), 0777);
    init_lookup_table();
    ThreadPool pool(thread::hardware_concurrency());

    uint8_t* useful_nodes_mask = (uint8_t*)calloc(BITSET_SIZE, sizeof(uint8_t));
    atomic<uint64_t> total_useful_nodes{0};
    atomic<uint64_t> total_edges{0};

    // Way Caching Buffers
    vector<CachedWay> cached_ways;
    vector<uint64_t> way_nodes_topology;
    mutex way_cache_mtx;

    // STEP 1: Discovery & Caching (Pass 1 - Ways)
    {
        osmium::io::Reader reader{argv[1], osmium::osm_entity_bits::way};
        uint64_t total_size = reader.file_size();
        unordered_map<string, uint32_t> name_pool;
        mutex name_pool_mtx;
        uint32_t name_offset = 0;
        ofstream name_out(DATA_DIR + "road_names.bin", ios::binary);

        while (auto buf = reader.read()) {
            pool.enqueue([&, buf = move(buf)]() mutable {
                vector<CachedWay> local_ways;
                vector<uint64_t> local_topology;

                for (const auto& way : buf.select<osmium::Way>()) {
                    uint8_t hw = get_hw_id(way.tags().get_value_by_key("highway"));
                    if (hw == 0) continue;

                    uint32_t n_off = 0xFFFFFFFF;
                    const char* name_str = way.tags().get_value_by_key("name");
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

                    bool oneway = (strcmp(way.tags().get_value_by_key("oneway", ""), "yes") == 0);
                    const auto& nodes = way.nodes();

                    uint32_t topology_start = local_topology.size();
                    for (const auto& n : nodes) {
                        if (n.ref() < BITSET_SIZE && useful_nodes_mask[n.ref()] == 0) {
                            useful_nodes_mask[n.ref()] = 1;
                            total_useful_nodes++;
                        }
                        local_topology.push_back(n.ref());
                    }

                    local_ways.push_back({ n_off, topology_start, (uint16_t)nodes.size(), hw, oneway });
                    total_edges += (nodes.size() - 1) * (oneway ? 1 : 2);
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
    vector<NodeTemp> nodes_raw(total_useful_nodes);
    {
        osmium::io::Reader reader{argv[1], osmium::osm_entity_bits::node};
        uint64_t total_size = reader.file_size();
        atomic<uint64_t> idx{0};
        while (auto buf = reader.read()) {
            pool.enqueue([&nodes_raw, &idx, &useful_nodes_mask, buf = move(buf), &pool]() mutable {
                for (const auto& n : buf.select<osmium::Node>()) {
                    if (n.id() < BITSET_SIZE && useful_nodes_mask[n.id()]) {
                        uint64_t current_idx = idx.fetch_add(1);
                        double lat = n.location().lat();
                        double lon = n.location().lon();
                        nodes_raw[current_idx] = { (uint64_t)n.id(), latlng_to_spatial(lat, lon), (int32_t)(lat * 1e7), (int32_t)(lon * 1e7) };
                    }
                }
                pool.notify_worker_done();
            });
            print_progress("Step 2: Node Scan", reader.offset(), total_size);
        }
        pool.wait_finished();
    }

    free(useful_nodes_mask);

    cout << "Parallel Radix Sorting Nodes..." << endl;
    parallel_radix_sort(nodes_raw, 64);

    vector<NodeMaster> node_masters(total_useful_nodes);
    vector<IDMapping> id_to_local(total_useful_nodes);
    vector<uint32_t> zone_node_counts(NUM_ZONES, 0);

    for (uint32_t i = 0; i < total_useful_nodes; ++i) {
        node_masters[i] = { nodes_raw[i].lat_e7, nodes_raw[i].lon_e7, nodes_raw[i].spatial_value, 0 };
        id_to_local[i] = { nodes_raw[i].osm_id, i };
        zone_node_counts[(int)((nodes_raw[i].spatial_value >> 58) & 0x3F)]++;
    }
    vector<NodeTemp>().swap(nodes_raw);

    cout << "Parallel Radix Sorting Mapping..." << endl;
    parallel_radix_sort(id_to_local, 64);
    build_id_tile_index(id_to_local);

    // STEP 3: Graph Construction (In-Memory from cached ways)
    vector<TmpEdge> tmp_edges(total_edges);
    atomic<uint64_t> global_edge_idx{0};
    {
        size_t n_ways = cached_ways.size();
        size_t chunk = n_ways / thread::hardware_concurrency();
        vector<future<void>> construction_tasks;

        for (int t = 0; t < thread::hardware_concurrency(); ++t) {
            construction_tasks.push_back(async(launch::async, [&, t, chunk, n_ways]() {
                size_t start_way = t * chunk;
                size_t end_way = (t == thread::hardware_concurrency() - 1) ? n_ways : (t + 1) * chunk;
                vector<TmpEdge> local_buffer;
                local_buffer.reserve(10000);

                for (size_t w = start_way; w < end_way; ++w) {
                    const auto& cw = cached_ways[w];
                    for (size_t i = 0; i < cw.node_count - 1; ++i) {
                        uint64_t u_osm = way_nodes_topology[cw.first_node_idx + i];
                        uint64_t v_osm = way_nodes_topology[cw.first_node_idx + i + 1];

                        uint32_t u_lid = get_local_id_by_osm(u_osm, id_to_local);
                        uint32_t v_lid = get_local_id_by_osm(v_osm, id_to_local);
                        if (u_lid == 0xFFFFFFFF || v_lid == 0xFFFFFFFF) continue;

                        uint32_t dist = fast_dist_mm(node_masters[u_lid].lat_e7, node_masters[u_lid].lon_e7,
                                                     node_masters[v_lid].lat_e7, node_masters[v_lid].lon_e7);

                        local_buffer.push_back({ u_lid, v_lid, dist, cw.name_offset, cw.type });
                        if (!cw.oneway) local_buffer.push_back({ v_lid, u_lid, dist, cw.name_offset, cw.type });

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
        cout << "Step 3: Graph Construction from Memory Complete." << endl;
    }

    vector<IDMapping>().swap(id_to_local);
    vector<uint64_t>().swap(way_nodes_topology);
    vector<CachedWay>().swap(cached_ways);

    cout << "Parallel Radix Sorting Edges..." << endl;
    parallel_radix_sort(tmp_edges, 32);

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
        zone_tasks.push_back(async(launch::async, [=, &node_masters, &tmp_edges, &zone_node_counts, &current_zone_node_starts, &current_zone_edge_starts]() {
            uint32_t node_count = zone_node_counts[zid];
            if (node_count == 0) return;
            uint32_t edge_start = current_zone_edge_starts[zid];
            uint32_t node_start = current_zone_node_starts[zid];
            uint32_t local_edge_ptr = 0;
            ofstream node_out(DATA_DIR + "nodes_zone_" + to_string(zid) + ".bin", ios::binary);
            ofstream edge_out(DATA_DIR + "edges_zone_" + to_string(zid) + ".bin", ios::binary);
            for (uint32_t i = 0; i < node_count; ++i) {
                uint32_t lid = node_start + i;
                node_masters[lid].edge_ptr = local_edge_ptr;
                while (edge_start + local_edge_ptr < tmp_edges.size() && tmp_edges[edge_start + local_edge_ptr].source_lid == lid) {
                    const auto& te = tmp_edges[edge_start + local_edge_ptr];
                    FinalEdge fe = { te.target_lid, te.dist_mm, te.name_offset, te.type };
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