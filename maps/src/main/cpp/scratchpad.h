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
 * Designed to handle 2.6 Billion+ node IDs with ZERO collisions.
 * Uses a directory of pages to keep RAM usage low by only allocating
 * memory for the geographic area explored during the search.
 */
class RoutingScratchpad {
public:
    struct Entry {
        uint32_t node_id; // Explicitly kept to match your existing API
        uint32_t g_fwd;
        uint32_t g_bwd;
        uint32_t p_fwd;
        uint32_t p_bwd;
    };

private:
    // Page bits 14 = 16,384 entries per page.
    // Directory size = 2^32 / 2^14 = 262,144 pointers.
    // Directory RAM = ~2MB.
    static constexpr uint32_t PAGE_BITS = 14;
    static constexpr uint32_t PAGE_SIZE = (1 << PAGE_BITS);
    static constexpr uint32_t PAGE_MASK = (PAGE_SIZE - 1);
    static constexpr uint32_t DIR_SIZE = (1ULL << 32) >> PAGE_BITS;

    Entry** m_directory;
    std::vector<uint32_t> m_active_pages;

public:
    RoutingScratchpad() {
        // Allocate the directory (the top level of the page table)
        m_directory = (Entry**)calloc(DIR_SIZE, sizeof(Entry*));
        m_active_pages.reserve(1024);
    }

    ~RoutingScratchpad() {
        cleanup_pages();
        if (m_directory) free(m_directory);
    }

    /**
     * @brief Frees all allocated pages to prevent OOM between routes.
     */
    void cleanup_pages() {
        for (uint32_t page_idx : m_active_pages) {
            if (m_directory[page_idx]) {
                free(m_directory[page_idx]);
                m_directory[page_idx] = nullptr;
            }
        }
        m_active_pages.clear();
    }

    /**
     * @brief Clears the scratchpad for a new search.
     */
    inline void reset() {
        cleanup_pages();
    }

    /**
     * @brief Direct-mapped access to a node's data.
     * Zero collisions, O(1) complexity.
     */
    inline Entry& get_entry(uint32_t node_id) {
        uint32_t dir_idx = node_id >> PAGE_BITS;
        uint32_t page_offset = node_id & PAGE_MASK;

        // Level 1: Check if the page exists
        if (__builtin_expect(m_directory[dir_idx] == nullptr, 0)) {
            // Level 2: Lazy allocation of the page
            Entry* new_page = (Entry*)malloc(PAGE_SIZE * sizeof(Entry));

            // Initialize the new page with 0xFF (Infinity/Null)
            memset(new_page, 0xFF, PAGE_SIZE * sizeof(Entry));

            m_directory[dir_idx] = new_page;
            m_active_pages.push_back(dir_idx);
        }

        Entry& e = m_directory[dir_idx][page_offset];

        // Ensure the node_id is set for the first time it's accessed
        // to stay compatible with your existing reconstruction logic.
        if (__builtin_expect(e.node_id == 0xFFFFFFFF, 0)) {
            e.node_id = node_id;
        }

        return e;
    }

    /**
     * @brief API-compatible operator overload.
     */
    inline Entry& operator[](uint32_t node_id) {
        return get_entry(node_id);
    }
};

#endif