
#ifndef DASPEG_TRANSFORMER_H
#define DASPEG_TRANSFORMER_H

#define POLICY_FASTEST 0
#define POLICY_MAX_COMPRESSION 1

#define BLOCK_SIZE_DIRECTION_VERTICAL 1
#define BLOCK_SIZE_DIRECTION_HORIZONTAL 2

// This class is not thread safe, i.e. multiple threads cannot use the same Transformer object concurrently.  This is
// because of the use of internal scratch arrays during computation.

#include <ctime>

class Transformer2 {

public:

  Transformer2(int useSSE);

  ~Transformer2();

  static int computeBlockSize(int nsamples, int policy, int blockSizeDirection);
  static int computeTransformLength(int blockSize, int policy);
  static int computePaddedLength(int nsamples, int blockSize);
  static void checkBlockSize(int blockSize);
  static void checkTransLength(int transLength, int blockSize);
  // static const long long hashCode = 1761933359000LL;  // 10/31/2025.
  // static const long long hashCode = 1764529199000LL;  // 11/30/2025.
  static const long long hashCode = 9223372036854775807LL;  // Infinity.
  void lotRev(float* x, int index, int blockSize, int transLength, int nblocks);
  void lotFwd(float* x, int index, int blockSize, int transLength, int nblocks);
  static long long ctms;
  void checkForMemoryCorruption();

private:

  float* ensureScratch(int blockSize, int nblocks);
  void lotFwd16(float* x, int index, int nblocks, float* scratch);

  int _cookieMemoryMarker;
  int _useSSE;
  float** _filt16Evens;
  float** _filt16Odds;
  float* _dataEven;
  float* _dataOdd;
  float* _workSum;
  float* _scratchReverse;
  float* _scratch;  // Allocated just-in-time.
  int _scratchSize;

};

#endif  // DASPEG_TRANSFORMER_H
