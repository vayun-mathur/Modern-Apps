#ifndef BUCKET_QUEUE_H
#define BUCKET_QUEUE_H

#include <vector>
#include <cstdint>

/**
 * BUCKET QUEUE (DIAL'S OPTIMIZATION)
 * For road routing, costs are limited. A circular bucket queue provides O(1) push/pop operations,
 * which is significantly faster than the O(log N) overhead of a standard priority queue.
 */
const uint32_t BUCKET_COUNT = 524288; // ~524k buckets, must be power of 2 for masking

struct BucketQueue {
    std::vector<uint32_t> buckets[BUCKET_COUNT];
    uint32_t min_score = 0xFFFFFFFF;
    uint32_t count = 0;

    /**
     * Pushes a node into the bucket corresponding to its score.
     * Uses bitwise AND for fast circular indexing (requires BUCKET_COUNT to be a power of 2).
     */
    inline void push(uint32_t score, uint32_t node) {
        buckets[score & (BUCKET_COUNT - 1)].push_back(node);
        if (score < min_score) min_score = score;
        count++;
    }

    /**
     * Pops the node with the lowest score.
     * Scans forward from the last known minimum score to find the next non-empty bucket.
     */
    inline uint32_t pop() {
        while (buckets[min_score & (BUCKET_COUNT - 1)].empty()) {
            min_score++;
        }
        uint32_t node = buckets[min_score & (BUCKET_COUNT - 1)].back();
        buckets[min_score & (BUCKET_COUNT - 1)].pop_back();
        count--;
        return node;
    }

    /**
     * Clears all buckets and resets the state for a new search.
     */
    void clear() {
        for (uint32_t i = 0; i < BUCKET_COUNT; ++i) {
            if (!buckets[i].empty()) buckets[i].clear();
        }
        min_score = 0xFFFFFFFF;
        count = 0;
    }

    inline bool empty() const { return count == 0; }
};

#endif // BUCKET_QUEUE_H