
package org.atlas.seiszip;

/**
 * Transformed block compression using run-length coding and Huffman coding.
 *
 * @author  Dave Diller and Richard Foy, ported to Java by Jed Diller.
 */

public class BlockCompressor2 {

  private static final boolean c_verbose1 = false;

  private static final boolean c_checkNonZeroCount_AlwaysTrue = true;

  public static final boolean c_complainIfNotFreed = true;

  private native long blockCompressorAlloc_native();
  private native void blockCompressorFree_native(long blockCompressorNative);
  private native int dataEncode_native(long blockCompressor, float[] data, int nsamps, float distortion, byte[] encodedData,
				       int index, int outputBufferSize);
  private native int dataDecode_native(long blockCompressorNative, byte[] encodedData, int index, byte[] workBuffer, int workBufferSize,
				       int nsamps, float[] data);
  private native void setManualDelta_native(long blockCompressorNative, float delta);
  private native void unsetManualDelta_native(long blockCompressorNative);


  private static final float CPDF = 0.26f;  // Quantization factor.


  /**
   * Computes the quantization delta.
   *
   * @param  x  transformed samples.
   * @param  n  number of samples.
   * @param  distortion  desired distortion level.
   * @return  quantization delta.
   */
  public static float computeDelta(float[] x, int n, float distortion) {

    if (Transformer2.c_integrityTest) {
      return 1.0f;
    }

    double blockVar;
    float delta, quarterDelta, mquarterDelta;
    int i, n2;

    /* Get a low approximation of delta. */
    blockVar = 0.0;
    for (i=0; i<n; i++)
      blockVar += (double)(x[i]*x[i]);

    blockVar /= (double)n;
    blockVar = Math.sqrt(blockVar);

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
        blockVar += (double)(x[i]*x[i]);
      } else if (x[i] < mquarterDelta) {
        n2++;
        blockVar += (double)(x[i]*x[i]);
      }
    }

    if (n2 > 0) {
      blockVar /= (double)n2;
      blockVar = Math.sqrt(blockVar);
      delta = distortion * (float)blockVar / CPDF;
    } else {
      delta = 1.0f;
    }

    return delta;
  }


  /**
   * Applies quantization.
   *
   * @param  transformed samples.
   * @param  number of samples.
   * @param  quantization delta.
   * @param  output quantized samples.
   */
  private static void quantize(float[] x, int n, float delta, int[] ix) {

    int i;
    float temp;
                
    delta = 1.0f / delta;

    for (i=0; i<n; i++) {
      temp = x[i] * delta;
      // Math.round() is dreadfully slow.
      // ix[i] = (int)Math.round(temp);
      // Is is faster to avoid the function call?  Seems like it!
      // ix[i] = (int)NINT(temp);
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
  /*package*/ static int runLengthEncode(int[] quantdata, int n, byte[] encodedChars) {

    int i, nbytes, zrun, nwords, istart;
    short si;

    nwords = n / CompressionUtil2.SIZEOF_INT;
                
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
          CompressionUtil2.stuffShortInBytes(si, encodedChars, nbytes);
          nbytes += 2;
          istart += 65535;
        }
        zrun = i - istart;
        if (zrun > 255) {
          encodedChars[nbytes] = 106;
          nbytes++;
          si = (short)zrun;
          CompressionUtil2.stuffShortInBytes(si, encodedChars, nbytes);      
          nbytes += 2;
        } else if (zrun > 100) {
          encodedChars[nbytes] = 105;
          nbytes++;
          encodedChars[nbytes] = (byte)zrun;
          nbytes++;
        } else {
          encodedChars[nbytes] = (byte)zrun;
          nbytes++;
        }
      } else if (quantdata[i] < 75  &&  quantdata[i] > -74) {
        encodedChars[nbytes] = (byte)(quantdata[i] + 180);
        nbytes++;
        i++;
      } else {
        /* The data is >= 75 or <= -75. */
        if (quantdata[i] > 0) {
          if (quantdata[i] < 256) {
            encodedChars[nbytes] = 101;
            nbytes++;
            encodedChars[nbytes] = (byte)quantdata[i];        
            nbytes++;
          } else if (quantdata[i] < 65536) {
            encodedChars[nbytes] = 103;
            nbytes++;
            si = (short)quantdata[i];
            CompressionUtil2.stuffShortInBytes(si, encodedChars, nbytes);        
            nbytes += 2;
          } else {
            /* It's a huge integer. */
            encodedChars[nbytes] = (byte)255;
            nbytes++;
            CompressionUtil2.stuffIntInBytes(quantdata[i], encodedChars, nbytes); 
            nbytes += 4;
          }
        } else {
          /* Less than 0. */
          if (quantdata[i] > -256) {
            encodedChars[nbytes] = 102;
            nbytes++;
            encodedChars[nbytes] = (byte)(-quantdata[i]);
            nbytes++;
          } else if (quantdata[i] > -65536) {  
            encodedChars[nbytes] = 104;
            nbytes++;
            si = (short)(-quantdata[i]);
            CompressionUtil2.stuffShortInBytes(si, encodedChars, nbytes);        
            nbytes += 2;
          } else {
            /* It's a huge negative integer. */
            encodedChars[nbytes] = (byte)255;
            nbytes++;
            CompressionUtil2.stuffIntInBytes(quantdata[i], encodedChars, nbytes);
            nbytes += 4;
          }
        }
        i++;
      }

    }
          
    int nInts = nbytes / CompressionUtil2.SIZEOF_INT;
    if ((nInts*CompressionUtil2.SIZEOF_INT) < nbytes) {
      // Be sure that the extra characters contain zeros.
      encodedChars[nbytes] = 0;
      encodedChars[nbytes+1] = 0;
      encodedChars[nbytes+2] = 0;
      nInts++;
    }

    return nbytes;
  }


  private HuffCoder2 _huffCoder;
  private int[] _idata = new int[1024];            // Just an initial guess on the size.
  private byte[] _huffchars = new byte[1024*4];    // Just an initial guess on the size.
  private float _manualDelta = Float.MAX_VALUE;
  private long _blockCompressorNative = -1L;
  private boolean _freed = false;
  private int _printCount = 0;
  

  /**
   * Creates a new block compressor with the default Huffman value count.
   *
   * @param  useNativeMethods  flag to use native methods.
   */
  public BlockCompressor2(boolean useNativeMethods) {

    this(HuffCoder2.c_huffCount, useNativeMethods);
  }

  /**
   * Creates a new block compressor with a custom Huffman value count.
   *
   * @param  huffTable  a Huffman value count of length 257.
   * @param  useNativeMethods  flag to use native methods.
   */
  public BlockCompressor2(int[] huffTable, boolean useNativeMethods) {

    if (useNativeMethods) {
      System.loadLibrary("com_daspeg");
      _blockCompressorNative = this.blockCompressorAlloc_native();
      return;
    }

    _huffCoder = new HuffCoder2(huffTable, useNativeMethods);
  }


  /**
   * Frees resources if native code is used.
   */
  public void free() {

    _freed = true;

    _huffCoder.free();

    if (_blockCompressorNative != -1L) {
      this.blockCompressorFree_native(_blockCompressorNative);
      _blockCompressorNative = -1L;
    }
  }


  // This method is called when this object is garbage collected.
  @Override
  protected void finalize() throws Throwable {

    if (c_complainIfNotFreed) {
      if (!_freed) {
	System.out.println();
	System.out.println(this.getClass().getName() + " was not freed");
	System.out.println();
      }
    }
      

    try {
      this.free();
    } finally {
      super.finalize();
    }
  }


  /**
   * Manually sets the delta value for quantization (ignoring the distortion value).
   * This is so that distortion does not vary from frame to frame.
   *
   * @param  delta  the value to use for delta in all cases.
   */
  public void setManualDelta(float delta) {

    if (_blockCompressorNative != -1L) {
      this.setManualDelta_native(_blockCompressorNative, delta);
      return;
    }

    _manualDelta = delta;
  }


  /**
   * Unsets the manual delta value, so that it will be computed from the data.
   */
  public void unsetManualDelta() {

    if (_blockCompressorNative != -1L) {
      this.unsetManualDelta_native(_blockCompressorNative);
      return;
    }

    _manualDelta = Float.MAX_VALUE;
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
  private static void runLengthDecodeDequant(byte[] huffchars, int nbytes,
                                             float delta, float[] quantdata) {

    if (huffchars == null)
      throw new IllegalArgumentException("huffchars == null");
    if (huffchars.length < nbytes)
      throw new IllegalArgumentException("huffchars.length < nbytes");
    if (quantdata == null)
      throw new IllegalArgumentException("quantdata == null");

    int i, j, iend, ival;

    for (i=j=0; i<nbytes;) {

      if (j >= quantdata.length)
	throw new RuntimeException("Dequantization exceeded buffer length");

      // Function calls in tight loops are hurting performance.
      int ihuffchar = (int)huffchars[i];
      if (ihuffchar < 0)
	ihuffchar += 256;

      if (ihuffchar > 0  &&  ihuffchar < 101) {
        iend = j + ihuffchar;
        for (; j<iend; j++) quantdata[j] = 0.0f;
        i++;
      } else if (ihuffchar == 105) {
        i++;
        ival = (int)huffchars[i];
        if (ival < 0) ival += 256;
        iend = j + ival;
        for (; j<iend; j++) quantdata[j] = 0.0f;
        i++;
      } else if (ihuffchar > 106  &&  ihuffchar < 255) {
        quantdata[j++] = ((float)ihuffchar - 180.0f) * delta;
        i++;
      } else {
        if (huffchars[i] == 101) {
          i++;
          ival = (int)huffchars[i];
          if (ival < 0) ival += 256;
          quantdata[j++] = (float)ival * delta;
          i++;
        } else if (huffchars[i] == 102) {
          i++;
          ival = (int)huffchars[i];
          if (ival < 0) ival += 256;
          quantdata[j++] = (-(float)ival) * delta;
          i++;
        } else if (huffchars[i] == 103) {
          i++;
          ival = CompressionUtil2.stuffBytesInShort(huffchars, i);
          if (ival < 0) ival += 65536;
          quantdata[j++] = (float)ival * delta;
          i += 2;
        } else if (huffchars[i] == 104) {
          i++;
          ival = CompressionUtil2.stuffBytesInShort(huffchars, i);
          if (ival < 0) ival += 65536;
          quantdata[j++] = (-(float)ival) * delta;
          i += 2;
        } else if (huffchars[i] == 106) {
          i++;
          ival = CompressionUtil2.stuffBytesInShort(huffchars, i);
          if (ival < 0) ival += 65536;
          iend = j + ival;
          for (; j<iend; j++) quantdata[j] = 0.0f;
          i += 2;
        } else if (ihuffchar == 255) {
          i++;
          ival = CompressionUtil2.stuffBytesInInt(huffchars, i);
          quantdata[j++] = ((float)ival) * delta;
          i += 4;
        } else {
          // System.out.println("HuffTableDecode: bad character encountered at element " + (int)huffchars[i]);
	  // Just punt - don't know what else to do.  This may actually happen,
	  // because data gets corrupted.
	  // TODO: Perhaps we should zero the data.
	  return;
        }
      }
    }
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
  public int dataEncode(float[] data, int nsamps, float distortion, byte[] encodedData, int index, int outputBufferSize) {

    if (_blockCompressorNative != -1L) {
      return this.dataEncode_native(_blockCompressorNative, data, nsamps, distortion, encodedData, index, outputBufferSize);
    }

    int i;
    int nbytesHdrExpected = CompressionUtil2.SIZEOF_INT * 2;
                
    if (outputBufferSize < nbytesHdrExpected+1)
      return 0;

    float delta;
    if (_manualDelta != Float.MAX_VALUE) {
      // Delta was set manually.
      delta = _manualDelta;
    } else {
      delta = this.computeDelta(data, nsamps, distortion);
    }

    // TODO: Should this method be synchronized?
    // Need extra samples to set nNonZero.
    while (_idata.length < nsamps+2)
      _idata = new int[_idata.length*2];

    BlockCompressor2.quantize(data, nsamps, delta, _idata);

    for (i=nsamps-1; i>=0; i--) if (_idata[i] != 0) break;
    int nNonZero = i + 1;
    // This must be a multiple of 2.
    if ((nNonZero >> 1) << 1 < nNonZero) nNonZero++;
   
    /* Load delta. */
    // int idelta = (int)delta;
    int idelta = Float.floatToIntBits(delta);
    CompressionUtil2.stuffIntInBytes(idelta, encodedData, index);
    int nbytesHdr = CompressionUtil2.SIZEOF_INT;
    index += CompressionUtil2.SIZEOF_INT;
          
    /* Load the number of non-zero values. */
    CompressionUtil2.stuffIntInBytes(nNonZero, encodedData, index);
    nbytesHdr += CompressionUtil2.SIZEOF_INT;
    index += CompressionUtil2.SIZEOF_INT;

    if (c_verbose1) {
      if (_printCount <= 10)
	System.out.println("BlockCompressor2.dataEncode: delta= " + delta + " idelta= " + idelta + " nNonZero= " + nNonZero);
      _printCount++;
    }
          
    /* Run-length encode. */
    /* The input must be terminated with a non-zero value first. */
    _idata[nNonZero] = 1;
    /* Note that we don't even try to encode the zeroed part of the data. */
    int nbytes = nNonZero * CompressionUtil2.SIZEOF_INT;

    // We need extra space because compression may actually be negative.
    while (_huffchars.length < nbytes*2)
      _huffchars = new byte[_huffchars.length*2];

    nbytes = BlockCompressor2.runLengthEncode(_idata, nbytes, _huffchars);

    /* Sanity check. */
    if (nbytesHdr != nbytesHdrExpected) assert nbytes != 0;

    /* Huffman encode. */
    nbytes = _huffCoder.huffEncode(_huffchars, nbytes, encodedData, index,
                                   outputBufferSize-nbytesHdr);         

    if (nbytes == 0)
      return 0;  // Overflow!

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
  public int dataDecode(byte[] encodedData, int index, byte[] workBuffer, int workBufferSize, int nsamps, float[] data) {

    if (_blockCompressorNative != -1L) {
      return this.dataDecode_native(_blockCompressorNative, encodedData, index, workBuffer, workBufferSize, nsamps, data);
    }

    /* Unload delta. */
    float delta;
    int idelta = CompressionUtil2.stuffBytesInInt(encodedData, index);
    delta = Float.intBitsToFloat(idelta);
    index += CompressionUtil2.SIZEOF_FLOAT;

    /* Unload the number of non-zero samples. */
    int nNonZero = CompressionUtil2.stuffBytesInInt(encodedData, index);
    if (nNonZero < 0)
      nNonZero = 0;
    index += CompressionUtil2.SIZEOF_INT;

    if (c_verbose1) {
      if (_printCount <= 10)
	System.out.println("BlockCompressor2.dataDecode: delta= " + delta + " idelta= " + idelta + " nNonZero= " + nNonZero);
      _printCount++;
    }
                
    /* Huffman decode. */
    int nbytes = _huffCoder.huffDecode(encodedData, index, workBuffer, workBufferSize);

    if (nbytes == -1)
      return -1;

    /* Run-length decode and dequantize. */
    BlockCompressor2.runLengthDecodeDequant(workBuffer, nbytes, delta, data);
          
    /* Fill in the zeros. */
    /* We know that nNonZero is a multiple of 2. */
    for (int i=0; i<nsamps-nNonZero; i++)
      data[i+nNonZero] = 0.0f;

    return 0;
  }

}
