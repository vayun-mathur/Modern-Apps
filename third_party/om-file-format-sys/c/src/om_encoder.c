//
//  om_encoder.c
//  OpenMeteoApi
//
//  Created by Patrick Zippenfenig on 29.10.2024.
//

#include "om_encoder.h"
#include <assert.h>
#include "vp4.h"
#include "fp.h"
#include "delta2d.h"
#include "conf.h"

#pragma clang diagnostic error "-Wswitch"

OmError_t om_encoder_init(
    OmEncoder_t* encoder,
    float scale_factor,
    float add_offset,
    OmCompression_t compression,
    OmDataType_t data_type,
    const uint64_t* dimensions,
    const uint64_t* chunks,
    uint64_t dimension_count
) {
    for (uint64_t i = 0; i < dimension_count; i++) {
        if (dimensions[i] == 0) {
            return ERROR_INVALID_DIMENSIONS;
        }
        if (chunks[i] == 0 || chunks[i] > dimensions[i]) {
            return ERROR_INVALID_CHUNK_DIMENSIONS;
        }
    }

    encoder->scale_factor = scale_factor;
    encoder->add_offset = add_offset;
    encoder->dimensions = dimensions;
    encoder->chunks = chunks;
    encoder->dimension_count = dimension_count;
    encoder->data_type = data_type;
    encoder->compression = compression;

    OmError_t error = ERROR_OK;
    encoder->bytes_per_element = om_get_bytes_per_element(data_type, &error);
    encoder->bytes_per_element_compressed = om_get_bytes_per_element_compressed(data_type, compression, &error);

    return error;
}

ALWAYS_INLINE uint64_t om_encode_compress(
    OmDataType_t data_type,
    OmCompression_t compression_type,
    const void* input,
    uint64_t count,
    void* output
) {
    uint64_t result = 0;

    switch (compression_type) {
        case COMPRESSION_PFOR_DELTA2D_INT16:
        case COMPRESSION_PFOR_DELTA2D_INT16_LOGARITHMIC:
            // The initializer should have ensured that the data type is float
            assert(data_type == DATA_TYPE_FLOAT_ARRAY && "Expecting float array");
            result = p4nzenc128v16((uint16_t*)input, (size_t)count, (unsigned char*)output);
            break;

        case COMPRESSION_FPX_XOR2D:
            assert(data_type == DATA_TYPE_FLOAT_ARRAY || data_type == DATA_TYPE_DOUBLE_ARRAY && "Expecting float or double array");
            if (data_type == DATA_TYPE_FLOAT_ARRAY) {
                result = om_common_compress_fpxenc32((float*)input, (size_t)count, (unsigned char*)output);
            } else if (data_type == DATA_TYPE_DOUBLE_ARRAY) {
                result = om_common_compress_fpxenc64((double*)input, (size_t)count, (unsigned char*)output);
            }
            break;

        case COMPRESSION_PFOR_DELTA2D:
            switch (data_type) {
                case DATA_TYPE_INT8_ARRAY:
                    result = p4nzenc8((uint8_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_UINT8_ARRAY:
                    result = p4ndenc8((uint8_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_INT16_ARRAY:
                    result = p4nzenc128v16((uint16_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_UINT16_ARRAY:
                    result = p4ndenc128v16((uint16_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_INT32_ARRAY:
                    result = p4nzenc128v32((uint32_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_UINT32_ARRAY:
                    result = p4ndenc128v32((uint32_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_INT64_ARRAY:
                    result = p4nzenc64((uint64_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_UINT64_ARRAY:
                    result = p4ndenc64((uint64_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_FLOAT_ARRAY:
                    result = p4nzenc128v32((uint32_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_DOUBLE_ARRAY:
                    result = p4nzenc64((uint64_t*)input, (size_t)count, (unsigned char*)output);
                    break;
                case DATA_TYPE_NONE:
                case DATA_TYPE_STRING:
                case DATA_TYPE_STRING_ARRAY:
                case DATA_TYPE_INT8:
                case DATA_TYPE_UINT8:
                case DATA_TYPE_INT16:
                case DATA_TYPE_UINT16:
                case DATA_TYPE_INT32:
                case DATA_TYPE_UINT32:
                case DATA_TYPE_INT64:
                case DATA_TYPE_UINT64:
                case DATA_TYPE_FLOAT:
                case DATA_TYPE_DOUBLE:
                    break;
            }
            break;

        case COMPRESSION_NONE:
            break;
    }

    return result;
}

ALWAYS_INLINE void om_encode_filter(
    OmDataType_t data_type,
    OmCompression_t compression_type,
    void* data,
    uint64_t length_in_chunk,
    uint64_t length_last
) {
    switch (compression_type) {
        case COMPRESSION_PFOR_DELTA2D_INT16:
        case COMPRESSION_PFOR_DELTA2D_INT16_LOGARITHMIC:
            // The initializer should have ensured that the data type is float
            assert(data_type == DATA_TYPE_FLOAT_ARRAY && "Expecting float array");
            delta2d_encode16((size_t)(length_in_chunk / length_last), (size_t)length_last, (int16_t*)data);
            break;

        case COMPRESSION_FPX_XOR2D:
            assert(data_type == DATA_TYPE_FLOAT_ARRAY || data_type == DATA_TYPE_DOUBLE_ARRAY && "Expecting float or double array");
            if (data_type == DATA_TYPE_FLOAT_ARRAY) {
                delta2d_encode_xor((size_t)(length_in_chunk / length_last), (size_t)length_last, (float*)data);
            } else if (data_type == DATA_TYPE_DOUBLE_ARRAY) {
                delta2d_encode_xor_double((size_t)(length_in_chunk / length_last), (size_t)length_last, (double*)data);
            }
            break;

        case COMPRESSION_PFOR_DELTA2D:
            switch (data_type) {
                case DATA_TYPE_INT8_ARRAY:
                case DATA_TYPE_UINT8_ARRAY:
                    delta2d_encode8((size_t)(length_in_chunk / length_last), (size_t)length_last, (int8_t*)data);
                    break;
                case DATA_TYPE_INT16_ARRAY:
                case DATA_TYPE_UINT16_ARRAY:
                    delta2d_encode16((size_t)(length_in_chunk / length_last), (size_t)length_last, (int16_t*)data);
                    break;
                case DATA_TYPE_INT32_ARRAY:
                case DATA_TYPE_UINT32_ARRAY:
                case DATA_TYPE_FLOAT_ARRAY:
                    delta2d_encode32((size_t)(length_in_chunk / length_last), (size_t)length_last, (int32_t*)data);
                    break;
                case DATA_TYPE_INT64_ARRAY:
                case DATA_TYPE_UINT64_ARRAY:
                case DATA_TYPE_DOUBLE_ARRAY:
                    delta2d_encode64((size_t)(length_in_chunk / length_last), (size_t)length_last, (int64_t*)data);
                    break;
                default:
                    break;
            }
            break;
        case COMPRESSION_NONE:
            break;
    }
}

ALWAYS_INLINE void om_encode_copy(
    OmDataType_t data_type,
    OmCompression_t compression_type,
    uint64_t count,
    float scale_factor,
    float add_offset,
    const void* input,
    void* output
) {
    switch (compression_type) {
        case COMPRESSION_PFOR_DELTA2D_INT16:
            assert(data_type == DATA_TYPE_FLOAT_ARRAY && "Expecting float array");
            om_common_copy_float_to_int16(count, scale_factor, add_offset, input, output);
            break;

        case COMPRESSION_PFOR_DELTA2D_INT16_LOGARITHMIC:
            assert(data_type == DATA_TYPE_FLOAT_ARRAY && "Expecting float array");
            om_common_copy_float_to_int16_log10(count, scale_factor, input, output);
            break;

        case COMPRESSION_FPX_XOR2D:
            assert(data_type == DATA_TYPE_FLOAT_ARRAY || data_type == DATA_TYPE_DOUBLE_ARRAY && "Expecting float or double array");
            if (data_type == DATA_TYPE_FLOAT_ARRAY) {
                om_common_copy32(count, input, output);
            } else if (data_type == DATA_TYPE_DOUBLE_ARRAY) {
                om_common_copy64(count, input, output);
            }
            break;

        case COMPRESSION_PFOR_DELTA2D:
            switch (data_type) {
                case DATA_TYPE_INT8_ARRAY:
                case DATA_TYPE_UINT8_ARRAY:
                    om_common_copy8(count, input, output);
                    break;
                case DATA_TYPE_INT16_ARRAY:
                case DATA_TYPE_UINT16_ARRAY:
                    om_common_copy16(count, input, output);
                    break;
                case DATA_TYPE_INT32_ARRAY:
                case DATA_TYPE_UINT32_ARRAY:
                    om_common_copy32(count, input, output);
                    break;
                case DATA_TYPE_FLOAT_ARRAY:
                    om_common_copy_float_to_int32(count, scale_factor, add_offset, input, output);
                    break;
                case DATA_TYPE_INT64_ARRAY:
                case DATA_TYPE_UINT64_ARRAY:
                    om_common_copy64(count, input, output);
                    break;
                case DATA_TYPE_DOUBLE_ARRAY:
                    om_common_copy_double_to_int64(count, scale_factor, add_offset, input, output);
                    break;
                default:
                break;
            }
            break;

        case COMPRESSION_NONE:
            break;
    }
}

uint64_t om_encoder_count_chunks(const OmEncoder_t* encoder) {
    uint64_t n = 1;
    for (uint64_t i = 0; i < encoder->dimension_count; i++) {
        n *= divide_rounded_up(encoder->dimensions[i], encoder->chunks[i]);
    }
    return n;
}

uint64_t om_encoder_count_chunks_in_array(const OmEncoder_t* encoder, const uint64_t* array_count) {
    uint64_t numberOfChunksInArray = 1;
    for (uint64_t i = 0; i < encoder->dimension_count; i++) {
        numberOfChunksInArray *= divide_rounded_up(array_count[i], encoder->chunks[i]);
    }
    return numberOfChunksInArray;
}

uint64_t om_encoder_chunk_buffer_size(const OmEncoder_t* encoder) {
    uint64_t chunkLength = 1;
    for (uint64_t i = 0; i < encoder->dimension_count; i++) {
        chunkLength *= encoder->chunks[i];
    }
    return chunkLength * encoder->bytes_per_element_compressed;
}

uint64_t om_encoder_compressed_chunk_buffer_size(const OmEncoder_t* encoder) {
    uint64_t chunkLength = 1;
    for (uint64_t i = 0; i < encoder->dimension_count; i++) {
        chunkLength *= encoder->chunks[i];
    }
    // P4NENC256_BOUND. Compressor may write 32 integers more
    return (chunkLength + 255) /256 + (chunkLength + 32) * encoder->bytes_per_element_compressed;
}

uint64_t om_encoder_lut_buffer_size(const uint64_t* lookUpTable, uint64_t lookUpTableCount) {
    uint64_t buffer[LUT_CHUNK_COUNT+32] = {0};
    const uint64_t nLutChunks = divide_rounded_up(lookUpTableCount, LUT_CHUNK_COUNT);
    uint64_t maxLength = 0;
    for (uint64_t i = 0; i < nLutChunks; i++) {
        const uint64_t rangeStart = i * LUT_CHUNK_COUNT;
        const uint64_t rangeEnd = om_min(rangeStart + LUT_CHUNK_COUNT, lookUpTableCount);
        const uint64_t len = p4ndenc64((uint64_t*)&lookUpTable[rangeStart], rangeEnd - rangeStart, (unsigned char *)buffer);
        if (len > maxLength) maxLength = len;
    }
    // Compression function can write 32 integers more
    return maxLength * nLutChunks + 32 * sizeof(uint64_t);
}

uint64_t om_encoder_compress_lut(const uint64_t* lookUpTable, uint64_t lookUpTableCount, uint8_t* out, uint64_t compressed_lut_buffer_size) {
    const uint64_t nLutChunks = divide_rounded_up(lookUpTableCount, LUT_CHUNK_COUNT);
    const uint64_t lutSize = compressed_lut_buffer_size - 32 * sizeof(uint64_t);
    const uint64_t lutChunkLength = lutSize / nLutChunks;

    for (uint64_t i = 0; i < nLutChunks; i++) {
        const uint64_t rangeStart = i * LUT_CHUNK_COUNT;
        const uint64_t rangeEnd = om_min(rangeStart + LUT_CHUNK_COUNT, lookUpTableCount);
        const uint64_t len = p4ndenc64((uint64_t*)&lookUpTable[rangeStart], rangeEnd - rangeStart, &out[i * lutChunkLength]);
        for (uint64_t j = i * lutChunkLength + len; j < (i+1) * lutChunkLength; j++) {
            out[j] = 0; // fill remaining space with 0
        }
    }
    return lutSize;
}

uint64_t om_encoder_compress_chunk(
    const OmEncoder_t* encoder,
    const void* array,
    const uint64_t* arrayDimensions,
    const uint64_t* arrayOffset,
    const uint64_t* arrayCount,
    uint64_t chunkIndex,
    uint64_t chunkIndexOffsetInThisArray,
    uint8_t* out,
    uint8_t* chunkBuffer
) {

    const uint64_t dimension_count = encoder->dimension_count;
    // The total size of `arrayDimensions`. Only used to check for out of bound reads
    uint64_t arrayTotalCount = 1;
    for (uint64_t i = 0; i < dimension_count; i++) {
        arrayTotalCount *= arrayDimensions[i];
    }

    uint64_t rollingMultiply = 1;
    uint64_t rollingMultiplyChunkLength = 1;
    uint64_t rollingMultiplyTargetCube = 1;
    uint64_t readCoordinate = 0;
    uint64_t writeCoordinate = 0;
    uint64_t linearReadCount = 1;
    bool linearRead = true;
    uint64_t lengthLast = 0;

    for (uint64_t i_forward = 0; i_forward < dimension_count; i_forward++) {
        const uint64_t i = dimension_count - i_forward - 1;
        const uint64_t dimension = encoder->dimensions[i];
        const uint64_t chunk = encoder->chunks[i];

        const uint64_t nChunksInThisDimension = divide_rounded_up(dimension, chunk);
        const uint64_t c0 = (chunkIndex / rollingMultiply) % nChunksInThisDimension;
        const uint64_t c0Offset = (chunkIndexOffsetInThisArray / rollingMultiply) % nChunksInThisDimension;
        const uint64_t length0 = om_min((c0 + 1) * chunk, dimension) - c0 * chunk;

        if (i == dimension_count - 1) {
            lengthLast = length0;
        }

        readCoordinate += rollingMultiplyTargetCube * (c0Offset * encoder->chunks[i] + arrayOffset[i]);
        assert(length0 <= arrayCount[i]);
        assert(length0 <= arrayDimensions[i]);

        if (i == dimension_count - 1 && !(arrayCount[i] == length0 && arrayDimensions[i] == length0)) {
            linearReadCount = length0;
            linearRead = false;
        }
        if (linearRead && arrayCount[i] == length0 && arrayDimensions[i] == length0) {
            linearReadCount *= length0;
        } else {
            linearRead = false;
        }

        rollingMultiply *= nChunksInThisDimension;
        rollingMultiplyTargetCube *= arrayDimensions[i];
        rollingMultiplyChunkLength *= length0;
    }

    const uint64_t lengthInChunk = rollingMultiplyChunkLength;

    while (true) {
        assert(readCoordinate + linearReadCount <= arrayTotalCount);
        assert(writeCoordinate + linearReadCount <= lengthInChunk);

        om_encode_copy(
            encoder->data_type,
            encoder->compression,
            linearReadCount,
            encoder->scale_factor,
            encoder->add_offset,
            (const uint8_t*)array + encoder->bytes_per_element * readCoordinate,
            chunkBuffer + encoder->bytes_per_element_compressed * writeCoordinate
        );

        readCoordinate += linearReadCount - 1;
        writeCoordinate += linearReadCount - 1;
        writeCoordinate += 1;

        rollingMultiplyTargetCube = 1;
        linearRead = true;
        linearReadCount = 1;

        for (uint64_t i_forward = 0; i_forward < dimension_count; i_forward++) {
            uint64_t i = dimension_count - i_forward - 1;
            const uint64_t chunk = encoder->chunks[i];

            const uint64_t qPos = ((readCoordinate / rollingMultiplyTargetCube) % arrayDimensions[i] - arrayOffset[i]) / chunk;
            const uint64_t length0 = om_min((qPos + 1) * chunk, arrayCount[i]) - qPos * chunk;
            readCoordinate += rollingMultiplyTargetCube;

            if (i == dimension_count - 1 && !(arrayCount[i] == length0 && arrayDimensions[i] == length0)) {
                linearReadCount = length0;
                linearRead = false;
            }
            if (linearRead && arrayCount[i] == length0 && arrayDimensions[i] == length0) {
                linearReadCount *= length0;
            } else {
                linearRead = false;
            }
            const uint64_t q0 = ((readCoordinate / rollingMultiplyTargetCube) % arrayDimensions[i] - arrayOffset[i]) % encoder->chunks[i];
            if (q0 != 0 && q0 != length0) {
                break;
            }
            readCoordinate -= length0 * rollingMultiplyTargetCube;
            rollingMultiplyTargetCube *= arrayDimensions[i];

            if (i == 0) {
                om_encode_filter(encoder->data_type, encoder->compression, chunkBuffer, lengthInChunk, lengthLast);
                uint64_t compressed_length = om_encode_compress(encoder->data_type, encoder->compression, chunkBuffer, lengthInChunk, out);
                return compressed_length;
            }
        }
    }
}
