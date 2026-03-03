
#ifndef SEISPEG_COMPRESSION_UTIL2_H
#define SEISPEG_COMPRESSION_UTIL2_H

#include <stddef.h>
#include <vector>

#define SIZEOF_CHAR 1
#define SIZEOF_SHORT 2
#define SIZEOF_INT 4
#define SIZEOF_FLOAT 4

#define SHORT_MAX_VALUE 32767
#define BYTE_MAX_VALUE 127
#define FLOAT_MAX_VALUE 0x1.fffffeP+127f

#define VERBOSE_OBJECT_LIFETIME 0

static inline void arraycopy(float* src, int indexSrc, float* dst, int indexDst, int n) {
  
  for (int i=0; i<n; i++)
    dst[indexDst+i] = src[indexSrc+i];
}


static inline void arraycopy(short* src, int indexSrc, float* dst, int indexDst, int n) {
  
  for (int i=0; i<n; i++)
    dst[indexDst+i] = (float)src[indexSrc+i];
}


static inline int floatToIntBits(float fValue) {
  
  int* iValue = (int*)&fValue;
  return *iValue;
}


static inline float intBitsToFloat(int iValue) {
  
  float* fValue = (float*)&iValue;
  return *fValue;
}


static inline void stuffIntInBytes(int ival, char bvals[], size_t offset) {
  
  bvals[offset]   = (ival >> 24);
  bvals[1+offset] = ((ival << 8) >> 24);
  bvals[2+offset] = ((ival << 16) >> 24);
  bvals[3+offset] = ((ival << 24) >> 24);
}


static inline void stuffFloatInBytes(float fval, char bvals[], size_t offset) {
  
  stuffIntInBytes(floatToIntBits(fval), bvals, offset);
}


static inline int unsignedByte(char i) {
  
  if (i < 0) {
    return (int)i + 256;
  } else {
    return (int)i;
  }
}


static inline int stuffBytesInInt(const char bvals[], size_t index) {
  
  int i0 = ((unsignedByte(bvals[index]))) << 24;
  int i1 = ((unsignedByte(bvals[1+index]))) << 16;
  int i2 = ((unsignedByte(bvals[2+index]))) << 8;
  int i3 = unsignedByte(bvals[3+index]);	
  return i0 + i1 + i2 + i3;
}


static inline int stuffBytesInInt(const std::vector<char>& bvalArray, size_t index) {

  return stuffBytesInInt(bvalArray.data(), index);
}


static inline float stuffBytesInFloat(const char bvals[], size_t index) {
  
  int ival = stuffBytesInInt(bvals, index);
  return intBitsToFloat(ival);
}


static inline signed short byteToShort(const char bytes[]){
  
  signed short result = 0;
  result = bytes[1]; // high byte
  result = (result<<8) + bytes[0]; // low byte
  return result;
}	


static inline void shortToByte(signed short num, char bytes[], size_t index){
  
  bytes[index] = (num & 0xFF);
  bytes[index+1] = ((num>>8) & 0xFF);
}


static inline void stuffShortInBytes(short uval, char bvals[], size_t index) {
  
  int ival = (int)uval;
  bvals[index] = ((ival << 16) >> 24);
  bvals[1+index] = ((ival << 24) >> 24);	
}


static inline int stuffBytesInShort(const char bvals[], int index) {
  
  int i0 = (short)(((int)(bvals[index])) << 8);
  int i1 = (short)unsignedByte(bvals[1+index]);
  return (i0 + i1);
}


static inline int stuffBytesInShort(const std::vector<char>& bvalArray, int index) {

  return stuffBytesInShort(bvalArray.data(), index);
}

#endif  // SEISPEG_COMPRESSION_UTIL2_H
