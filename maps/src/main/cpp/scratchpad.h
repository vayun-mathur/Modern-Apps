#ifndef SCRATCHPAD_H
#define SCRATCHPAD_H

#include <cstdint>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "OfflineRouterNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * @brief Two-Level Page Table Scratchpad.
 */
class RoutingScratchpad {
public:
    struct Entry {
        uint32_t node_id;
        uint32_t g_fwd;
        uint32_t g_bwd;
        uint32_t p_fwd;
        uint32_t p_bwd;
    };

private:
    static constexpr uint32_t PAGE_BITS = 14;
    static constexpr uint32_t PAGE_SIZE = (1 << PAGE_BITS);
    static constexpr uint32_t PAGE_MASK = (PAGE_SIZE - 1);
    static constexpr uint32_t DIR_SIZE = (1ULL << 32) >> PAGE_BITS;

    Entry** m_directory;
    std::vector<uint32_t> m_active_pages;

public:
    RoutingScratchpad() {
        m_directory = (Entry**)calloc(DIR_SIZE, sizeof(Entry*));
        m_active_pages.reserve(1024);
    }

    ~RoutingScratchpad() {
        cleanup_pages();
        if (m_directory) free(m_directory);
    }

    void cleanup_pages() {
        for (uint32_t page_idx : m_active_pages) {
            if (m_directory[page_idx]) {
                free(m_directory[page_idx]);
                m_directory[page_idx] = nullptr;
            }
        }
        m_active_pages.clear();
    }

    inline void reset() {
        cleanup_pages();
    }

    inline Entry& get_entry(uint32_t node_id) {
        uint32_t dir_idx = node_id >> PAGE_BITS;
        uint32_t page_offset = node_id & PAGE_MASK;

        if (__builtin_expect(m_directory[dir_idx] == nullptr, 0)) {
            Entry* new_page = (Entry*)malloc(PAGE_SIZE * sizeof(Entry));
            memset(new_page, 0xFF, PAGE_SIZE * sizeof(Entry));
            m_directory[dir_idx] = new_page;
            m_active_pages.push_back(dir_idx);
        }

        Entry& e = m_directory[dir_idx][page_offset];
        if (__builtin_expect(e.node_id == 0xFFFFFFFF, 0)) {
            e.node_id = node_id;
        }
        return e;
    }

    inline Entry& operator[](uint32_t node_id) {
        return get_entry(node_id);
    }
};

/**
 * @brief Simple Page Table for Traffic Speeds per zone.
 */
class TrafficPageTable {
private:
    static constexpr uint32_t PAGE_BITS = 14;
    static constexpr uint32_t PAGE_SIZE = (1 << PAGE_BITS);
    static constexpr uint32_t PAGE_MASK = (PAGE_SIZE - 1);
    static constexpr uint32_t DIR_SIZE = (1ULL << 32) >> PAGE_BITS; // Support full 32-bit ID space

    uint8_t** m_directory;
    std::vector<uint32_t> m_active_pages;

public:
    TrafficPageTable() {
        m_directory = (uint8_t**)calloc(DIR_SIZE, sizeof(uint8_t*));
    }

    ~TrafficPageTable() {
        clear();
        if (m_directory) free(m_directory);
    }

    inline void set_speed(uint32_t local_edge_id, uint8_t speed_kph) {
        uint32_t dir_idx = local_edge_id >> PAGE_BITS;
        uint32_t page_offset = local_edge_id & PAGE_MASK;

        if (__builtin_expect(m_directory[dir_idx] == nullptr, 0)) {
            uint8_t* new_page = (uint8_t*)malloc(PAGE_SIZE * sizeof(uint8_t));
            memset(new_page, 0, PAGE_SIZE * sizeof(uint8_t));
            m_directory[dir_idx] = new_page;
            m_active_pages.push_back(dir_idx);
        }
        m_directory[dir_idx][page_offset] = speed_kph;
    }

    inline uint8_t get_speed(uint32_t local_edge_id) const {
        uint32_t dir_idx = local_edge_id >> PAGE_BITS;
        uint32_t page_offset = local_edge_id & PAGE_MASK;
        if (m_directory[dir_idx] == nullptr) return 0;
        return m_directory[dir_idx][page_offset];
    }

    void clear() {
        for (uint32_t page_idx : m_active_pages) {
            if (m_directory[page_idx]) free(m_directory[page_idx]);
            m_directory[page_idx] = nullptr;
        }
        m_active_pages.clear();
    }
};

#endif
