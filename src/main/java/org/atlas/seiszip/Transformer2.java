
package org.atlas.seiszip;

/**
 * Lapped orthogonal transforms.
 * <p>
 * See the accompanying file LOT_ZouAndPearlman.pdf for more information on LOT.
 * @author  Dave Diller and Richard Foy, ported to Java by Jed Diller.
 */

public class Transformer2 implements AutoCloseable {

  private static final boolean c_debug1 = false;
  private static final boolean c_useJavaVector = c_debug1;

  private static final boolean c_debug2 = false;
  private static final boolean c_debug3 = false;

  private static final boolean c_debug4 = false;
  private static final boolean c_experimentWithBlockSizes = c_debug4;

  
  /**
   * Compression policies, which are used to select the block size and the
   * transform length.
   */
  public enum Policy {FASTEST, MAX_COMPRESSION}

  /**
   * Block size direction.
   */
  public enum BlockSizeDirection {VERTICAL, HORIZONTAL}


  /**
   * Computes the block size.
   *
   * @param  nsamples  the number of samples.
   * @param  policy  the compression policy (fastest or maximum compression).
   * @return  the block size.
   */
  public static int computeBlockSize(int nsamples, Policy policy, BlockSizeDirection direction) {

    if (nsamples <= 8) return 8;
    if (nsamples <= 16) return 16;
    if (nsamples <= 24) return 24;
    if (nsamples <= 32) return 32;

    // Most of this is based on experimentation.

    if (policy.equals(Policy.FASTEST)) {

      if (nsamples <= 48) {
        return 24;
      } else if (nsamples <= 64) {
        return 32;
      } else {
        // 64 is the most that we ever do when striving for fastest.
        return 64;
      }

    } else {
      // Maximum compression.

      // The original strategy was to try for the biggest block size that is a multiple of 16;
      int nBlocks = nsamples / 16;
      if (nBlocks*16 < nsamples) nBlocks++;
      int blockSize = nBlocks * 16;

      // Testing shows that you don't get appreciably better compression ratios
      // with block sizes over 512, and it gets substantially slower.  Here are some
      // comparisons on the Statoil data:
      //   blockSize=256  compressionRatio=25.9 compressionSpeed=22  uncompressionSpeed=40
      //   blockSize=512  compressionRatio=28.9 compressionSpeed=24  uncompressionSpeed=42
      //   blockSize=1024 compressionRatio=29.4 compressionSpeed=18  uncompressionSpeed=33
      // Large blocks also strain memory.
      
      // April 15, 2025: Test show no very little difference between 256 and 512 in speed for the general block size, and the compression
      //                 ratio is slightly lower with 512.

      /*
      if (true) {
        if (blockSize > 256)
	  blockSize = 256;
      } else {
        if (blockSize > 512)
	  blockSize = 512;
      }
      */

      // If you change the code here, also change it in Transformer.cpp.
      if (direction == BlockSizeDirection.VERTICAL) {
	// We want bigger blocks in time.
	// if (blockSize > 2048)
	//   blockSize = 2048;
	// The April 2025 timing tests are inconclusive, but this seems to be a good size.
	// if (blockSize > 1024)  THIS CAUSED TileVersionTest.tileSizeTortureTest TO FAIL
	//   blockSize = 1024;
	if (c_experimentWithBlockSizes) {
	  if (blockSize > 1024)
	    blockSize = 1024;
	} else {
	  if (blockSize > 256)
	    blockSize = 256;
	}
      } else {
        // if (blockSize > 256)
	//   blockSize = 256;
        // if (blockSize > 64)
	//   blockSize = 64;
	// The April 2025 timing tests are inconclusive, but this seems to be a good size.
	// if (blockSize > 128)
	//   blockSize = 128;
	if (c_experimentWithBlockSizes) {
	  if (blockSize > 128)
	    blockSize = 128;
	} else {
	  if (blockSize > 256)
	    blockSize = 256;
	}
      }

      return blockSize;
    }

  }


  /**
   * Computes the transform length.
   *
   * @param  blockSize  the block size.
   * @param  policy  the compression policy (fastest or maximum compression).
   * @return  the transform length.
   */
  public static int computeTransformLength(int blockSize, Policy policy) {

    if (c_debug2)
      return 8;

    if (policy.equals(Policy.FASTEST)) {
      // If we're looking for speed, we always use the fastest transform.
      return 8;

    } else {
      // Going for maximum compression - always try to use 16.
      if ((blockSize/16)*16 == blockSize) {
        return 16;
      } else {
        // Not a multiple of 16 - forced to use 8.
	if (c_debug3)
	  throw new RuntimeException("What?  Block size is not a multiple of 16?");
        return 8;
      }

    }

  }

  
  /**
   * Checks the block size for validity.
   *
   * @param  blockSize  the block size.
   * @exception  IllegalArgumentException of the block size is invalid.
   */
  public static void checkBlockSize(int blockSize) {

    // Must always be a multiple of 8.
    if ((blockSize / 8) * 8 != blockSize)
      throw new IllegalArgumentException("Block size of " + blockSize + " is invalid");
  }


  /**
   * Checks the transform length for validity.
   *
   * @param  transLength  the transform length.
   * @param  blockSize  the block size.
   * @exception  IllegalArgumentException of the transform length is invalid.
   */
  public static void checkTransLength(int transLength, int blockSize) {

    if (transLength == 8) {
      int nsubBlocks = blockSize / 8;
      if (nsubBlocks*8 != blockSize)
        throw new IllegalArgumentException("Invalid transform length of "
                                           + transLength + " for block size of 8");
    } else if (transLength == 16) {
      int nsubBlocks = blockSize / 16;
      if (nsubBlocks*16 != blockSize)
        throw new IllegalArgumentException("Invalid transform length of "
                                           + transLength + " for block size of 16");
    }
  }

  
  /**
   * Computes the padded transform length.
   *
   * @param  number of samples.
   * @param  blockSize.
   * @return  the padded transform length.
   */
  public static int computePaddedLength(int nsamples, int blockSize) {
           
    if (blockSize == 0)
      throw new IllegalArgumentException("Block Size is Invalid");        

    // Round up to a multiple of the block size.
    int n = (nsamples / blockSize) * blockSize;
    if (n < nsamples)
      n += blockSize;
    return n;
  }


  private native long transformerAlloc_native(int useSSE);
  private native void transformerFree_native(long transformerNative);
  private native void lotFwd_native(long transformerNative, float[] x, int index, int blockSize, int transLength, int nblocks);
  private native void lotRev_native(long transformerNative, float[] x, int index, int blockSize, int transLength, int nblocks);

  
  /*
   * This filter is for the length-8 case.  It was selected after tests with
   * many different filters from the LOT family.
   */
  private static final float FILT00 = -0.04739361256361008f;
  private static final float FILT01 = -0.04662604257464409f;
  private static final float FILT02 = -0.00119693996384740f;
  private static final float FILT03 = -0.03382463753223419f;
  private static final float FILT04 =  0.02838252671062946f;
  private static final float FILT05 = -0.01656247489154339f;
  private static final float FILT06 =  0.07613456249237061f;
  private static final float FILT07 =  0.07250666618347168f;
  private static final float FILT08 = -0.03595214337110519f;
  private static final float FILT09 = -0.08879133313894272f;
  private static final float FILT10 =  0.13751053810119629f;
  private static final float FILT11 =  0.07318570464849472f;
  private static final float FILT12 =  0.07364673167467117f;
  private static final float FILT13 =  0.14130237698554993f;
  private static final float FILT14 = -0.09677020460367203f;
  private static final float FILT15 = -0.04223278537392616f;
  private static final float FILT16 =  0.01202779170125723f;
  private static final float FILT17 = -0.01751151494681835f;
  private static final float FILT18 =  0.12299209833145142f;
  private static final float FILT19 =  0.22184988856315613f;
  private static final float FILT20 = -0.24440830945968628f;
  private static final float FILT21 = -0.15647235512733459f;
  private static final float FILT22 =  0.01386095304042101f;
  private static final float FILT23 = -0.03452030941843987f;
  private static final float FILT24 =  0.12838034331798553f;
  private static final float FILT25 =  0.15070076286792755f;
  private static final float FILT26 = -0.08759644627571106f;
  private static final float FILT27 = -0.03334903717041016f;
  private static final float FILT28 = -0.02273095957934856f;
  private static final float FILT29 = -0.08336708694696426f;
  private static final float FILT30 =  0.15335069596767426f;
  private static final float FILT31 =  0.13196527957916260f;
  private static final float FILT32 =  0.22580096125602722f;
  private static final float FILT33 =  0.32441934943199158f;
  private static final float FILT34 = -0.36810591816902161f;
  private static final float FILT35 = -0.39882853627204895f;
  private static final float FILT36 =  0.38496315479278564f;
  private static final float FILT37 =  0.35616609454154968f;
  private static final float FILT38 = -0.34223899245262146f;
  private static final float FILT39 = -0.24626369774341583f;
  private static final float FILT40 =  0.33917742967605591f;
  private static final float FILT41 =  0.43858313560485840f;
  private static final float FILT42 = -0.32764276862144470f;
  private static final float FILT43 = -0.13819301128387451f;
  private static final float FILT44 = -0.12365391105413437f;
  private static final float FILT45 = -0.31876283884048462f;
  private static final float FILT46 =  0.43257290124893188f;
  private static final float FILT47 =  0.33427309989929199f;
  private static final float FILT48 =  0.39115539193153381f;
  private static final float FILT49 =  0.37391233444213867f;
  private static final float FILT50 =  0.06256388127803802f;
  private static final float FILT51 =  0.39160564541816711f;
  private static final float FILT52 = -0.41305914521217346f;
  private static final float FILT53 = -0.08888602256774902f;
  private static final float FILT54 = -0.37077668309211731f;
  private static final float FILT55 = -0.40489980578422546f;
  private static final float FILT56 =  0.40100517868995667f;
  private static final float FILT57 =  0.17178836464881897f;
  private static final float FILT58 =  0.45991656184196472f;
  private static final float FILT59 =  0.33414626121520996f;
  private static final float FILT60 =  0.31529939174652100f;
  private static final float FILT61 =  0.46039211750030518f;
  private static final float FILT62 =  0.13931363821029663f;
  private static final float FILT63 =  0.37151649594306946f;


  // Filter array for the lot8 transforms.  Length of 64 elements.
  private static final float globalFilt8[] = {
    FILT00,  FILT01,  FILT02,  FILT03,  FILT04,  FILT05,  FILT06,  FILT07,
    FILT08,  FILT09,  FILT10,  FILT11,  FILT12,  FILT13,  FILT14,  FILT15,
    FILT16,  FILT17,  FILT18,  FILT19,  FILT20,  FILT21,  FILT22,  FILT23,
    FILT24,  FILT25,  FILT26,  FILT27,  FILT28,  FILT29,  FILT30,  FILT31,
    FILT32,  FILT33,  FILT34,  FILT35,  FILT36,  FILT37,  FILT38,  FILT39,
    FILT40,  FILT41,  FILT42,  FILT43,  FILT44,  FILT45,  FILT46,  FILT47,
    FILT48,  FILT49,  FILT50,  FILT51,  FILT52,  FILT53,  FILT54,  FILT55,
    FILT56,  FILT57,  FILT58,  FILT59,  FILT60,  FILT61,  FILT62,  FILT63
  };


  /**
   * Returns a copy of the filter that is used for the 8-sized block transform.
   * This is FYI only, for examination of the filter.
   *
   * @return  a copy of the filter that is used for the 8-sized block transform.
   */
  public static float[] getFilter8() {

    return globalFilt8.clone();
  }


  /*package*/ static boolean c_integrityTest = false;

  
  private float[] _tmp;
  private long _transformerNative = -1L;
  private boolean _freed = false;
  // private JavaVectorTransformer _javaVectorTransformer;


  /**
   * Constructor.
   *
   * @param  useNativeMethods  flag to use native methods.
   * @param  useNativeMethods  flag to use SSE.
   */
  public Transformer2(boolean useNativeMethods, boolean useSSE) {

    if (useNativeMethods) {
      System.loadLibrary("com_daspeg");
      int iuseSSE = 1;
      if (!useSSE)
	iuseSSE = 0;
      _transformerNative = this.transformerAlloc_native(iuseSSE);
      return;
    }

    _tmp = new float[16];

    // if (c_useJavaVector)
    //   _javaVectorTransformer = new JavaVectorTransformer(globalFilt16);
  }


  /**
   * Frees resources if native code is used.
   */
  public void free() {

    _freed = true;

    if (_transformerNative != -1L) {
      this.transformerFree_native(_transformerNative);
      _transformerNative = -1L;
    }
  }


  /*
  // This method is called when this object is garbage collected.
  @Override
  protected void finalize() throws Throwable {

    if (BlockCompressor2.c_complainIfNotFreed) {
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
  */

  @Override
  public void close() {

    if (BlockCompressor2.c_complainIfNotFreed) {
      if (!_freed) {
	System.out.println();
	System.out.println(this.getClass().getName() + " was not freed");
	System.out.println();
      }
    }

    this.free();
  }
  

  /**
   * Forward lapped orthogonal transform for the length-8 case.
   *
   * @param  x  array to be transformed.
   * @param  index  index of first element in array to transform.
   * @param  nblocks  number of blocks.
   * @param  scratch  work array - must be nsamps+8 in length.
   */
  private void lotFwd8(float[] x, int index, int nblocks, float[] scratch) {

    int i, k, nsamps;
    float d0p15, d1p14, d2p13, d3p12, d4p11, d5p10, d6p9, d7p8;
    float d0m15, d1m14, d2m13, d3m12, d4m11, d5m10, d6m9, d7m8;
                
    if (c_integrityTest) {
      /* Exchange the order of the samples, but don't alter any amplitudes. */
      for ( i=0; i<nblocks*8; i+=2) {
        float save = x[i+index];
        x[i+index] = x[i+1+index];
        x[i+1+index] = save;
      }
      return;
    }

    nsamps = nblocks << 3;   /* nsamps = nblocks * 8 */

    /* Mirror at left side. */
    scratch[0] = x[3+index];
    scratch[1] = x[2+index];
    scratch[2] = x[1+index];
    scratch[3] = x[0+index];

    /* Mirror at right side. */
    scratch[4 + nsamps] = x[-1 + nsamps+index];
    scratch[5 + nsamps] = x[-2 + nsamps+index];
    scratch[6 + nsamps] = x[-3 + nsamps+index];
    scratch[7 + nsamps] = x[-4 + nsamps+index];
          
    for ( i=0; i<nsamps; i++) scratch[i+4] = x[i+index];

    k = 0;
    /* One loop for each block of samples. */
    for ( i=0; i<nsamps; i+=8) {

      d0p15 = scratch[i+0] + scratch[i+15];
      d1p14 = scratch[i+1] + scratch[i+14];
      d2p13 = scratch[i+2] + scratch[i+13];
      d3p12 = scratch[i+3] + scratch[i+12];
      d4p11 = scratch[i+4] + scratch[i+11];
      d5p10 = scratch[i+5] + scratch[i+10];
      d6p9  = scratch[i+6] + scratch[i+9];
      d7p8  = scratch[i+7] + scratch[i+8];
      d0m15 = scratch[i+0] - scratch[i+15];
      d1m14 = scratch[i+1] - scratch[i+14];
      d2m13 = scratch[i+2] - scratch[i+13];
      d3m12 = scratch[i+3] - scratch[i+12];
      d4m11 = scratch[i+4] - scratch[i+11];
      d5m10 = scratch[i+5] - scratch[i+10];
      d6m9  = scratch[i+6] - scratch[i+9];
      d7m8  = scratch[i+7] - scratch[i+8];

      x[k+index] = FILT00 * d0p15  +  FILT08 * d1p14
        + FILT16 * d2p13  +  FILT24 * d3p12
        + FILT32 * d4p11  +  FILT40 * d5p10
        + FILT48 * d6p9  +  FILT56 * d7p8;
      x[k+1+index] = FILT01 * d0m15  +  FILT09 * d1m14
        + FILT17 * d2m13  +  FILT25 * d3m12
        + FILT33 * d4m11  +  FILT41 * d5m10
        + FILT49 * d6m9  +  FILT57 * d7m8;

      x[k+2+index] = FILT02 * d0p15  +  FILT10 * d1p14
        + FILT18 * d2p13  +  FILT26 * d3p12
        + FILT34 * d4p11  +  FILT42 * d5p10
        + FILT50 * d6p9  +  FILT58 * d7p8;
      x[k+3+index] = FILT03  * d0m15  +  FILT11  * d1m14
        + FILT19 * d2m13  +  FILT27 * d3m12
        + FILT35 * d4m11  +  FILT43 * d5m10
        + FILT51 * d6m9  +  FILT59 * d7m8;

      x[k+4+index] = FILT04 * d0p15  +  FILT12 * d1p14
        + FILT20 * d2p13  +  FILT28 * d3p12
        + FILT36 * d4p11  +  FILT44 * d5p10
        + FILT52 * d6p9  +  FILT60 * d7p8;
      x[k+5+index] = FILT05 * d0m15  +  FILT13 * d1m14
        + FILT21 * d2m13  +  FILT29 * d3m12
        + FILT37 * d4m11  +  FILT45 * d5m10
        + FILT53 * d6m9  +  FILT61 * d7m8;

      x[k+6+index] = FILT06 * d0p15  +  FILT14 * d1p14
        + FILT22 * d2p13  +  FILT30 * d3p12
        + FILT38 * d4p11  +  FILT46 * d5p10
        + FILT54 * d6p9  +  FILT62 * d7p8;
      x[k+7+index] = FILT07 * d0m15  +  FILT15 * d1m14
        + FILT23 * d2m13  +  FILT31 * d3m12
        + FILT39 * d4m11  +  FILT47 * d5m10
        + FILT55 * d6m9  +  FILT63 * d7m8;

      k += 8;
    }

    return;
  }


  /**
   * Reverse lapped orthogonal transform for the length-8 case.
   *
   * @param  x  array to be inverse transformed.
   * @param  index  index of first element in array to transform.
   * @param  nblocks  number of blocks.
   * @param  scratch  work array - must be nsamps+8 in length.
   */
  private void lotRev8( float[] x, int index, int nblocks, float[] scratch) {

    int i, nsamps, nblocksM1;

    if (c_integrityTest) {
      /* Exchange the order of the samples, but don't alter any amplitudes. */
      for ( i=0; i<nblocks*8; i+=2) {
        float save = x[i+index];
        x[i+index] = x[i+1+index];
        x[i+1+index] = save;
      }
      return;
    }

    nsamps = nblocks << 3;   /* nsamps = nblocks * 8 */
    nblocksM1 = nblocks - 1;
          
    int xIndex = index;
    int scratchIndex = 0;

    /* Loop through the blocks. */
    for ( i=0; i<nblocks; i++) {

      /* It's faster not to check the DC, since it's almost surely
       * non-zero. */
      _tmp[0] = FILT00 * x[xIndex];  _tmp[1] = FILT08 * x[xIndex];
      _tmp[2] = FILT16 * x[xIndex];  _tmp[3] = FILT24 * x[xIndex];
      _tmp[4] = FILT32 * x[xIndex];  _tmp[5] = FILT40 * x[xIndex];
      _tmp[6] = FILT48 * x[xIndex];  _tmp[7] = FILT56 * x[xIndex];
            
      if ( x[2+xIndex] != 0.0f) {
        _tmp[0] += FILT02 * x[2+xIndex];  _tmp[1] += FILT10 * x[2+xIndex];
        _tmp[2] += FILT18 * x[2+xIndex];  _tmp[3] += FILT26 * x[2+xIndex];
        _tmp[4] += FILT34 * x[2+xIndex];  _tmp[5] += FILT42 * x[2+xIndex];
        _tmp[6] += FILT50 * x[2+xIndex];  _tmp[7] += FILT58 * x[2+xIndex];
      }
             
      if ( x[4+xIndex] != 0.0f) {
        _tmp[0] += FILT04 * x[4+xIndex];  _tmp[1] += FILT12 * x[4+xIndex];
        _tmp[2] += FILT20 * x[4+xIndex];  _tmp[3] += FILT28 * x[4+xIndex];
        _tmp[4] += FILT36 * x[4+xIndex];  _tmp[5] += FILT44 * x[4+xIndex];
        _tmp[6] += FILT52 * x[4+xIndex];  _tmp[7] += FILT60 * x[4+xIndex];
      }
            
      if ( x[6+xIndex] != 0.0f) {
        _tmp[0] += FILT06 * x[6+xIndex];  _tmp[1] += FILT14 * x[6+xIndex];
        _tmp[2] += FILT22 * x[6+xIndex];  _tmp[3] += FILT30 * x[6+xIndex];
        _tmp[4] += FILT38 * x[6+xIndex];  _tmp[5] += FILT46 * x[6+xIndex];
        _tmp[6] += FILT54 * x[6+xIndex];  _tmp[7] += FILT62 * x[6+xIndex];
      }
            
            
      /* Ditto for not checking x[1]. */
      _tmp[8] = FILT01 * x[1+xIndex];   _tmp[9] = FILT09 * x[1+xIndex];
      _tmp[10] = FILT17 * x[1+xIndex];  _tmp[11] = FILT25 * x[1+xIndex];
      _tmp[12] = FILT33 * x[1+xIndex];  _tmp[13] = FILT41 * x[1+xIndex];
      _tmp[14] = FILT49 * x[1+xIndex];  _tmp[15] = FILT57 * x[1+xIndex];
            
      if ( x[3+xIndex] != 0.0f) {
        _tmp[8] += FILT03 * x[3+xIndex];   _tmp[9] += FILT11 * x[3+xIndex];
        _tmp[10] += FILT19 * x[3+xIndex];  _tmp[11] += FILT27 * x[3+xIndex];
        _tmp[12] += FILT35 * x[3+xIndex];  _tmp[13] += FILT43 * x[3+xIndex];
        _tmp[14] += FILT51 * x[3+xIndex];  _tmp[15] += FILT59 * x[3+xIndex];
      }
            
      if ( x[5+xIndex] != 0.0f) {
        _tmp[8] += FILT05 * x[5+xIndex];   _tmp[9] += FILT13 * x[5+xIndex];
        _tmp[10] += FILT21 * x[5+xIndex];  _tmp[11] += FILT29 * x[5+xIndex];
        _tmp[12] += FILT37 * x[5+xIndex];  _tmp[13] += FILT45 * x[5+xIndex];
        _tmp[14] += FILT53 * x[5+xIndex];  _tmp[15] += FILT61 * x[5+xIndex];
      }
            
      if ( x[7+xIndex] != 0.0f) {
        _tmp[8] += FILT07 * x[7+xIndex];   _tmp[9] += FILT15 * x[7+xIndex];
        _tmp[10] += FILT23 * x[7+xIndex];  _tmp[11] += FILT31 * x[7+xIndex];
        _tmp[12] += FILT39 * x[7+xIndex];  _tmp[13] += FILT47 * x[7+xIndex];
        _tmp[14] += FILT55 * x[7+xIndex];  _tmp[15] += FILT63 * x[7+xIndex];
      }
            
      scratch[15+scratchIndex] = _tmp[0] - _tmp[8];   _tmp[8] += _tmp[0];
      scratch[14+scratchIndex] = _tmp[1] - _tmp[9];   _tmp[9] += _tmp[1];
      scratch[13+scratchIndex] = _tmp[2] - _tmp[10];  _tmp[10] += _tmp[2];
      scratch[12+scratchIndex] = _tmp[3] - _tmp[11];  _tmp[11] += _tmp[3];
      scratch[11+scratchIndex] = _tmp[4] - _tmp[12];  _tmp[12] += _tmp[4];
      scratch[10+scratchIndex] = _tmp[5] - _tmp[13];  _tmp[13] += _tmp[5];
      scratch[9+scratchIndex]  = _tmp[6] - _tmp[14];   _tmp[14] += _tmp[6];
      scratch[8+scratchIndex]  = _tmp[7] - _tmp[15];   _tmp[15] += _tmp[7];

      if ( i == 0) {
        /* Left edge. */
        scratch[4+scratchIndex] = _tmp[11];
        scratch[5+scratchIndex] = _tmp[10];
        scratch[6+scratchIndex] = _tmp[9];
        scratch[7+scratchIndex] = _tmp[8];
      }
            
      scratch[scratchIndex] += _tmp[8];
      scratch[1+scratchIndex] += _tmp[9];
      scratch[2+scratchIndex] += _tmp[10];
      scratch[3+scratchIndex] += _tmp[11];
      scratch[4+scratchIndex] += _tmp[12];
      scratch[5+scratchIndex] += _tmp[13];
      scratch[6+scratchIndex] += _tmp[14];
      scratch[7+scratchIndex] += _tmp[15];

      if ( i == nblocksM1) {
        /* Right edge (last time thru loop). */
        scratch[8+scratchIndex] += scratch[15+scratchIndex];
        scratch[9+scratchIndex] += scratch[14+scratchIndex];
        scratch[10+scratchIndex] += scratch[13+scratchIndex];
        scratch[11+scratchIndex] += scratch[12+scratchIndex];
      }

      xIndex += 8;
      scratchIndex += 8;
    }
          
    for ( i=0; i<nsamps; i++) x[i+index] = scratch[i+4];

    return;
  }


  /*
   * This filter is for the length-16 case.  It was selected after tests with
   * many different filters from the LOT family.  It has length of 256 elements.
   */
  private static final float globalFilt16[] = {
    -0.05092546716332436f, -0.04800906777381897f,  0.00414894195273519f, -0.02083428017795086f, 
    0.00727677764371037f, -0.01368454564362764f,  0.01039289310574532f, -0.00904755853116512f, 
    0.01269984804093838f, -0.00400549918413162f,  0.01494983769953251f,  0.00126677460502833f, 
    0.01628162339329720f,  0.00898398645222187f,  0.01730429194867611f,  0.02478851750493050f, 
    -0.04416475072503090f, -0.06306689232587814f,  0.03449244424700737f, -0.02380717545747757f, 
    0.05057477205991745f,  0.00585399661213160f,  0.04923382028937340f,  0.03318041190505028f, 
    0.03116884268820286f,  0.04958778619766235f,  0.00236834678798914f,  0.04767716303467751f, 
    -0.02650943025946617f,  0.02465381100773811f, -0.04639538377523422f, -0.03057979419827461f, 
    -0.03090312704443932f, -0.06785343587398529f,  0.08187537640333176f,  0.01948466524481773f, 
    0.06891985237598419f,  0.08599705994129181f, -0.00495519628748298f,  0.08212102204561234f, 
    -0.07446990907192230f,  0.01403521653264761f, -0.07664451748132706f, -0.05682519450783730f, 
    -0.01186644099652767f, -0.06315757334232330f,  0.06287240236997604f,  0.03753550350666046f, 
    -0.01165023352950811f, -0.06340452283620834f,  0.11659996211528778f,  0.09895730763673782f, 
    0.00534545863047242f,  0.10264379531145096f, -0.11356112360954285f, -0.04641539230942726f, 
    -0.05042800307273865f, -0.11235136538743973f,  0.09689835458993912f,  0.00650629680603743f, 
    0.08959857374429703f,  0.10656486451625824f, -0.06378589570522308f, -0.01267870981246233f, 
    0.01285405177623034f, -0.03134901076555252f,  0.12193045765161514f,  0.15737636387348175f, 
    -0.11007650941610336f, -0.01514582801610231f, -0.07915185391902924f, -0.13471862673759460f, 
    0.14168362319469452f,  0.08557172119617462f,  0.02428064867854118f,  0.12253648042678833f, 
    -0.15165045857429504f, -0.10914323478937149f,  0.03618234023451805f,  0.00253067375160754f, 
    0.04166804254055023f,  0.01321700401604176f,  0.07948959618806839f,  0.15558218955993652f, 
    -0.18215398490428925f, -0.16363291442394257f,  0.12292949110269547f,  0.04734316468238831f, 
    0.04377902299165726f,  0.12589003145694733f, -0.16998200118541718f, -0.17978045344352722f, 
    0.14265312254428864f,  0.06966695189476013f,  0.01126913074404001f,  0.03483581170439720f, 
    0.07368443161249161f,  0.07279250770807266f, -0.01334957964718342f,  0.05988258868455887f, 
    -0.10735398530960083f, -0.15115077793598175f,  0.19063347578048706f,  0.21396283805370331f, 
    -0.20784924924373627f, -0.18499191105365753f,  0.15553742647171021f,  0.11333797872066498f, 
    -0.05390042439103127f,  0.02340606972575188f, -0.07172303646802902f, -0.06155961006879807f, 
    0.10767285525798798f,  0.14833539724349976f, -0.12189307808876038f, -0.09884083271026611f, 
    0.08136536926031113f,  0.06104232743382454f, -0.03727753087878227f, -0.00742463627830148f, 
    -0.01261481456458569f, -0.03411408141255379f,  0.05694158002734184f,  0.07956540584564209f, 
    -0.10047722607851028f, -0.12376008927822113f,  0.14114053547382355f,  0.09753937274217606f, 
    0.14232714474201202f,  0.20832858979701996f, -0.22185355424880981f, -0.22708790004253387f, 
    0.24318979680538177f,  0.26028376817703247f, -0.25543534755706787f, -0.26008918881416321f, 
    0.26438581943511963f,  0.25928497314453125f, -0.25799125432968140f, -0.24207605421543121f, 
    0.24217848479747772f,  0.23651280999183655f, -0.20771762728691101f, -0.14138355851173401f, 
    0.17631556093692780f,  0.27445021271705627f, -0.28413963317871094f, -0.27951043844223022f, 
    0.24745246767997742f,  0.19036246836185455f, -0.12664590775966644f, -0.03730802610516548f, 
    -0.04332009702920914f, -0.12344301491975784f,  0.18856252729892731f,  0.24215136468410492f, 
    -0.27186822891235352f, -0.28049305081367493f,  0.26579040288925171f,  0.16568998992443085f, 
    0.20833195745944977f,  0.29243454337120056f, -0.27813091874122620f, -0.17133343219757080f, 
    0.04240495711565018f, -0.10057521611452103f,  0.22874785959720612f,  0.29450646042823792f, 
    -0.29088282585144043f, -0.22571973502635956f,  0.09691336750984192f, -0.04596826806664467f, 
    0.17975607514381409f,  0.28661811351776123f, -0.30193713307380676f, -0.21314570307731628f, 
    0.23714594542980194f,  0.31508100032806396f, -0.18560475111007690f,  0.01107664406299591f, 
    -0.21623404324054718f, -0.31488713622093201f,  0.26970902085304260f,  0.10787588357925415f, 
    0.10492639243602753f,  0.27358931303024292f, -0.31658893823623657f, -0.21826802194118500f, 
    0.00987942516803741f, -0.18919040262699127f,  0.31637305021286011f,  0.23052150011062622f, 
    0.26165023446083069f,  0.27759197354316711f, -0.05150613188743591f,  0.22331990301609039f, 
    -0.32924216985702515f, -0.20078578591346741f, -0.07991197705268860f, -0.30131199955940247f, 
    0.30354225635528564f,  0.07861359417438507f,  0.19761487841606140f,  0.33342185616493225f, 
    -0.22616995871067047f,  0.05068635195493698f, -0.28473275899887085f, -0.26713839173316956f, 
    0.28090313076972961f,  0.22914972901344299f,  0.11566746234893799f,  0.34049138426780701f, 
    -0.20711791515350342f,  0.14810700714588165f, -0.34126159548759460f, -0.17647065222263336f, 
    -0.17796021699905396f, -0.34309706091880798f,  0.15066449344158173f, -0.20486447215080261f, 
    0.33997192978858948f,  0.10668116062879562f,  0.22463223338127136f,  0.28189635276794434f, 
    0.29416474699974060f,  0.14253759384155273f,  0.26168128848075867f,  0.29512420296669006f, 
    0.08735719323158264f,  0.34579148888587952f, -0.11966320872306824f,  0.27994415163993835f, 
    -0.28042265772819519f,  0.11689667403697968f, -0.34702816605567932f, -0.08213546127080917f, 
    -0.29749101400375366f, -0.26349446177482605f, -0.15335811674594879f, -0.30471205711364746f, 
    0.30092546343803406f,  0.05685322359204292f,  0.34059208631515503f,  0.12008785456418991f, 
    0.31829196214675903f,  0.18218725919723511f,  0.28621721267700195f,  0.23660972714424133f, 
    0.23576197028160095f,  0.28340902924537659f,  0.18350340425968170f,  0.31790497899055481f, 
    0.11961393058300018f,  0.33970499038696289f,  0.05408555269241333f,  0.30441030859947205f
  };


  /**
   * Returns a copy of the filter that is used for the 16-sized block transform.
   * This is FYI only, for examination of the filter.
   *
   * @return  a copy of the filter that is used for the 16-sized block transform.
   */
  public static float[] getFilter16() {

    return globalFilt16.clone();
  }


  /**
   * Forward lapped orthogonal transform for the length-16 case.
   *
   * @param  x  array to be transformed.
   * @param  index  index of first element in array to transform.
   * @param  nblocks  number of blocks.
   * @param  scratch  work array - must be nsamps+32 in length.
   */
  private void lotFwd16(float[] x, int index, int nblocks, float[] scratch) {

    int nsamps, i, k, l;
    float d0p31, d1p30, d2p29, d3p28, d4p27, d5p26, d6p25, d7p24;
    float d8p23, d9p22, d10p21, d11p20, d12p19, d13p18, d14p17, d15p16;
    float d0m31, d1m30, d2m29, d3m28, d4m27, d5m26, d6m25, d7m24;
    float d8m23, d9m22, d10m21, d11m20, d12m19, d13m18, d14m17, d15m16;
         
    if (c_integrityTest) {
      /* Exchange the order of the samples, but don't alter any amplitudes. */
      for (i=0; i<nblocks*16; i+=4) {
        float save0  = x[i+index];
        float save1  = x[i+1+index];
        x[i+index]   = x[i+3+index];
        x[i+1+index] = x[i+2+index];
        x[i+2+index] = save1;
        x[i+3+index] = save0;
      }
      return;
    }

    nsamps = nblocks << 4;   /* nsamps = nblocks * 16 */

    /* Mirror at left side. */
    scratch[0] = x[7+index];
    scratch[1] = x[6+index];
    scratch[2] = x[5+index];
    scratch[3] = x[4+index];
    scratch[4] = x[3+index];
    scratch[5] = x[2+index];
    scratch[6] = x[1+index];
    scratch[7] = x[index];

    /* Mirror at right side. */
    scratch[8+nsamps]  = x[-1+nsamps+index];
    scratch[9+nsamps]  = x[-2+nsamps+index];
    scratch[10+nsamps] = x[-3+nsamps+index];
    scratch[11+nsamps] = x[-4+nsamps+index];
    scratch[12+nsamps] = x[-5+nsamps+index];
    scratch[13+nsamps] = x[-6+nsamps+index];
    scratch[14+nsamps] = x[-7+nsamps+index];
    scratch[15+nsamps] = x[-8+nsamps+index];

    for (i=0; i<nsamps; i++)
      scratch[i+8] = x[i+index];

    k = 0;
    for (i=0; i<nsamps; i+=16) {
            
      d0p31  = scratch[i] + scratch[31+i];
      d1p30  = scratch[1+i] + scratch[30+i];
      d2p29  = scratch[2+i] + scratch[29+i];
      d3p28  = scratch[3+i] + scratch[28+i];
      d4p27  = scratch[4+i] + scratch[27+i];
      d5p26  = scratch[5+i] + scratch[26+i];
      d6p25  = scratch[6+i] + scratch[25+i];
      d7p24  = scratch[7+i] + scratch[24+i];
      d8p23  = scratch[8+i] + scratch[23+i];
      d9p22  = scratch[9+i] + scratch[22+i];
      d10p21 = scratch[10+i] + scratch[21+i];
      d11p20 = scratch[11+i] + scratch[20+i];
      d12p19 = scratch[12+i] + scratch[19+i];
      d13p18 = scratch[13+i] + scratch[18+i];
      d14p17 = scratch[14+i] + scratch[17+i];
      d15p16 = scratch[15+i] + scratch[16+i];

      d0m31  = scratch[i] - scratch[31+i];
      d1m30  = scratch[1+i] - scratch[30+i];
      d2m29  = scratch[2+i] - scratch[29+i];
      d3m28  = scratch[3+i] - scratch[28+i];
      d4m27  = scratch[4+i] - scratch[27+i];
      d5m26  = scratch[5+i] - scratch[26+i];
      d6m25  = scratch[6+i] - scratch[25+i];
      d7m24  = scratch[7+i] - scratch[24+i];
      d8m23  = scratch[8+i] - scratch[23+i];
      d9m22  = scratch[9+i] - scratch[22+i];
      d10m21 = scratch[10+i] - scratch[21+i];
      d11m20 = scratch[11+i] - scratch[20+i];
      d12m19 = scratch[12+i] - scratch[19+i];
      d13m18 = scratch[13+i] - scratch[18+i];
      d14m17 = scratch[14+i] - scratch[17+i];
      d15m16 = scratch[15+i] - scratch[16+i];

      for (l=0; l<16; l+=2) {

        x[k+index] =  globalFilt16[l] * d0p31 + globalFilt16[16+l] * d1p30 + globalFilt16[32+l] * d2p29 + globalFilt16[48+l] * d3p28
          + globalFilt16[64+l] * d4p27 + globalFilt16[80+l] * d5p26 + globalFilt16[96+l] * d6p25 + globalFilt16[112+l] * d7p24
          + globalFilt16[128+l] * d8p23 + globalFilt16[144+l] * d9p22 + globalFilt16[160+l] * d10p21 + globalFilt16[176+l] * d11p20
          + globalFilt16[192+l] * d12p19 + globalFilt16[208+l] * d13p18 + globalFilt16[224+l] * d14p17 + globalFilt16[240+l] * d15p16;

        x[k+1+index] =  globalFilt16[1+l] * d0m31 + globalFilt16[17+l] * d1m30 + globalFilt16[33+l] * d2m29 + globalFilt16[49+l] * d3m28
          + globalFilt16[65+l] * d4m27 + globalFilt16[81+l] * d5m26 + globalFilt16[97+l] * d6m25 + globalFilt16[113+l] * d7m24
          + globalFilt16[129+l] * d8m23 + globalFilt16[145+l] * d9m22 + globalFilt16[161+l] * d10m21 + globalFilt16[177+l] * d11m20
          + globalFilt16[193+l] * d12m19 + globalFilt16[209+l] * d13m18 + globalFilt16[225+l] * d14m17 + globalFilt16[241+l] * d15m16;

        k += 2;        
      }

    }

    return;
  }


  // This is an optimization of the method above, by ChatGPT.
  private void lotFwd16_FROM_CHATGPT_IS_SLOWER(float[] x, int index, int nblocks, float[] scratch) {
    
    int nsamps = nblocks << 4; // nblocks * 16

    if (c_integrityTest) {
      for (int i = 0; i < nsamps; i += 4) {
	int base = i + index;
	float save0 = x[base];
	float save1 = x[base + 1];
	x[base]     = x[base + 3];
	x[base + 1] = x[base + 2];
	x[base + 2] = save1;
	x[base + 3] = save0;
      }
      return;
    }

    // Fill scratch buffer (mirrored edges + middle)
    for (int i = 0; i < 8; i++) {
      scratch[i]             = x[7 - i + index]; // left mirror
      scratch[8 + nsamps + i] = x[nsamps - 1 - i + index]; // right mirror
    }

    System.arraycopy(x, index, scratch, 8, nsamps); // middle section

    int k = 0;
    for (int i = 0; i < nsamps; i += 16) {
      float[] sum = new float[16];
      float[] diff = new float[16];

      for (int j = 0; j < 16; j++) {
	sum[j]  = scratch[i + j] + scratch[i + 31 - j];
	diff[j] = scratch[i + j] - scratch[i + 31 - j];
      }

      for (int l = 0; l < 16; l += 2) {
	float accEven = 0f;
	float accOdd  = 0f;
	for (int m = 0; m < 16; m++) {
	  accEven += globalFilt16[(m << 4) + l]     * sum[m];
	  accOdd  += globalFilt16[(m << 4) + l + 1] * diff[m];
	}

	x[k + index]     = accEven;
	x[k + 1 + index] = accOdd;
	k += 2;
      }
    }
  }
  

  /**
   * Reverse lapped orthogonal transform for the length-16 case.
   *
   * @param  x  array to be inverse transformed.
   * @param  index  index of first element in array to transform.
   * @param  nblocks  number of blocks.
   * @param  scratch  work array - must be nsamps+32 in length.
   */
  private void lotRev16(float[] x, int index, int nblocks, float[] scratch) {

    int nsamps, i, allZerosInBlock, nblocksM1;
    float tmp0, tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, tmp7;
    float tmp8, tmp9, tmp10, tmp11, tmp12, tmp13, tmp14, tmp15;
    float tmp16, tmp17, tmp18, tmp19, tmp20, tmp21, tmp22, tmp23;
    float tmp24, tmp25, tmp26, tmp27, tmp28, tmp29, tmp30, tmp31;

    if (c_integrityTest) {
      /* Exchange the order of the samples, but don't alter any amplitudes. */
      for (i=0; i<nblocks*16; i+=4) {
        float save0  = x[i+index];
        float save1  = x[i+1+index];
        x[i+index]   = x[i+3+index];
        x[i+1+index] = x[i+2+index];
        x[i+2+index] = save1;
        x[i+3+index] = save0;
      }
      return;
    }

    nsamps = nblocks << 4;   /* nsamps = nblocks * 16 */
    nblocksM1 = nblocks - 1;

    /* Check for all zeros. */ 
    boolean allZeros = true;
    for (i=0; i<nsamps; i++) {
      if (x[i+index] != 0.0f) { 
        allZeros = false;
        break;                  
      }          
    }
         
    if (allZeros) return;

    int xIndex = index;
    int scratchIndex = 0;

    /* Loop through the blocks. */
    for (i=0; i<nblocks; i++) {
                  
      if (x[xIndex] != 0.0f) {         
        float xVal = x[xIndex];
        tmp0 = globalFilt16[0] * xVal;     tmp2 = globalFilt16[16] * xVal;
        tmp4 = globalFilt16[32] * xVal;    tmp6 = globalFilt16[48] * xVal;
        tmp8 = globalFilt16[64] * xVal;    tmp10 = globalFilt16[80] * xVal;
        tmp12 = globalFilt16[96] * xVal;   tmp14 = globalFilt16[112] * xVal;
        tmp16 = globalFilt16[128] * xVal;  tmp18 = globalFilt16[144] * xVal;
        tmp20 = globalFilt16[160] * xVal;  tmp22 = globalFilt16[176] * xVal;
        tmp24 = globalFilt16[192] * xVal;  tmp26 = globalFilt16[208] * xVal;
        tmp28 = globalFilt16[224] * xVal;  tmp30 = globalFilt16[240] * xVal;
      } else {
        tmp0 = 0.0f;   tmp2 = 0.0f;   tmp4 = 0.0f;   tmp6 = 0.0f;
        tmp8 = 0.0f;   tmp10 = 0.0f;  tmp12 = 0.0f;  tmp14 = 0.0f;
        tmp16 = 0.0f;  tmp18 = 0.0f;  tmp20 = 0.0f;  tmp22 = 0.0f;
        tmp24 = 0.0f;  tmp26 = 0.0f;  tmp28 = 0.0f;  tmp30 = 0.0f;
      }
      
      if (x[1+xIndex] != 0.0f) {    
        float xVal = x[1+xIndex];
        tmp1 = globalFilt16[1] * xVal;     tmp3 = globalFilt16[17] * xVal;
        tmp5 = globalFilt16[33] * xVal;    tmp7 = globalFilt16[49] * xVal;
        tmp9 = globalFilt16[65] * xVal;    tmp11 = globalFilt16[81] * xVal;
        tmp13 = globalFilt16[97] * xVal;   tmp15 = globalFilt16[113] * xVal;
        tmp17 = globalFilt16[129] * xVal;  tmp19 = globalFilt16[145] * xVal;
        tmp21 = globalFilt16[161] * xVal;  tmp23 = globalFilt16[177] * xVal;
        tmp25 = globalFilt16[193] * xVal;  tmp27 = globalFilt16[209] * xVal;
        tmp29 = globalFilt16[225] * xVal;  tmp31 = globalFilt16[241] * xVal;
      } else {
        tmp1 = 0.0f;   tmp3 = 0.0f;   tmp5 = 0.0f;   tmp7 = 0.0f;
        tmp9 = 0.0f;  tmp11 = 0.0f;  tmp13 = 0.0f;   tmp15 = 0.0f;
        tmp17 = 0.0f;  tmp19 = 0.0f;  tmp21 = 0.0f;  tmp23 = 0.0f;
        tmp25 = 0.0f;  tmp27 = 0.0f;  tmp29 = 0.0f;  tmp31 = 0.0f;
      }
              
      if (x[2+xIndex] != 0.0f) { 
        float xVal = x[2+xIndex];
        tmp0 += globalFilt16[2] * xVal;     tmp2 += globalFilt16[18] * xVal;
        tmp4 += globalFilt16[34] * xVal;    tmp6 += globalFilt16[50] * xVal;
        tmp8 += globalFilt16[66] * xVal;    tmp10 += globalFilt16[82] * xVal;
        tmp12 += globalFilt16[98] * xVal;   tmp14 += globalFilt16[114] * xVal;
        tmp16 += globalFilt16[130] * xVal;  tmp18 += globalFilt16[146] * xVal;
        tmp20 += globalFilt16[162] * xVal;  tmp22 += globalFilt16[178] * xVal;
        tmp24 += globalFilt16[194] * xVal;  tmp26 += globalFilt16[210] * xVal;
        tmp28 += globalFilt16[226] * xVal;  tmp30 += globalFilt16[242] * xVal;
      }
      
      if (x[3+xIndex] != 0.0f) {
        float xVal = x[3+xIndex];
        tmp1 += globalFilt16[3] * xVal;     tmp3 += globalFilt16[19] * xVal;
        tmp5 += globalFilt16[35] * xVal;    tmp7 += globalFilt16[51] * xVal;
        tmp9 += globalFilt16[67] * xVal;    tmp11 += globalFilt16[83] * xVal;
        tmp13 += globalFilt16[99] * xVal;   tmp15 += globalFilt16[115] * xVal;
        tmp17 += globalFilt16[131] * xVal;  tmp19 += globalFilt16[147] * xVal;
        tmp21 += globalFilt16[163] * xVal;  tmp23 += globalFilt16[179] * xVal;
        tmp25 += globalFilt16[195] * xVal;  tmp27 += globalFilt16[211] * xVal;
        tmp29 += globalFilt16[227] * xVal;  tmp31 += globalFilt16[243] * xVal;
      }

      if (x[4+xIndex] != 0.0f) {
        float xVal = x[4+xIndex];
        tmp0 += globalFilt16[4] * xVal;     tmp2 += globalFilt16[20] * xVal;
        tmp4 += globalFilt16[36] * xVal;    tmp6 += globalFilt16[52] * xVal;
        tmp8 += globalFilt16[68] * xVal;    tmp10 += globalFilt16[84] * xVal;
        tmp12 += globalFilt16[100] * xVal;  tmp14 += globalFilt16[116] * xVal;
        tmp16 += globalFilt16[132] * xVal;  tmp18 += globalFilt16[148] * xVal;
        tmp20 += globalFilt16[164] * xVal;  tmp22 += globalFilt16[180] * xVal;
        tmp24 += globalFilt16[196] * xVal;  tmp26 += globalFilt16[212] * xVal;
        tmp28 += globalFilt16[228] * xVal;  tmp30 += globalFilt16[244] * xVal;
      }
      
      if (x[5+xIndex] != 0.0f) { 
        float xVal = x[5+xIndex];
        tmp1 += globalFilt16[5] * xVal;     tmp3 += globalFilt16[21] * xVal;
        tmp5 += globalFilt16[37] * xVal;    tmp7 += globalFilt16[53] * xVal;
        tmp9 += globalFilt16[69] * xVal;    tmp11 += globalFilt16[85] * xVal;
        tmp13 += globalFilt16[101] * xVal;  tmp15 += globalFilt16[117] * xVal;
        tmp17 += globalFilt16[133] * xVal;  tmp19 += globalFilt16[149] * xVal;
        tmp21 += globalFilt16[165] * xVal;  tmp23 += globalFilt16[181] * xVal;
        tmp25 += globalFilt16[197] * xVal;  tmp27 += globalFilt16[213] * xVal;
        tmp29 += globalFilt16[229] * xVal;  tmp31 += globalFilt16[245] * xVal;
      }
       
      if (x[6+xIndex] != 0.0f) {
        float xVal = x[6+xIndex];
        tmp0 += globalFilt16[6] * xVal;     tmp2 += globalFilt16[22] * xVal;
        tmp4 += globalFilt16[38] * xVal;    tmp6 += globalFilt16[54] * xVal;
        tmp8 += globalFilt16[70] * xVal;    tmp10 += globalFilt16[86] * xVal;
        tmp12 += globalFilt16[102] * xVal;  tmp14 += globalFilt16[118] * xVal;
        tmp16 += globalFilt16[134] * xVal;  tmp18 += globalFilt16[150] * xVal;
        tmp20 += globalFilt16[166] * xVal;  tmp22 += globalFilt16[182] * xVal;
        tmp24 += globalFilt16[198] * xVal;  tmp26 += globalFilt16[214] * xVal;
        tmp28 += globalFilt16[230] * xVal;  tmp30 += globalFilt16[246] * xVal;
      }
      
      if (x[7+xIndex] != 0.0f) {
        float xVal = x[7+xIndex];
        tmp1 += globalFilt16[7] * xVal;     tmp3 += globalFilt16[23] * xVal;
        tmp5 += globalFilt16[39] * xVal;    tmp7 += globalFilt16[55] * xVal;
        tmp9 += globalFilt16[71] * xVal;    tmp11 += globalFilt16[87] * xVal;
        tmp13 += globalFilt16[103] * xVal;  tmp15 += globalFilt16[119] * xVal;
        tmp17 += globalFilt16[135] * xVal;  tmp19 += globalFilt16[151] * xVal;
        tmp21 += globalFilt16[167] * xVal;  tmp23 += globalFilt16[183] * xVal;
        tmp25 += globalFilt16[199] * xVal;  tmp27 += globalFilt16[215] * xVal;
        tmp29 += globalFilt16[231] * xVal;  tmp31 += globalFilt16[247] * xVal;
      }
      
      if (x[8+xIndex] != 0.0f) {
        float xVal = x[8+xIndex];
        tmp0 += globalFilt16[8] * xVal;     tmp2 += globalFilt16[24] * xVal;
        tmp4 += globalFilt16[40] * xVal;    tmp6 += globalFilt16[56] * xVal;
        tmp8 += globalFilt16[72] * xVal;    tmp10 += globalFilt16[88] * xVal;
        tmp12 += globalFilt16[104] * xVal;  tmp14 += globalFilt16[120] * xVal;
        tmp16 += globalFilt16[136] * xVal;  tmp18 += globalFilt16[152] * xVal;
        tmp20 += globalFilt16[168] * xVal;  tmp22 += globalFilt16[184] * xVal;
        tmp24 += globalFilt16[200] * xVal;  tmp26 += globalFilt16[216] * xVal;
        tmp28 += globalFilt16[232] * xVal;  tmp30 += globalFilt16[248] * xVal;
      }
      
      if (x[9+xIndex] != 0.0f) {
        float xVal = x[9+xIndex];
        tmp1 += globalFilt16[9] * xVal;     tmp3 += globalFilt16[25] * xVal;
        tmp5 += globalFilt16[41] * xVal;    tmp7 += globalFilt16[57] * xVal;
        tmp9 += globalFilt16[73] * xVal;    tmp11 += globalFilt16[89] * xVal;
        tmp13 += globalFilt16[105] * xVal;  tmp15 += globalFilt16[121] * xVal;
        tmp17 += globalFilt16[137] * xVal;  tmp19 += globalFilt16[153] * xVal;
        tmp21 += globalFilt16[169] * xVal;  tmp23 += globalFilt16[185] * xVal;
        tmp25 += globalFilt16[201] * xVal;  tmp27 += globalFilt16[217] * xVal;
        tmp29 += globalFilt16[233] * xVal;  tmp31 += globalFilt16[249] * xVal;
      }

      if (x[10+xIndex] != 0.0f) {
        float xVal = x[10+xIndex];
        tmp0 += globalFilt16[10] * xVal;    tmp2 += globalFilt16[26] * xVal;
        tmp4 += globalFilt16[42] * xVal;    tmp6 += globalFilt16[58] * xVal;
        tmp8 += globalFilt16[74] * xVal;    tmp10 += globalFilt16[90] * xVal;
        tmp12 += globalFilt16[106] * xVal;  tmp14 += globalFilt16[122] * xVal;
        tmp16 += globalFilt16[138] * xVal;  tmp18 += globalFilt16[154] * xVal;
        tmp20 += globalFilt16[170] * xVal;  tmp22 += globalFilt16[186] * xVal;
        tmp24 += globalFilt16[202] * xVal;  tmp26 += globalFilt16[218] * xVal;
        tmp28 += globalFilt16[234] * xVal;  tmp30 += globalFilt16[250] * xVal;
      }
      
      if (x[11+xIndex] != 0.0f) {
        float xVal = x[11+xIndex];
        tmp1 += globalFilt16[11] * xVal;    tmp3 += globalFilt16[27] * xVal;
        tmp5 += globalFilt16[43] * xVal;    tmp7 += globalFilt16[59] * xVal;
        tmp9 += globalFilt16[75] * xVal;    tmp11 += globalFilt16[91] * xVal;
        tmp13 += globalFilt16[107] * xVal;  tmp15 += globalFilt16[123] * xVal;
        tmp17 += globalFilt16[139] * xVal;  tmp19 += globalFilt16[155] * xVal;
        tmp21 += globalFilt16[171] * xVal;  tmp23 += globalFilt16[187] * xVal;
        tmp25 += globalFilt16[203] * xVal;  tmp27 += globalFilt16[219] * xVal;
        tmp29 += globalFilt16[235] * xVal;  tmp31 += globalFilt16[251] * xVal;
      }
      
      if (x[12+xIndex] != 0.0f) {
        float xVal = x[12+xIndex];
        tmp0 += globalFilt16[12] * xVal;    tmp2 += globalFilt16[28] * xVal;
        tmp4 += globalFilt16[44] * xVal;    tmp6 += globalFilt16[60] * xVal;
        tmp8 += globalFilt16[76] * xVal;    tmp10 += globalFilt16[92] * xVal;
        tmp12 += globalFilt16[108] * xVal;  tmp14 += globalFilt16[124] * xVal;
        tmp16 += globalFilt16[140] * xVal;  tmp18 += globalFilt16[156] * xVal;
        tmp20 += globalFilt16[172] * xVal;  tmp22 += globalFilt16[188] * xVal;
        tmp24 += globalFilt16[204] * xVal;  tmp26 += globalFilt16[220] * xVal;
        tmp28 += globalFilt16[236] * xVal;  tmp30 += globalFilt16[252] * xVal;
      }
      
      if (x[13+xIndex] != 0.0f) {
        float xVal = x[13+xIndex];
        tmp1 += globalFilt16[13] * xVal;    tmp3 += globalFilt16[29] * xVal;
        tmp5 += globalFilt16[45] * xVal;    tmp7 += globalFilt16[61] * xVal;
        tmp9 += globalFilt16[77] * xVal;    tmp11 += globalFilt16[93] * xVal;
        tmp13 += globalFilt16[109] * xVal;  tmp15 += globalFilt16[125] * xVal;
        tmp17 += globalFilt16[141] * xVal;  tmp19 += globalFilt16[157] * xVal;
        tmp21 += globalFilt16[173] * xVal;  tmp23 += globalFilt16[189] * xVal;
        tmp25 += globalFilt16[205] * xVal;  tmp27 += globalFilt16[221] * xVal;
        tmp29 += globalFilt16[237] * xVal;  tmp31 += globalFilt16[253] * xVal;
      }
      
      if (x[14+xIndex] != 0.0f) {
        float xVal = x[14+xIndex];
        tmp0 += globalFilt16[14] * xVal;    tmp2 += globalFilt16[30] * xVal;
        tmp4 += globalFilt16[46] * xVal;    tmp6 += globalFilt16[62] * xVal;
        tmp8 += globalFilt16[78] * xVal;    tmp10 += globalFilt16[94] * xVal;
        tmp12 += globalFilt16[110] * xVal;  tmp14 += globalFilt16[126] * xVal;
        tmp16 += globalFilt16[142] * xVal;  tmp18 += globalFilt16[158] * xVal;
        tmp20 += globalFilt16[174] * xVal;  tmp22 += globalFilt16[190] * xVal;
        tmp24 += globalFilt16[206] * xVal;  tmp26 += globalFilt16[222] * xVal;
        tmp28 += globalFilt16[238] * xVal;  tmp30 += globalFilt16[254] * xVal;
      }
      
      if (x[15+xIndex] != 0.0f) {
        float xVal = x[15+xIndex];
        tmp1 += globalFilt16[15] * xVal;    tmp3 += globalFilt16[31] * xVal;
        tmp5 += globalFilt16[47] * xVal;    tmp7 += globalFilt16[63] * xVal;
        tmp9 += globalFilt16[79] * xVal;    tmp11 += globalFilt16[95] * xVal;
        tmp13 += globalFilt16[111] * xVal;  tmp15 += globalFilt16[127] * xVal;
        tmp17 += globalFilt16[143] * xVal;  tmp19 += globalFilt16[159] * xVal;
        tmp21 += globalFilt16[175] * xVal;  tmp23 += globalFilt16[191] * xVal;
        tmp25 += globalFilt16[207] * xVal;  tmp27 += globalFilt16[223] * xVal;
        tmp29 += globalFilt16[239] * xVal;  tmp31 += globalFilt16[255] * xVal;
      }

      if (i == 0) {
        /* Left edge. */
        scratch[8+scratchIndex] = tmp14 + tmp15;
        scratch[9+scratchIndex] = tmp12 + tmp13;
        scratch[10+scratchIndex] = tmp10 + tmp11;
        scratch[11+scratchIndex] = tmp8 + tmp9;
        scratch[12+scratchIndex] = tmp6 + tmp7;
        scratch[13+scratchIndex] = tmp4 + tmp5;
        scratch[14+scratchIndex] = tmp2 + tmp3;
        scratch[15+scratchIndex] = tmp0 + tmp1;
      }
              
      scratch[scratchIndex] += tmp0 + tmp1;       scratch[31+scratchIndex] = tmp0 - tmp1;
      scratch[1+scratchIndex] += tmp2 + tmp3;     scratch[30+scratchIndex] = tmp2 - tmp3;
      scratch[2+scratchIndex] += tmp4 + tmp5;     scratch[29+scratchIndex] = tmp4 - tmp5;
      scratch[3+scratchIndex] += tmp6 + tmp7;     scratch[28+scratchIndex] = tmp6 - tmp7;
      scratch[4+scratchIndex] += tmp8 + tmp9;     scratch[27+scratchIndex] = tmp8 - tmp9;
      scratch[5+scratchIndex] += tmp10 + tmp11;   scratch[26+scratchIndex] = tmp10 - tmp11;
      scratch[6+scratchIndex] += tmp12 + tmp13;   scratch[25+scratchIndex] = tmp12 - tmp13;
      scratch[7+scratchIndex] += tmp14 + tmp15;   scratch[24+scratchIndex] = tmp14 - tmp15;
      scratch[8+scratchIndex] += tmp16 + tmp17;   scratch[23+scratchIndex] = tmp16 - tmp17;
      scratch[9+scratchIndex] += tmp18 + tmp19;   scratch[22+scratchIndex] = tmp18 - tmp19;
      scratch[10+scratchIndex] += tmp20 + tmp21;  scratch[21+scratchIndex] = tmp20 - tmp21;
      scratch[11+scratchIndex] += tmp22 + tmp23;  scratch[20+scratchIndex] = tmp22 - tmp23;
      scratch[12+scratchIndex] += tmp24 + tmp25;  scratch[19+scratchIndex] = tmp24 - tmp25;
      scratch[13+scratchIndex] += tmp26 + tmp27;  scratch[18+scratchIndex] = tmp26 - tmp27;
      scratch[14+scratchIndex] += tmp28 + tmp29;  scratch[17+scratchIndex] = tmp28 - tmp29;
      scratch[15+scratchIndex] += tmp30 + tmp31;  scratch[16+scratchIndex] = tmp30 - tmp31;

      if (i == nblocksM1) {
        /* Right edge (last time thru loop). */
        scratchIndex += 16;
        scratch[scratchIndex] += tmp0 - tmp1;
        scratch[1+scratchIndex] += tmp2 - tmp3;
        scratch[2+scratchIndex] += tmp4 - tmp5;
        scratch[3+scratchIndex] += tmp6 - tmp7;
        scratch[4+scratchIndex] += tmp8 - tmp9;
        scratch[5+scratchIndex] += tmp10 - tmp11;
        scratch[6+scratchIndex] += tmp12 - tmp13;
        scratch[7+scratchIndex] += tmp14 - tmp15;      
      }

      xIndex += 16;
      scratchIndex += 16;

    }

    for (i=0; i<nsamps; i++)
      x[i+index] = scratch[i+8];

    return;
  }


  /**
   * Forward lapped orthogonal transform for the length-8 or length-16 case.
   *
   * @param  x  array to be transformed.
   * @param  index  index of first element in array to transform.
   * @param  blockSize  the block size.
   * @param  transLength  the transform length - must be 8 or 16.
   * @param  nblocks  number of blocks.
   * @param  scratch  work array - must be nsamps+32 in length.
   */
  public void lotFwd(float[] x, int index, int blockSize, int transLength, int nblocks, float[] scratch) {

    if (_transformerNative != -1L) {
      this.lotFwd_native(_transformerNative, x, index, blockSize, transLength, nblocks);
      return;
    }

    int i, nsubBlocks;
                
    if (transLength == 8) {
      nsubBlocks = blockSize / 8;
      assert nsubBlocks*8 == blockSize;
      lotFwd8(x, index, nsubBlocks*nblocks, scratch);
      if (blockSize > 8) {
        for (i=0; i<nblocks; i++)
          multiplex8(x, i*blockSize+index, nsubBlocks, scratch);
      }
    } else if (transLength == 16) {
      nsubBlocks = blockSize / 16;
      assert nsubBlocks*16 == blockSize;
      if (c_useJavaVector) {
	throw new RuntimeException("Oops");
	// _javaVectorTransformer.lotFwd16(x, index, nsubBlocks*nblocks, scratch);
      } else {
	lotFwd16(x, index, nsubBlocks*nblocks, scratch);
      }
      if (blockSize > 16) {
        for (i=0; i<nblocks; i++)
          multiplex16(x, i*blockSize+index, nsubBlocks, scratch);
      }
    } else {
      assert false;
    }
  }


  /**
   * Reverse lapped orthogonal transform for the length-8 or length-16 case.
   *
   * @param  x  array to be transformed.
   * @param  index  index of first element in array to transform.
   * @param  blockSize  the block size.
   * @param  transLength  the transform length - must be 8 or 16.
   * @param  nblocks  number of blocks.
   * @param  scratch  work array - must be nsamps+32 in length.
   */
  public void lotRev(float[] x, int index, int blockSize, int transLength, int nblocks, float[] scratch) {

    if (_transformerNative != -1L) {
      this.lotRev_native(_transformerNative, x, index, blockSize, transLength, nblocks);
      return;
    }

    int i, nsubBlocks;
                
    if (transLength == 8) {
      nsubBlocks = blockSize / 8;
      assert(nsubBlocks*8 == blockSize);
      if (blockSize > 8) {
        for (i=0; i<nblocks; i++)
          deMultiplex8(x, i*blockSize+index, nsubBlocks, scratch);
      }
      lotRev8(x, index, nsubBlocks*nblocks, scratch);
    } else if (transLength == 16) {
      nsubBlocks = blockSize / 16;
      assert nsubBlocks*16 == blockSize;
      if (blockSize > 16) {
        for (i=0; i<nblocks; i++)
          deMultiplex16(x, i*blockSize+index, nsubBlocks, scratch);
      }
      lotRev16(x, index, nsubBlocks*nblocks, scratch);
    } else {
      assert false;
    }
  }


  /**
   * Multiplexes values to minimize cache misses when transforming in the
   * second dimension.  For the length-8 case.
   *
   * @param  x  array to multiplex.
   * @param  xBaseIndex  offset in array.
   * @param  nblocks  the number of blocks.
   * @param  scratch  a work array.
   */
  private static void multiplex8(float[] x, int xBaseIndex, int nblocks, float[] scratch) {
          
    int indexScratch0 = 0;
    int indexScratch1 = nblocks;  
    int indexScratch2 = nblocks*2;
    int indexScratch3 = nblocks*3;  
    int indexScratch4 = nblocks*4;  
    int indexScratch5 = nblocks*5;  
    int indexScratch6 = nblocks*6;  
    int indexScratch7 = nblocks*7;  

    int i;

    int xIndex = xBaseIndex;

    for (i=0; i<nblocks; i++) {
                          
      scratch[indexScratch0+i] = x[xIndex+0];
      scratch[indexScratch1+i] = x[xIndex+1];
      scratch[indexScratch2+i] = x[xIndex+2];
      scratch[indexScratch3+i] = x[xIndex+3];
      scratch[indexScratch4+i] = x[xIndex+4];
      scratch[indexScratch5+i] = x[xIndex+5];
      scratch[indexScratch6+i] = x[xIndex+6];
      scratch[indexScratch7+i] = x[xIndex+7];
                    
      xIndex += 8;  
    }

    // Surprisingly this didn't help.
    //~ System.arraycopy(scratch, 0, x, xBaseIndex, nblocks*8);
    for (i=0; i<nblocks*8; i++)
      x[i+xBaseIndex] = scratch[i];

    return;
  }


  /**
   * Demultiplexes values to minimize cache misses when transforming in the
   * second dimension.  For the length-8 case.
   *
   * @param  x  array to demultiplex.
   * @param  xBaseIndex  offset in array.
   * @param  nblocks  the number of blocks.
   * @param  scratch  a work array.
   */
  private void deMultiplex8(float[] x, int xBaseIndex, int nblocks, float[] scratch) {

    int x0Index = 0;
    int x1Index = nblocks;
    int x2Index = nblocks*2;
    int x3Index = nblocks*3;
    int x4Index = nblocks*4;
    int x5Index = nblocks*5;
    int x6Index = nblocks*6;
    int x7Index = nblocks*7;

    int i;
                
    int scratchIndex = 0;
                
    for (i=0; i<nblocks; i++) {
                  
      scratch[scratchIndex+0] = x[xBaseIndex+x0Index+i];
      scratch[scratchIndex+1] = x[xBaseIndex+x1Index+i];
      scratch[scratchIndex+2] = x[xBaseIndex+x2Index+i];
      scratch[scratchIndex+3] = x[xBaseIndex+x3Index+i];
      scratch[scratchIndex+4] = x[xBaseIndex+x4Index+i];
      scratch[scratchIndex+5] = x[xBaseIndex+x5Index+i];
      scratch[scratchIndex+6] = x[xBaseIndex+x6Index+i];
      scratch[scratchIndex+7] = x[xBaseIndex+x7Index+i];
            
      scratchIndex += 8;
    }

    for (i=0; i<nblocks*8; i++)
      x[i+xBaseIndex] = scratch[i];

    return;
  }


  /**
   * Multiplexes values to minimize cache misses when transforming in the
   * second dimension.  For the length-16 case.
   *
   * @param  x  array to multiplex.
   * @param  xBaseIndex  offset in array.
   * @param  nblocks  the number of blocks.
   * @param  scratch  a work array.
   */
  private void multiplex16(float[] x, int xBaseIndex,  int nblocks, float[] scratch) {

    int scratchIndex0 = 0;
    int scratchIndex1 = nblocks;
    int scratchIndex2 = nblocks*2;
    int scratchIndex3 = nblocks*3;
    int scratchIndex4 = nblocks*4;
    int scratchIndex5 = nblocks*5; 
    int scratchIndex6 = nblocks*6; 
    int scratchIndex7 = nblocks*7; 
    int scratchIndex8 = nblocks*8; 
    int scratchIndex9 = nblocks*9; 
    int scratchIndex10 = nblocks*10;
    int scratchIndex11 = nblocks*11; 
    int scratchIndex12 = nblocks*12; 
    int scratchIndex13 = nblocks*13; 
    int scratchIndex14 = nblocks*14; 
    int scratchIndex15 = nblocks*15;  

    int i;
                
    int xIndex = xBaseIndex;        
                
    for (i=0; i<nblocks; i++) {
                
      scratch[scratchIndex0+i] = x[xIndex+0];
      scratch[scratchIndex1+i] = x[xIndex+1];
      scratch[scratchIndex2+i] = x[xIndex+2];
      scratch[scratchIndex3+i] = x[xIndex+3];
      scratch[scratchIndex4+i] = x[xIndex+4];
      scratch[scratchIndex5+i] = x[xIndex+5];
      scratch[scratchIndex6+i] = x[xIndex+6];
      scratch[scratchIndex7+i] = x[xIndex+7];             
      scratch[scratchIndex8+i] = x[xIndex+8];
      scratch[scratchIndex9+i] = x[xIndex+9];
      scratch[scratchIndex10+i] = x[xIndex+10];
      scratch[scratchIndex11+i] = x[xIndex+11];
      scratch[scratchIndex12+i] = x[xIndex+12];
      scratch[scratchIndex13+i] = x[xIndex+13];
      scratch[scratchIndex14+i] = x[xIndex+14];
      scratch[scratchIndex15+i] = x[xIndex+15];
            
      xIndex += 16;
    }

    for (i=0; i<nblocks*16; i++)
      x[i+xBaseIndex] = scratch[i];

    return;
  }


  /**
   * Demultiplexes values to minimize cache misses when transforming in the
   * second dimension.  For the length-16 case.
   *
   * @param  x  array to demultiplex.
   * @param  xBaseIndex  offset in array.
   * @param  nblocks  the number of blocks.
   * @param  scratch  a work array.
   */
  private void deMultiplex16(float[] x, int xBaseIndex, int nblocks, float[] scratch) {

    int x0Index = 0;
    int x1Index = nblocks;
    int x2Index = nblocks*2;
    int x3Index = nblocks*3;
    int x4Index = nblocks*4;
    int x5Index = nblocks*5;
    int x6Index = nblocks*6;
    int x7Index = nblocks*7;
    int x8Index = nblocks*8;
    int x9Index = nblocks*9;
    int x10Index = nblocks*10;
    int x11Index = nblocks*11;
    int x12Index = nblocks*12;
    int x13Index = nblocks*13;
    int x14Index = nblocks*14;
    int x15Index = nblocks*15;
                
    int i;
                
    int scratchIndex = 0;
                
    for (i=0; i<nblocks; i++) {
            
      scratch[scratchIndex+0] = x[xBaseIndex+x0Index+i];
      scratch[scratchIndex+1] = x[xBaseIndex+x1Index+i];
      scratch[scratchIndex+2] = x[xBaseIndex+x2Index+i];
      scratch[scratchIndex+3] = x[xBaseIndex+x3Index+i];
      scratch[scratchIndex+4] = x[xBaseIndex+x4Index+i];
      scratch[scratchIndex+5] = x[xBaseIndex+x5Index+i];
      scratch[scratchIndex+6] = x[xBaseIndex+x6Index+i];
      scratch[scratchIndex+7] = x[xBaseIndex+x7Index+i];
      scratch[scratchIndex+8] = x[xBaseIndex+x8Index+i];
      scratch[scratchIndex+9] = x[xBaseIndex+x9Index+i];
      scratch[scratchIndex+10] = x[xBaseIndex+x10Index+i];
      scratch[scratchIndex+11] = x[xBaseIndex+x11Index+i];
      scratch[scratchIndex+12] = x[xBaseIndex+x12Index+i];
      scratch[scratchIndex+13] = x[xBaseIndex+x13Index+i];
      scratch[scratchIndex+14] = x[xBaseIndex+x14Index+i];
      scratch[scratchIndex+15] = x[xBaseIndex+x15Index+i];

      scratchIndex += 16;
            
    }
          
    for (i=0; i<nblocks*16; i++)
      x[i+xBaseIndex] = scratch[i];

    return;
  }

}
