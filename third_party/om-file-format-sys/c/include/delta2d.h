#ifndef delta2d_h
#define delta2d_h

#include <stdio.h>
#include <stdint.h>

void delta2d_decode8(const size_t length0, const size_t length1, int8_t* chunkBuffer);
void delta2d_encode8(const size_t length0, const size_t length1, int8_t* chunkBuffer);

void delta2d_decode16(const size_t length0, const size_t length1, int16_t* chunkBuffer);
void delta2d_encode16(const size_t length0, const size_t length1, int16_t* chunkBuffer);

void delta2d_decode32(const size_t length0, const size_t length1, int32_t* chunkBuffer);
void delta2d_encode32(const size_t length0, const size_t length1, int32_t* chunkBuffer);

void delta2d_decode64(const size_t length0, const size_t length1, int64_t* chunkBuffer);
void delta2d_encode64(const size_t length0, const size_t length1, int64_t* chunkBuffer);

void delta2d_encode_xor(const size_t length0, const size_t length1, float* chunkBuffer);
void delta2d_decode_xor(const size_t length0, const size_t length1, float* chunkBuffer);

void delta2d_encode_xor_double(const size_t length0, const size_t length1, double* chunkBuffer);
void delta2d_decode_xor_double(const size_t length0, const size_t length1, double* chunkBuffer);


#endif /* deleta2d_h */
