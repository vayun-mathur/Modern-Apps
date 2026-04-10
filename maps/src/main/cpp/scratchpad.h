#ifndef SCRATCHPAD_H
#define SCRATCHPAD_H

#include <cstdint>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "OfflineRouterNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Power 25 = 33.5 Million slots.
// At 20 bytes per entry, this is ~640MB. Much safer for Android than 3GB.
constexpr uint32_t SCRATCHPAD_POWER = 27;
constexpr uint32_t SCRATCHPAD_SIZE = (1 << SCRATCHPAD_POWER);
constexpr uint32_t SCRATCHPAD_MASK = (SCRATCHPAD_SIZE - 1);

class RoutingScratchpad {
public:
    struct Entry {
        uint32_t node_id;
        uint32_t g_fwd;
        uint32_t g_bwd;
        uint32_t p_fwd;
        uint32_t p_bwd;
    };

    // Single array of structures for cache efficiency
    Entry m_entries[SCRATCHPAD_SIZE];

    RoutingScratchpad() {}

    inline void reset() {
        // Initialize node_ids to 0xFFFFFFFF to mark as empty
        // We use a custom loop or memset because Entry is large
        memset(m_entries, 0xFF, SCRATCHPAD_SIZE * sizeof(Entry));
    }

    inline Entry& get_entry(uint32_t node_id) {
        uint32_t h = (node_id ^ (node_id >> 16)) & SCRATCHPAD_MASK;

        for (uint32_t i = 0; i < 1024; ++i) { // Smaller probe limit for speed
            if (__builtin_expect(m_entries[h].node_id == node_id, 1)) {
                return m_entries[h];
            }

            if (__builtin_expect(m_entries[h].node_id == 0xFFFFFFFF, 0)) {
                m_entries[h].node_id = node_id;
                // g_fwd, g_bwd, p_fwd, p_bwd are already 0xFFFFFFFF from reset()
                return m_entries[h];
            }

            h = (h + 1) & SCRATCHPAD_MASK;
        }

        LOGE("CRITICAL: Scratchpad probe limit exceeded for node %u", node_id);
        return m_entries[0];
    }

    inline Entry& operator[](uint32_t node_id) {
        return get_entry(node_id);
    }
};

#endif