//
//  om_common.h
//  OpenMeteoApi
//
//  Created by Patrick Zippenfenig on 29.10.2024.
//

#ifndef OM_COMMON_H
#define OM_COMMON_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

/// Number of look-up-table addresses to individual data chunks that will be compressed.
/// Effectively only the first address will be stored and the next addresses are delta and fixed-bytes-encoded.
/// A larger number reduces file size marginally if a lot of chunks are used. However, read performance decreases.
#define LUT_CHUNK_COUNT 64

typedef enum {
    ERROR_OK = 0, // not an error
    ERROR_INVALID_COMPRESSION_TYPE = 1,
    ERROR_INVALID_DATA_TYPE = 2,
    ERROR_OUT_OF_BOUND_READ = 3,
    ERROR_NOT_AN_OM_FILE = 4,
    ERROR_DEFLATED_SIZE_MISMATCH = 5,
    ERROR_INVALID_DIMENSIONS = 6,
    ERROR_INVALID_CHUNK_DIMENSIONS = 7,
    ERROR_INVALID_READ_OFFSET = 8,
    ERROR_INVALID_READ_COUNT = 9,
    ERROR_INVALID_CUBE_OFFSET = 10,
} OmError_t;

const char* om_error_string(OmError_t error);

/// Data types
typedef enum {
    DATA_TYPE_NONE = 0,
    DATA_TYPE_INT8 = 1,
    DATA_TYPE_UINT8 = 2,
    DATA_TYPE_INT16 = 3,
    DATA_TYPE_UINT16 = 4,
    DATA_TYPE_INT32 = 5,
    DATA_TYPE_UINT32 = 6,
    DATA_TYPE_INT64 = 7,
    DATA_TYPE_UINT64 = 8,
    DATA_TYPE_FLOAT = 9,
    DATA_TYPE_DOUBLE = 10,
    DATA_TYPE_STRING = 11,
    DATA_TYPE_INT8_ARRAY = 12,
    DATA_TYPE_UINT8_ARRAY = 13,
    DATA_TYPE_INT16_ARRAY = 14,
    DATA_TYPE_UINT16_ARRAY = 15,
    DATA_TYPE_INT32_ARRAY = 16,
    DATA_TYPE_UINT32_ARRAY = 17,
    DATA_TYPE_INT64_ARRAY = 18,
    DATA_TYPE_UINT64_ARRAY = 19,
    DATA_TYPE_FLOAT_ARRAY = 20,
    DATA_TYPE_DOUBLE_ARRAY = 21,
    DATA_TYPE_STRING_ARRAY = 22
} OmDataType_t;

/// Compression types
typedef enum {
    COMPRESSION_PFOR_DELTA2D_INT16 = 0, // Lossy compression using 2D delta coding and scale-factor. Only supports float and scales to 16-bit signed integer.
    COMPRESSION_FPX_XOR2D = 1, // Lossless float/double compression using 2D xor coding.
    COMPRESSION_PFOR_DELTA2D = 2, // PFor integer compression. Floating point values are scaled to 32 bit signed integers. Doubles are scaled to 64 bit signed integers.
    COMPRESSION_PFOR_DELTA2D_INT16_LOGARITHMIC = 3, // Similar to `COMPRESSION_PFOR_DELTA2D_INT16` but applies `log10(1+x)` before.
    COMPRESSION_NONE = 4
} OmCompression_t;

/// Get the number of bytes per element.
/// This function will set an error if called for an invalid data type.
/// It only supports array types.
uint8_t om_get_bytes_per_element(OmDataType_t data_type, OmError_t* error);

/// Get the number of bytes per element after compression.
/// This function will set an error if called for an invalid data type.
/// It only supports array types.
uint8_t om_get_bytes_per_element_compressed(OmDataType_t data_type, OmCompression_t compression, OmError_t* error);

static inline uint64_t divide_rounded_up(uint64_t dividend, uint64_t divisor) {
    return (dividend + divisor - 1) / divisor;
}

static inline uint64_t om_min(uint64_t a, uint64_t b) { return a < b ? a : b; }
static inline uint64_t om_max(uint64_t a, uint64_t b) { return a > b ? a : b; }

/// Copy 16 bit integer array and convert to float
void om_common_copy_float_to_int16(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst);
void om_common_copy_float_to_int32(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst);
void om_common_copy_double_to_int64(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst);

/// Copy 16 bit integer array and convert to float and scale log10
void om_common_copy_float_to_int16_log10(uint64_t length, float scale_factor, const void* src, void* dst);

/// Convert int16 and scale to float
void om_common_copy_int16_to_float(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst);
void om_common_copy_int32_to_float(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst);
void om_common_copy_int64_to_double(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst);

/// Convert int16 and scale to float with log10
void om_common_copy_int16_to_float_log10(uint64_t length, float scale_factor, const void* src, void* dst);

void om_common_copy8(uint64_t length, const void* src, void* dst);
void om_common_copy16(uint64_t length, const void* src, void* dst);
void om_common_copy32(uint64_t length, const void* src, void* dst);
void om_common_copy64(uint64_t length, const void* src, void* dst);

uint64_t om_common_compress_fpxenc32(const void* src, uint64_t length, void* dst);
uint64_t om_common_compress_fpxenc64(const void* src, uint64_t length, void* dst);
uint64_t om_common_decompress_fpxdec32(const void* src, uint64_t length, void* dst);
uint64_t om_common_decompress_fpxdec64(const void* src, uint64_t length, void* dst);



#endif // OM_COMMON_H
