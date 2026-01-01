
package org.atlas.seiszip;

import java.nio.IntBuffer;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * This class compresses trace headers.  It does so by transposing the headers
 * and then run-length encoding them.  It encodes runs of constant length or
 * ascending or descending values.
 */

public class HdrCompressor {

  private static final Logger LOG =
    Logger.getLogger("org.atlas.seiszip");

  private static int[] c_candidateValues = HdrCompressor.getCandidateUniqueValues();

  private static final int SIZEOF_INT = 4;

  private static final int IND_COOKIE = 0;
  private static final int IND_SYM_CONST = 1;        // Run of constant values.
  private static final int IND_SYM_ASCEND = 2;       // Run of mono ascending values.
  private static final int IND_SYM_DESCEND = 3;      // Run of mono descending values.
  private static final int IND_SYM_DELTA = 4;        // Run of delta values.
  private static final int IND_SYM_FLOATS = 5;       // Run of float delta values.
  private static final int IND_OUT_COUNT = 6;
  private static final int IND_HDR_LENGTH= 7;
  private static final int IND_NTRACES = 8;
  private static final int IND_REMUTE = 9;
  /*package*/ static final int HDR_LENGTH = 10;

  private static final int OLD_COOKIE1 = 6821923;
  private static final int COOKIE      = 1215649;

  // This is the approximate minimum value that an int will have if it contains positive float bits.
  private static final int MIN_POS_FLOAT_BITS =  700000000;
  // This is the approximate maximum value that an int will have if it contains negative float bits.
  private static final int MAX_NEG_FLOAT_BITS = -700000000;

  // Compression level of 6 in java.util.zip seems to give compression ratios near the max and
  // it isn't as slow as MAX_COMPRESSION (9).
  private static final int BEST_ZIP_LEVEL = 6;

  private int[] _transposedHdrs;
  private int[] _runLengthEncodedValues;
  private byte[] _zipWorkBuffer;
  private int[] _singleHdrWork;
  private int[] _uniqueValues = new int[5];
  private Deflater _zipDeflater = new Deflater(BEST_ZIP_LEVEL);
  private Inflater _zipInflater = new Inflater();


  /**
   * Creates a new HdrCompressor.
   */
  public HdrCompressor() {
  }


  /**
   * Returns the appropriate number of bytes for the output buffer for compressed
   * trace headers.
   *
   * @param  maxHdrLength  the maximum length of any header (number of ints).
   * @param  maxFrameSize  the maximum number of trace headers per frame/ensemble.
   * @return  the appropriate number of bytes for the output buffer for compressed
   *          trace headers.
   */
  public static int getOutputBufferSize(int maxHdrLength, int maxFrameSize) {

    if (maxHdrLength < 1  ||  maxFrameSize < 1)
      throw new IllegalArgumentException("Header length of headers per frame is invalid");

    // We add 1 to each header to ensure space for the remute index.
    return ((maxHdrLength+1) * maxFrameSize + HDR_LENGTH) * SIZEOF_INT;
  }

  /**
   * Ensures that the internal header work buffers are created and of appropriate size.
   *
   * @param  hdrs  some trace headers, or null if hdrIntBuffer is used.
   * @param  hdrIntBuffer  an IntBuffer of headers, or null if hdrs is used.
   * @param  hdrLength  the length of each header (may be less than the array length).
   */
  private void ensureHdrBuffers(int[][] hdrs, IntBuffer hdrIntBuffer, int hdrLength) {

    if (hdrLength < 1)
      throw new IllegalArgumentException("Header length is nonsensical");

    int lenHdr0 = 0;
    int nIntsMin;
    // We add 1 to each header to ensure space for the remute index.
    if (hdrs != null) {
      lenHdr0 = hdrs[0].length;
      nIntsMin = hdrs.length * (hdrs[0].length+1) + HDR_LENGTH;
    } else {
      int nTraces = hdrIntBuffer.capacity() / hdrLength;
      nIntsMin = hdrIntBuffer.capacity() + HDR_LENGTH + nTraces;
    }

    nIntsMin += 1024;  // Throw in some extra for good measure.

    int lenHdr = Math.max(lenHdr0, hdrLength);
    lenHdr += 128;  // Throw in some extra for good measure.

    if (_singleHdrWork == null  ||  _singleHdrWork.length < lenHdr)
      _singleHdrWork = new int[lenHdr];
    if (_transposedHdrs == null  ||  _transposedHdrs.length < nIntsMin)
      _transposedHdrs = new int[nIntsMin];
  }


  /**
   * Ensures that the internal Zip work buffers are created and of appropriate size.
   *
   * @param  hdrs  some trace headers, or null if hdrIntBuffer is used.
   * @param  hdrIntBuffer  an IntBuffer of headers, or null if hdrs is used.
   */
  private void ensureZipBuffers(int[][] hdrs, IntBuffer hdrIntBuffer) {

    int nIntsMin;
    // We add 1 to each header to ensure space for the remute index.
    if (hdrs != null) {
      nIntsMin = hdrs.length * (hdrs[0].length+1) + HDR_LENGTH;
    } else {
      // We have no choice but to double the capacity, because we don't
      // know how many traces there really are.
      nIntsMin = hdrIntBuffer.capacity()*2 + HDR_LENGTH;
    }

    nIntsMin += 1024;  // Throw in some extra for good measure.

    if (_runLengthEncodedValues == null  ||  _runLengthEncodedValues.length < nIntsMin)
      _runLengthEncodedValues = new int[nIntsMin];
    if (_zipWorkBuffer == null  ||  _zipWorkBuffer.length < nIntsMin*SIZEOF_INT)
      _zipWorkBuffer = new byte[nIntsMin*SIZEOF_INT];
  }


  /**
   * Compresses a 2D array of trace headers.
   *
   * @param  hdrs  the trace headers.
   * @param  hdrLength  the length of each header (may be less than the array length).
   * @param  traces  the trace samples used to determine the mute/remute, or null if no
   *                 mute/remute is desired.
   * @param  nTraces  the number of live trace headers.
   * @param  encodedBytes  the output compressed headers.  To determine the necessary
   *                        size use getOutputBufferSize().
   * @param  offset  the byte offset to begin in the output compressed headers.
   * @return  the number of bytes used to encode the data.
   */
  public int compress(int[][] hdrs, int hdrLength, float[][] traces, int nTraces,
                      byte[] encodedBytes, int offset) {

    return this.private_compress(hdrs, null, hdrLength, traces, nTraces,
                                 encodedBytes, offset);
  }


  /**
   * Compresses a 2D array of trace headers.
   *
   * @param  hdrIntBuffer  an IntBuffer that contains the trace headers.
   * @param  hdrLength  the length of each header.
   * @param  traces  the trace samples used to determine the mute/remute, or null if no
   *                 mute/remute is desired.
   * @param  nTraces  the number of live trace headers.
   * @param  encodedBytes  the output compressed headers.  To determine the necessary
   *                        size use getOutputBufferSize().
   * @param  offset  the byte offset to begin in the output compressed headers.
   * @return  the number of bytes used to encode the data.
   */
  public int compress(IntBuffer hdrIntBuffer, int hdrLength, float[][] traces, int nTraces,
                      byte[] encodedBytes, int offset) {

    return this.private_compress(null, hdrIntBuffer, hdrLength, traces, nTraces,
                                 encodedBytes, offset);
  }


  /**
   * Compresses a 2D array of trace headers.
   *
   * @param  hdrs  the trace headers, or null if hdrIntBuffer is used.
   * @param  hdrIntBuffer  an IntBuffer that contains the trace headers, or null if hdrs is used.
   * @param  hdrLength  the length of each header.
   * @param  traces  the trace samples used to determine the mute/remute, or null if no
   *                 mute/remute is desired.
   * @param  nTraces  the number of live trace headers.
   * @param  encodedBytes  the output compressed headers.  To determine the necessary
   *                        size use getOutputBufferSize().
   * @param  offset  the byte offset to begin in the output compressed headers.
   * @return  the number of bytes used to encode the data.
   */
  private int private_compress(int[][] hdrs, IntBuffer hdrIntBuffer, int hdrLength,
                               float[][] traces,int nTraces,
                               byte[] encodedBytes, int offset) {

    if (encodedBytes.length-offset < (hdrLength*nTraces)*SIZEOF_INT)
      throw new IllegalArgumentException("Output encodedValues byte buffer is too small");

    this.ensureZipBuffers(hdrs, hdrIntBuffer);
    this.ensureHdrBuffers(hdrs, hdrIntBuffer, hdrLength);

    // Transpose the header values as we copy them into place.
    int count = 0;
    if (hdrs != null) {
      for (int i=0; i<hdrLength; i++) {
        for (int j=0; j<nTraces; j++) {
          _transposedHdrs[count] = hdrs[j][i];
          count++;
        }
      }
    } else {
      for (int j=0; j<nTraces; j++) {
        hdrIntBuffer.position(j * hdrLength);
        hdrIntBuffer.get(_singleHdrWork, 0, hdrLength);
        for (int i=0; i<hdrLength; i++) {
          int dest = j + i * nTraces;
          _transposedHdrs[dest] = _singleHdrWork[i];
        }
      }
      count = hdrLength * nTraces;
    }

    if (traces != null) {
      // Save the index of the first non-zero trace for remuting.
      for (int j=0; j<nTraces; j++) {
        _transposedHdrs[count] = HdrCompressor.getFirstNonZero(traces[j]);
        count++;
      }
      _runLengthEncodedValues[IND_REMUTE] = 1;
    } else {
      _runLengthEncodedValues[IND_REMUTE] = 0;
    }

    // We get 3 values that don't occur in the data.  We use these values
    // as flags in the data.
    this.getUniqueValues(_transposedHdrs, count, _uniqueValues);
    int endOfData = _uniqueValues[0];
    int runSymbolConst = _uniqueValues[0];  // We can use the first value for two purposes.
    int runSymbolAscend = _uniqueValues[1];
    int runSymbolDescend = _uniqueValues[2];
    int runSymbolDelta = _uniqueValues[3];
    int runSymbolFloats = _uniqueValues[4];

    _transposedHdrs[count] = endOfData;  // This is why we add 1 (or more) to the length.

    _runLengthEncodedValues[IND_HDR_LENGTH] = hdrLength;
    _runLengthEncodedValues[IND_NTRACES] = nTraces;

    // Run-length encode the header values.
    int nInts = this.runLengthEncode(_transposedHdrs, runSymbolConst, runSymbolAscend,
                                     runSymbolDescend, runSymbolDelta, runSymbolFloats,
                                     endOfData, _runLengthEncodedValues);

    // Zip the run-length encoded values.
    // Tests show that java.util.zip coding is more effective on trace headers than
    // HuffCoder alone is, although java.util.zip is quite a bit slower than HuffCoder.
    return this.zip(_runLengthEncodedValues, nInts, _zipWorkBuffer, encodedBytes, offset);
  }


  /**
   * Returns the index of the first non-zero trace sample.
   *
   * @param  trace  a seismic trace.
   * @return  the index of the first non-zero trace sample.
   */
  private static int getFirstNonZero(float[] trace) {

    for (int i=0; i<trace.length; i++) {
      if (trace[i] != 0.0F) return i;
    }
    return trace.length;  // All zeros.
  }


  /**
   * Applies a remute to a seismictrace.
   *
   * @param  trace  a seismic trace.
   * @param  indexFirstNonZero  the index of the first non-zero sample.
   */
  private static void applyRemute(float[] trace, int indexFirstNonZero) {

    for (int i=0; i<Math.min(indexFirstNonZero,trace.length); i++) trace[i] = 0.0F;
  }


  /**
   * Uncompresses a 2D array of trace headers.
   *
   * @param  encodedBytes  the input compressed headers (from the compress() method).
   * @param  offset  the byte offset to begin in the input compressed headers.
   * @param  nBytes  the number of input compressed bytes.
   * @param  hdrs  the output trace headers.
   * @param  traces  the trace samples apply remute, or null if no remute is desired.
   * @return  the number of live output trace headers.
   */
  public int uncompress(byte[] encodedBytes, int offset, int nBytes,
                        int[][] hdrs, float[][] traces)
  throws DataFormatException, DataCorruptedException {

    return this.private_uncompress(encodedBytes, offset, nBytes,
                                   hdrs, null, traces);
  }


  /**
   * Uncompresses a 2D array of trace headers.
   *
   * @param  encodedBytes  the input compressed headers (from the compress() method).
   * @param  offset  the byte offset to begin in the input compressed headers.
   * @param  nBytes  the number of input compressed bytes.
   * @param  hdrIntBuffer  the output trace headers.
   * @param  traces  the trace samples apply remute, or null if no remute is desired.
   * @return  the number of live output trace headers.
   */
  public int uncompress(byte[] encodedBytes, int offset, int nBytes,
                        IntBuffer hdrIntBuffer, float[][] traces)
  throws DataFormatException, DataCorruptedException {

    return this.private_uncompress(encodedBytes, offset, nBytes,
                                   null, hdrIntBuffer, traces);
  }


  /**
   * Uncompresses a 2D array of trace headers.
   *
   * @param  encodedBytes  the input compressed headers (from the compress() method).
   * @param  offset  the byte offset to begin in the input compressed headers.
   * @param  nBytes  the number of input compressed bytes.
   * @param  hdrs  the output trace headers, or null if hdrIntBuffer is used.
   * @param  hdrIntBuffer  the output compressed headers, or null if hdrs is used.
   * @param  traces  the trace samples apply remute, or null if no remute is desired.
   * @return  the number of live output trace headers.
   */
  public int private_uncompress(byte[] encodedBytes, int offset, int nBytes,
                                int[][] hdrs, IntBuffer hdrIntBuffer, float[][] traces)
  throws DataFormatException, DataCorruptedException {

    this.ensureZipBuffers(hdrs, hdrIntBuffer);

    this.unzip(encodedBytes, offset, nBytes, _zipWorkBuffer, _runLengthEncodedValues);

    if (_runLengthEncodedValues[IND_COOKIE] != COOKIE)
      throw new RuntimeException("Compressed data is corrupted or from an unsupported version");

    int hdrLength = _runLengthEncodedValues[IND_HDR_LENGTH];
    int nTraces = _runLengthEncodedValues[IND_NTRACES];
    int iRemute = _runLengthEncodedValues[IND_REMUTE];

    if (hdrLength < 1  ||  nTraces < 1)
      throw new DataCorruptedException("Header length and/or trace count is invalid");
    if (iRemute != 0  &&  iRemute != 1)
      throw new DataCorruptedException("Remute flag is invalid");

    this.ensureHdrBuffers(hdrs, hdrIntBuffer, hdrLength);

    // Decode the header values.
    this.runLengthDecode(_runLengthEncodedValues, _transposedHdrs);

    // Transpose the header values back as we copy them into place.
    int count = 0;
    if (hdrs != null) {
      count = 0;
      for (int i=0; i<hdrLength; i++) {
        for (int j=0; j<nTraces; j++) {
          hdrs[j][i] = _transposedHdrs[count];
          count++;
        }
      }
    } else {
      for (int j=0; j<nTraces; j++) {
        for (int i=0; i<hdrLength; i++) {
          int src = j + i * nTraces;
          _singleHdrWork[i] = _transposedHdrs[src];
        }
        hdrIntBuffer.position(j * hdrLength);
        hdrIntBuffer.put(_singleHdrWork, 0, hdrLength);
      }
      count = hdrLength * nTraces;
    }

    if (traces != null  &&  iRemute == 1) {
      // Apply the remute.
      for (int j=0; j<nTraces; j++) {
        HdrCompressor.applyRemute(traces[j], _transposedHdrs[count]);
        count++;
      }
    }

    return nTraces;
  }


  /**
   * Finds unique values that do not occur in the input data.  This algorithm depends on the
   * fact that the data has fewer values than the range of ints.
   *
   * @param  inValues  input values.
   * @param  nInput  number of input values.
   * @param  uniqueValues  output unique values.
   */
  private void getUniqueValues(int[] inValues, int nInput, int[] uniqueValues) {

    int outCount = 0;
    int candidateCount = 0;

    while (true) {

      int candidateValue;
      if (candidateCount < c_candidateValues.length) {
        // Try one of the specially computed values.
        candidateValue = c_candidateValues[candidateCount];
      } else {
        // First candidate is just a big negative integer.
        candidateValue = Integer.MIN_VALUE + candidateCount;
      }
      candidateCount++;

      int n = 0;
      for (n=0; n<nInput; n++) {
        if (inValues[n] == candidateValue) {
          // Oops.  This value occurs in the data.
          break;
        }
      }

      if (n == nInput) {
        // The candidate value was not found in the data.
        uniqueValues[outCount] = candidateValue;
        outCount++;
        if (outCount == uniqueValues.length) return;  // Got 'em all.
      }
    }

  }


  // Returns candidates for unique values that have a lot of zeros in them.
  private static int[] getCandidateUniqueValues() {

    byte[] bVals = new byte[4];
    bVals[1] = 0;
    bVals[2] = 0;
    bVals[3] = 0;
    int[] uniqueValues = new int[256];

    int count = 0;

    // Make some special ones that will compress well in a Huffman encoder.
    // These are values that occur a lot in byte data that is presented to the
    // Huffman coder.

    bVals[0] = (byte)65;
    uniqueValues[count] = BlockCompressor.stuffBytesInInt(bVals, 0);
    count++;

    bVals[0] = (byte)1;
    uniqueValues[count] = BlockCompressor.stuffBytesInInt(bVals, 0);
    count++;

    bVals[0] = (byte)74;
    uniqueValues[count] = BlockCompressor.stuffBytesInInt(bVals, 0);
    count++;

    bVals[0] = (byte)68;
    uniqueValues[count] = BlockCompressor.stuffBytesInInt(bVals, 0);
    count++;

    for (int i=Byte.MIN_VALUE; i<=Byte.MAX_VALUE; i++) {
      if (count < uniqueValues.length) {
        bVals[0] = (byte)i;
        uniqueValues[count] = BlockCompressor.stuffBytesInInt(bVals, 0);
      }
    }
    return uniqueValues;
  }


  /**
   * Returns true if the input integer value is probably a non-zero float, otherwise false.
   *
   * @param  iVal  an integer value.
   * @return  true if the input integer value is probably a non-zero float, otherwise false.
   */
  private static boolean probablyFloat(int iVal) {

    if (iVal > MIN_POS_FLOAT_BITS  ||  iVal < MAX_NEG_FLOAT_BITS) {
      if (Float.isNaN(Float.intBitsToFloat(iVal))) {
        return false;
      } else {
        return true;
      }
    } else {
      return false;
    }
  }


  /**
   * Run-length encodes an array of integers.
   *
   * @param  inValues  input array.  Must be terminated by the endOfData value.
   * @param  runSymbolConst  value used to represent constant runs.  Must not occur in the data.
   * @param  runSymbolAscend  value used to represent ascending runs.  Must not occur in the data.
   * @param  runSymbolDescend  value used to represent descending runs.  Must not occur in the data.
   * @param  runSymbolDelta  value used to represent non-unity delta runs.  Must not occur in the data.
   * @param  runSymbolFloats  value used to represent floats delta runs.  Must not occur in the data.
   * @param  endOfData  value used to terminate the input.  Must not occur in the data.
   * @param  encodedValues  output encoded values.  Must be large enough to hold the input plus header.
   * @return  the number of output values.
   */
  // This algorithm will never grow the size, except the header.
  /*package*/ int runLengthEncode(int[] inValues, int runSymbolConst, int runSymbolAscend,
                                  int runSymbolDescend, int runSymbolDelta, int runSymbolFloats,
                                  int endOfData, int[] encodedValues) {

    if (inValues.length < 1) return 0;

    if (runSymbolConst == runSymbolAscend)
      throw new IllegalArgumentException("runSymbolConst == runSymbolAscend");
    if (runSymbolConst == runSymbolDescend)
      throw new IllegalArgumentException("runSymbolConst == runSymbolDescend");
    if (runSymbolConst == runSymbolDelta)
      throw new IllegalArgumentException("runSymbolConst == runSymbolDelta");
    if (runSymbolConst == runSymbolFloats)
      throw new IllegalArgumentException("runSymbolConst == runSymbolFloats");

    // Store the header.
    // TODO: Store the dimensions of the input data?
    encodedValues[IND_COOKIE] = COOKIE;
    encodedValues[IND_SYM_CONST] = runSymbolConst;
    encodedValues[IND_SYM_ASCEND] = runSymbolAscend;
    encodedValues[IND_SYM_DESCEND] = runSymbolDescend;
    encodedValues[IND_SYM_DELTA] = runSymbolDelta;
    encodedValues[IND_SYM_FLOATS] = runSymbolFloats;
    int outCount = HDR_LENGTH;

    int inCount = 0;

    while (true) {

      int firstVal = inValues[inCount];
      int delta = inValues[inCount+1] - firstVal;
      float deltaFloat = 0.0F;

      int runSymbol = runSymbolConst;
      int i = inCount + 1;
      int runLength = 1;
      while (inValues[i] != endOfData  &&  inValues[i] == firstVal) {
        runLength++;
        i++;
      }

      // It takes 3 values to store a run, so we don't bother unless the
      // run is longer than 3.

      if (runLength < 3) {
        // Constant didn't work.  Check for mono increasing.
        runSymbol = runSymbolAscend;
        i = inCount + 1;
        runLength = 1;
        while (inValues[i] != endOfData  &&  inValues[i] == firstVal+runLength) {
          runLength++;
          i++;
        }
      }

      if (runLength < 3) {
        // Constant and increasing didn't work.  Check for mono decreasing.
        runSymbol = runSymbolDescend;
        i = inCount + 1;
        runLength = 1;
        while (inValues[i] != endOfData  &&  inValues[i] == firstVal-runLength) {
          runLength++;
          i++;
        }
      }

      if (runLength < 3) {
        // Constant and mono didn't work.  Try a non-unity delta.
        runSymbol = runSymbolDelta;
        i = inCount + 1;
        runLength = 1;
        while (inValues[i] != endOfData  &&  inValues[i] == firstVal+runLength*delta) {
          runLength++;
          i++;
        }
        if (runLength < 5) runLength = 0;  // Disqualified - it takes 4 to store a delta run.
      }

      if (runLength < 3) {
        // Nothing else has worked.  Try a run of floats.
        // The logic here ensures that Nans are not used.
        if (HdrCompressor.probablyFloat(firstVal)
            &&  HdrCompressor.probablyFloat(inValues[inCount+1])) {
          float firstValFloat = Float.intBitsToFloat(firstVal);
          deltaFloat = Float.intBitsToFloat(inValues[inCount+1]) - firstValFloat;
          runSymbol = runSymbolFloats;
          i = inCount + 1;
          runLength = 1;
          // We require the exact equal bit pattern on output (not just almost equal).
          // This will not work on large floats, but it's the best that we can to.
          while (inValues[i] != endOfData  &&  HdrCompressor.probablyFloat(inValues[i])
                 &&  inValues[i] == Float.floatToIntBits(firstValFloat+(runLength)*deltaFloat)) {
            runLength++;
            i++;
          }
          if (runLength < 5) runLength = 0;  // Disqualified - it takes 4 to store a delta run.
        }
      }

      if (runLength > 3) {
        // We got some kind of run.
        if (runSymbol == runSymbolDelta) {
          encodedValues[outCount] = runSymbol;
          encodedValues[outCount+1] = runLength;
          encodedValues[outCount+2] = firstVal;
          encodedValues[outCount+3] = delta;
          inCount += runLength;
          outCount += 4;
        } else if (runSymbol == runSymbolFloats) {
          encodedValues[outCount] = runSymbol;
          encodedValues[outCount+1] = runLength;
          encodedValues[outCount+2] = firstVal;
          encodedValues[outCount+3] = Float.floatToIntBits(deltaFloat);
          inCount += runLength;
          outCount += 4;
        } else {
          encodedValues[outCount] = runSymbol;
          encodedValues[outCount+1] = runLength;
          encodedValues[outCount+2] = firstVal;
          inCount += runLength;
          outCount += 3;
        }
      } else {
        // No run of any kind was found.
        encodedValues[outCount] = inValues[inCount];
        inCount++;
        outCount++;
      }

      if (inValues[inCount] == endOfData) {
        // Yes, the output length includes the header.
        encodedValues[IND_OUT_COUNT] = outCount;
        return outCount;
      }

    }  // End of endless loop.

  }


  /**
   * Decodes run-length encoded integers.
   *
   * @param  encodedValues  input encoded values.
   * @param  outValues  output decoded values.
   * @return  the number of output values.
   */
  /*package*/ int runLengthDecode(int[] encodedValues, int[] outValues) {

    if (encodedValues[IND_COOKIE] == OLD_COOKIE1)
      throw new RuntimeException("Compressed data is from an unsupported version");
    if (encodedValues[IND_COOKIE] != COOKIE)
      throw new IllegalArgumentException("Input encoded values have invalid header "
                                         + "(wrong endianness?) ["
                                         + encodedValues[IND_COOKIE] + "!=" + COOKIE + "]");

    int runSymbolConst = encodedValues[IND_SYM_CONST];
    int runSymbolAscend = encodedValues[IND_SYM_ASCEND];
    int runSymbolDescend = encodedValues[IND_SYM_DESCEND];
    int runSymbolDelta = encodedValues[IND_SYM_DELTA];
    int runSymbolFloats = encodedValues[IND_SYM_FLOATS];
    int nInput = encodedValues[IND_OUT_COUNT];

    int outCount = 0;
    int delta = 0;
    float deltaFloat = 0.0F;
    float firstValFloat = 0.0F;
    for (int inCount=HDR_LENGTH; inCount<nInput;) {
      if (encodedValues[inCount] == runSymbolConst
          ||  encodedValues[inCount] == runSymbolAscend
          ||  encodedValues[inCount] == runSymbolDescend
          ||  encodedValues[inCount] == runSymbolDelta
          ||  encodedValues[inCount] == runSymbolFloats) {

        int runLength = encodedValues[inCount+1];
        int firstVal = encodedValues[inCount+2];
        if (encodedValues[inCount] == runSymbolDelta)
          delta = encodedValues[inCount+3];
        if (encodedValues[inCount] == runSymbolFloats) {
          deltaFloat = Float.intBitsToFloat(encodedValues[inCount+3]);
          firstValFloat = Float.intBitsToFloat(firstVal);
        }

        for (int i=0; i<runLength; i++) {
          if (encodedValues[inCount] == runSymbolConst) {
            outValues[outCount] = firstVal;
          } else if (encodedValues[inCount] == runSymbolAscend) {
            outValues[outCount] = firstVal + i;
          } else if (encodedValues[inCount] == runSymbolDescend) {
            outValues[outCount] = firstVal - i;
          } else if (encodedValues[inCount] == runSymbolDelta) {
            outValues[outCount] = firstVal + i*delta;
          } else {
            outValues[outCount] = Float.floatToIntBits(firstValFloat + (i)*deltaFloat);
          }
          outCount++;
        }

        if (encodedValues[inCount] == runSymbolDelta
            ||  encodedValues[inCount] == runSymbolFloats) {
          inCount += 4;
        } else {
          inCount += 3;
        }

      } else {
        outValues[outCount] = encodedValues[inCount];
        outCount++;
        inCount++;
      }
    }

    return outCount;
  }


  /**
   * Applies java.util.zip compression to an array of run-length encoded integers.
   *
   * @param  encodedValues  an array of run-length encoded integers.
   * @param  nValues  the number of run-length encoded integers.
   * @param  zipInput  a work buffer for zip input values.
   * @param  zipOutput  the output zipped bytes.
   * @param  offset  the byte offset to begin in the output data.
   * @return  the number of bytes in the compressed data.
   */
  private int zip(int[] encodedValues, int nValues, byte[] zipInput,
                  byte[] zipOutput, int offset) {

    int index = 0;
    for (int i=0; i<nValues; i++) {
      BlockCompressor.stuffIntInBytes(encodedValues[i], zipInput, index);
      index += SIZEOF_INT;
    }

    _zipDeflater.reset();  // Yes, this is necessary each time.
    _zipDeflater.setInput(zipInput, 0, nValues*SIZEOF_INT);
    _zipDeflater.finish();

    // This probably returns 0 if the output buffer is too small.
    int nBytes = _zipDeflater.deflate(zipOutput, offset, zipOutput.length-offset);
    if (nBytes == 0)
      throw new RuntimeException("java.util.zip.Deflator.deflate() returned 0");
    return nBytes;
  }


  /**
   * Applies java.util.zip decompression to a byte array of compressed values.
   *
   * @param  unzipInput  array of compressed data.
   * @param  offset  the byte offset to begin in the input data.
   * @param  nBytesInput  number of bytes of compressed data.
   * @param  unzipOutput  a work buffer for unzip output values.
   * @param  encodedValues  output array of run-length encoded integers.
   * @return  the number of integers in the output.
   */
  private int unzip(byte[] unzipInput, int offset, int nBytesInput, byte[] unzipOutput,
                    int[] encodedValues)
  throws DataFormatException {

    _zipInflater.reset();  // Yes, this is necessary each time.
    _zipInflater.setInput(unzipInput, offset, nBytesInput);
    // This probably returns 0 if the output buffer is too small.
    int nBytesOutput = _zipInflater.inflate(unzipOutput);
    if (nBytesOutput == 0)
      throw new RuntimeException("java.util.zip.Inflator.inflate() returned 0");

    int nInts = nBytesOutput / SIZEOF_INT;
    if (nInts*SIZEOF_INT != nBytesOutput)  // Never happens in a sane world.
      throw new RuntimeException("Unzip returned an odd number of bytes");

    int index = 0;
    for (int i=0; i<nInts; i++) {
      encodedValues[i] = BlockCompressor.stuffBytesInInt(unzipOutput, index);
      index += SIZEOF_INT;
    }

    return nInts;
  }

}
