
package org.atlas.seiszip;

import java.nio.ByteOrder;
import java.util.logging.Logger;

/**
 * Transformed block compression using run-length coding and Huffman coding.
 *
 * @author  Dave Diller and Richard Foy, ported to Java by Jed Diller.
 */

public class BlockCompressor {

  private static final Logger LOG =
    Logger.getLogger("org.atlas.seiszip");

  private static boolean c_littleEndian = true;
  static {
    if (ByteOrder.LITTLE_ENDIAN.equals(ByteOrder.nativeOrder())) {
      c_littleEndian = true;
    } else {
      c_littleEndian = false;
    }
  }

  private static final int SIZEOF_INT = 4;
  private static final int SIZEOF_CHAR = 1;
  private static final int SIZEOF_FLOAT = 4;
  private static final float CPDF = 0.26F;               /* quantization factor */

  // Faster than Math.round().
  private static int nint(double a) {
    return (a > 0.0) ? (int)(a+0.5F) : (int)(a-0.5F);
  }


  /*package*/ static void stuffIntInBytes( int ival, byte[] bvals, int offset) {

    bvals[offset]   = (byte)(ival >> 24);
    bvals[1+offset] = (byte)((ival << 8) >> 24);
    bvals[2+offset] = (byte)((ival << 16) >> 24);
    bvals[3+offset] = (byte)((ival << 24) >> 24);
  }


  // This method violates endianess neutrality.
  /*package*/ static void copyIntInBytes_DONT_USE( int ival, byte[] bvals, int offset) {

    if (c_littleEndian) {
      bvals[3+offset] = (byte)(ival >> 24);
      bvals[2+offset] = (byte)((ival << 8) >> 24);
      bvals[1+offset] = (byte)((ival << 16) >> 24);
      bvals[offset]   = (byte)((ival << 24) >> 24);	   
    } else {
      stuffIntInBytes(ival, bvals, offset);
    }
  }


  // This method violates endianess neutrality.
  /*package*/ static int copyBytesToInt_DONT_USE( byte[] bvals, int offset) {

    if (c_littleEndian) {
      int i0 = ((bvals[offset+3])) << 24;
      int i1 = (unsignedByte(bvals[offset+2])) << 16;
      int i2 = (unsignedByte(bvals[offset+1])) << 8;
      int i3 = unsignedByte(bvals[offset]);
      return i0 + i1 + i2 + i3;	
    } else {
      return stuffBytesInInt(bvals, offset);
    }
  }


  /*package*/ static int unsignedByte( byte i ) {

    if ( i < 0 ) {
      return i + 256;
    } else {
      return i;
    }
  }
		  

  /*package*/ static int stuffBytesInInt(byte[] bvals, int index) {

    int i0 = ((unsignedByte(bvals[index]))) << 24;
    int i1 = ((unsignedByte(bvals[1+index]))) << 16;
    int i2 = ((unsignedByte(bvals[2+index]))) << 8;
    int i3 = unsignedByte(bvals[3+index]);	
		
    return i0 + i1 + i2 + i3;
  }


  /*package*/ static void stuffShortInBytes( short uval, byte[] bvals, int index) {

    int ival = uval;
    bvals[index] = (byte)((ival << 16) >> 24);
    bvals[1+index] = (byte)((ival << 24) >> 24);	
  }


  /*package*/ static int stuffBytesInShort(byte[] bvals, int index) {

    int i0 = (short)(((bvals[index])) << 8);
    int i1 = (short)unsignedByte(bvals[1+index]);
		
    return (i0 + i1);
  }
	
  /*package*/ static int unsignedShort( short i ) {

    if ( i < 0 ) {
      return i + 65536;
    } else {
      return i;
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
  private static float computeDelta( float[] x, int n, float distortion ) {

    if (Transformer.c_integrityTest) {
      return 1.0F;
    }

    double blockVar;
    float delta, quarterDelta, mquarterDelta;
    int i, n2;

    /* Get a low approximation of delta. */
    blockVar = 0.0;
    for ( i=0; i<n; i++ ) blockVar += (x[i]*x[i]);

    blockVar /= n;
    blockVar = Math.sqrt(blockVar);

    delta = distortion * (float)blockVar / CPDF;

    /* Compute delta without the near-zero samples. */
    quarterDelta = delta * 0.25F;
    mquarterDelta = -quarterDelta;
    blockVar = 0.0;
    n2 = 0;
    /* Every 8th sample should be OK for this application. */
    /* Why not use them all - should make very little difference. */
    /* for ( i=0; i<n; i+=8 ) { */
    for ( i=0; i<n; i++ ) {
      if ( x[i] > quarterDelta ) {
        n2++;
        blockVar += (x[i]*x[i]);
      } else if ( x[i] < mquarterDelta ) {
        n2++;
        blockVar += (x[i]*x[i]);
      }
    }

    if ( n2 > 0 ) {
      blockVar /= n2;
      blockVar = Math.sqrt(blockVar);
      delta = distortion * (float)blockVar / CPDF;
    } else {
      delta = 1.0F;
    }

    return delta;
  }


  /**
   * Applies quantization.
   *
   * @param  x transformed samples.
   * @param  n number of samples.
   * @param  delta quantization delta.
   * @param  ix output quantized samples.
   */
  private static void quantize( float[] x, int n, float delta, int[] ix ) {

    int i;
    float temp;
		
    delta = 1.0F / delta;

    for ( i=0; i<n; i++ ) {
      temp = x[i] * delta;
      // Math.round() is dreadfully slow.
      // ix[i] = (int)Math.round( temp );
      // Is is faster to avoid the function call?  Seems like it!
      // ix[i] = (int)nint(temp );
      if (temp > 0.0) {
        ix[i] = (int)(temp + 0.5F);
      } else {
        ix[i] = (int)(temp - 0.5F);
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

    nwords = n / SIZEOF_INT;
		
    for (i=nbytes=0; i<nwords;) {

      if ( quantdata[i] == 0 ) {
        /* Begin a run of zeros. */
        istart = i;
        i++;
        /* There is a non-zero dummy value at the end of the buffer. */
        while ( quantdata[i] == 0 ) i++;

        while ( i > istart+65535 ) {
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
            stuffShortInBytes(si, encodedChars, nbytes);	
            nbytes += 2;
          } else {
            /* It's a huge integer. */
            encodedChars[nbytes] = (byte)255;
            nbytes++;
            stuffIntInBytes(quantdata[i], encodedChars, nbytes); 
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
            stuffShortInBytes(si, encodedChars, nbytes);	
            nbytes += 2;
          } else {
            /* It's a huge negative integer. */
            encodedChars[nbytes] = (byte)255;
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


  private HuffCoder _huffCoder;
  private int[] _idata = new int[1024];            // Just an initial guess on the size.
  private byte[] _huffchars = new byte[1024*4];    // Just an initial guess on the size.
  private float _manualDelta = Float.MAX_VALUE;


  /**
   * Creates a new block compressor with the default Huffman value count.
   */
  /*package*/ BlockCompressor() {

    this(HuffCoder.c_huffCount);
  }

  /**
   * Creates a new block compressor with the custom Huffman value count.
   *
   * @param  huffTable  a Huffman value count of length 257.
   */
  /*package*/ BlockCompressor(int[] huffTable) {

    _huffCoder = new HuffCoder(huffTable);
  }


  /**
   * Manually sets the delta value for quantization (ignoring the distortion value).
   * This is so that distortion does not vary from frame to frame.
   *
   * @param  delta  the value to use for delta in all cases.
   */
  /*package*/ void setDelta(float delta) {
    _manualDelta = delta;
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

    int i, j, iend, ival;
    short si;

    for ( i=j=0; i<nbytes; ) {

      // Function calls in tight loops are hurting performance.
      // int ihuffchar = unsignedByte(huffchars[i]);
      int ihuffchar = huffchars[i];
      if (ihuffchar < 0) ihuffchar += 256;

      if ( ihuffchar > 0  &&  ihuffchar < 101 ) {
        iend = j + ihuffchar;
        for ( ; j<iend; j++ ) quantdata[j] = 0.0F;
        i++;
      } else if ( ihuffchar == 105 ) {
        i++;
        // ival = unsignedByte(huffchars[i]);
        ival = huffchars[i];
        if (ival < 0) ival += 256;
        iend = j + ival;
        for ( ; j<iend; j++ ) quantdata[j] = 0.0F;
        i++;
      } else if ( ihuffchar > 106  &&  ihuffchar < 255 ) {
        // quantdata[j++] = ((float)unsignedByte(huffchars[i++]) - 180.0F) * delta;
        quantdata[j++] = (ihuffchar - 180.0F) * delta;
        i++;
      } else {
        if ( huffchars[i] == 101 ) {
          i++;
          // ival = unsignedByte(huffchars[i]);
          ival = huffchars[i];
          if (ival < 0) ival += 256;
          quantdata[j++] = ival * delta;
          i++;
        } else if ( huffchars[i] == 102 ) {
          i++;
          // ival = unsignedByte(huffchars[i]);
          ival = huffchars[i];
          if (ival < 0) ival += 256;
          quantdata[j++] = (-(float)ival) * delta;
          i++;
        } else if ( huffchars[i] == 103 ) {
          i++;
          // ival = unsignedShort(stuffBytesInShort(huffchars, i));
          ival = stuffBytesInShort(huffchars, i);
          if (ival < 0) ival += 65536;
          quantdata[j++] = ival * delta;
          i += 2;
        } else if ( huffchars[i] == 104 ) {
          i++;
          // ival = unsignedShort(stuffBytesInShort(huffchars, i));
          ival = stuffBytesInShort(huffchars, i);
          if (ival < 0) ival += 65536;
          quantdata[j++] = (-(float)ival) * delta;
          i += 2;
        } else if ( huffchars[i] == 106 ) {
          i++;
          // ival = unsignedShort(stuffBytesInShort(huffchars, i));
          ival = stuffBytesInShort(huffchars, i);
          if (ival < 0) ival += 65536;
          iend = j + ival;
          for ( ; j<iend; j++ ) quantdata[j] = 0.0F;
          i += 2;
        } else if ( ihuffchar == 255 ) {
          i++;
          ival = stuffBytesInInt(huffchars, i);
          quantdata[j++] = (ival) * delta;
          i += 4;
        } else {
          LOG.warning("HuffTableDecode: bad character encountered at element " + 
                      (int)huffchars[i]);
          // Just punt - don't know what else to do.  This may actually happen,
          // because data gets corrupted.
          // TODO: Perhaps we should zero the data.
          return;
        }
      }
    }
    return;
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
  /*package*/ int dataEncode(float[] data, int nsamps, float distortion,
                             byte[] encodedData, int index, int outputBufferSize) {

    int i;
    int nbytesHdrExpected = SIZEOF_INT * 2;
		
    if (outputBufferSize < nbytesHdrExpected+1 ) return 0;

    float delta;
    if (_manualDelta != Float.MAX_VALUE) {
      // Delta was set manually.
      delta = _manualDelta;
    } else {
      delta = computeDelta( data, nsamps, distortion );
    }

    /// Need extra samples to set nNonZero.
    while (_idata.length < nsamps+2)
      _idata = new int[_idata.length*2];

    BlockCompressor.quantize(data, nsamps, delta, _idata);

    for ( i=nsamps-1; i>=0; i-- ) if ( _idata[i] != 0 ) break;
    int nNonZero = i + 1;
    // This must be a multiple of 2.
    if ( (nNonZero >> 1) << 1 < nNonZero ) nNonZero++;
   
    /* Load delta. */
    // int idelta = (int)delta;
    int idelta = Float.floatToIntBits(delta);
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
    while (_huffchars.length < nbytes*2)
      _huffchars = new byte[_huffchars.length*2];

    nbytes = BlockCompressor.runLengthEncode(_idata, nbytes, _huffchars);

    /* Sanity check. */
    if (nbytesHdr != nbytesHdrExpected) assert nbytes != 0;

    /* Huffman encode. */
    nbytes = _huffCoder.huffEncode(_huffchars, nbytes, encodedData, index,
                                   outputBufferSize-nbytesHdr );	 

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
  /*package*/ int dataDecode(byte[] encodedData, int index, byte[] workBuffer,
                             int workBufferSize, int nsamps, float[] data) {

    /* Unload delta. */
    float delta;
    int idelta = stuffBytesInInt(encodedData, index);
    // delta = (float )idelta;
    delta = Float.intBitsToFloat(idelta);
    index += SIZEOF_FLOAT;
	
    /* Unload the number of non-zero samples. */
    int nNonZero = stuffBytesInInt(encodedData, index);
    index += SIZEOF_INT;
		
    /* Huffman decode. */
    int nbytes = _huffCoder.huffDecode(encodedData, index, workBuffer, workBufferSize);
		
    if (nbytes == -1) return -1;

    /* Run-length decode and dequantize. */
    BlockCompressor.runLengthDecodeDequant(workBuffer, nbytes, delta, data);
	  
    /* Fill in the zeros. */
    /* We know that nNonZero is a multiple of 2. */
    for (int i=0; i<nsamps-nNonZero; i++) data[i+nNonZero] = 0.0F;

    return 0;
  }

}
