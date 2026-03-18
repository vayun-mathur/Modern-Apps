#ifndef SCRATCHPAD_H
#define SCRATCHPAD_H

#include <vector>
#include <cstdint>

/**
 * @brief A high-performance, non-allocating scratchpad for A* search metadata.
 */
class RoutingScratchpad {
public:
    struct Entry {
        uint32_t node_id;
        uint32_t g_score;
        uint32_t parent_id;
        uint32_t search_id;
    };

    explicit RoutingScratchpad(uint32_t power_of_two_size = 20);

    /**
     * @brief "Clears" the scratchpad by incrementing the generation ID.
     */
    void reset();

    /**
     * @brief Access metadata for a specific node ID using array syntax.
     */
    Entry& operator[](uint32_t node_id);

    /**
     * @brief Explicitly gets or initializes metadata for a node.
     */
    Entry& get_entry(uint32_t node_id);

private:
    uint32_t m_size;
    uint32_t m_mask;
    uint32_t m_current_search_id;
    std::vector<Entry> m_buffer;

    inline uint32_t hash(uint32_t x) const {
        return x & m_mask;
    }
};

#endif // SCRATCHPAD_H