#ifndef RADIX_HEAP_H
#define RADIX_HEAP_H

#include <cstdint>
#include <algorithm>

struct Node {
    uint32_t score;
    uint32_t id;
};

/**
 * @brief Ultra-fast Radix Heap using static 2D arrays.
 * Removes all heap allocations and vector overhead from the search loop.
 */
class RadixHeap {
private:
    // 33 buckets, each with a fixed capacity.
    // 128k entries per bucket * 33 buckets * 8 bytes/node = ~34.6 MB total.
    // Most A* frontiers for 6M nodes explored fit comfortably in this distribution.
    static constexpr uint32_t BUCKET_CAPACITY = 128 * 1024;

    uint64_t m_bucket_mask;
    uint32_t m_last_pop_value;
    uint32_t m_count;

    // Member arrays will reside in the BSS segment if RadixHeap is global
    uint32_t m_sizes[33];
    Node m_buckets[33][BUCKET_CAPACITY];

    inline uint32_t get_bucket_idx(uint32_t score) const {
        uint32_t x = score ^ m_last_pop_value;
        // __builtin_clz is a single cycle 'CLZ' instruction on ARM64
        return x == 0 ? 0 : 32 - __builtin_clz(x);
    }

public:
    RadixHeap() : m_bucket_mask(0), m_last_pop_value(0), m_count(0) {
        for (int i = 0; i < 33; ++i) m_sizes[i] = 0;
    }

    inline void push(uint32_t score, uint32_t node_id) {
        uint32_t i = get_bucket_idx(score);

        // Raw array access: No bounds check in release for maximum speed
        uint32_t pos = m_sizes[i]++;
        m_buckets[i][pos] = {score, node_id};

        m_bucket_mask |= (1ULL << i);
        m_count++;
    }

    inline uint32_t pop() {
        // If bucket 0 is empty, we must redistribute from the next non-empty bucket
        if (__builtin_expect(m_sizes[0] == 0, 0)) {
            // Find the index of the first non-zero bit after bit 0
            uint32_t i = __builtin_ctzll(m_bucket_mask & ~1ULL);

            Node* b = m_buckets[i];
            uint32_t b_size = m_sizes[i];

            // 1. Find min element to advance last_pop_value.
            // Modern Clang will auto-vectorize this loop using UMIN instructions.
            uint32_t min_score = b[0].score;
            for (uint32_t j = 1; j < b_size; ++j) {
                if (b[j].score < min_score) {
                    min_score = b[j].score;
                }
            }

            m_last_pop_value = min_score;

            // 2. Redistribute elements into lower buckets.
            // Because we advanced last_pop_value, get_bucket_idx(node.score)
            // is guaranteed to return a value < i.
            for (uint32_t j = 0; j < b_size; ++j) {
                const Node& node = b[j];
                uint32_t idx = get_bucket_idx(node.score);

                uint32_t pos = m_sizes[idx]++;
                m_buckets[idx][pos] = node;

                m_bucket_mask |= (1ULL << idx);
            }

            // Clear the bucket we just emptied
            m_sizes[i] = 0;
            m_bucket_mask &= ~(1ULL << i);
        }

        // Pop from bucket 0 (always O(1))
        uint32_t node_id = m_buckets[0][--m_sizes[0]].id;

        // Update mask if bucket 0 became empty
        if (m_sizes[0] == 0) {
            m_bucket_mask &= ~1ULL;
        }

        m_count--;
        return node_id;
    }

    /**
     * @brief Resets the heap for a new search.
     * Does not zero memory, just resets pointers/masks.
     */
    void clear() {
        for (uint32_t i = 0; i < 33; ++i) m_sizes[i] = 0;
        m_last_pop_value = 0;
        m_count = 0;
        m_bucket_mask = 0;
    }

    inline bool empty() const { return m_count == 0; }
    inline uint32_t size() const { return m_count; }
};

#endif // RADIX_HEAP_H