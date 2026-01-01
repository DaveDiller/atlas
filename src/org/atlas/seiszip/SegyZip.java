
package org.atlas.seiszip;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.util.logging.Logger;

/**
 * This class compresses SEGY files using 2D SeisPEG.
 * <p>
 * This is an expert's tool.  It only works on IEEE format SEGY files.  It requires you to know
 * the appropriate block sizes and transform length.  It requires you to know the length and byte
 * offset of the header entry that delimits frames.  The use of the command line args is unfriendly.
 * <p>
 * Beware of confusing the byte offset with the byte number of headers.  For example, the offset to the
 * first non-standard header byte is 180, not 181.
 */

public class SegyZip {

  private static final Logger LOG =
    Logger.getLogger("org.atlas.seiszip");

  /*package*/ static final int LEN_REEL_HDR = 3200;
  /*package*/ static final int LEN_BINARY_HDR = 400;
  /*package*/ static final int NBYTES_PER_HDR = 240;
  /*package*/ static final int NINTS_PER_HDR = 60;
  /*package*/ static final int COOKIE_V1 = 35478349;

  private FileInputStream _inputStream;
  private FileOutputStream _outputStream;
  private FileChannel _inputChannel;
  private FileChannel _outputChannel;
  private long _nbytesRead = 0L;
  private long _nbytesWritten = 0L;
  private SeisPEG _seisPEG;
  private int _nbytesPerTrace;
  private int _frameKeyOffset;
  private int _frameKeyLength;
  private float[][] _traces;
  private int[][] _hdrs;
  private byte[] _compressedHdrBytes;
  private byte[] _compressedTrcBytes;
  private ByteBuffer _compressedHdrByteBuffer;
  private ByteBuffer _compressedTrcByteBuffer;
  private long _tracesToDump;
  private ByteBuffer _workBuffer;
  private long _frameWrittenCount = 0L;


  /**
   * Constructor for dumping values.  This constructor is useful for examining which header to use for
   * the frame key.
   *
   * @param  inFile  input SEGY file.
   * @param  frameKeyOffset  the offset in the SEGY header to a header entry that is the same for each frame
   *                         and different between frames.  For example this value would be 16 for source
   *                         and 20 for CDP.
   * @param  frameKeyLength  the length (in bytes) in the SEGY header to a header entry that is the same for each
   *                         frame and different between frames.  This value is 2 for a short and 4 for an int.
   * @param  tracesToDump  the number of traces to dump before stopping.
   */
  public SegyZip(String inFile, int frameKeyOffset, int frameKeyLength, long tracesToDump) throws IOException {

    float distortion = 0.1F;
    float ftGainExponent = 0.0F;
    int verticalBlockSize = 64;
    int horizontalBlockSize = 64;
    int verticalTransLength = 16;
    int horizontalTransLength = 16;

    this.init(inFile, (String)null, distortion, ftGainExponent,
	      verticalBlockSize, horizontalBlockSize,
	      verticalTransLength, horizontalTransLength,
	      frameKeyOffset, frameKeyLength, tracesToDump);
  }


  /**
   * Constructor for compression a file.  Writes the reel header and binary header to the output file.
   *
   * @param  inFile  input SEGY file.
   * @param  outFile  output compressed file.  Any existing file is written over.
   * @param  distortion  the allowed distortion.  The value .1 is a good aggressive default.
   * @param  ftGainExponent  function-of-time gain exponent.  This parameter is useful for gaining
   *                         raw shot records.  Use 0.0 for no gain.
   * @param  verticalBlockSize  the vertical block size.  Must be a multiple of 8.
   * @param  horizontalBlockSize  the horizontal block size.    Must be a multiple of 8.
   * @param  verticalTransLength  the vertical transform length.  Must be 8 or 16.
   * @param  horizontalTransLength  the horizontal transform length.  Must be 8 or 16.
   * @param  frameKeyOffset  the offset in the SEGY header to a header entry that is the same for each frame
   *                         and different between frames.  For example this value would be 16 for source
   *                         and 20 for CDP.
   * @param  frameKeyLength  the length (in bytes) in the SEGY header to a header entry that is the same for each
   *                         frame and different between frames.  This value is 2 for a short and 4 for an int.
   */

  public SegyZip(String inFile, String outFile, float distortion, float ftGainExponent,
		 int verticalBlockSize, int horizontalBlockSize,
		 int verticalTransLength, int horizontalTransLength,
		 int frameKeyOffset, int frameKeyLength) throws IOException {

    long tracesToDump = -1L;

    this.init(inFile, outFile, distortion, ftGainExponent,
	      verticalBlockSize, horizontalBlockSize,
	      verticalTransLength, horizontalTransLength,
	      frameKeyOffset, frameKeyLength, tracesToDump);
  }


  private void init(String inFile, String outFile, float distortion, float ftGainExponent,
		    int verticalBlockSize, int horizontalBlockSize,
		    int verticalTransLength, int horizontalTransLength,
		    int frameKeyOffset, int frameKeyLength, long tracesToDump) throws IOException {

    _frameKeyOffset = frameKeyOffset;
    _frameKeyLength = frameKeyLength;
    _tracesToDump = tracesToDump;

    _inputStream = new FileInputStream(inFile);
    _inputChannel = _inputStream.getChannel();
    if (outFile != null) {
      _outputStream = new FileOutputStream(outFile);
      _outputChannel = _outputStream.getChannel();
    }

    _workBuffer = ByteBuffer.allocate(8);  // Big enough to hold 2 integers.
    _workBuffer.order(ByteOrder.BIG_ENDIAN);

    if (_outputChannel != null) {
      // Write the file header.
      _workBuffer.putInt(0, COOKIE_V1);
      _workBuffer.putInt(4, 0);  // For future use.
      _workBuffer.position(0);
      int nWritten = _outputChannel.write(_workBuffer);
      _nbytesWritten += nWritten;
      if (nWritten != 8)
	throw new IOException("Error writing file header: " + nWritten + "!=8");
    }

    // We don't try to compress the reel header.
    ByteBuffer reelHdrBuffer = ByteBuffer.allocate(LEN_REEL_HDR);
    int nRead = _inputChannel.read(reelHdrBuffer);
    _nbytesRead += nRead;
    if (nRead != LEN_REEL_HDR)
      throw new IOException("Error reading SEG-Y reel header: " + nRead + "!=" + LEN_REEL_HDR);
    if (outFile != null) {
      reelHdrBuffer.position(0);
      int nWritten = _outputChannel.write(reelHdrBuffer);
      _nbytesWritten += nWritten;
      if (nWritten != LEN_REEL_HDR)
	throw new IOException("Error writing SEG-Y reel header: " + nWritten + "!=" + LEN_REEL_HDR);
    }

    // We don't try to compress the binary header.
    ByteBuffer binaryHdrBuffer = ByteBuffer.allocate(LEN_BINARY_HDR);
    nRead = _inputChannel.read(binaryHdrBuffer);
    _nbytesRead += nRead;
    if (nRead != LEN_BINARY_HDR)
      throw new IOException("Error reading SEG-Y binary header: " + nRead + "!=" + LEN_BINARY_HDR);
    if (outFile != null) {
      binaryHdrBuffer.position(0);
      int nWritten = _outputChannel.write(binaryHdrBuffer);
      _nbytesWritten += nWritten;
      if (nWritten != LEN_BINARY_HDR)
	throw new IOException("Error writing SEG-Y binary header: " + nWritten + "!=" + LEN_BINARY_HDR);
    }

    // This magically makes all of the value come out properly when retrieved.  Nice!
    binaryHdrBuffer.order(ByteOrder.BIG_ENDIAN);

    int tracesPerFrame = binaryHdrBuffer.getShort(12);
    System.out.println("tracesPerFrame= " + tracesPerFrame);
    float sampleInterval = binaryHdrBuffer.getShort(16) / 1000.0F;
    System.out.println("sampleInterval= " + sampleInterval);
    int samplesPerTrace = binaryHdrBuffer.getShort(20);
    System.out.println("samplesPerTrace= " + samplesPerTrace);
    _nbytesPerTrace = samplesPerTrace * 4;

    _seisPEG = new SeisPEG(samplesPerTrace, tracesPerFrame, distortion,
			   verticalBlockSize, horizontalBlockSize,
			   verticalTransLength, horizontalTransLength);

    if (ftGainExponent != 0.0) _seisPEG.setGainExponent(ftGainExponent);

    _traces = new float[tracesPerFrame][samplesPerTrace];
    _hdrs = new int[tracesPerFrame][NINTS_PER_HDR];

    _compressedHdrBytes = new byte[HdrCompressor.getOutputBufferSize(NINTS_PER_HDR,tracesPerFrame)];
    _compressedHdrByteBuffer = ByteBuffer.wrap(_compressedHdrBytes);

    _compressedTrcBytes = new byte[samplesPerTrace*tracesPerFrame*4];
    _compressedTrcByteBuffer = ByteBuffer.wrap(_compressedTrcBytes);
  }


  /**
   * Dumps the number of traces that were specified by the 'tracesToDump' parameter in the constructor.
   */
  public void dump() throws IOException {
    this.zip();
  }


  /**
   * Performs the compression and closes the files.
   */
  public void zip() throws IOException {

    ByteBuffer hdrByteBuffer = ByteBuffer.allocateDirect(NBYTES_PER_HDR);
    hdrByteBuffer.order(ByteOrder.BIG_ENDIAN);
    ByteBuffer trcByteBuffer = ByteBuffer.allocateDirect(_nbytesPerTrace);
    trcByteBuffer.order(ByteOrder.BIG_ENDIAN);

    IntBuffer hdrBuffer = hdrByteBuffer.asIntBuffer();
    FloatBuffer trcBuffer = trcByteBuffer.asFloatBuffer();

    boolean finished = false;
    long traceInFileCount = 1L;
    int traceInFrameCount = 0;
    int frameKeyLast = Integer.MIN_VALUE;

    while (!finished) {

      hdrByteBuffer.position(0);
      int nRead = _inputChannel.read(hdrByteBuffer);
      _nbytesRead += nRead;

      if (nRead == -1) {
	// We have reached EOF.

	if (traceInFrameCount != 0) this.writeFrame(traceInFrameCount, traceInFileCount);

	finished = true;

      } else {

	if (nRead != NBYTES_PER_HDR)
	  throw new IOException("Error reading SEG-Y trace header " + traceInFileCount
				+ ": " + nRead + "!=" + NBYTES_PER_HDR);

	trcByteBuffer.position(0);
	nRead = _inputChannel.read(trcByteBuffer);
	_nbytesRead += nRead;
	if (nRead != _nbytesPerTrace)
	  throw new IOException("Error reading SEG-Y trace " + traceInFileCount
				+ ": " + nRead + "!=" + _nbytesPerTrace);

	int frameKey;
	if (_frameKeyLength == 2) {
	  frameKey = hdrByteBuffer.getShort(_frameKeyOffset);
	} else if (_frameKeyLength == 4) {
	  frameKey = hdrByteBuffer.getInt(_frameKeyOffset);
	} else {
	  throw new RuntimeException("Frame key length " + _frameKeyLength + " is not recognized");
	}

	if (_tracesToDump >= 0) {
	  System.out.println("frameKey= " + frameKey + " at trace " + traceInFileCount);
	}

	if (frameKey != frameKeyLast) {
	  // This is the beginning of a new frame.
	  if (traceInFrameCount != 0) this.writeFrame(traceInFrameCount, traceInFileCount);
	  traceInFrameCount = 0;
	}
	frameKeyLast = frameKey;

	if (traceInFrameCount >= _hdrs.length)
	  throw new IOException("Observed traces per frame exceeds " + _hdrs.length
				+ " - the header at byte offset " + _frameKeyOffset + " may be wrong");

	// Fill the next trace and header.
	hdrBuffer.position(0);
	hdrBuffer.get(_hdrs[traceInFrameCount]);
	trcBuffer.position(0);
	trcBuffer.get(_traces[traceInFrameCount]);

	traceInFrameCount++;

      }

      traceInFileCount++;

      if (_tracesToDump >= 0  &&  traceInFileCount >= _tracesToDump) finished = true;

    }  // End of loop over all read statements.

    _inputStream.close();
    if (_outputStream != null) _outputStream.close();
  }


  private void writeFrame(int traceInFrameCount, long traceInFileCount) throws IOException {

    if (traceInFrameCount == 0) return;
    if (_tracesToDump >= 0) return;  // Just dumping traces, not writing them.

    if (traceInFrameCount == 1)
      LOG.warning("Only one trace per frame found near trace " + traceInFileCount);

    // Compress the headers.
    int nbytesHdrs = _seisPEG.compressHdrs(_hdrs, NINTS_PER_HDR, traceInFrameCount, _compressedHdrBytes);

    // Compress the traces.
    int nbytesTraces = _seisPEG.compress(_traces, traceInFrameCount, _compressedTrcBytes);

    // Write the lengths of the compressed data.
    _workBuffer.putInt(0, nbytesHdrs);
    _workBuffer.putInt(4, nbytesTraces);
    _workBuffer.position(0);
    int nWritten = _outputChannel.write(_workBuffer);
    _nbytesWritten += nWritten;
    if (nWritten != 8)
      throw new IOException("Error writing mini header near trace " + traceInFileCount
			    + ": " + nWritten + "!=8");

    // Write the compressed headers.
    _compressedHdrByteBuffer.position(0);
    _compressedHdrByteBuffer.limit(nbytesHdrs);
    nWritten = _outputChannel.write(_compressedHdrByteBuffer);
    _nbytesWritten += nWritten;
    if (nWritten != nbytesHdrs)
      throw new IOException("Error writing compressed headers near trace " + traceInFileCount
			    + ": " + nWritten + "!=" + nbytesHdrs);

    // Write the compressed traces.
    _compressedTrcByteBuffer.position(0);
    _compressedTrcByteBuffer.limit(nbytesTraces);
    nWritten = _outputChannel.write(_compressedTrcByteBuffer);
    _nbytesWritten += nWritten;
    if (nWritten != nbytesTraces)
      throw new IOException("Error writing compressed data near trace " + traceInFileCount
			    + ": " + nWritten + "!=" + nbytesTraces);

    _frameWrittenCount++;
    if (_frameWrittenCount%1000 == 0)
      System.out.println("Finished compressing and writing frame " + _frameWrittenCount + " ...");
  }


  /**
   * Returns the compression ratio.
   *
   * @return  the compression ratio.
   */
  public double getCompressionRatio() {
    return (double)_nbytesRead / (double)_nbytesWritten;
  }


  /**
   * Command line interface.  Call with zero-length args to see usage.
   */
  public static void main(String[] args) {

    if (args.length == 4) {

      try {

	String inFile = args[0];
	int frameKeyOffset = Integer.parseInt(args[1]);
	int frameKeyLength = Integer.parseInt(args[2]);
	int tracesToDump = Integer.parseInt(args[3]);

	SegyZip segyZip = new SegyZip(inFile, frameKeyOffset, frameKeyLength, tracesToDump);

	segyZip.dump();

	// We have to call System.exit() because SeisPEG has threads running.
	System.exit(0);

      } catch (Exception e) {

	e.printStackTrace();
	System.exit(-1);

      }


    } else if (args.length == 8) {

      try {

	String inFile = args[0];
	String outFile = args[1];
	float distortion = Float.parseFloat(args[2]);
	float ftGainExponent = Float.parseFloat(args[3]);
	int verticalBlockSize = Integer.parseInt(args[4]);
	int horizontalBlockSize = Integer.parseInt(args[5]);
	int frameKeyOffset = Integer.parseInt(args[6]);
	int frameKeyLength = Integer.parseInt(args[7]);

	int verticalTransLength = 16;
	int horizontalTransLength = 16;

	SegyZip segyZip = new SegyZip(inFile, outFile, distortion, ftGainExponent,
				      verticalBlockSize, horizontalBlockSize,
				      verticalTransLength, horizontalTransLength,
				      frameKeyOffset, frameKeyLength);

	segyZip.zip();

	System.out.println("Data compression ratio= " + segyZip.getCompressionRatio());

	// We have to call System.exit() because SeisPEG has threads running.
	System.exit(0);

      } catch (Exception e) {

	e.printStackTrace();
	System.exit(-1);

      }

    } else {

      throw new IllegalArgumentException("Usage: java SegyZip segyInputFile zippedOutputFile distortion "
					 + "ftGainExponent verticalBlockSize horizontalBlockSize frameKeyOffset "
					 + "frameKeyLength\n"
					 + "or\n"
					 + "java SegyZip segyInputFile frameKeyOffset frameKeyLength tracesToDump");

    }

  }

}
