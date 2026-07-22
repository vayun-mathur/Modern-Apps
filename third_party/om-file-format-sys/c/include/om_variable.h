//
//  om_variable.h
//  OpenMeteoApi
//
//  Created by Patrick Zippenfenig on 16.11.2024.
//

#ifndef OM_VARIABLE_H
#define OM_VARIABLE_H

#include "om_common.h"
#include "om_file.h"

/**
 TODO:
 - String array support
 */

/// =========== Structures describing the data layout ===============

typedef struct {
    uint8_t data_type; // OmDataType_t
    uint8_t compression_type; // OmCompression_t
    uint16_t name_size; // maximum 65k characters in name strings
    uint32_t children_count;

    // Followed by payload
    //uint32_t[children_count] children_length;
    //uint32_t[children_count] children_offset;

    // Scalars are now set
    //void* value;

    // name is always last
    //char[name_size] name;
} OmVariableV3_t;

typedef struct {
    uint8_t data_type; // OmDataType_t
    uint8_t compression_type; // OmCompression_t
    uint16_t name_size; // maximum 65k characters in name strings
    uint32_t children_count;
    uint64_t lut_size;
    uint64_t lut_offset;
    uint64_t dimension_count;

    float scale_factor;
    float add_offset;

    // Followed by payload: NOTE: Lets to try 64 bit align it somehow
    //uint32_t[children_count] children_length;
    //uint32_t[children_count] children_offset;

    // Afterwards additional payload from value types
    //uint64_t[dimension_count] dimensions;
    //uint64_t[dimension_count] chunks;

    // name is always last
    //char[name_size] name;
} OmVariableArrayV3_t;

/// only expose an opaque pointer
typedef void* OmVariable_t;



/// =========== Functions for reading ===============


/// After reading data for the variable, initialize it. This is literally a simple cast to an opaque pointer. Source memory must remain accessible!
const OmVariable_t* om_variable_init(const void* src);

/// Get the name of of a given variable. No guarantee for zero termination! The length in bytes of the string is placed into `length`.
const char* om_variable_get_name(const OmVariable_t* variable, uint16_t* length);

/// Get the type of the current variable
OmDataType_t om_variable_get_type(const OmVariable_t* variable);

/// Get the compression type of the current variable
OmCompression_t om_variable_get_compression(const OmVariable_t* variable);

float om_variable_get_scale_factor(const OmVariable_t* variable);

float om_variable_get_add_offset(const OmVariable_t* variable);

/// Get number of dimensions
uint64_t om_variable_get_dimensions_count(const OmVariable_t* variable);

/// Get a pointer to the dimensions of a OM variable
const uint64_t* om_variable_get_dimensions(const OmVariable_t* variable);

/// Get a pointer to the chunk dimensions of an OM Variable
const uint64_t* om_variable_get_chunks(const OmVariable_t* variable);

/// Return how many children are available for a given variable
uint32_t om_variable_get_children_count(const OmVariable_t* variable);

/// Get the file offset where a specified child or children can be read
bool om_variable_get_children(const OmVariable_t* variable, uint32_t children_offset, uint32_t children_count, uint64_t* children_offsets, uint64_t* children_sizes);

/// Read a variable as a scalar. Returns the size and value into the value and size field. `value` needs to be a pointer that then points to the value
OmError_t om_variable_get_scalar(const OmVariable_t* variable, void** value, uint64_t* size);

/// Validate that the variable metadata is consistent with the buffer size.
/// This checks that dimension arrays, chunk arrays, children arrays, and name
/// all fit within the given buffer. Returns ERROR_OK if valid.
OmError_t om_variable_validate(const void* src, uint64_t buffer_size);




/// =========== Functions for writing ===============

/// Get the length of a scalar variable if written to a file.
/// If the scalar is a string, we need to know the length of the string.
size_t om_variable_write_scalar_size(uint16_t name_size, uint32_t children_count, OmDataType_t data_type, uint64_t string_size);

/// Write a scalar variable with name and children variables to a destination buffer
///
/// This function supports the following data types:
/// - DATA_TYPE_NONE: No value storage (passing a NULL pointer to value is allowed)
/// - DATA_TYPE_INT8/UINT8: 8-bit integer value
/// - DATA_TYPE_INT16/UINT16: 16-bit integer value
/// - DATA_TYPE_INT32/UINT32: 32-bit integer value
/// - DATA_TYPE_INT64/UINT64: 64-bit integer value
/// - DATA_TYPE_FLOAT: 32-bit floating point value
/// - DATA_TYPE_DOUBLE: 64-bit floating point value
///
/// @param dst Destination buffer to write the variable to
/// @param name_size Length of the variable name in bytes
/// @param children_count Number of child variables
/// @param children_offsets Array of offsets to child variables (can be NULL if children_count is 0)
/// @param children_sizes Array of sizes of child variables (can be NULL if children_count is 0)
/// @param name Pointer to the variable name string
/// @param data_type Type of the data to be stored (see OmDataType_t)
/// @param value Pointer to the value to be stored. For DATA_TYPE_NONE, this should be NULL.
///             For other types, this should point to a value of the corresponding C type.
///
/// @note The destination buffer must be large enough to hold the variable.
///       Use om_variable_write_scalar_size() to calculate the required size.
void om_variable_write_scalar(
    void* dst,
    uint16_t name_size,
    uint32_t children_count,
    const uint64_t* children_offsets,
    const uint64_t* children_sizes,
    const char* name,
    OmDataType_t data_type,
    const void* value,
    size_t string_size
);

/// Get the size of meta attributes of a numeric array if written to a file. Does not contain any data. Only offsets for the actual data.
size_t om_variable_write_numeric_array_size(uint16_t name_size, uint32_t children_count, uint64_t dimension_count);

/// Write meta data for a numeric array to file
void om_variable_write_numeric_array(void* dst, uint16_t name_size, uint32_t children_count, const uint64_t* children_offsets, const uint64_t* children_sizes, const char* name, OmDataType_t data_type, OmCompression_t compression_type, float scale_factor, float add_offset, uint64_t dimension_count, const uint64_t *dimensions, const uint64_t *chunks, uint64_t lut_size, uint64_t lut_offset);



/// =========== Internal functions ===============

/// Memory layout types
typedef enum {
    OM_MEMORY_LAYOUT_LEGACY = 0,
    OM_MEMORY_LAYOUT_ARRAY = 1,
    OM_MEMORY_LAYOUT_SCALAR = 3,
    //OM_MEMORY_LAYOUT_STRING_ARRAY = 5,
} OmMemoryLayout_t;

/// Check if a variable is legacy or version 3 array of scalar. Legacy files are the entire header containing magic number and version.
OmMemoryLayout_t _om_variable_memory_layout(const OmVariable_t* variable);




#endif // OM_VARIABLE_H
