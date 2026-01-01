
package org.atlas.seiszip;

import java.nio.IntBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

/**
 * This class provides the public API to the SeisPEG compression methods.
 * <p>
 * This class is not thread safe, and must be synchronized externally.
 * @author  Dave Diller and Richard Foy, ported to Java by Jed Diller.
 */

public class SeisPEG {

  private static final Logger LOG =
    Logger.getLogger("org.atlas.seiszip");

  private static int c_nThreads = 4;
  static {
    String s = System.getProperty("org.atlas.seiszip.nthreads");
    if (s != null) {
      try {
        c_nThreads = Integer.parseInt(s);
      } catch (Exception e) {
        c_nThreads = 2;  // Ensure the default.
        LOG.warning("Unable to parse 'org.atlas.seiszip.nthreads' property '" + s + "'");
      }
    }
    LOG.info("SeisPEG is using " + c_nThreads + " threads - reset with org.atlas.seiszip.nthreads=n");
  }

  /**
   * Compression policies, which are used to select the block size and the
   * transform length.
   */
  public enum Policy {FASTEST, MAX_COMPRESSION}

  private static final int FORWARD =  1;
  private static final int REVERSE = -1;

  private static final int IND_COOKIE = 0;
  private static final int IND_DISTORTION = 1;
  private static final int IND_N1 = 2;
  private static final int IND_N2 = 3;
  private static final int IND_VBLOCK_SIZE = 4;
  private static final int IND_HBLOCK_SIZE = 5;
  private static final int IND_VTRANS_LEN = 6;
  private static final int IND_HTRANS_LEN = 7;
  private static final int IND_NBYTES_TRACES = 8;
  private static final int IND_NBYTES_HDRS = 9;
  private static final int IND_FT_GAIN = 10;
  /*package*/ static final int LEN_HDR_INFO = 11;

  private static final int CACHE_SIZE = 32;

  // The first version did not have a cookie.
  private static final short COOKIE_V2 = 30607;  // Small enough to fit in a short.
  private static final short COOKIE_V3 = 30744;  // Small enough to fit in a short.
  private static final short BAD_AMPLITUDE_COOKIE_V2 = 29899;
  private static final short BAD_AMPLITUDE_COOKIE_V3 = 29941;

  private static final int SIZEOF_INT = 4;
  private static final int SIZEOF_FLOAT = 4;
  private static final int SIZEOF_SHORT = 2;
  private static final int SIZEOF_CHAR = 1;


  /**
   * Computes the padded transform length.
   *
   * @param  nsamples number of samples.
   * @param  blockSize blockSize.
   * @return  the padded transform length.
   */
  private static int computePaddedLength(int nsamples, int blockSize) {
           
    if (blockSize == 0) throw new IllegalArgumentException("Block Size is Invalid");	
    // Round up to a multiple of the block size.
    int n = (nsamples / blockSize) * blockSize;
    if (n < nsamples) n += blockSize;
    return n;
  }


  /**
   * Computes the block size.
   *
   * @param  nsamples  the number of samples.
   * @param  policy  the compression policy (fastest or maximum compression).
   * @return  the block size.
   */
  private static int computeBlockSize(int nsamples, Policy policy) {

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

      // Go for the biggest block size that is a multiple of 16;
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
      if (blockSize > 512) blockSize = 512;
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
  private static int computeTransLength(int blockSize, Policy policy) {

    if (policy.equals(Policy.FASTEST)) {
      // If we're looking for speed, we always use the fastest transform.
      return 8;

    } else {
      // Going for maximum compression - always try to use 16.
      if ((blockSize/16)*16 == blockSize) {
        return 16;
      } else {
        // Not a multiple of 16 - forced to use 8.
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
  private static void checkBlockSize(int blockSize) {

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
  private static void checkTransLength(int transLength, int blockSize) {

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
   * Fills a buffer.  Never fear - this method pads with zeros where they are needed
   * for the forward case.
   *
   * @param  direction  the direction (forward or reverse).
   * @param  traces  the sample data.
   * @param  n1  the number of samples.
   * @param  n2  the number of traces.
   * @param  paddedN1  the padded length of the sample axis.
   * @param  paddedN2  the padded length of the trace axis.
   * @param  workBuffer  a work buffer.
   * @param  ftGain  a function-of-time gain, or null if no gain should be applied.
   */
  private static void fillBuffer(int direction, float[][] traces, int n1, int n2,
                                 int paddedN1, int paddedN2, float[] workBuffer, float[] ftGain) {

    int index = 0;
    for (int j=0; j<n2; j++) {
      if (direction == FORWARD) {
        System.arraycopy(traces[j], 0, workBuffer, index, n1);
	if (ftGain != null) SeisPEG.applyFtGain(ftGain, workBuffer, index, n1);
        // Fill the padding with zeros in the n1 direction.
        for (int i=n1; i<paddedN1; i++) workBuffer[index+i] = 0.0F;
      } else {
        System.arraycopy(workBuffer, index, traces[j], 0, n1);
	if (ftGain != null) SeisPEG.removeFtGain(ftGain, traces[j]);
      }
      index += paddedN1;
    }

    if (direction == FORWARD) {
      // Fill the padding with zeros in the n2 direction.
      for (int j=n2; j<paddedN2; j++) {
        for (int i=0; i<paddedN1; i++) workBuffer[index+i] = 0.0F;
        index += paddedN1;
      }
    }
  }


  private int _n1;
  private int _n2;
  private float _distortion;
  private float _ftGainExponent;
  private boolean _ftGainExponentWasStored = false;
  private float[] _ftGain = null;  // May remain null.
  private int _verticalBlockSize;
  private int _horizontalBlockSize;
  private int _verticalTransLength;
  private int _horizontalTransLength;
  private int _paddedN1;
  private int _paddedN2;
  private float[] _workBuffer1 = null;  // Length of _paddedN1 * _paddedN2.
  private float[] _workBlock = null;    // Length of verticalBlockSize*horizontalBlockSize;
  private byte[] _workBuffer2 = null;   // Large enough to hold a decompressed block.
  private int _workBuffer2Size = 0;
  private byte[] _compressedBuffer = null;
  private Transformer _transformer = new Transformer();
  private BlockCompressor _blockCompressor;
  private HdrCompressor _hdrCompressor = new HdrCompressor();
  private float[][] _vecW = null;     // Length of CACHE_SIZE* _paddedN2.
  private float[] _scratch1 = null;
  private float[] _scratch2 = null;
  private TimeTransformer[] _timeTransformers = null;
  private X1Transformer[] _x1Transformers = null;
  private int _nEnsemblesChecked = 0;
  private int[] _hdrInfo = new int[LEN_HDR_INFO];

  private int _traceLength = 0;
  private int _hdrLength = 0;
  private long _nTracesWrittenTotal = 0L;
  private long _nBytesTotal = 0L;

  // No-arg constructor just for testing.
  public SeisPEG() {
    _blockCompressor = new BlockCompressor();
  }


  /**
   * Constructor used for experimenting with Huffman tables.
   *
   * @param  huffCount  the Huffman table value count.
   * @param  n1  the number of trace samples.
   * @param  n2  the maximum number of traces per frame/ensemble.
   * @param  distortion  the allowed distortion.  The value .1 is a good aggressive default.
   * @param  verticalBlockSize  the vertical block size.  Must be a multiple of 8.
   * @param  horizontalBlockSize  the horizontal block size.    Must be a multiple of 8.
   * @param  verticalTransLength  the vertical transform length.  Must be 8 or 16.
   * @param  horizontalTransLength  the horizontal transform length.  Must be 8 or 16.
   */
  /*package*/ SeisPEG(int[] huffCount, int n1, int n2, float distortion,
                      int verticalBlockSize, int horizontalBlockSize,
                      int verticalTransLength, int horizontalTransLength) {

    _blockCompressor = new BlockCompressor(huffCount);
    float ftGainExponent = 0.0F;
    this.init(n1, n2, distortion, ftGainExponent, verticalBlockSize, horizontalBlockSize,
              verticalTransLength, horizontalTransLength, "full");
  }


  /**
   * General purpose constructor.
   *
   * @param  n1  the number of trace samples.
   * @param  n2  the maximum number of traces per frame/ensemble.
   * @param  distortion  the allowed distortion.  The value .1 is good default.
   * @param  verticalBlockSize  the vertical block size.  Must be a multiple of 8.
   * @param  horizontalBlockSize  the horizontal block size.    Must be a multiple of 8.
   * @param  verticalTransLength  the vertical transform length.  Must be 8 or 16.
   * @param  horizontalTransLength  the horizontal transform length.  Must be 8 or 16.
   */
  public SeisPEG(int n1, int n2, float distortion,
                 int verticalBlockSize, int horizontalBlockSize,
                 int verticalTransLength, int horizontalTransLength) {

    _blockCompressor = new BlockCompressor();
    float ftGainExponent = 0.0F;
    this.init(n1, n2, distortion, ftGainExponent, verticalBlockSize, horizontalBlockSize,
              verticalTransLength, horizontalTransLength, "full");
  }


  /**
   * The preferred constructor (computes the best block sizes and transform lengths).
   *
   * @param  n1  the number of trace samples.
   * @param  n2  the maximum number of traces per frame/ensemble.
   * @param  distortion  the allowed distortion.  The value .1 is good default.
   * @param  policy  the compression policy (fastest or maximum compression).
   */
  public SeisPEG(int n1, int n2, float distortion, Policy policy) {

    // Determine some reasonable defaults.
    int verticalBlockSize = computeBlockSize(n1, policy);
    int verticalTransLength = computeTransLength(verticalBlockSize, policy);
    int horizontalBlockSize = computeBlockSize(n2, policy);
    int horizontalTransLength = computeTransLength(horizontalBlockSize, policy);

    _blockCompressor = new BlockCompressor();
    float ftGainExponent = 0.0F;
    this.init(n1, n2, distortion, ftGainExponent, verticalBlockSize, horizontalBlockSize,
              verticalTransLength, horizontalTransLength, "short");
  }


  /**
   * Constructor from existing compressed data (for the uncompression case).
   *
   * @param  compressedData  compressed data.
   * @throws DataCorruptedException 
   */
  public SeisPEG(CompressedData compressedData) throws DataCorruptedException {

    this(compressedData.getData());
  }


  /**
   * Constructor from existing compressed trace data (for the uncompression case).
   *
   * @param  compressedByteData  compressed trace byte data.
   * @throws DataCorruptedException 
   */
  public SeisPEG(byte[] compressedByteData) throws DataCorruptedException {

    SeisPEG.decodeHdr(compressedByteData, _hdrInfo);

    float distortion = Float.intBitsToFloat(_hdrInfo[IND_DISTORTION]);
    int n1 = _hdrInfo[IND_N1];
    int n2 = _hdrInfo[IND_N2];
    int verticalBlockSize = _hdrInfo[IND_VBLOCK_SIZE];
    int horizontalBlockSize = _hdrInfo[IND_HBLOCK_SIZE];
    int verticalTransLength = _hdrInfo[IND_VTRANS_LEN];
    int horizontalTransLength = _hdrInfo[IND_HTRANS_LEN];
    float ftGainExponent = Float.intBitsToFloat(_hdrInfo[IND_FT_GAIN]);

    _blockCompressor = new BlockCompressor();
    this.init(n1, n2, distortion, ftGainExponent, verticalBlockSize, horizontalBlockSize,
              verticalTransLength, horizontalTransLength, "existing");
  }

  /**
   * Does the real work of constructing a SeisPEG.
   *
   * @param  n1  the number of trace samples.
   * @param  n2  the maximum number of traces per frame/ensemble.
   * @param  distortion  the allowed distortion.  The value .1 is good default.
   * @param  ftGainExponent  function-of-time gain exponent.  Commonly reset later by the method setGainExponent().
   * @param  verticalBlockSize  the vertical block size.  Must be a multiple of 8.
   * @param  horizontalBlockSize  the horizontal block size.    Must be a multiple of 8.
   * @param  verticalTransLength  the vertical transform length.  Must be 8 or 16.
   * @param  horizontalTransLength  the horizontal transform length.  Must be 8 or 16.
   * @param  whichConstructor  for logging and debugging.
   */
  private void init(int n1, int n2, float distortion, float ftGainExponent,
                    int verticalBlockSize, int horizontalBlockSize,
                    int verticalTransLength, int horizontalTransLength,
                    String whichConstructor) {

    LOG.fine("SeisPEG.init " + whichConstructor
             + ": n1=" + n1 + " n2=" + n2);

    if (n1 < 1  ||  n2 < 1)
      throw new IllegalArgumentException("The data size must be non-zero");
    if (distortion <= 0.0F)
      throw new IllegalArgumentException("The distortion is less than or equal to zero");

    SeisPEG.checkBlockSize(verticalBlockSize);
    SeisPEG.checkBlockSize(horizontalBlockSize);
    SeisPEG.checkTransLength(verticalTransLength, verticalBlockSize);
    SeisPEG.checkTransLength(horizontalTransLength, horizontalBlockSize);

    _n1 = n1;
    _n2 = n2;
    _distortion = distortion;
    _ftGainExponent = ftGainExponent;
    _verticalBlockSize = verticalBlockSize;
    _horizontalBlockSize = horizontalBlockSize;
    _verticalTransLength = verticalTransLength;
    _horizontalTransLength = horizontalTransLength;
    _paddedN1 = SeisPEG.computePaddedLength(n1, verticalBlockSize);
    _paddedN2 = SeisPEG.computePaddedLength(n2, horizontalBlockSize);

    if (c_nThreads > 1) {
      _timeTransformers = new TimeTransformer[c_nThreads];
      _x1Transformers = new X1Transformer[c_nThreads];
      for (int i=0; i<c_nThreads; i++) {
        _timeTransformers[i] = new TimeTransformer(i, c_nThreads);
        _timeTransformers[i].start();
        _x1Transformers[i] = new X1Transformer(i, c_nThreads);
        _x1Transformers[i].start();
      }
    }
  }


  /**
   * Sets the gain exponent to use for a function-of-time gain.  Apply this gain is important for
   * raw shot records.  A value between 0.8 and 1.5 is recommended.
   *
   * @param  ftGainExponent  function-of-time gain exponent.  A value of 0.0 effectively disables the gain.
   */
  public void setGainExponent(float ftGainExponent) {
    if (_ftGainExponentWasStored  &&  _ftGainExponent != ftGainExponent)
      throw new IllegalStateException("Attempt to change ftGainExponent after it has been stored");
    _ftGainExponent = ftGainExponent;
  }


  /**
   * Manually sets the delta value for quantization (ignoring the distortion value).
   * This is so that distortion does not vary from frame to frame.
   *
   * @param  delta  the value to use for delta in all cases.
   */
  public void setDelta(float delta) {
    _blockCompressor.setDelta(delta);
  }


  /**
   * Returns the allowed distortion level.
   *
   * @return  the allowed distortion level.
   */
  public float getDistortion() {
    return _distortion;
  }


  /**
   * Returns the number of samples per trace.
   *
   * @return  the number of samples per trace.
   */
  public int getSamplesPerTrace() {
    return _n1;
  }


  /**
   * Returns the maximum number of traces per frame.
   *
   * @return  the maximum number of traces per frame.
   */
  public int getMaxTracesPerFrame() {
    return _n1;
  }


  /**
   * Returns the vertical block size.
   */
  public int getVerticalBlockSize() {
    return _verticalBlockSize;
  }


  /**
   * Returns the horizontal block size.
   */
  public int getHorizontalBlockSize() {
    return _horizontalBlockSize;
  }


  /**
   * Returns the vertical transform length.
   */
  public int getVerticalTransLength() {
    return _verticalTransLength;
  }


  /**
   * Returns the horizontal transform length.
   */
  public int getHorizontalTransLength() {
    return _horizontalTransLength;
  }


  /**
   * Returns a buffer of appropriate size for the compressed data.  This
   * buffer may contain extra space (the size of compressed data varies).
   *
   * @return  a buffer of appropriate size for the compressed data.
   */
  public CompressedData compressedBufferAlloc() {

    return new CompressedData(this.compressedByteBufferAlloc(), 0);
  }


  /**
   * Returns a buffer for compressed data (guessing at the size).
   *
   * @return  a buffer for compressed data.
   */
  private byte[] compressedByteBufferAlloc() {

    // This is pure (conservative) guesswork.
    int nbytes;
    if (_distortion > 0.1) {
      // Always at least 2:1 compression?
      nbytes = _paddedN1 * _paddedN2 * 4 / 2;
    } else if (_distortion > 0.01) {
      // Always at least 1:1 compression?
      nbytes = _paddedN1 * _paddedN2 * 4;
    } else {
      // Always at least 1:2 compression?
      nbytes = _paddedN1 * _paddedN2 * 4 * 2;
    }
    return new byte[nbytes];
  }


  /**
   * Returns a buffer of appropriate size for the uncompressed data.
   *
   * @return  a buffer of appropriate size for the uncompressed data.
   */
  public float[][] uncompressedBufferAlloc() {
    return new float[_n2][_n1];
  }


  /**
   * Applies the lapped orthogonal transform to trace data.  This method exists only
   * for the purpose of showing what the transformed data looks like - it is not used
   * as part of the compression process.
   *
   * @param  traces  the trace data.
   * @param  nTraces  the number of live traces.
   */
  public void transform(float[][] traces, int nTraces) {

    if (traces.length > _n2)
      throw new IllegalArgumentException("The size of the data cannot increase");
    if (traces[0].length != _n1)
      throw new IllegalArgumentException("The size of the data cannot vary (1)");

    if (_workBuffer1 == null) _workBuffer1 = new float[_paddedN1 * _paddedN2];

    // This method pads with zeros where they are needed.
    SeisPEG.fillBuffer(FORWARD, traces, _n1, nTraces, _paddedN1, _paddedN2, _workBuffer1, (float[])null);

    this.transform2D(_workBuffer1);

    // This method pads with zeros where they are needed.
    SeisPEG.fillBuffer(REVERSE, traces, _n1, nTraces, _paddedN1, _paddedN2, _workBuffer1, (float[])null);
  }


  /**
   * Applies the lapped orthogonal transform to trace data.  This method exists only
   * for the purpose of showing what the transformed data looks like - it is not used
   * as part of the compression process.
   *
   * @param  paddedTraces  the trace data, padded to a multiple of the block size
   *                       in both directions.  These values are changed by this method.
   */
  private void transform2D(float[] paddedTraces) {

    // Transform in x1 first.
    this.x1Transform(FORWARD, paddedTraces);

    // Transform in time.
    this.timeTransform(FORWARD, paddedTraces);
  }


  /**
   * Checks for the existence of bad amplitudes and complains if they are found.
   *
   * @param  traces  seismic traces.
   * @param  nTraces  the number of live traces.
   */
  private boolean containsBadAmplitude(float[][] traces, int nTraces) {

    _nEnsemblesChecked++;
    for (int j=0; j<nTraces; j++) {
      float[] trace = traces[j];
      for (int i=0; i<trace.length; i++) {
        // This test fails for NaNs as well as dangerously large numbers.
        if (trace[i] < -1.0E15  ||  trace[i] > 1.0E15) {
          LOG.warning("Found uncompressible bad amplitude " + trace[i]
                      + " at ensemble " + _nEnsemblesChecked);
          return true;
        }
      }
    }
    return false;
  }


  /**
   * Compresses a frame/ensemble of traces.  Does not alter the input data.
   *
   * @param  traces  the trace data.
   * @param  nTraces  the number of lives traces.
   * @param  outputData  space for the compressed data.  Must be large enough to hold
   *                     an uncompressed copy of the input data, including non-live traces.
   * @return  the number of bytes of output compressed data.
   * @throws DataCorruptedException 
   */
  public int compress(float[][] traces, int nTraces, byte[] outputData) throws DataCorruptedException {

    if (traces == null  ||  outputData == null)
      throw new IllegalArgumentException("Null arguments are not allowed");
    if (traces.length > _n2)
      throw new IllegalArgumentException("The size of the data cannot increase");
    if (traces[0].length != _n1)
      throw new IllegalArgumentException("The size of the data cannot vary (2)");
    // This isn't a stricly needed policy, but it's smart because we really don't
    // want to recompress with higher distortion.
    if (outputData.length < traces.length*traces[0].length*SIZEOF_FLOAT)
      throw new IllegalArgumentException("The output buffer must be large enough to hold "
                                         + "an uncompressed copy of the input");

    if (this.containsBadAmplitude(traces, nTraces))
      return this.badAmplitudeCompress(traces, nTraces, outputData);

    if (_ftGainExponent != 0.0F) {
      // We want to apply a gain first.
      if (_ftGain == null)
	_ftGain = this.computeFtGain(_n1, _ftGainExponent);
    }

    if (_workBuffer1 == null) _workBuffer1 = new float[_paddedN1 * _paddedN2];

    // This method pads with zeros where they are needed.
    SeisPEG.fillBuffer(FORWARD, traces, _n1, nTraces, _paddedN1, _paddedN2, _workBuffer1, _ftGain);

    int nbytes = this.compress2D(_workBuffer1, _distortion, _ftGainExponent,
                                 outputData, outputData.length);
    _ftGainExponentWasStored = true;

    if (nbytes > outputData.length)  // Should never happen.
      throw new RuntimeException("nbytes > outputData.length");

    if (nbytes == 0) {
      // Output buffer is too small.  Compression actually expanded the data!
      return this.badAmplitudeCompress(traces, nTraces, outputData);
    } else {
      // Everything is okay.
      return nbytes;
    }
  }


  /**
   * "Compresses" a frame/ensemble of data that has a bad amplitude.  Actually just
   * copies the input data to the output data, plus inserting a header.
   *
   * @param  traces  the trace data.
   * @param  nTraces  the number of live traces.
   * @param  compressedData  space for the compressed data.  May be null.
   * @return  a CompressedData object, which will be the input CompressedData object
   *          if the input is non-null and large enough to contain the compressed data,
   *          or a new larger CompressedData object if necessary.
   */
  private CompressedData badAmplitudeCompress(float[][] traces, int nTraces,
                                              CompressedData compressedData) {

    // Get an output buffer that is large enough.
    int size = traces[0].length * nTraces * SIZEOF_FLOAT;
    if (compressedData == null) compressedData = new CompressedData(new byte[size], 0);
    byte[] outputData = compressedData.getData();
    if (outputData.length < size) compressedData = new CompressedData(new byte[size], 0);
    outputData = compressedData.getData();

    // Yes, this is probably slow, but who cares - it better not be happening a lot.
    int index = 0;
    for (int j=0; j<nTraces; j++) {
      float[] trace = traces[j];
      for (int i=0; i<trace.length; i++) {
        int ival = Float.floatToIntBits(trace[i]);
        BlockCompressor.stuffIntInBytes(ival, outputData, index);
        index += SIZEOF_INT;
      }
    }

    short cookie;
    if (_ftGainExponent == 0.0) {
      // No reason to mess up compatibility with existing code if the gain isn't used.
      cookie = BAD_AMPLITUDE_COOKIE_V2;
    } else {
      cookie = BAD_AMPLITUDE_COOKIE_V3;
    }
    // Yes, we store a header in the data.  Then we zero the first samples during decompression.
    SeisPEG.encodeHdr(outputData, cookie, _distortion, _ftGainExponent,
		      _n1, _n2, _verticalBlockSize, _horizontalBlockSize,
                      _verticalTransLength, _horizontalTransLength, size, 0);
    _ftGainExponentWasStored = true;

    compressedData.setDataLength(size);

    return compressedData;
  }


  /**
   * "Compresses" a frame/ensemble of data that has a bad amplitude.  Actually just
   * copies the input data to the output data, plus inserting a header.
   *
   * @param  traces  the trace data.
   * @param  nTraces  the number of lives traces.
   * @param  outputData  space for the compressed data.  Must be large enough to hold
   *                     an uncompressed copy of the input data, including non-live traces.
   * @return  the number of bytes of output data.
   */
  private int badAmplitudeCompress(float[][] traces, int nTraces, byte[] outputData) {

    if (outputData.length < traces.length*traces[0].length*SIZEOF_FLOAT)
      throw new IllegalArgumentException("The output buffer must be large enough to hold "
                                         + "an uncompressed copy of the input");

    // Yes, this is probably slow, but who cares - it better not be happening a lot.
    int index = 0;
    for (int j=0; j<nTraces; j++) {
      float[] trace = traces[j];
      for (int i=0; i<trace.length; i++) {
        int ival = Float.floatToIntBits(trace[i]);
        BlockCompressor.stuffIntInBytes(ival, outputData, index);
        index += SIZEOF_INT;
      }
    }

    int size = traces[0].length * nTraces * SIZEOF_FLOAT;

    short cookie;
    if (_ftGainExponent == 0.0) {
      // No reason to mess up compatibility with existing code if the gain isn't used.
      cookie = BAD_AMPLITUDE_COOKIE_V2;
    } else {
      cookie = BAD_AMPLITUDE_COOKIE_V3;
    }
    // Yes, we store a header in the data.  Then we zero the first samples during decompression.
    SeisPEG.encodeHdr(outputData, cookie, _distortion, _ftGainExponent,
		      _n1, _n2, _verticalBlockSize, _horizontalBlockSize,
                      _verticalTransLength, _horizontalTransLength, size, 0);
    _ftGainExponentWasStored = true;

    return size;
  }


  /**
   * "Uncompresses" a frame/ensemble of data that had a bad amplitude.  Actually just
   * copies the input data to the output data, plus cleaning up where a header was inserted.
   *
   * @param  inData  the input compressed data.
   * @param  inDataLength  the length of the input data.
   * @param  traces  the trace data.
   */
  private void badAmplitudeUncompress(byte[] inData, int inDataLength, float[][] traces) {

    int nTraces = (inDataLength / SIZEOF_FLOAT) / traces[0].length;
    if (nTraces < 1)
      throw new IllegalArgumentException("Input data is impossibly short");
    if (nTraces > traces.length)
      throw new IllegalArgumentException("Input data is impossibly long "
                                         + nTraces + ">" + traces.length);

    // Yes, this is probably slow, but who cares - it better not be happening a lot.
    int index = 0;
    for (int j=0; j<nTraces; j++) {
      float[] trace = traces[j];
      for (int i=0; i<trace.length; i++) {
        int ival = BlockCompressor.stuffBytesInInt(inData, index);
        trace[i] = Float.intBitsToFloat(ival);
        index += SIZEOF_INT;
      }
      if (j == 0) {
        // Don't leave garbage values where the header was.
        for (int i=0; i<Math.min(LEN_HDR_INFO,trace.length); i++) trace[i] = 0.0F;
      }
    }
  }


  /**
   * Compresses a frame/ensemble of traces.  Does not alter the input data.
   *
   * @param  traces  the trace data.  All traces are assumed to be live.
   * @param  compressedData  space for the compressed data.  May be null.
   * @return  a CompressedData object, which will be the input CompressedData object
   *          if the input is non-null and large enough to contain the compressed data,
   *          or a new larger CompressedData object if necessary.
   * @throws DataCorruptedException 
   */
  public CompressedData compress(float[][] traces, CompressedData compressedData) throws DataCorruptedException {

    return this.compress(traces, traces.length, compressedData);
  }



  /**
   * Compresses a frame/ensemble of traces.  Does not alter the input data.
   *
   * @param  traces  the trace data.
   * @param  nTraces  the number of live traces.
   * @param  compressedData  space for the compressed data.  May be null.
   * @return  a CompressedData object, which will be the input CompressedData object
   *          if the input is non-null and large enough to contain the compressed data,
   *          or a new larger CompressedData object if necessary.
   * @throws DataCorruptedException 
   */
  public CompressedData compress(float[][] traces, int nTraces,
                                 CompressedData compressedData) throws DataCorruptedException {

    if (traces.length > _n2)
      throw new IllegalArgumentException("The size of the data cannot increase");
    if (traces[0].length != _n1)
      throw new IllegalArgumentException("The size of the data cannot vary (3)");

    if (this.containsBadAmplitude(traces, nTraces))
      return this.badAmplitudeCompress(traces, nTraces, compressedData);

    if (_ftGainExponent != 0.0F) {
      // We want to apply a gain first.
      if (_ftGain == null)
	_ftGain = this.computeFtGain(_n1, _ftGainExponent);
    }

    if (_workBuffer1 == null) _workBuffer1 = new float[_paddedN1 * _paddedN2];

    // This method pads with zeros where they are needed.
    SeisPEG.fillBuffer(FORWARD, traces, _n1, nTraces, _paddedN1, _paddedN2, _workBuffer1, _ftGain);

    if (compressedData == null) compressedData = this.compressedBufferAlloc();

    int nbytes = 0;
    while (nbytes == 0) {
 
      byte[] outputData = compressedData.getData();
      nbytes = this.compress2D(_workBuffer1, _distortion, _ftGainExponent,
                               outputData, outputData.length);
      _ftGainExponentWasStored = true;

      if (nbytes > outputData.length)  // Should never happen.
        throw new RuntimeException("nbytes > outputData.length");

      if (nbytes == 0) {
        // TODO: Does this code path get exercised?
        LOG.warning("Initial buffer was too small - compression must be "
                    + "repeated with a larger output buffer");
        if (LOG.isLoggable(Level.FINE)) Thread.dumpStack();
        // Refill the work buffer (which gets written over during compression).
        // This method pads with zeros where they are needed.
        SeisPEG.fillBuffer(FORWARD, traces, _n1, nTraces, _paddedN1, _paddedN2, _workBuffer1, _ftGain);
        // Increase the buffer size and try again.
        int newLength = outputData.length * 2;
        compressedData = new CompressedData(new byte[newLength], 0);
        // Control will pass back to the top of the loop to try again.
      } else {
        // The buffer size was OK.
        compressedData.setDataLength(nbytes);
        return compressedData;
      }

    }

    // Control will never reach here, but the compiler doesn't know that.
    return null;
  }


  /**
   * Convenience method to return the difference between the compressed and uncompressed
   * version of the data.  Does not alter the input data samples.
   *
   * @param  traces  the trace data.  All traces are assumed to be live.
   * @return  the difference between the compressed and uncompressed data.
   */
  public float[][] difference(float[][] traces) throws DataCorruptedException {

    CompressedData compressedData = this.compressedBufferAlloc();

    this.compress(traces, compressedData);

    float[][] uncompressedData = uncompressedBufferAlloc();

    this.uncompress(compressedData, uncompressedData);

    for (int j=0; j<traces.length; j++)
      for (int i=0; i<traces[0].length; i++) uncompressedData[j][i] -= traces[j][i];

    return uncompressedData;
  }


  /**
   * Uncompresses a frame/ensemble of traces.
   *
   * @param  compressedByteData  the compressed byte data.
   * @param  compressedDataLength  the length of the compressed data.
   * @param  traces  the output uncompressed data.
   */
  public void uncompress(byte[] compressedByteData, int compressedDataLength, float[][] traces)
  throws DataCorruptedException {

    if (traces.length != _n2  ||  traces[0].length != _n1)
      throw new IllegalArgumentException("The size of the data cannot vary (4) "
                                         + traces.length +" "+ _n2 +" "+
                                         traces[0].length +" "+ _n1);

    if (SeisPEG.badAmplitudeData(compressedByteData)) {
      SeisPEG.decodeHdr(compressedByteData, _hdrInfo);
      int nBytesTraces = _hdrInfo[IND_NBYTES_TRACES];
      this.badAmplitudeUncompress(compressedByteData, nBytesTraces, traces);
      return;
    }

    if (_ftGainExponent != 0.0F) {
      // We want to apply a gain first.
      if (_ftGain == null)
	_ftGain = this.computeFtGain(_n1, _ftGainExponent);
    }

    if (_workBuffer1 == null) _workBuffer1 = new float[_paddedN1 * _paddedN2];

    int iflag = this.uncompress2D(compressedByteData, compressedDataLength, _workBuffer1);
    if (iflag != 0) throw new DataCorruptedException("Compressed data is corrupted");

    SeisPEG.fillBuffer(REVERSE, traces, _n1, _n2, _paddedN1, _paddedN2, _workBuffer1, _ftGain);
  }


  /**
   * Uncompresses a frame/ensemble of traces.
   *
   * @param  compressedData  the compressed data.
   * @param  traces  the output uncompressed data.
   */
  public void uncompress(CompressedData compressedData, float[][] traces)
  throws DataCorruptedException {

    if (traces.length != _n2  ||  traces[0].length != _n1)
      throw new IllegalArgumentException("The size of the data cannot vary (5) "
                                         + traces.length +" "+ _n2 +" "+
                                         traces[0].length +" "+ _n1);

    byte[] inData = compressedData.getData();

    if (SeisPEG.badAmplitudeData(inData)) {
      this.badAmplitudeUncompress(inData, compressedData.getDataLength(), traces);
      return;
    }

    if (_ftGainExponent != 0.0F) {
      // We want to apply a gain first.
      if (_ftGain == null)
	_ftGain = this.computeFtGain(_n1, _ftGainExponent);
    }

    if (_workBuffer1 == null) _workBuffer1 = new float[_paddedN1 * _paddedN2];

    int iflag = this.uncompress2D(inData, compressedData.getDataLength(), _workBuffer1);
    if (iflag != 0) throw new DataCorruptedException("Compressed data is corrupted");

    // This method pads with zeros where they are needed.
    SeisPEG.fillBuffer(REVERSE, traces, _n1, _n2, _paddedN1, _paddedN2, _workBuffer1, _ftGain);
  }


  /**
   * Performs the transform along the sample (first) axis.
   *
   * @param  direction  forward or reverse.
   * @param  paddedTraces  the trace data, padded to a multiple of the block size
   *                       in both directions.
   */
  private void timeTransform(int direction, float[] paddedTraces) {

    if (_timeTransformers != null) {
      this.timeTransformThreaded(direction, paddedTraces);
      return;
    }

    int nblocksVertical = _paddedN1 / _verticalBlockSize;
    // The traces must be padded to an even multiple of the block size.
    assert nblocksVertical * _verticalBlockSize == _paddedN1;

    // An extra two blocks for the LOT.
    if (_scratch1 == null) _scratch1 =  new float[_paddedN1 + _verticalBlockSize];
					  
    for (int j=0; j<_paddedN2; j++) {
      int index = j * _paddedN1;
      if ( direction == FORWARD ) {
        _transformer.lotFwd(paddedTraces, index, _verticalBlockSize, _verticalTransLength,
                            nblocksVertical, _scratch1);
      } else {
        _transformer.lotRev(paddedTraces, index, _verticalBlockSize, _verticalTransLength,
                            nblocksVertical, _scratch1);
      }
    }

    return;
  }

  /**
   * Performs the transform along the sample (first) axis using threads.
   *
   * @param  direction  forward or reverse.
   * @param  paddedTraces  the trace data, padded to a multiple of the block size
   *                       in both directions.
   */
  private void timeTransformThreaded(int direction, float[] paddedTraces) {

    // Start all of the threads.
    for (int i=0; i<_timeTransformers.length; i++) {
      _timeTransformers[i].setData(direction, paddedTraces);
    }

    // Wait for all of the threads to finish.
    for (int i=0; i<_timeTransformers.length; i++)
      _timeTransformers[i].waitForFinish();
  }


  private class TimeTransformer extends Thread {

    private int _myThreadIndex;
    private int _nThreads;
    private Transformer _myTransformer = new Transformer();
    private int _direction;
    private float[] _paddedTraces = null;
    private float[] _myScratch1 = null;

    public TimeTransformer(int threadIndex, int nThreads) {
      _myThreadIndex = threadIndex;
      _nThreads = nThreads;
    }

    public synchronized void setData(int direction, float[] paddedTraces) {
      _direction = direction;
      _paddedTraces = paddedTraces;
      this.notifyAll();  // Wake up the thread - it's got work to do.
    }

    public synchronized void waitForFinish() {
      while (true) {
        if (_paddedTraces == null) return;
        try {
          this.wait();  // Wait for the thread to finish.
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public void run() {

      while (true) {

        synchronized (this) {

          if (_paddedTraces != null) {
            // Got some data.

            int nblocksVertical = _paddedN1 / _verticalBlockSize;
            // The traces must be padded to an even multiple of the block size.
            assert nblocksVertical * _verticalBlockSize == _paddedN1;

            // An extra two blocks for the LOT.
            if (_myScratch1 == null) _myScratch1 =  new float[_paddedN1 + _verticalBlockSize];
					  
            // for (int j=0; j<_paddedN2; j++ ) {
            for (int j=_myThreadIndex; j<_paddedN2; j+=_nThreads) {
              int index = j * _paddedN1;
              if ( _direction == FORWARD ) {
                _myTransformer.lotFwd(_paddedTraces, index, _verticalBlockSize, _verticalTransLength,
                                      nblocksVertical, _myScratch1);
              } else {
                _myTransformer.lotRev(_paddedTraces, index, _verticalBlockSize, _verticalTransLength,
                                      nblocksVertical, _myScratch1);
              }
            }

            _paddedTraces = null;  // Finished.

            // Notify anyone who is waiting of this thread to finish.
            this.notifyAll();

          } else {
            // Wait to be notified of some work.
            try {
              this.wait();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }

          }

        }  // End of synchronized block.

      }  // End of endless loop.

    }

  }  // End of inner class TimeTransformer.


  /**
   * Performs the transform along the trace (second) axis.
   *
   * @param  direction  forward or reverse.
   * @param  paddedTraces  the trace data, padded to a multiple of the block size
   *                       in both directions.
   */
  private void x1Transform(int direction, float[] paddedTraces) {

    if (_x1Transformers != null) {
      this.x1TransformThreaded(direction, paddedTraces);
      return;
    }

    int nblocksHorizontal = _paddedN2 / _horizontalBlockSize;
    // The traces must be padded to an even multiple of the block size.
    assert nblocksHorizontal * _horizontalBlockSize == _paddedN2;

    if (_vecW == null) _vecW = new float[CACHE_SIZE][_paddedN2];
    if (_scratch2 == null) _scratch2 = new float[_paddedN2 + _horizontalBlockSize];

    int nsamps = _paddedN1;

    for (int i=0; i<nsamps; i+=CACHE_SIZE) {

      int n = nsamps - i;
      if (n > CACHE_SIZE) n = CACHE_SIZE;

      for (int m=0; m<_paddedN2; m++) {
        int index = m * _paddedN1 + i;
        for (int l=0; l<n; l++) _vecW[l][m] = paddedTraces[l+index];
      }

      if (direction == FORWARD) {
        for (int l=0; l<n; l++) _transformer.lotFwd(_vecW[l], 0, _horizontalBlockSize,
                                                    _horizontalTransLength,
                                                    nblocksHorizontal, _scratch2);
      } else {
        for (int l=0; l<n; l++) _transformer.lotRev(_vecW[l], 0, _horizontalBlockSize,
                                                    _horizontalTransLength,
                                                    nblocksHorizontal, _scratch2);
      }

      for (int m=0; m<_paddedN2; m++) {
        int index = m * _paddedN1 + i;
        for (int l=0; l<n; l++) paddedTraces[l+index] = _vecW[l][m];
      }

    }

    return;
  }


  /**
   * Performs the transform along the trace (second) axis using threads.
   *
   * @param  direction  forward or reverse.
   * @param  paddedTraces  the trace data, padded to a multiple of the block size
   *                       in both directions.
   */
  private void x1TransformThreaded(int direction, float[] paddedTraces) {

    // Start all of the threads.
    for (int i=0; i<_x1Transformers.length; i++) {
      _x1Transformers[i].setData(direction, paddedTraces);
    }

    // Wait for all of the threads to finish.
    for (int i=0; i<_x1Transformers.length; i++)
      _x1Transformers[i].waitForFinish();
  }


  private class X1Transformer extends Thread {

    private int _myThreadIndex;
    private int _nThreads;
    private Transformer _myTransformer = new Transformer();
    private int _direction;
    private float[] _paddedTraces = null;
    private float[] _myScratch2 = null;
    private float[][] _myVecW = null;     // Length of CACHE_SIZE* _paddedN2.

    public X1Transformer(int threadIndex, int nThreads) {
      _myThreadIndex = threadIndex;
      _nThreads = nThreads;
    }

    public synchronized void setData(int direction, float[] paddedTraces) {
      _direction = direction;
      _paddedTraces = paddedTraces;
      this.notifyAll();  // Wake up the thread - it's got work to do.
    }

    public synchronized void waitForFinish() {
      while (true) {
        if (_paddedTraces == null) return;
        try {
          this.wait();  // Wait for the thread to finish.
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public void run() {

      while (true) {

        synchronized (this) {

          if (_paddedTraces != null) {
            // Got some data.

            int nblocksHorizontal = _paddedN2 / _horizontalBlockSize;
            // The traces must be padded to an even multiple of the block size.
            assert nblocksHorizontal * _horizontalBlockSize == _paddedN2;

            if (_myVecW == null) _myVecW = new float[CACHE_SIZE][_paddedN2];
            if (_myScratch2 == null) _myScratch2 = new float[_paddedN2 + _horizontalBlockSize];

            int nsamps = _paddedN1;

            // for (int i=0; i<nsamps; i+=CACHE_SIZE) {
            for (int i=_myThreadIndex*CACHE_SIZE; i<nsamps; i+=CACHE_SIZE*_nThreads) {

              int n = nsamps - i;
              if (n > CACHE_SIZE) n = CACHE_SIZE;

              for (int m=0; m<_paddedN2; m++) {
                int index = m * _paddedN1 + i;
                for (int l=0; l<n; l++) _myVecW[l][m] = _paddedTraces[l+index];
              }

              if (_direction == FORWARD) {
                for (int l=0; l<n; l++) _myTransformer.lotFwd(_myVecW[l], 0, _horizontalBlockSize,
                                                              _horizontalTransLength,
                                                              nblocksHorizontal, _myScratch2);
              } else {
                for (int l=0; l<n; l++) _myTransformer.lotRev(_myVecW[l], 0, _horizontalBlockSize,
                                                              _horizontalTransLength,
                                                              nblocksHorizontal, _myScratch2);
              }

              for (int m=0; m<_paddedN2; m++) {
                int index = m * _paddedN1 + i;
                for (int l=0; l<n; l++) _paddedTraces[l+index] = _myVecW[l][m];
              }

            }

            _paddedTraces = null;  // Finished.

            // Notify anyone who is waiting of this thread to finish.
            this.notifyAll();

          } else {
            // Wait to be notified of some work.
            try {
              this.wait();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }

          }

        }  // End of synchronized block.

      }  // End of endless loop.

    }

  }  // End of inner class X1Transformer.


  /**
   * Performs the transform along the trace (second) axis.
   *
   * @param  direction  forward or reverse.
   * @param  paddedTraces  the trace data, padded to a multiple of the block size
   *                       in both directions.
   * @param  paddedN1  the padded trace length.
   * @param  paddedN2  the padded traces per frame.
   * @param  distortion  the allowed distortion.
   * @param  verticalBlockSize  the vertical block size.
   * @param  horizontalBlockSize  the horizontal block size.
   * @param  encodedData  the output encoded data.
   * @param  index  the starting index into the encoded data.
   * @param  bufferSize  the size of the encoded data buffer.
   * @return  0 if the buffer is too small, otherwise the number of bytes required
   *          to hold the encoded data.
   */
  private int codeAllBlocks(int direction, float[] paddedTraces, int paddedN1, int paddedN2,
                            float distortion, int verticalBlockSize, int horizontalBlockSize,
                            byte[] encodedData, int index, int bufferSize) {

    // This could be threaded, but it would require us to separate all of the work buffers
    // and the instance of BlockCompressor that is used.

    int nblocksVertical = paddedN1 / verticalBlockSize;
    int nblocksHorizontal = paddedN2 / horizontalBlockSize;

    if (nblocksVertical < 1  ||  nblocksHorizontal < 1)
      throw new RuntimeException("Padded data size is less than 1 block");

    int samplesPerBlock = verticalBlockSize * horizontalBlockSize;
    // A column is a vertical series of blocks.
    int samplesPerColumn = samplesPerBlock * nblocksVertical;

    // We add extra space to ensure that dataEncode doesn't walk off the end.
    if (_workBlock == null)
      _workBlock = new float[verticalBlockSize*horizontalBlockSize];
    if (_workBuffer2 == null) {
      // Byte block large enough to hold a block with a compression ratio of !:1.
      _workBuffer2Size = verticalBlockSize * horizontalBlockSize * 4;
      _workBuffer2 = new byte[_workBuffer2Size];
    }

    int nbytesTotal = 0;
    int i;
	  
    int encodedDataIndex = index;
    int workBlockIndex = 0;
    int dataIndex = 0;
	  
    for (int l=0; l<nblocksHorizontal; l++) {
      for (int k=0; k<nblocksVertical; k++) {

        dataIndex = l * samplesPerColumn + k * verticalBlockSize;
        workBlockIndex = 0;
        int nbytes = 0;

        if (direction == REVERSE) {
          nbytes = BlockCompressor.stuffBytesInInt(encodedData, encodedDataIndex);
          if ((encodedDataIndex-index)+nbytes > bufferSize) {
            if (LOG.isLoggable(Level.FINE))
              LOG.fine("encodedDataIndex-index)+nbytes > bufferSize");
            return 0;  // Overflow!
          }
          int ierr = -1;
          while (ierr != 0) {
            ierr = _blockCompressor.dataDecode(encodedData, SIZEOF_INT+encodedDataIndex,
                                               _workBuffer2, _workBuffer2Size,
                                               samplesPerBlock, _workBlock);
            if (ierr != 0) {
              // Buffer is too small!
              _workBuffer2Size *= 2;
              _workBuffer2 = new byte[_workBuffer2Size];
            }
          }
          encodedDataIndex += nbytes;
        }
	      
        for (int j=0; j<horizontalBlockSize; j++) {

          if (direction == FORWARD) {
            for (i=0; i<verticalBlockSize; i++)
              _workBlock[i+workBlockIndex] = paddedTraces[i+dataIndex];
          } else {
            for (i=0; i<verticalBlockSize; i++)
              paddedTraces[i+dataIndex] = _workBlock[i+workBlockIndex];
          }
          dataIndex += paddedN1;
          workBlockIndex += verticalBlockSize;
        }

        if (direction == FORWARD) {
          // Encode the block.
          int nbytesAvailable = bufferSize - (encodedDataIndex+SIZEOF_INT - index);
		      
          if (nbytesAvailable < 1) {
            if (LOG.isLoggable(Level.FINE))
              LOG.fine("nbytesAvailable < 1");
            return 0;  // Certain Overflow!
          }
		
          nbytes = _blockCompressor.dataEncode(_workBlock, samplesPerBlock,
                                               distortion, encodedData, encodedDataIndex+SIZEOF_INT,
                                               nbytesAvailable);
          if (nbytes == 0) {
            if (LOG.isLoggable(Level.FINE))
              LOG.fine("nbytesAvailable == 0");
            return 0;  // Overflow!
          }
		
          nbytes += SIZEOF_INT;
          // Stuff the size of the block in front of the data.
          BlockCompressor.stuffIntInBytes(nbytes, encodedData, encodedDataIndex);
          encodedDataIndex += nbytes;
        }
        nbytesTotal += nbytes;
      }
    }

    return nbytesTotal;
  }


  /**
   * Stores values in the header of the encoded data.
   *
   * @param  encodedData  the encoded data.
   * @param  hdrInfo  an array of header data.
   * @return  the length of the stored header (same as returned from encodeHdr()).
   */
  private static int updateHdr(byte[] encodedData, int[] hdrInfo) {

    short cookie = (short)hdrInfo[IND_COOKIE];
    float distortion = Float.intBitsToFloat(hdrInfo[IND_DISTORTION]);
    int n1 = hdrInfo[IND_N1];
    int n2 = hdrInfo[IND_N2];
    int verticalBlockSize = hdrInfo[IND_VBLOCK_SIZE];
    int horizontalBlockSize = hdrInfo[IND_HBLOCK_SIZE];
    int verticalTransLength = hdrInfo[IND_VTRANS_LEN];
    int horizontalTransLength = hdrInfo[IND_HTRANS_LEN];
    int nBytesTraces = hdrInfo[IND_NBYTES_TRACES];
    int nBytesHdrs = hdrInfo[IND_NBYTES_HDRS];
    float ftGainExponent = Float.intBitsToFloat(hdrInfo[IND_FT_GAIN]);

    return SeisPEG.encodeHdr(encodedData, cookie, distortion, ftGainExponent, n1,
                             n2, verticalBlockSize, horizontalBlockSize,
                             verticalTransLength, horizontalTransLength,
                             nBytesTraces, nBytesHdrs);
  }


  /**
   * Stores values in the header of the encoded data.
   *
   * @param  encodedData  the encoded data.
   * @param  cookie  the cookie to use (different for bad-amplitude data).
   * @param  distortion  the allowed distortion.
   * @param  ftGainExponent  the function-of-time gain exponent, or 0.0 if none was applied.
   * @param  n1  the number of samples.
   * @param  n2  the number of traces.
   * @param  verticalBlockSize  the vertical block size.
   * @param  horizontalBlockSize  the horizontal block size.
   * @param  verticalTransLength  the vertical transform length.  Must be 8 or 16.
   * @param  horizontalTransLength  the horizontal transform length.  Must be 8 or 16.
   * @param  nBytesTraces  the number of bytes used to store the traces (if traces and headers
   *                       are compressed together), otherwise 0;
   * @param  nBytesHdrs  the number of bytes used to store the headers (if traces and headers
   *                     are compressed together), otherwise 0;
   * @return  the length of the stored header.
   */
  private static int encodeHdr(byte[] encodedData, short cookie, float distortion, float ftGainExponent,
			       int n1, int n2, int verticalBlockSize, int horizontalBlockSize,
                               int verticalTransLength, int horizontalTransLength,
                               int nBytesTraces, int nBytesHdrs) {

    int encodedDataIndex = 0;
    BlockCompressor.stuffShortInBytes(cookie, encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_SHORT;
    int idistortion = Float.floatToIntBits(distortion);
    BlockCompressor.stuffIntInBytes(idistortion, encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;
    BlockCompressor.stuffIntInBytes(n1, encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;
    BlockCompressor.stuffIntInBytes(n2, encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;
    // Assume that block sizes will never exceed 32767.
    if (verticalBlockSize > Short.MAX_VALUE)
      throw new RuntimeException("verticalBlockSize > Short.MAX_VALUE");
    BlockCompressor.stuffShortInBytes((short)verticalBlockSize, encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_SHORT; 
    if (horizontalBlockSize > Short.MAX_VALUE)
      throw new RuntimeException("horizontalBlockSize > Short.MAX_VALUE");
    BlockCompressor.stuffShortInBytes((short)horizontalBlockSize, encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_SHORT;
    // Assume that the transform length will never exceed 127.
    if (verticalTransLength > Byte.MAX_VALUE)
      throw new RuntimeException("verticalTransLength > Byte.MAX_VALUE");
    encodedData[encodedDataIndex] = (byte)verticalTransLength;
    encodedDataIndex += SIZEOF_CHAR;
    if (horizontalTransLength > Byte.MAX_VALUE)
      throw new RuntimeException("horizontalTransLength > Byte.MAX_VALUE");
    encodedData[encodedDataIndex] = (byte)horizontalTransLength;
    encodedDataIndex += SIZEOF_CHAR;
    BlockCompressor.stuffIntInBytes(nBytesTraces, encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;
    BlockCompressor.stuffIntInBytes(nBytesHdrs, encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;
    if (cookie == COOKIE_V3  ||  cookie == BAD_AMPLITUDE_COOKIE_V3) {
      int iftGainExponent = Float.floatToIntBits(ftGainExponent);
      BlockCompressor.stuffIntInBytes(iftGainExponent, encodedData, encodedDataIndex);
      encodedDataIndex += SIZEOF_INT;
    }

    return encodedDataIndex;
  }


  /**
   * Checks the integrity of the data (e.g. for the existence of the cookied).
   *
   * @param  encodedData  the compressed data.
   * @throws DataCorruptedException 
   */
  /*package*/ static void checkDataIntegrity(byte[] encodedData) throws DataCorruptedException {

    int[] hdrInfo = new int[LEN_HDR_INFO];
    SeisPEG.decodeHdr(encodedData, hdrInfo);
  }


  /**
   * Returns true if the encoded data contained bad amplitudes, otherwise false.
   *
   * @param  encodedData  the encoded data.
   * @return  true if the encoded data contained bad amplitudes, otherwise false.
   */
  private static boolean badAmplitudeData(byte[] encodedData)  {

    int cookie = BlockCompressor.stuffBytesInShort(encodedData, 0);
    if (cookie == COOKIE_V2  ||  cookie == COOKIE_V3) {
      return false;
    } else if (cookie == BAD_AMPLITUDE_COOKIE_V2  ||  cookie == BAD_AMPLITUDE_COOKIE_V3) {
      return true;
    } else {
      throw new RuntimeException("Sorry - you are trying to uncompress data from an "
                                 + "unsupported unreleased version of SeisPEG "
                                 + "(contact ddiller@tierrageo.com if this is unacceptable) "
                                 + cookie + " " + COOKIE_V2 + " " + COOKIE_V3);
    }
  }


  /**
   * Fetches the compressed data header information.
   *
   * @param  encodedData  the encoded data.
   * @param  hdrInfo  an array of resulting values.  Inspect code for details.
   * @return  the number of bytes in the encoded header.
   * @throws DataCorruptedException 
   */
  private static int decodeHdr(byte[] encodedData, int[] hdrInfo) throws DataCorruptedException {

    int encodedDataIndex = 0;
    int cookie = BlockCompressor.stuffBytesInShort(encodedData, encodedDataIndex);
    if (cookie != COOKIE_V2  &&  cookie != BAD_AMPLITUDE_COOKIE_V2
	&&  cookie != COOKIE_V3  &&  cookie != BAD_AMPLITUDE_COOKIE_V3)
      throw new DataCorruptedException("This frame has not been compressed with a supported version of SeisPEG or the frame has been corrupted" 
                                 + cookie + " " + COOKIE_V2 + " " + COOKIE_V3);
    encodedDataIndex += SIZEOF_SHORT;
    int idistortion = BlockCompressor.stuffBytesInInt(encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;
    int n1 = BlockCompressor.stuffBytesInInt(encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;  
    int n2 = BlockCompressor.stuffBytesInInt(encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;  
    int verticalBlockSize = BlockCompressor.stuffBytesInShort(encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_SHORT;
    int horizontalBlockSize = BlockCompressor.stuffBytesInShort(encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_SHORT;
    int verticalTransLength = encodedData[encodedDataIndex];
    encodedDataIndex += SIZEOF_CHAR; 
    int horizontalTransLength = encodedData[encodedDataIndex];
    encodedDataIndex += SIZEOF_CHAR; 
    int nBytesTraces = BlockCompressor.stuffBytesInInt(encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;  
    int nBytesHdrs = BlockCompressor.stuffBytesInInt(encodedData, encodedDataIndex);
    encodedDataIndex += SIZEOF_INT;
    int iftGainExponent = 0;
    if (cookie == COOKIE_V3  ||  cookie == BAD_AMPLITUDE_COOKIE_V3) {
      iftGainExponent = BlockCompressor.stuffBytesInInt(encodedData, encodedDataIndex);
      encodedDataIndex += SIZEOF_INT;
    }

    hdrInfo[IND_COOKIE] = cookie;
    hdrInfo[IND_DISTORTION] = idistortion;
    hdrInfo[IND_N1] = n1;
    hdrInfo[IND_N2] = n2;
    hdrInfo[IND_VBLOCK_SIZE] = verticalBlockSize;
    hdrInfo[IND_HBLOCK_SIZE] = horizontalBlockSize;
    hdrInfo[IND_VTRANS_LEN] = verticalTransLength;
    hdrInfo[IND_HTRANS_LEN] = horizontalTransLength;
    hdrInfo[IND_NBYTES_TRACES] = nBytesTraces;
    hdrInfo[IND_NBYTES_HDRS] = nBytesHdrs;
    hdrInfo[IND_FT_GAIN] = iftGainExponent;

    for (int i=IND_N1; i<IND_HTRANS_LEN; i++)
      if (hdrInfo[i] < 1) throw new RuntimeException("Invalid header - data corrupted?");
	  
    return encodedDataIndex;
	  
  }


  /**
   * Performs the forward compression - for testing.
   *
   * @param  paddedTraces  the trace data, padded to a multiple of the block size
   *                       in both directions.
   * @param  n1  number of samples per trace.
   * @param  n2  number of traces per frame.
   * @param  paddedN1  the padded length of the sample axis.
   * @param  paddedN2  the padded length of the trace axis.
   * @param  distortion  the allowed distortion.
   * @param  verticalBlockSize  the vertical block size.  Must be a multiple of 8.
   * @param  horizontalBlockSize  the horizontal block size.    Must be a multiple of 8.
   * @param  verticalTransLength  the vertical transform length.  Must be 8 or 16.
   * @param  horizontalTransLength  the horizontal transform length.  Must be 8 or 16.
   * @param  encodedData  the output encoded data.
   * @param  outputBufferSize  the size of the encoded data buffer.
   * @return  0 if the buffer is too small, otherwise the number of bytes required
   *          to hold the encoded data.
   * @throws DataCorruptedException 
   */
  /*package*/ int compress2D(float[] paddedTraces, int n1, int n2,
                             int paddedN1, int paddedN2, float distortion, float ftGainExponent,
                             int verticalBlockSize, int horizontalBlockSize,
                             int verticalTransLength, int horizontalTransLength,
                             byte[] encodedData, int outputBufferSize) throws DataCorruptedException {

    if (paddedN1 == 0  ||  paddedN2 == 0  ||  verticalBlockSize == 0
        ||  horizontalBlockSize == 0  ||  verticalTransLength == 0
        ||  horizontalBlockSize == 0) throw new IllegalArgumentException("Invalid args");

    _n1 = n1;
    _n2 = n2;
    _paddedN1 = paddedN1;
    _paddedN2 = paddedN2;
    _verticalBlockSize = verticalBlockSize;
    _horizontalBlockSize = horizontalBlockSize;
    _verticalTransLength = verticalTransLength;
    _horizontalTransLength = horizontalTransLength;

    return this.compress2D(paddedTraces, distortion, ftGainExponent, encodedData, outputBufferSize);
  }


  /**
   * Computes the function-of-time gain.
   *
   * @param  samplesPerTrace  samplesPerTrace.
   * @param  ftGainExponent  the gain exponent.
   */ 
  private float[] computeFtGain(int samplesPerTrace, float ftGainExponent) {

    float[] ftGain = new float[samplesPerTrace];
    double sum = 0.0;
    for (int i=0; i<ftGain.length; i++) {
      // We can just assume a nominal sample interval of 4.0.
      // double time = ((double)i * (double)sampleInterval) / 1000.0;
      double time = (i * 4.0) / 1000.0;
      // Add a small number to stabilize.
      ftGain[i] = (float)Math.pow(time, ftGainExponent) + 0.001F;
      sum += ftGain[i];
    }

    // Now we divide by the mean, to make the amplitudes more similar to the original.
    float averageGain = (float)(sum / ftGain.length);
    for (int i=0; i<ftGain.length; i++) {
      ftGain[i] /= averageGain;
    }

    return ftGain;
  }


  /**
   * Applies the function-of-time gain.
   *
   * @param  ftGain  the gain function.
   * @param  buffer  a buffer of data.
   * @param  index  the starting index of the data to gain.
   * @param  n1  the number of samples to gain.
   */ 
  private static void applyFtGain(float[] ftGain, float[] buffer, int index, int n1) {

    for (int i=0; i<n1; i++) buffer[index+i] *= ftGain[i];
  }


  /**
   * Removes the function-of-time gain.
   *
   * @param  ftGain  the gain function.
   * @param  trace  a seismic trace.
   */ 
  private static void removeFtGain(float[] ftGain, float[] trace) {

    for (int i=0; i<trace.length; i++) trace[i] /= ftGain[i];
  }


  /**
   * Performs the forward compression.
   *
   * @param  paddedTraces  the trace data, padded to a multiple of the block size
   *                       in both directions.
   * @param  distortion  the allowed distortion.
   * @param  encodedData  the output encoded data.
   * @param  outputBufferSize  the size of the encoded data buffer.
   * @return  0 if the buffer is too small, otherwise the number of bytes required
   *          to hold the encoded data.
   * @throws DataCorruptedException 
   */
  private int compress2D(float[] paddedTraces, float distortion, float ftGainExponent,
                         byte[] encodedData, int outputBufferSize) throws DataCorruptedException {

    // Transform in x1 first.
    this.x1Transform(FORWARD, paddedTraces);

    // Transform in time.
    this.timeTransform(FORWARD, paddedTraces);

    // Encode the header.
    short cookie;
    if (ftGainExponent == 0.0) {
      // No reason to mess up compatibility with existing code if the gain isn't used.
      cookie = COOKIE_V2;
    } else {
      cookie = COOKIE_V3;
    }
    int nbytesHdr = SeisPEG.encodeHdr(encodedData, cookie, distortion, ftGainExponent,
				      _n1, _n2, _verticalBlockSize, _horizontalBlockSize,
                                      _verticalTransLength, _horizontalTransLength,
                                      0, 0);

    // Encode each transform block individually.
    int nbytesData = this.codeAllBlocks(FORWARD, paddedTraces, _paddedN1, _paddedN2,
                                        distortion, _verticalBlockSize,
                                        _horizontalBlockSize, encodedData, nbytesHdr,
                                        outputBufferSize-nbytesHdr);

    if (nbytesData == 0) {
      // We don't have enough room in the output buffer.  Punt.
      return 0;
    }

    int nBytesTotal = nbytesHdr + nbytesData;

    // Update the header.
    SeisPEG.decodeHdr(encodedData, _hdrInfo);
    _hdrInfo[IND_NBYTES_TRACES] = nBytesTotal;
    _hdrInfo[IND_NBYTES_HDRS] = 0;
    SeisPEG.updateHdr(encodedData, _hdrInfo);
    
    return nBytesTotal;
  }


  /**
   * Performs the reverse uncompression.
   *
   * @param  encodedData  the input encoded data.
   * @param  inputBufferSize  the size of the encoded data buffer.
   * @param  paddedTraces  the output trace data, padded to a multiple of the block size
   *                       in both directions.
   * @return  -1 if the data appears to be corrupted, otherwise 0.
   * @throws DataCorruptedException 
   */
  /*package*/ int uncompress2D(byte[] encodedData, int inputBufferSize,
                               float[] paddedTraces) throws DataCorruptedException {

    // Decode the hdr.
    int nbytesHdr = SeisPEG.decodeHdr(encodedData, _hdrInfo);

    _n1 = _hdrInfo[IND_N1];
    _n2 = _hdrInfo[IND_N2];
    _verticalBlockSize = _hdrInfo[IND_VBLOCK_SIZE];
    _horizontalBlockSize = _hdrInfo[IND_HBLOCK_SIZE];
    _verticalTransLength = _hdrInfo[IND_VTRANS_LEN];
    _horizontalTransLength = _hdrInfo[IND_HTRANS_LEN];	
						   
    _paddedN1 = SeisPEG.computePaddedLength(_n1, _verticalBlockSize);
    _paddedN2 = SeisPEG.computePaddedLength(_n2, _horizontalBlockSize);
						   
    // Decode each transform block individually.
    // We don't care about the size of the output buffer, since it's
    // actually an input buffer.

    float distortion = 0.0F; /*NOT USED*/

    int nbytes = this.codeAllBlocks(REVERSE, paddedTraces, _paddedN1, _paddedN2,
                                    distortion, _verticalBlockSize,
                                    _horizontalBlockSize, encodedData, nbytesHdr,
                                    inputBufferSize-nbytesHdr);
    if (nbytes == 0) return -1;

    // Transform in time.
    this.timeTransform(REVERSE, paddedTraces);

    // Transform in x1 last.
    this.x1Transform(REVERSE, paddedTraces);

    return 0;
  }


  /**
   * Returns the appropriate number of bytes for the output buffer for compressed
   * trace headers.
   *
   * @param  maxHdrLength  the maximum length of any header (number of ints).
   * @param  maxTracesPerFrame  the maximum number of trace per frame.
   * @return  the appropriate number of bytes for the output buffer for compressed
   *          trace headers.
   */
  public static int getOutputHdrBufferSize(int maxHdrLength, int maxTracesPerFrame) {

    return HdrCompressor.getOutputBufferSize(maxHdrLength, maxTracesPerFrame);
  }


  /**
   * Compresses a 2D array of trace headers.
   *
   * @param  hdrs  the trace headers.
   * @param  hdrLength  the length of each header (may be less than the array length).
   * @param  nTraces  the number of live trace headers.
   * @param  encodedBytes  the output compressed headers.  To determine the necessary
   *                        size use getOutputBufferSize().
   * @return  the number of bytes used to encode the data.
   */
  public int compressHdrs(int[][] hdrs, int hdrLength, int nTraces, byte[] encodedBytes) {

    return _hdrCompressor.compress(hdrs, hdrLength, (float[][])null, nTraces, encodedBytes, 0);
  }


  /**
   * Convenience method for JavaSeis format.
   * Compresses a frame/ensemble of traces and trace headers.  Does not alter the input data.
   *
   * @param  traces  the trace data.
   * @param  nTraces  the number of lives traces.
   * @param  hdrIntBuffer  an IntBuffer that contains the trace headers.
   * @param  hdrLength  the length of each header.
   * @param  outputData  space for the compressed data.  Must be large enough to hold
   *                     an uncompressed copy of the input data, including non-live traces,
   *                     and the space required for headers (use HdrCompressor.getOutputBufferSize()).
   * @return  the number of bytes of output compressed data.
   * @throws DataCorruptedException 
   */
  public int compress(float[][] traces, int nTraces, IntBuffer hdrIntBuffer, int hdrLength,
                      byte[] outputData) throws DataCorruptedException {

    int nBytesTraces = this.compress(traces, nTraces, outputData);
    int nBytesHdrs = _hdrCompressor.compress(hdrIntBuffer, hdrLength, traces, nTraces,
                                             outputData, nBytesTraces);

    // Update the header.
    SeisPEG.decodeHdr(outputData, _hdrInfo);
    _hdrInfo[IND_NBYTES_TRACES] = nBytesTraces;
    _hdrInfo[IND_NBYTES_HDRS] = nBytesHdrs;
    SeisPEG.updateHdr(outputData, _hdrInfo);

    return nBytesTraces + nBytesHdrs;
  }


  /**
   * Uncompresses a 2D array of trace headers.
   *
   * @param  encodedBytes  the input compressed headers (from the compress() method).
   * @param  nBytes  the number of input compressed bytes.
   * @param  hdrs  the output trace headers.
   * @return  the number of live output trace headers.
   */
  public int uncompressHdrs(byte[] encodedBytes, int nBytes, int[][] hdrs)
  throws DataCorruptedException, DataFormatException {

    return _hdrCompressor.uncompress(encodedBytes, 0, nBytes, hdrs, (float[][])null);
  }


  /**
   * Convenience method for JavaSeis format.
   * Uncompresses a frame/ensemble of traces and trace headers.
   *
   * @param  compressedByteData  the compressed byte data.
   * @param  compressedDataLength  the length of the compressed data.
   * @param  traces  the output uncompressed data.
   * @param  hdrIntBuffer  the output trace headers.
   * @return  the number of live output trace headers.
   */
  public int uncompress(byte[] compressedByteData, int compressedDataLength,
                        float[][] traces, IntBuffer hdrIntBuffer)
  throws DataCorruptedException, DataFormatException {

    SeisPEG.decodeHdr(compressedByteData, _hdrInfo);

    this.uncompress(compressedByteData, compressedDataLength, traces);
    int nBytesTraces = _hdrInfo[IND_NBYTES_TRACES];
    int nBytesHdrs = _hdrInfo[IND_NBYTES_HDRS];
    return _hdrCompressor.uncompress(compressedByteData, nBytesTraces, nBytesHdrs,
                                     hdrIntBuffer, traces);
  }

  /**
   * Updates compression statistics that can be used to compute compression ratios.
   * This method accumulates the values that are passed to it.
   *
   * @param  nTracesWritten  the number of traces just written.
   * @param  traceLength  the trace length (number of floats).
   * @param  hdrLength  the trace header length (number of ints).
   * @param  nBytes  the number of bytes used to compress the traces and headers.
   */
  public void updateStatistics(int nTracesWritten, int traceLength, int hdrLength,
                               int nBytes) {

    _traceLength = traceLength;
    _hdrLength = hdrLength;
    _nTracesWrittenTotal += nTracesWritten;
    _nBytesTotal += nBytes;
  }

  /**
   * Returns the number of traces that were written.
   *
   * @return  the number of traces that were written.
   */
  public long countTracesWritten() {
    return _nTracesWrittenTotal;
  }

  /**
   * Returns the apparent compression ratio, including both traces and headers.
   *
   * @return  the apparent compression ratio, including both traces and headers.
   */
  public double getCompressionRatio() {

    return (double)(_traceLength * _nTracesWrittenTotal * 4 + _hdrLength * _nTracesWrittenTotal * 4)
      / (double)_nBytesTotal;
  }

}
