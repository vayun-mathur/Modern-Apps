#ifndef RADIX_HEAP_H
#define RADIX_HEAP_H

#include <cstdint>
#include <algorithm>
#include <cstdlib>

struct HeapNode {
    uint32_t score;
    uint32_t id;
};

/**
 * @brief Ultra-fast Radix Heap using static 2D arrays.
 * Removed all heap allocations and vector overhead from the search loop.
 */
class RadixHeap {
private:
    static constexpr uint32_t BUCKET_CAPACITY = 256 * 1024;

    uint64_t m_bucket_mask;
    uint32_t m_last_pop_value;
    uint32_t m_count;

    uint32_t m_sizes[33];
    HeapNode m_buckets[33][BUCKET_CAPACITY];

    inline uint32_t get_bucket_idx(uint32_t score) const {
        uint32_t x = score ^ m_last_pop_value;
        return x == 0 ? 0 : 32 - __builtin_clz(x);
    }

public:
    RadixHeap() : m_bucket_mask(0), m_last_pop_value(0), m_count(0) {
        memset(m_sizes, 0, sizeof(m_sizes));
    }

    inline void push(uint32_t score, uint32_t node_id) {
        uint32_t i = get_bucket_idx(score);
        uint32_t pos = m_sizes[i]++;
        m_buckets[i][pos] = {score, node_id};
        m_bucket_mask |= (1ULL << i);
        m_count++;
    }

    inline uint32_t pop() {
        if (__builtin_expect(m_sizes[0] == 0, 0)) {
            uint32_t i = __builtin_ctzll(m_bucket_mask & ~1ULL);
            HeapNode* b = m_buckets[i];
            uint32_t b_size = m_sizes[i];

            uint32_t min_score = b[0].score;
            for (uint32_t j = 1; j < b_size; ++j) {
                if (b[j].score < min_score) min_score = b[j].score;
            }

            m_last_pop_value = min_score;

            for (uint32_t j = 0; j < b_size; ++j) {
                const HeapNode& node = b[j];
                uint32_t idx = get_bucket_idx(node.score);
                m_buckets[idx][m_sizes[idx]++] = node;
                m_bucket_mask |= (1ULL << idx);
            }

            m_sizes[i] = 0;
            m_bucket_mask &= ~(1ULL << i);
        }

        uint32_t node_id = m_buckets[0][--m_sizes[0]].id;
        if (m_sizes[0] == 0) m_bucket_mask &= ~1ULL;
        m_count--;
        return node_id;
    }

    void clear() {
        for (uint32_t i = 0; i < 33; ++i) m_sizes[i] = 0;
        m_last_pop_value = 0;
        m_count = 0;
        m_bucket_mask = 0;
    }

    inline bool empty() const { return m_count == 0; }
    inline uint32_t size() const { return m_count; }
    inline uint32_t top_key() const { return m_last_pop_value; }
};

#endif // RADIX_HEAP_H