
#ifndef SEISPEG_HUFF_CODER_H
#define SEISPEG_HUFF_CODER_H

#include "HuffNode.h"

class HuffCoder2 {

private:

  void huffCheckTree(const char* message);

  int _cookie;
  HuffNode* _globalRoot;
  int* _huffCode;
  int* _huffLen;
  char* _bVals;

  static const int c_huffTable[];
  static const int c_hdrHuffCount_NOT_USED[];
  static const int c_hdrHuffCountImproved_NOT_USED[];

public:

  HuffCoder2();
  HuffCoder2(const int* huffTable);
  ~HuffCoder2();
  int huffEncode(char* runLengthEncodedData, int ninputBytes, char* huffEncodedData, int index, int outputBufferSize);
  int huffEncode(int* runLengthEncodedData, int ninputInts, char* huffEncodedData, int index, int outputBufferSize);
  // int huffDecode(char* huffEncodedData, int index, char* cHout, int outputBufferSize);
  int huffDecode(const char* huffEncodedData, int index, char* cHout, int outputBufferSize);

};

#endif  // SEISPEG_HUFF_CODER_H
