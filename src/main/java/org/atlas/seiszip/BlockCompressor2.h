
#ifndef SEISPEG_BLOCK_COMPRESSOR2_H
#define SEISPEG_BLOCK_COMPRESSOR2_H

#include "HuffCoder2.h"

class BlockCompressor2 {

private:

  int _cookie;
  HuffCoder2* _huffCoder;
  float _manualDelta;
  int _idataLength;
  int* _idata;
  int _huffcharsLength;
  char* _huffchars;
  float* _workTrace;
  int samplesPerTrace;

public:

  BlockCompressor2();
  ~BlockCompressor2();

  float computeDelta(float* x, int n, float distortion);
  void setManualDelta(float delta);
  void unsetManualDelta();
  int dataEncode(float* data, int nsamps, float distortion, char* encodedData, int index, int outputBufferSize);
  // int dataDecode(char* encodedData, int index, char* workBuffer, int workBufferSize, int nsamps, float* data);
  int dataDecode(const char* encodedData, int index, char* workBuffer, int workBufferSize, int nsamps, float* data);

};

#endif  // SEISPEG_BLOCK_COMPRESSOR2
