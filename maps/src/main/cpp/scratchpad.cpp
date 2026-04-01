#include "scratchpad.h"
#include <android/log.h>

#define LOG_TAG "OfflineRouterNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

RoutingScratchpad::RoutingScratchpad(uint32_t power_of_two_size)
        : m_current_search_id(0) {
    m_size = 1 << power_of_two_size;
    m_mask = m_size - 1;
    m_buffer.resize(m_size, {0, 0xFFFFFFFF, 0xFFFFFFFF, 0});
}

/**
 * Resets the search ID to distinguish between different routing requests.
 * If the 32-bit ID wraps around, clear the entire buffer to avoid stale data.
 */
void RoutingScratchpad::reset() {
    m_current_search_id++;
    if (m_current_search_id == 0) {
        for (auto& entry : m_buffer) {
            entry.search_id = 0;
        }
        m_current_search_id = 1;
    }
}

RoutingScratchpad::Entry& RoutingScratchpad::operator[](uint32_t node_id) {
    return get_entry(node_id);
}

/**
 * Retrieves or initializes an entry for a specific node.
 * Uses linear probing with a safety exit to prevent infinite loops
 * in the unlikely event of a full table.
 */
RoutingScratchpad::Entry& RoutingScratchpad::get_entry(uint32_t node_id) {
    uint32_t h = hash(node_id);
    uint32_t probe_count = 0;

    // Safety limit: Even with collisions, a chain shouldn't exceed a small
    // fraction of the table given the low load factor.
    // 10,000 is an extremely generous limit for a 33-million entry table.
    const uint32_t MAX_PROBES = 10000;

    while (probe_count < MAX_PROBES) {
        Entry& entry = m_buffer[h];

        // Slot matches this node or is 'stale' (from a previous routing request)
        if (entry.search_id != m_current_search_id || entry.node_id == node_id) {
            if (entry.search_id != m_current_search_id) {
                // Initialize the stale entry for the current routing request
                entry.node_id = node_id;
                entry.g_score = 0xFFFFFFFF;
                entry.parent_id = 0xFFFFFFFF;
                entry.search_id = m_current_search_id;
            }
            return entry;
        }

        // Linear probing collision: check the next slot
        h = (h + 1) & m_mask;
        probe_count++;
    }

    // If we hit this, something is critically wrong with the hash distribution or data integrity.
    LOGE("CRITICAL: Scratchpad probe limit exceeded for node %u at hash index %u (Probe count: %u)",
         node_id, hash(node_id), probe_count);

    // Return the first slot as a fallback to avoid crashing,
    // though the route will likely be corrupted.
    return m_buffer[0];
}

double RoutingScratchpad::load_factor() const {
    uint32_t used_slots = 0;
    for (const auto& entry : m_buffer) {
        if (entry.search_id == m_current_search_id) {
            used_slots++;
        }
    }
    return (double)used_slots / m_size;
}