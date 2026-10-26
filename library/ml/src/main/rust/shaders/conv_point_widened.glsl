//! `conv_point_int8.comp`'s wide paths over weights the runtime widened to fp16 at upload
//! (`p.in1` = 3: four channels per lane, = 4: two). Every int8 value is exact in fp16, so each
//! tap is the same float the int8 paths unpack, summed in the same order: bit-identical, without
//! the per-tap `bitfieldExtract` + int-to-float that made those paths ALU-bound. `p.weight` is
//! still a 32-bit word offset, of a 16-byte-aligned fp16 row block; `in_c % 16 == 0`.

/// Four positions starting at element `at`, which need not be quad-aligned.
vec4 shifted_quad(uint at, uint last_quad) {
    uint q = at >> 2u;
    uint s = at & 3u;
    f16vec4 a = arena_quad[q];
    f16vec4 b = arena_quad[min(q + 1u, last_quad)];
    f16vec4 h = s == 0u ? a
        : s == 1u ? f16vec4(a.yzw, b.x)
        : s == 2u ? f16vec4(a.zw, b.xy)
        : f16vec4(a.w, b.xyz);
    return vec4(h);
}

void store_row(uint channel, uint position_base, uint positions, vec4 sum) {
    if (channel >= p.out_c) {
        return;
    }
    float scale = float(weights[p.act_weight + channel]);
    float offset = float(weights[p.bias + channel]);
    for (uint j = 0u; j < BLOCK; j++) {
        if (position_base + j >= positions) {
            continue;
        }
        uint index = channel * positions + position_base + j;
        float value = quant_store(fused_store(sum[j] * scale + offset, p.act, channel, index));
        arena[p.out_at + index] = float16_t(value);
    }
}

void widened_block() {
    uint tile = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
    if (tile >= p.count) {
        return;
    }
    uint positions = p.out_h * p.out_w;
    uint tiles_across = (positions + TILE - 1u) / TILE;
    uint lid = gl_LocalInvocationID.x;
    uint channel_base = (tile / tiles_across) * TILE + (lid >> 3u) * BLOCK;
    uint position_base = (tile % tiles_across) * TILE + (lid & 7u) * BLOCK;
    if (position_base >= positions) {
        return;
    }
    uint rows8[BLOCK];
    vec4 sum[BLOCK];
    for (uint i = 0u; i < BLOCK; i++) {
        uint row = min(channel_base + i, p.out_c - 1u);
        rows8[i] = (p.weight + ((row * p.in_c) >> 1u)) >> 2u;
        sum[i] = vec4(0.0);
    }
    if ((positions & 3u) == 0u && (p.in0 & 3u) == 0u) {
        uint quads = positions >> 2u;
        uint quad = (p.in0 + position_base) >> 2u;
        for (uint k = 0u; k < p.in_c; k += 8u) {
            uvec4 w[BLOCK];
            for (uint i = 0u; i < BLOCK; i++) {
                w[i] = weights128[rows8[i] + (k >> 3u)];
            }
            for (uint t = 0u; t < 8u; t++) {
                vec4 x = vec4(arena_quad[quad + (k + t) * quads]);
                for (uint i = 0u; i < BLOCK; i++) {
                    sum[i] += unpackHalf2x16(w[i][t >> 1u])[t & 1u] * x;
                }
            }
        }
    } else {
        uint first = p.in0 + position_base;
        uint last_quad = (p.in0 + p.in_c * positions - 1u) >> 2u;
        for (uint k = 0u; k < p.in_c; k += 8u) {
            uvec4 w[BLOCK];
            for (uint i = 0u; i < BLOCK; i++) {
                w[i] = weights128[rows8[i] + (k >> 3u)];
            }
            for (uint t = 0u; t < 8u; t++) {
                vec4 x = shifted_quad(first + (k + t) * positions, last_quad);
                for (uint i = 0u; i < BLOCK; i++) {
                    sum[i] += unpackHalf2x16(w[i][t >> 1u])[t & 1u] * x;
                }
            }
        }
    }
    for (uint i = 0u; i < BLOCK; i++) {
        store_row(channel_base + i, position_base, positions, sum[i]);
    }
}

void widened_split() {
    uint tile = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
    if (tile >= p.count) {
        return;
    }
    uint positions = p.out_h * p.out_w;
    uint tiles_across = (positions + TILE - 1u) / TILE;
    uint lid = gl_LocalInvocationID.x;
    uint channel_base = (tile / tiles_across) * (TILE / BLOCK * SPLIT) + (lid >> 3u) * SPLIT;
    uint position_base = (tile % tiles_across) * TILE + (lid & 7u) * BLOCK;
    if (position_base >= positions) {
        return;
    }
    uint rows8[SPLIT];
    vec4 sum[SPLIT];
    for (uint i = 0u; i < SPLIT; i++) {
        uint row = min(channel_base + i, p.out_c - 1u);
        rows8[i] = (p.weight + ((row * p.in_c) >> 1u)) >> 2u;
        sum[i] = vec4(0.0);
    }
    if ((positions & 3u) == 0u && (p.in0 & 3u) == 0u) {
        uint quads = positions >> 2u;
        uint quad = (p.in0 + position_base) >> 2u;
        for (uint k = 0u; k < p.in_c; k += 8u) {
            uvec4 w[SPLIT];
            for (uint i = 0u; i < SPLIT; i++) {
                w[i] = weights128[rows8[i] + (k >> 3u)];
            }
            for (uint t = 0u; t < 8u; t++) {
                vec4 x = vec4(arena_quad[quad + (k + t) * quads]);
                for (uint i = 0u; i < SPLIT; i++) {
                    sum[i] += unpackHalf2x16(w[i][t >> 1u])[t & 1u] * x;
                }
            }
        }
    } else {
        uint first = p.in0 + position_base;
        uint last_quad = (p.in0 + p.in_c * positions - 1u) >> 2u;
        for (uint k = 0u; k < p.in_c; k += 8u) {
            uvec4 w[SPLIT];
            for (uint i = 0u; i < SPLIT; i++) {
                w[i] = weights128[rows8[i] + (k >> 3u)];
            }
            for (uint t = 0u; t < 8u; t++) {
                vec4 x = shifted_quad(first + (k + t) * positions, last_quad);
                for (uint i = 0u; i < SPLIT; i++) {
                    sum[i] += unpackHalf2x16(w[i][t >> 1u])[t & 1u] * x;
                }
            }
        }
    }
    for (uint i = 0u; i < SPLIT; i++) {
        store_row(channel_base + i, position_base, positions, sum[i]);
    }
}
