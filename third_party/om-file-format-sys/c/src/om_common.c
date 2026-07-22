//
//  om_common.c
//  OpenMeteoApi
//
//  Created by Patrick Zippenfenig on 30.10.2024.
//

#include "om_common.h"
#include <math.h>
#include "vp4.h"
#include "fp.h"
#include "conf.h"
#pragma clang diagnostic ignored "-Wunused-parameter"
#pragma clang diagnostic warning "-Wbad-function-cast"
#pragma clang diagnostic error "-Wswitch"

const char* om_error_string(OmError_t error) {
    switch (error) {
        case ERROR_OK:
            return "No error occured";
        case ERROR_INVALID_COMPRESSION_TYPE:
            return "Invalid compression type";
        case ERROR_INVALID_DATA_TYPE:
            return "Invalid data type";
        case ERROR_OUT_OF_BOUND_READ:
            return "Corrupted data with potential out-of-bound read";
        case ERROR_NOT_AN_OM_FILE:
            return "Not an OM file";
        case ERROR_DEFLATED_SIZE_MISMATCH:
            return "Corrupted data: Deflated size does not match";
        case ERROR_INVALID_DIMENSIONS:
            return "Invalid dimensions";
        case ERROR_INVALID_CHUNK_DIMENSIONS:
            return "Invalid chunk dimensions";
        case ERROR_INVALID_READ_OFFSET:
            return "Invalid read offset dimensions";
        case ERROR_INVALID_READ_COUNT:
            return "Invalid read count dimensions";
        case ERROR_INVALID_CUBE_OFFSET:
            return "Invalid read cube offset dimensions";
    }
    return "";
}

uint8_t om_get_bytes_per_element(OmDataType_t data_type, OmError_t* error) {
    switch (data_type) {
        case DATA_TYPE_INT8_ARRAY:
        case DATA_TYPE_UINT8_ARRAY:
            return 1;

        case DATA_TYPE_INT16_ARRAY:
        case DATA_TYPE_UINT16_ARRAY:
            return 2;

        case DATA_TYPE_INT32_ARRAY:
        case DATA_TYPE_UINT32_ARRAY:
        case DATA_TYPE_FLOAT_ARRAY:
            return 4;

        case DATA_TYPE_INT64_ARRAY:
        case DATA_TYPE_UINT64_ARRAY:
        case DATA_TYPE_DOUBLE_ARRAY:
            return 8;

        case DATA_TYPE_STRING_ARRAY:
            // NOTE: STRING_ARRAY is currently not implemented!
            *error = ERROR_INVALID_DATA_TYPE;
            return 0;
            break;

        case DATA_TYPE_INT8:
        case DATA_TYPE_UINT8:
        case DATA_TYPE_INT16:
        case DATA_TYPE_UINT16:
        case DATA_TYPE_INT32:
        case DATA_TYPE_UINT32:
        case DATA_TYPE_FLOAT:
        case DATA_TYPE_INT64:
        case DATA_TYPE_UINT64:
        case DATA_TYPE_DOUBLE:
        case DATA_TYPE_NONE:
        case DATA_TYPE_STRING:
            // NOTE: These datatypes are scalar and should not be used with this function!
            *error = ERROR_INVALID_DATA_TYPE;
            return 0;
    }
}

uint8_t om_get_bytes_per_element_compressed(OmDataType_t data_type, OmCompression_t compression, OmError_t* error) {
    // Adjust compressed size based on compression type
    switch (compression) {
        case COMPRESSION_PFOR_DELTA2D_INT16:
        case COMPRESSION_PFOR_DELTA2D_INT16_LOGARITHMIC:
            if (data_type != DATA_TYPE_FLOAT_ARRAY) {
                *error = ERROR_INVALID_DATA_TYPE;
                break;
            }
            return 2;

        case COMPRESSION_FPX_XOR2D:
            if (data_type != DATA_TYPE_FLOAT_ARRAY && data_type != DATA_TYPE_DOUBLE_ARRAY) {
                *error = ERROR_INVALID_DATA_TYPE;
                break;
            }
            return om_get_bytes_per_element(data_type, error);
        case COMPRESSION_PFOR_DELTA2D:
            return om_get_bytes_per_element(data_type, error);

        default:
            *error = ERROR_INVALID_COMPRESSION_TYPE;
    }
    return om_get_bytes_per_element(data_type, error);
}

void om_common_copy_float_to_int16(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        float val = ((float *)src)[i];
        if (isnan(val)) {
            ((int16_t *)dst)[i] = INT16_MAX;
        } else {
            float scaled = (val + add_offset) * scale_factor;
            float clamped = fmaxf(INT16_MIN, fminf(INT16_MAX, roundf(scaled)));
            ((int16_t *)dst)[i] = (int16_t)clamped;
        }
    }
}

void om_common_copy_float_to_int32(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        float val = ((float *)src)[i];
        if (isnan(val)) {
            ((int32_t *)dst)[i] = INT32_MAX;
        } else {
            float scaled = (val + add_offset) * scale_factor;
            float clamped = fmaxf((float)INT32_MIN, fminf((float)INT32_MAX, roundf(scaled)));
            ((int32_t *)dst)[i] = (int32_t)clamped;
        }
    }
}

void om_common_copy_double_to_int64(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        double val = ((double *)src)[i];
        if (isnan(val)) {
            ((int64_t *)dst)[i] = INT64_MAX;
        } else {
            double scaled = (val + (double)add_offset) * (double)scale_factor;
            double clamped = fmax((double)INT64_MIN, fmin((double)INT64_MAX, round(scaled)));
            ((int64_t *)dst)[i] = (int64_t)clamped;
        }
    }
}

void om_common_copy_float_to_int16_log10(uint64_t length, float scale_factor, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        float val = ((float *)src)[i];
        if (isnan(val)) {
            ((int16_t *)dst)[i] = INT16_MAX;
        } else {
            float scaled = log10f(1 + val) * scale_factor;
            float clamped = fmaxf(INT16_MIN, fminf(INT16_MAX, roundf(scaled)));
            ((int16_t *)dst)[i] = (int16_t)clamped;
        }
    }
}

void om_common_copy_int16_to_float(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        int16_t val = ((int16_t *)src)[i];
        ((float *)dst)[i] = (val == INT16_MAX) ? NAN : (float)val / scale_factor - add_offset;
    }
}

void om_common_copy_int32_to_float(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        int32_t val = ((int32_t *)src)[i];
        ((float *)dst)[i] = (val == INT32_MAX) ? NAN : (float)val / scale_factor - add_offset;
    }
}

void om_common_copy_int64_to_double(uint64_t length, float scale_factor, float add_offset, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        int64_t val = ((int64_t *)src)[i];
        ((double *)dst)[i] = (val == INT64_MAX) ? NAN : (double)val / (double)scale_factor - (double)add_offset;
    }
}

void om_common_copy_int16_to_float_log10(uint64_t length, float scale_factor, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        int16_t val = ((int16_t *)src)[i];
        ((float *)dst)[i] = (val == INT16_MAX) ? NAN : powf(10, (float)val / scale_factor) - 1;
    }
}

void om_common_copy8(uint64_t length, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        ((int8_t *)dst)[i] = ((int8_t *)src)[i];
    }
}

void om_common_copy16(uint64_t length, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        ((int16_t *)dst)[i] = ((int16_t *)src)[i];
    }
}

void om_common_copy32(uint64_t length, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        ((int32_t *)dst)[i] = ((int32_t *)src)[i];
    }
}

void om_common_copy64(uint64_t length, const void* src, void* dst) {
    for (uint64_t i = 0; i < length; ++i) {
        ((int64_t *)dst)[i] = ((int64_t *)src)[i];
    }
}

uint64_t om_common_compress_fpxenc32(const void* src, uint64_t length, void* dst) {
    return fpxenc32((uint32_t*)src, length, (unsigned char *)dst, 0);
}

uint64_t om_common_compress_fpxenc64(const void* src, uint64_t length, void* dst) {
    return fpxenc64((uint64_t*)src, length, (unsigned char *)dst, 0);
}

uint64_t om_common_decompress_fpxdec32(const void* src, uint64_t length, void* dst) {
    return fpxdec32((unsigned char *)src, length, (uint32_t *)dst, 0);
}

uint64_t om_common_decompress_fpxdec64(const void* src, uint64_t length, void* dst) {
    return fpxdec64((unsigned char *)src, length, (uint64_t *)dst, 0);
}
