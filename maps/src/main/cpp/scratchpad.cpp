#include "scratchpad.h"

RoutingScratchpad::RoutingScratchpad(uint32_t power_of_two_size)
        : m_current_search_id(0) {
    m_size = 1 << power_of_two_size;
    m_mask = m_size - 1;
    m_buffer.resize(m_size, {0, 0xFFFFFFFF, 0xFFFFFFFF, 0});
}

void RoutingScratchpad::reset() {
    m_current_search_id++;
    if (m_current_search_id == 0) {
        for (auto& entry : m_buffer) entry.search_id = 0;
        m_current_search_id = 1;
    }
}

RoutingScratchpad::Entry& RoutingScratchpad::operator[](uint32_t node_id) {
    return get_entry(node_id);
}

RoutingScratchpad::Entry& RoutingScratchpad::get_entry(uint32_t node_id) {
    uint32_t h = hash(node_id);
    while (true) {
        Entry& entry = m_buffer[h];
        if (entry.search_id != m_current_search_id || entry.node_id == node_id) {
            if (entry.search_id != m_current_search_id) {
                entry.node_id = node_id;
                entry.g_score = 0xFFFFFFFF;
                entry.parent_id = 0xFFFFFFFF;
                entry.search_id = m_current_search_id;
            }
            return entry;
        }
        h = (h + 1) & m_mask;
    }
}