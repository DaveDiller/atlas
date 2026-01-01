
/*******************************************************************************
 * For documentation of the public C++ methods in this code, see the accompanying
 * Java file with the same class name.
 ********************************************************************************/

#include "BlockCompressor2.h"
#include "HuffCoder2.h"
#include "CompressionUtil2.h"

#include <assert.h>
#include <math.h>
#include <stdio.h>

#define c_checkNonZeroCount 1

#define CPDF 0.26f  // Quantization factor.

#define BLOCK_COMPRESSOR_COOKIE 134435188

#define C_DEBUG1 0

/**
 * No-arg constructor.
 */
BlockCompressor2::BlockCompressor2() {

  if (VERBOSE_OBJECT_LIFETIME)
    printf("this = %p was created ...\n", (void*)this);

  _cookie = BLOCK_COMPRESSOR_COOKIE;
  _huffCoder = new HuffCoder2();
  _idata = new int[1024]{0};  // Just an initial guess on the size.
  _idataLength = 1024;
  _huffchars = new char[1024 * 4]{0};  // Just an initial guess on the size.
  _huffcharsLength = 1024 * 4;
  _manualDelta = FLOAT_MAX_VALUE;
}


BlockCompressor2::~BlockCompressor2() {

  if (VERBOSE_OBJECT_LIFETIME)
    printf("this = %p was destroyed ...\n", (void*)this);

  assert(_cookie == BLOCK_COMPRESSOR_COOKIE);

  if (C_DEBUG1) {
    // Skip the code that is apparently causing a core dump.
  } else {
    delete[] _huffchars;
    delete[] _idata;
    delete _huffCoder;
  }
}


/**
 * Manually sets the delta value for quantization (ignoring the distortion value).
 * This is so that distortion does not vary from frame to frame.
 *
 * @param  delta  the value to use for delta in all cases.
 */
void BlockCompressor2::setManualDelta(float delta) {

  _manualDelta = delta;
}


/**
 * Unsets the manual delta value for quantization.
 */
void BlockCompressor2::unsetManualDelta() {

  _manualDelta = FLOAT_MAX_VALUE;
}


/**
 * Applies quantization.
 *
 * @param  x transformed samples.
 * @param  n number of samples.
 * @param  delta quantization delta.
 * @param  ix output quantized samples.
 */
static void quantize(float* x, int n, float delta, int* ix) {

  int i;
  float temp;
		
  delta = 1.0f / delta;

  for (i=0; i<n; i++) {
    temp = x[i] * delta;
    // Math.round() is dreadfully slow.
    // ix[i] = (int)Math.round(temp);
    // Is is faster to avoid the function call?  Seems like it!
    // ix[i] = (int)nint(temp);
    if (temp > 0.0) {
      ix[i] = (int)(temp + 0.5f);
    } else {
      ix[i] = (int)(temp - 0.5f);
    }
  }

}


/**
 * Applies run length encoding.  There is no provision for the output buffer being
 * too small.
 *
 * @param  quantdata  quantized data (or other integer data).  Must be terminated
 *                    with a non-zero value.
 * @param  n  number of samples.
 * @param  encodedChars  run-length encoded data.
 * @return  number of bytes required to store the encoded data.
 */
static int runLengthEncode(int* quantdata, int n, char* encodedChars) {

  int i, nbytes, zrun, nwords, istart;
  short si;

  nwords = n / SIZEOF_INT;
		
  for (i=nbytes=0; i<nwords;) {

    if (quantdata[i] == 0) {
      /* Begin a run of zeros. */
      istart = i;
      i++;
      /* There is a non-zero dummy value at the end of the buffer. */
      while (quantdata[i] == 0) i++;

      while (i > istart+65535) {
	/* Encode runs of 65535. */
	encodedChars[nbytes++] = 106;
	si = (short)65535;
	stuffShortInBytes(si, encodedChars, nbytes); 
	nbytes += 2;
	istart += 65535;
      }
      zrun = i - istart;
      if (zrun > 255) {
	encodedChars[nbytes] = 106;
	nbytes++;
	si = (short)zrun;
	stuffShortInBytes(si, encodedChars, nbytes);      
	nbytes += 2;
      } else if (zrun > 100) {
	encodedChars[nbytes] = 105;
	nbytes++;
	encodedChars[nbytes] = (char)zrun;
	nbytes++;
      } else {
	encodedChars[nbytes] = (char)zrun;
	nbytes++;
      }
    } else if (quantdata[i] < 75  &&  quantdata[i] > -74) {
      encodedChars[nbytes] = (char)(quantdata[i] + 180);
      nbytes++;
      i++;
    } else {
      /* The data is >= 75 or <= -75. */
      if (quantdata[i] > 0) {
	if (quantdata[i] < 256) {
	  encodedChars[nbytes] = 101;
	  nbytes++;
	  encodedChars[nbytes] = (char)quantdata[i];	
	  nbytes++;
	} else if (quantdata[i] < 65536) {
	  encodedChars[nbytes] = 103;
	  nbytes++;
	  si = (short)quantdata[i];
	  stuffShortInBytes(si, encodedChars, nbytes);	
	  nbytes += 2;
	} else {
	  /* It's a huge integer. */
	  encodedChars[nbytes] = (char)255;
	  nbytes++;
	  stuffIntInBytes(quantdata[i], encodedChars, nbytes); 
	  nbytes += 4;
	}
      } else {
	/* Less than 0. */
	if (quantdata[i] > -256) {
	  encodedChars[nbytes] = 102;
	  nbytes++;
	  encodedChars[nbytes] = (char)(-quantdata[i]);
	  nbytes++;
	} else if (quantdata[i] > -65536) {  
	  encodedChars[nbytes] = 104;
	  nbytes++;
	  si = (short)(-quantdata[i]);
	  stuffShortInBytes(si, encodedChars, nbytes);	
	  nbytes += 2;
	} else {
	  /* It's a huge negative integer. */
	  encodedChars[nbytes] = (char)255;
	  nbytes++;
	  stuffIntInBytes(quantdata[i], encodedChars, nbytes);
	  nbytes += 4;
	}
      }
      i++;
    }

  }
	  
  int nInts = nbytes / SIZEOF_INT;
  if ((nInts*SIZEOF_INT) < nbytes) {
    // Be sure that the extra characters contain zeros.
    encodedChars[nbytes] = 0;
    encodedChars[nbytes+1] = 0;
    encodedChars[nbytes+2] = 0;
    nInts++;
  }

  return nbytes;
}


/**
 * Run-length decoding and dequantization (both steps applied at once for
 * performance gain.  NOTE: the output must not overlay the input.
 *
 * @param  huffchars  Huffman decoded data (needs run-length decoding).
 * @param  nbytes  number of input bytes.
 * @param  delta  quantization delta.
 * @param  quantdata  output decoded and dequantized data.
 */
static void runLengthDecodeDequant(char* huffchars, int nbytes,
                                   float delta, float* quantdata) {

  int i, j, iend, ival;
  // short si;

  for (i=j=0; i<nbytes;) {

    // Function calls in tight loops are hurting performance.
    // int ihuffchar = unsignedByte(huffchars[i]);
    int ihuffchar = (int)huffchars[i];
    if (ihuffchar < 0) ihuffchar += 256;

    if (ihuffchar > 0  &&  ihuffchar < 101) {
      iend = j + ihuffchar;
      for (; j<iend; j++) quantdata[j] = 0.0f;
      i++;
    } else if (ihuffchar == 105) {
      i++;
      // ival = unsignedByte(huffchars[i]);
      ival = (int)huffchars[i];
      if (ival < 0) ival += 256;
      iend = j + ival;
      for (; j<iend; j++) quantdata[j] = 0.0f;
      i++;
    } else if (ihuffchar > 106  &&  ihuffchar < 255) {
      // quantdata[j++] = ((float)unsignedByte(huffchars[i++]) - 180.0f) * delta;
      quantdata[j++] = ((float)ihuffchar - 180.0f) * delta;
      i++;
    } else {
      if (huffchars[i] == 101) {
	i++;
	// ival = unsignedByte(huffchars[i]);
	ival = (int)huffchars[i];
	if (ival < 0) ival += 256;
	quantdata[j++] = (float)ival * delta;
	i++;
      } else if (huffchars[i] == 102) {
	i++;
	// ival = unsignedByte(huffchars[i]);
	ival = (int)huffchars[i];
	if (ival < 0) ival += 256;
	quantdata[j++] = (-(float)ival) * delta;
	i++;
      } else if (huffchars[i] == 103) {
	i++;
	// ival = unsignedShort(stuffBytesInShort(huffchars, i));
	ival = stuffBytesInShort(huffchars, i);
	if (ival < 0) ival += 65536;
	quantdata[j++] = (float)ival * delta;
	i += 2;
      } else if (huffchars[i] == 104) {
	i++;
	// ival = unsignedShort(stuffBytesInShort(huffchars, i));
	ival = stuffBytesInShort(huffchars, i);
	if (ival < 0) ival += 65536;
	quantdata[j++] = (-(float)ival) * delta;
	i += 2;
      } else if (huffchars[i] == 106) {
	i++;
	// ival = unsignedShort(stuffBytesInShort(huffchars, i));
	ival = stuffBytesInShort(huffchars, i);
	if (ival < 0) ival += 65536;
	iend = j + ival;
	for (; j<iend; j++) quantdata[j] = 0.0f;
	i += 2;
      } else if (ihuffchar == 255) {
	i++;
	ival = stuffBytesInInt(huffchars, i);
	quantdata[j++] = ((float)ival) * delta;
	i += 4;
      } else {
	// LOG.warning("HuffTableDecode: bad character encountered at element " + 
	// (int)huffchars[i]);
	// Just punt - don't know what else to do.  This may actually happen,
	// because data gets corrupted.
	// TODO: Perhaps we should zero the data.
	return;
      }
    }
  }

}


/**
 * Computes the quantization delta.
 *
 * @param  x  transformed samples.
 * @param  n  number of samples.
 * @param  distortion  desired distortion level.
 * @return  quantization delta.
 */
float BlockCompressor2::computeDelta(float* x, int n, float distortion) {
  
  double blockVar;
  float delta, quarterDelta, mquarterDelta;
  int i, n2;

  /* Get a low approximation of delta. */
  blockVar = 0.0;
  for (i=0; i<n; i++) blockVar += (x[i]*x[i]);

  blockVar /= n;
  blockVar = sqrt(blockVar);

  delta = distortion * (float)blockVar / CPDF;

  /* Compute delta without the near-zero samples. */
  quarterDelta = delta * 0.25f;
  mquarterDelta = -quarterDelta;
  blockVar = 0.0;
  n2 = 0;
  /* Every 8th sample should be OK for this application. */
  /* Why not use them all - should make very little difference. */
  /* for (i=0; i<n; i+=8) { */
  for (i=0; i<n; i++) {
    if (x[i] > quarterDelta) {
      n2++;
      blockVar += (x[i]*x[i]);
    } else if (x[i] < mquarterDelta) {
      n2++;
      blockVar += (x[i]*x[i]);
    }
  }

  if (n2 > 0) {
    blockVar /= n2;
    blockVar = sqrt(blockVar);
    delta = distortion * (float)blockVar / CPDF;
  } else {
    delta = 1.0f;
  }

  return delta;
}


/**
 * Quantizes and encodes a body of data.
 *
 * @param  data  transformed sample values.
 * @param  nsamps  number of sample values.
 * @param  distortion  the distortion level.
 * @param  encodedData  output encoded data.
 * @param  index  starting index in the output encoded data.
 * @param  outputBufferSize  the size of the output buffer.
 * @return  number of output bytes, or 0 if the output buffer is too small.
 */
int BlockCompressor2::dataEncode(float* data, int nsamps, float distortion,
				char* encodedData, int index, int outputBufferSize) {

  int i;
  int nbytesHdrExpected = SIZEOF_INT * 2;
		
  if (outputBufferSize < nbytesHdrExpected+1) return 0;

  float delta;
  if (_manualDelta != FLOAT_MAX_VALUE) {
    // Delta was set manually.
    delta = _manualDelta;
  } else {
    delta = computeDelta(data, nsamps, distortion);
  }

  /// Need extra samples to set nNonZero.
  while (_idataLength < nsamps+2) {
    delete[] _idata;
    _idata = new int[_idataLength * 2]{0};
    _idataLength = _idataLength * 2;
  }

  quantize(data, nsamps, delta, _idata);

  for (i=nsamps-1; i>=0; i--) if (_idata[i] != 0) break;
  int nNonZero = i + 1;
  // This must be a multiple of 2.
  if ((nNonZero >> 1) << 1 < nNonZero) nNonZero++;
   
  /* Load delta. */
  int idelta = floatToIntBits(delta);
  stuffIntInBytes(idelta, encodedData, index);
  int nbytesHdr = SIZEOF_INT;
  index += SIZEOF_INT;
	  
  /* Load the number of non-zero values. */
  stuffIntInBytes(nNonZero, encodedData, index);
  nbytesHdr += SIZEOF_INT;
  index += SIZEOF_INT;
	  
  /* Run-length encode. */
  /* The input must be terminated with a non-zero value first. */
  _idata[nNonZero] = 1;
  /* Note that we don't even try to encode the zeroed part of the data. */
  int nbytes = nNonZero * SIZEOF_INT;

  // We need extra space because compression may actually be negative.
  while (_huffcharsLength < nbytes*2) {
    delete[] _huffchars;
    _huffchars = new char[_huffcharsLength * 2]{(char)0};
    _huffcharsLength = _huffcharsLength * 2;
  }

  nbytes = runLengthEncode(_idata, nbytes, _huffchars);

  /* Sanity check. */
  if (nbytesHdr != nbytesHdrExpected)
    assert(nbytes != 0);

  /* Huffman encode. */
  nbytes = _huffCoder->huffEncode(_huffchars, nbytes, encodedData, index, outputBufferSize-nbytesHdr);	 

  if (nbytes == 0) return 0;  /* Overflow! */

  return nbytes + nbytesHdr;
}


/**
 * Decodes and dequantizes a body of data
 *
 * @param  encodedData  encoded byte data.
 * @param  index  index into the encoded data.
 * @param  workBuffer  work buffer.
 * @param  workBufferSize  size of the work buffer.
 * @param  nsamps  number of output samples.
 * @param  data  output decoded data.
 * @return   0 if OK, -1 if output buffer is too small.
 */
// int BlockCompressor2::dataDecode(char* encodedData, int index, char* workBuffer,
int BlockCompressor2::dataDecode(const char* encodedData, int index, char* workBuffer,
				 int workBufferSize, int nsamps, float* data) {

  /* Unload delta. */
  float delta;
  int idelta = stuffBytesInInt(encodedData, index);
  delta = intBitsToFloat(idelta);
  index += SIZEOF_FLOAT;
	
  /* Unload the number of non-zero samples. */
  int nNonZero = stuffBytesInInt(encodedData, index);
  if (c_checkNonZeroCount) {
    if (nNonZero < 0)
      nNonZero = 0;
  }
  index += SIZEOF_INT;

  /* Huffman decode. */
  int nbytes = _huffCoder->huffDecode(encodedData, index, workBuffer, workBufferSize);
  if (nbytes == -1) return -1;

  /* Run-length decode and dequantize. */
  runLengthDecodeDequant(workBuffer, nbytes, delta, data);
	  
  /* Fill in the zeros. */
  /* We know that nNonZero is a multiple of 2. */
  for (int i=0; i<nsamps-nNonZero; i++) data[i+nNonZero] = 0.0f;

  return 0;
}


/*
int main_FOR_SIMPLE_TESTING_BlockCompressor2(int argc, char** argv) {

  BlockCompressor2* blockCompressor = new BlockCompressor2();
  delete blockCompressor;

  return 0;
}
*/
