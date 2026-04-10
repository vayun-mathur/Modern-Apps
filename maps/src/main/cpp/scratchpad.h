#ifndef SCRATCHPAD_H
#define SCRATCHPAD_H

#include <cstdint>
#include <cstring> // For memset
#include <android/log.h>

#define LOG_TAG "OfflineRouterNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

constexpr uint32_t SCRATCHPAD_POWER = 28;
constexpr uint32_t SCRATCHPAD_SIZE = (1 << SCRATCHPAD_POWER);
constexpr uint32_t SCRATCHPAD_MASK = (SCRATCHPAD_SIZE - 1);

class RoutingScratchpad {
public:
    struct EntryProxy {
        uint32_t& g_score;
        uint32_t& parent_id;
    };

    uint32_t m_node_ids[SCRATCHPAD_SIZE];
    uint32_t m_g_scores[SCRATCHPAD_SIZE];
    uint32_t m_parent_ids[SCRATCHPAD_SIZE];

    RoutingScratchpad() {
    }

    inline void reset() {
        memset(m_node_ids, 0xFF, sizeof(m_node_ids));
    }

    inline uint32_t get_g_score(uint32_t node_id) const {
        uint32_t h = (node_id ^ (node_id >> 16)) & SCRATCHPAD_MASK;
        while (__builtin_expect(m_node_ids[h] != node_id, 0)) {
            h = (h + 1) & SCRATCHPAD_MASK;
        }
        return m_g_scores[h];
    }

    inline EntryProxy get_entry(uint32_t node_id) {
        uint32_t h = (node_id ^ (node_id >> 16)) & SCRATCHPAD_MASK;

        for (uint32_t i = 0; i < 10000; ++i) {
            uint32_t current_slot_id = m_node_ids[h];

            // Scenario 1: Found the node
            if (__builtin_expect(current_slot_id == node_id, 1)) {
                return { m_g_scores[h], m_parent_ids[h] };
            }

            // Scenario 2: Found an empty slot
            if (__builtin_expect(current_slot_id == 0xFFFFFFFF, 0)) {
                m_node_ids[h] = node_id;
                m_g_scores[h] = 0xFFFFFFFF;
                m_parent_ids[h] = 0xFFFFFFFF;
                return { m_g_scores[h], m_parent_ids[h] };
            }

            h = (h + 1) & SCRATCHPAD_MASK;
        }

        LOGE("CRITICAL: Scratchpad probe limit exceeded for node %u", node_id);
        return { m_g_scores[0], m_parent_ids[0] };
    }

    inline EntryProxy operator[](uint32_t node_id) {
        return get_entry(node_id);
    }
};

#endif