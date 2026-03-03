
package org.atlas.seiszip;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

/**
 * This class uncompresses SEGY files that were compressed using 2D SeisPEG.
 */

public class SegyUnzip {

  private static final Logger LOG =
    Logger.getLogger("org.atlas.seiszip");

  private String _inFile = null;
  private String _outFile = null;
  private FileInputStream _inputStream;
  private FileOutputStream _outputStream;
  private FileChannel _inputChannel;
  private FileChannel _outputChannel;
  private long _nbytesRead = 0L;
  private long _inputStreamOffset = 0L;
  private long _nbytesWritten = 0L;
  private SeisPEG _seisPEG;
  private int _nbytesPerTrace;
  private int _nbytesPerTraceOut;
  private float[][] _traces = null;
  private int[][] _hdrs = null;
  private byte[] _compressedHdrBytes = null;
  private byte[] _compressedTrcBytes = null;
  private ByteBuffer _compressedHdrByteBuffer = null;
  private ByteBuffer _compressedTrcByteBuffer = null;
  private ByteBuffer _twoWords = null;
  private ByteBuffer _hdrByteBuffer = null;
  private ByteBuffer _trcByteBuffer = null;
  private IntBuffer _hdrBuffer = null;
  private FloatBuffer _trcBuffer = null;
  private int _tracesPerFrameLast = -1;
  private int _samplesPerTraceLast = -1;
  private int _fileCount = 0;
  private String[] _inputFileList;
  private File _inputDir;
  private File _outputDir;
  private String _volumeName;
  private String _segyExtension;
  private String _seispegExtension;
  private long _minVolume = Long.MIN_VALUE;
  private long _maxVolume = Long.MAX_VALUE;
  private long _volumeInc = 1;
  private long _minFrame = Long.MIN_VALUE;
  private long _maxFrame = Long.MAX_VALUE;
  private long _frameInc = 1;
  private long _minTrace = Long.MIN_VALUE;
  private long _maxTrace = Long.MAX_VALUE;
  private long _traceInc = 1;
  private long _maxSample = Long.MAX_VALUE;
  private boolean _ignoreErrors = false;


  /**
   * Historic constructor for uncompression a file.  Writes the reel header and binary header to the output file.
   *
   * @param  inFile  input compressed file.
   * @param  outFile  output SEGY file.  Any existing file is written over.
   */
  public SegyUnzip(String inFile, String outFile) throws IOException {

    this.initializeFile(inFile, outFile);
  }


  /**
   * Constructor that supports range-limited uncompression of multiple files.
   *
   * @param  inputDir  an directory of input files with names similar to "SOURCE_004514.sgy.syz".
   * @param  outputDir  an output directory.
   * @param  volumeName  the volume name, which defaults to "SOURCE_" if null.
   * @param  segyExtension  the SEG-Y file extension, which defaults to ".sgy" if null.
   * @param  seispegExtension  the SeisPEG file extension, which defaults to ".syz" if null.
   * @param  minVolume  the miniumum volume (file) to uncompress, or Long.MIN_VALUE for all.
   * @param  maxVolume  the maxiumum volume (file) to uncompress, or Long.MAX_VALUE for all.
   * @param  volumeInc  the increment between volumes (files) to uncompress.
   * @param  minFrame  the miniumum frame to uncompress in each file, or Long.MIN_VALUE for all.  Frames start at 1.
   * @param  maxFrame  the maxiumum frame to uncompress in each file, or Long.MAX_VALUE for all.
   * @param  frameInc  the increment between frame in each file to uncompress.
   * @param  minTrace  the miniumum trace to uncompress in each frame, or Long.MIN_VALUE for all.  Traces start at 1.
   * @param  maxTrace  the maxiumum trace to uncompress in each frame, or Long.MAX_VALUE for all.
   * @param  traceInc  the increment between trace in each frame to uncompress.
   * @param  maxSample  the maxiumum sample to uncompress in each frame, or Long.MAX_VALUE for all.
   * @param  ignoreErrors  if true, any error uncompressing an individual file is ignored and the
   *                       compression goes to the next file.
   */
  public SegyUnzip(String inputDir, String outputDir, String volumeName,
		   String segyExtension, String seispegExtension,
		   long minVolume, long maxVolume, long volumeInc,
		   long minFrame, long maxFrame, long frameInc,
		   long minTrace, long maxTrace, long traceInc,
		   long maxSample, boolean ignoreErrors) {

    if (inputDir == null)
      throw new IllegalArgumentException("Input directory is null");
    _inputDir = new File(inputDir);
    if (!_inputDir.exists())
      throw new IllegalArgumentException("Input directory '" + inputDir + "' does not exist");
    if (!_inputDir.isDirectory())
      throw new IllegalArgumentException("Input directory '" + inputDir + "' is not actually a directory");
    _inputFileList = _inputDir.list();
    if (_inputFileList == null  ||  _inputFileList.length == 0)
      throw new IllegalArgumentException("Input directory '" + inputDir + "' is empty");
    Arrays.sort(_inputFileList);

    if (outputDir == null)
      throw new IllegalArgumentException("Output directory is null");
    _outputDir = new File(outputDir);
    if (_outputDir.exists()) {
      if (!_outputDir.isDirectory())
	throw new IllegalArgumentException("Output directory '" + outputDir + "' is not actually a directory");
    } else {
      boolean success = _outputDir.mkdirs();
      if (!success)
	throw new IllegalArgumentException("Unable to create output directory " + outputDir);
    }

    if (volumeName == null) volumeName = "SOURCE_";  // From typical Tierra usage.
    if (segyExtension == null) segyExtension = ".sgy";
    if (seispegExtension == null) seispegExtension = ".syz";  // From typical Tierra usage.
    _volumeName = volumeName;
    _segyExtension = segyExtension;
    _seispegExtension = seispegExtension;

    if (minVolume > maxVolume)
      throw new IllegalArgumentException("minVolume > maxVolume");
    if (volumeInc < 1)
      throw new IllegalArgumentException("volumeInc < 1");
    if (minFrame > maxFrame)
      throw new IllegalArgumentException("minFrame > maxFrame");
    if (frameInc < 1)
      throw new IllegalArgumentException("frameInc < 1");
    if (minTrace > maxTrace)
      throw new IllegalArgumentException("minTrace > maxTrace");
    if (traceInc < 1)
      throw new IllegalArgumentException("traceInc < 1");

    _minVolume = minVolume;
    _maxVolume = maxVolume;
    _volumeInc = volumeInc;
    _minFrame = minFrame;
    _maxFrame = maxFrame;
    _frameInc = frameInc;
    _minTrace = minTrace;
    _maxTrace = maxTrace;
    _traceInc = traceInc;
    _maxSample = maxSample;
    _ignoreErrors = ignoreErrors;
  }


  /**
   * Initializes uncompression of one file.  Writes the reel header and binary header to the output file.
   *
   * @param  inFile  input compressed file.
   * @param  outFile  output SEGY file.  Any existing file is written over.
   */
  private void initializeFile(String inFile, String outFile) throws IOException {

    _nbytesRead = 0L;
    _inputStreamOffset = 0L;
    _nbytesWritten = 0L;

    _inFile = inFile;
    _outFile = outFile;

    _inputStream = new FileInputStream(inFile);
    _inputChannel = _inputStream.getChannel();
    _outputStream = new FileOutputStream(outFile);
    _outputChannel = _outputStream.getChannel();

    if (_twoWords == null) {
      _twoWords = ByteBuffer.allocate(8);  // Big enough to hold 2 integers.
      _twoWords.order(ByteOrder.BIG_ENDIAN);
    }

    // Read the file header.
    _twoWords.position(0);
    int nRead = _inputChannel.read(_twoWords);
    _nbytesRead += nRead;
    _inputStreamOffset += nRead;
    if (nRead != 8)
      throw new IOException("For file '" + _inFile + "' - Error reading file header: " + nRead + "!=8");
    int cookie = _twoWords.getInt(0);
    if (cookie != SegyZip.COOKIE_V1)
      throw new RuntimeException("For file '" + _inFile
				 + "' - Sorry - you are trying to unzip data from an invalid or corrupted file "
                                 + "(contact Dave.Diller@weinmangeoscience.com if this is unacceptable) "
                                 + cookie + " " + SegyZip.COOKIE_V1);

    // First the reel header.
    ByteBuffer reelHdrBuffer = ByteBuffer.allocate(SegyZip.LEN_REEL_HDR);
    nRead = _inputChannel.read(reelHdrBuffer);
    _nbytesRead += nRead;
    _inputStreamOffset += nRead;
    if (nRead != SegyZip.LEN_REEL_HDR)
      throw new IOException("For file '" + _inFile
			    + "' - Error reading SEG-Y reel header: " + nRead + "!=" + SegyZip.LEN_REEL_HDR);

    reelHdrBuffer.position(0);
    int nWritten = _outputChannel.write(reelHdrBuffer);
    _nbytesWritten += nWritten;
    if (nWritten != SegyZip.LEN_REEL_HDR)
      throw new IOException("For file '" + _outFile
			    + "' - Error writing SEG-Y reel header: " + nWritten + "!=" + SegyZip.LEN_REEL_HDR);

    // Next the binary header.
    ByteBuffer binaryHdrBuffer = ByteBuffer.allocate(SegyZip.LEN_BINARY_HDR);
    nRead = _inputChannel.read(binaryHdrBuffer);
    _nbytesRead += nRead;
    _inputStreamOffset += nRead;
    if (nRead != SegyZip.LEN_BINARY_HDR)
      throw new IOException("For file '" + _inFile
			    + "' - Error reading SEG-Y binary header: " + nRead + "!=" + SegyZip.LEN_BINARY_HDR);

    // This magically makes all of the value come out properly when retrieved.  Nice!
    binaryHdrBuffer.order(ByteOrder.BIG_ENDIAN);

    int tracesPerFrame = binaryHdrBuffer.getShort(12);
    float sampleInterval = (float)binaryHdrBuffer.getShort(16) / 1000.0F;
    int samplesPerTrace = (int)binaryHdrBuffer.getShort(20);
    _nbytesPerTrace = samplesPerTrace * 4;

    System.out.println("For file '" + _inFile + "' ...");
    if (_fileCount == 0) {
      System.out.println("  tracesPerFrame= " + tracesPerFrame);
      System.out.println("  sampleInterval= " + sampleInterval);
      System.out.println("  samplesPerTrace= " + samplesPerTrace);
    }
    _fileCount++;

    int nsamplesOutput;
    if (_maxSample < samplesPerTrace) {
      nsamplesOutput = (int)_maxSample;
      binaryHdrBuffer.putShort(20, (short)nsamplesOutput);
    } else {
      nsamplesOutput = samplesPerTrace;
    }
    _nbytesPerTraceOut = nsamplesOutput * 4;

    binaryHdrBuffer.position(0);
    nWritten = _outputChannel.write(binaryHdrBuffer);
    _nbytesWritten += nWritten;
    if (nWritten != SegyZip.LEN_BINARY_HDR)
      throw new IOException("For file '" + _outFile
			    + "' - Error writing SEG-Y binary header: " + nWritten + "!=" + SegyZip.LEN_BINARY_HDR);

    if (_traces == null  ||  _traces.length != tracesPerFrame  ||  _traces[0].length != samplesPerTrace) {
      _traces = new float[tracesPerFrame][samplesPerTrace];
    }
    if (_hdrs == null  ||  _hdrs.length != tracesPerFrame  ||  _hdrs[0].length != SegyZip.NINTS_PER_HDR) {
      _hdrs = new int[tracesPerFrame][SegyZip.NINTS_PER_HDR];
    }

    if (_compressedHdrBytes == null  ||  _tracesPerFrameLast != tracesPerFrame) {
      _compressedHdrBytes = new byte[HdrCompressor.getOutputBufferSize(SegyZip.NINTS_PER_HDR,tracesPerFrame)];
      _compressedHdrByteBuffer = ByteBuffer.wrap(_compressedHdrBytes);
    }

    if (_compressedTrcBytes == null  ||  _tracesPerFrameLast != tracesPerFrame
	||  _samplesPerTraceLast != samplesPerTrace) {
      _compressedTrcBytes = new byte[samplesPerTrace*tracesPerFrame*4];
      _compressedTrcByteBuffer = ByteBuffer.wrap(_compressedTrcBytes);
    }

    _samplesPerTraceLast = samplesPerTrace;
    _tracesPerFrameLast = tracesPerFrame;
  }


  // Returns null if the file is not in range, otherwise returns the full file path.
  private String inputFileIsInRange(String inputFile) throws IOException {

    if (!inputFile.startsWith(_volumeName)) return null;  // Don't recognize the file name.
    String s = inputFile.substring(_volumeName.length(),inputFile.length());  // Trim off the volume name.
    int index = s.indexOf(_seispegExtension);
    if (index < 1) return null;  // SeisPEG extension doesn't match.
    s = s.substring(0,index);
    index = s.indexOf(_segyExtension);
    if (index < 1) return null;  // SEG-Y extension doesn't match.
    s = s.substring(0,index);

    File file = new File(_inputDir, inputFile);
    if (!file.exists())  // Can't happen in a sane world.
      throw new IOException("Input file '" + file.getAbsolutePath() + "' does not exist");

    long volumeNumber;
    try {
      volumeNumber = Long.parseLong(s);
    } catch (Exception e) {
      e.printStackTrace();
      throw new IOException("Unable to parse volume number for input file '" + file.getAbsolutePath() + "'");
    }
    if (volumeNumber < _minVolume) return null;  // Out of range.
    if (volumeNumber > _maxVolume) return null;  // Out of range.
    if (_minVolume == Long.MIN_VALUE) {
      if (volumeNumber%_volumeInc != 0) return null;  // Not on the increment.
    } else {
      if ((volumeNumber-_minVolume)%_volumeInc != 0) return null;  // Not on the increment.
    }

    // If control reaches here the input file is valid.
    return file.getAbsolutePath();
  }


  private String getOutputFile(String inputFile) throws IOException {

    int index = inputFile.indexOf(_seispegExtension);
    if (index < 1)
      throw new IOException("SeisPEG extension '" + _seispegExtension + "' is missing from input file '"
			    + new File(_inputDir,inputFile).getAbsolutePath() + "'");
    String outputFile = inputFile.substring(0,index);
    return new File(_outputDir, outputFile).getAbsolutePath();
  }


  /**
   * Performs the uncompression on all files.
   */
  public void unzipAllFiles() throws IOException {

    // Loop over all files in the input directory.
    for (int j=0; j<_inputFileList.length; j++) {

      try {

	String inFile = this.inputFileIsInRange(_inputFileList[j]);
	if (inFile != null) {
	  // This is a file that we are supposed to uncompress.
	  String outFile = this.getOutputFile(_inputFileList[j]);
	  this.initializeFile(inFile, outFile);
	  this.unzipFile();
	}

      } catch (IOException e) {
	if (_ignoreErrors) {
	  // Whine but keep going.
	  e.printStackTrace();
	} else {
	  throw e;
	}
      }

    }

  }


  /**
   * Performs the uncompression and closes the files.
   */
  public void unzipFile() throws IOException {

    if (_hdrByteBuffer == null) {
      _hdrByteBuffer = ByteBuffer.allocateDirect(SegyZip.NBYTES_PER_HDR);
      _hdrByteBuffer.order(ByteOrder.BIG_ENDIAN);
      _hdrBuffer = _hdrByteBuffer.asIntBuffer();
    }
 
    if (_trcByteBuffer == null) {
      _trcByteBuffer = ByteBuffer.allocateDirect(_nbytesPerTrace);
      _trcByteBuffer.order(ByteOrder.BIG_ENDIAN);
      _trcBuffer = _trcByteBuffer.asFloatBuffer();
    }

    long inputTraceCount = 1L;
    long outputTraceCount = 1L;
    long frameCounter = 1L;

    while (true) {

      // This is a new frame.

      boolean readThisFrame = true;
      if (frameCounter < _minFrame) readThisFrame = false;  // Out of range.
      if (frameCounter > _maxFrame) readThisFrame = false;  // Out of range.
      if (_minFrame == Long.MIN_VALUE) {
	if (frameCounter%_frameInc != 0) readThisFrame = false;  // Not on the increment.
      } else {
	if ((frameCounter-_minFrame)%_frameInc != 0) readThisFrame = false;  // Not on the increment.
      }

      // Read the lengths of the compressed data.
      _twoWords.position(0);
      int nRead = _inputChannel.read(_twoWords);
      _nbytesRead += nRead;
      _inputStreamOffset += nRead;

      if (nRead == -1) {
	// We have reached EOF.
	_inputStream.close();
	_outputStream.close();
	System.out.println("  Data expansion ratio= " + this.getUncompressionRatio());
	return;
      }

      if (nRead != 8)
	throw new IOException("For file '" + _inFile
			      + "' - Error reading mini header near trace " + inputTraceCount
			      + ": " + nRead + "!=8");
      int nbytesHdrs = _twoWords.getInt(0);
      int nbytesTraces = _twoWords.getInt(4);

      if (!readThisFrame) {

	// Skip over this frame.
	_inputStreamOffset += (nbytesHdrs + nbytesTraces);
	_inputChannel.position(_inputStreamOffset);

      } else {

	// Read the compressed headers.
	_compressedHdrByteBuffer.position(0);
	_compressedHdrByteBuffer.limit(nbytesHdrs);
	nRead = _inputChannel.read(_compressedHdrByteBuffer);
	_nbytesRead += nRead;
	_inputStreamOffset += nRead;
	if (nRead != nbytesHdrs)
	  throw new IOException("For file '" + _inFile
				+ "' - Error reading compressed headers near trace " + inputTraceCount
				+ ": " + nRead + "!=" + nbytesHdrs);

	// Read the compressed traces.
	_compressedTrcByteBuffer.position(0);
	_compressedTrcByteBuffer.limit(nbytesTraces);
	nRead = _inputChannel.read(_compressedTrcByteBuffer);
	_nbytesRead += nRead;
	_inputStreamOffset += nRead;
	if (nRead != nbytesTraces)
	  throw new IOException("For file '" + _inFile
				+ "' - Error reading compressed data near trace " + inputTraceCount
				+ ": " + nRead + "!=" + nbytesTraces);

	// We can create the SeisPEG from the first compressed traces that are encountered.
	if (_seisPEG == null)
	  _seisPEG = new SeisPEG(_compressedTrcBytes);

	// Uncompress the headers.
	int tracesInFrame = 0;
	try {
	  tracesInFrame = _seisPEG.uncompressHdrs(_compressedHdrBytes, nbytesHdrs, _hdrs);
	} catch (DataFormatException e) {
	  throw new IOException(e.toString());
	}

	// Uncompress the traces.
	_seisPEG.uncompress(_compressedTrcBytes, nbytesTraces, _traces);

	for (int j=0; j<tracesInFrame; j++) {

	  int traceCounter = j + 1;
	  boolean writeThisTrace = true;
	  if (traceCounter < _minTrace) writeThisTrace = false;  // Out of range.
	  if (traceCounter > _maxTrace) writeThisTrace = false;  // Out of range.
	  if (_minTrace == Long.MIN_VALUE) {
	    if (traceCounter%_traceInc != 0) writeThisTrace = false;  // Not on the increment.
	  } else {
	    if ((traceCounter-_minTrace)%_traceInc != 0) writeThisTrace = false;  // Not on the increment.
	  }

	  if (writeThisTrace) {

	    // Fill the next trace and header.
	    _hdrBuffer.position(0);
	    _hdrBuffer.put(_hdrs[j]);
	    _trcBuffer.position(0);
	    _trcBuffer.put(_traces[j]);

	    // Write the header.
	    _hdrByteBuffer.position(0);
	    int nWritten = _outputChannel.write(_hdrByteBuffer);
	    _nbytesWritten += nWritten;
	    if (nWritten != SegyZip.NBYTES_PER_HDR)
	      throw new IOException("For file '" + _outFile
				    + "' - Error writing SEG-Y trace header " + outputTraceCount
				    + ": " + nWritten + "!=" + SegyZip.NBYTES_PER_HDR);

	    // Write the trace.
	    _trcByteBuffer.position(0);
	    _trcByteBuffer.limit(_nbytesPerTraceOut);
	    nWritten = _outputChannel.write(_trcByteBuffer);
	    _nbytesWritten += nWritten;
	    if (nWritten != _nbytesPerTraceOut)
	      throw new IOException("For file '" + _outFile
				    + "' - Error writing SEG-Y trace " + outputTraceCount
				    + ": " + nWritten + "!=" + _nbytesPerTraceOut);

	  } else {
	    // Don't write this trace, just add the length.

	    _nbytesWritten += (SegyZip.NBYTES_PER_HDR + _nbytesPerTraceOut);

	  }

	  inputTraceCount++;
	  outputTraceCount++;
	}

	if (frameCounter%250 == 0)
	  System.out.println("  Finished uncompressing and writing frame " + frameCounter + " ...");

      }  // End of block when not skipping over a frame.

      frameCounter++;

    }  // End of infinite loop over read statements.

  }


  /**
   * Returns the uncompression ratio.
   *
   * @return  the uncompression ratio.
   */
  public double getUncompressionRatio() {
    return (double)_nbytesWritten / (double)_nbytesRead;
  }


  /**
   * Command line interface.  Call with zero-length args to see usage.
   */
  public static void main(String[] args) {

    boolean historicUsage = true;
    for (int i=0; i<args.length; i++) {
      int index = args[i].indexOf("=");
      if (index > 0) historicUsage = false;
    }

    if (historicUsage) {

      if (args.length != 2)
	throw new IllegalArgumentException("Usage: java SegyUnzip zippedInputFile segyOutputFile \n"
					   + "  or\n"
					   + "java SegyUnzip inputDir=some_directory outputDir=some_directory "
					   + "[volumeName=SOURCE_] [segyExtension=.sgy] [seispegExtension=.syz] "
					   + "[minVolume=first_available] [maxVolume=last_available] [volumeInc=1] "
					   + "[minFrame=1] [maxFrame=last_available] [frameInc=1] "
					   + "[minTrace=1] [maxTrace=last_available] [traceInc=1] "
					   + "[maxSample=last_available] [ignoreErrors=false]");

      try {

	String inFile = args[0];
	String outFile = args[1];
	SegyUnzip segyUnzip = new SegyUnzip(inFile, outFile);

	segyUnzip.unzipFile();

	// We have to call System.exit() because SeisPEG has threads running.
	System.exit(0);

      } catch (Exception e) {

	e.printStackTrace();
	System.exit(-1);

      }

    } else {

      // A typical input file name is "SOURCE_004514.sgy.syz"

      // Not historic usage.
      String inputDir = null;  // No default.
      String outputDir = null;  // No default.
      String volumeName = "SOURCE_";  // From typical Tierra usage.
      String segyExtension = ".sgy";
      String seispegExtension = ".syz";  // From typical Tierra usage.
      long minVolume = Long.MIN_VALUE;
      long maxVolume = Long.MAX_VALUE;
      long volumeInc = 1L;
      long minFrame = Long.MIN_VALUE;
      long maxFrame = Long.MAX_VALUE;
      long frameInc = 1L;
      long minTrace = Long.MIN_VALUE;
      long maxTrace = Long.MAX_VALUE;
      long traceInc = 1L;
      long maxSample = Long.MAX_VALUE;
      boolean ignoreErrors = false;

      try {

	for (int i=0; i<args.length; i++) {

	  String s = args[i];
	  if (s.startsWith("inputDir=")) {
	    inputDir = s.substring("inputDir=".length(),s.length());
	  } else if (s.startsWith("outputDir=")) {
	    outputDir = s.substring("outputDir=".length(),s.length());
	  } else if (s.startsWith("volumeName=")) {
	    volumeName = s.substring("volumeName=".length(),s.length());
	  } else if (s.startsWith("segyExtension=")) {
	    segyExtension = s.substring("segyExtension=".length(),s.length());
	  } else if (s.startsWith("seispegExtension=")) {
	    seispegExtension = s.substring("seispegExtension=".length(),s.length());
	  } else if (s.startsWith("minVolume=")) {
	    s = s.substring("minVolume=".length(),s.length());
	    minVolume = Long.parseLong(s);
	  } else if (s.startsWith("maxVolume=")) {
	    s = s.substring("maxVolume=".length(),s.length());
	    maxVolume = Long.parseLong(s);
	  } else if (s.startsWith("volumeInc=")) {
	    s = s.substring("volumeInc=".length(),s.length());
	    volumeInc = Long.parseLong(s);

	  } else if (s.startsWith("minFrame=")) {
	    s = s.substring("minFrame=".length(),s.length());
	    minFrame = Long.parseLong(s);
	  } else if (s.startsWith("maxFrame=")) {
	    s = s.substring("maxFrame=".length(),s.length());
	    maxFrame = Long.parseLong(s);
	  } else if (s.startsWith("frameInc=")) {
	    s = s.substring("frameInc=".length(),s.length());
	    frameInc = Long.parseLong(s);
	  } else if (s.startsWith("minTrace=")) {
	    s = s.substring("minTrace=".length(),s.length());
	    minTrace = Long.parseLong(s);
	  } else if (s.startsWith("maxTrace=")) {
	    s = s.substring("maxTrace=".length(),s.length());
	    maxTrace = Long.parseLong(s);
	  } else if (s.startsWith("traceInc=")) {
	    s = s.substring("traceInc=".length(),s.length());
	    traceInc = Long.parseLong(s);
	  } else if (s.startsWith("maxSample=")) {
	    s = s.substring("maxSample=".length(),s.length());
	    maxSample = Long.parseLong(s);
	  } else if (s.startsWith("ignoreErrors=")) {
	    s = s.substring("ignoreErrors=".length(),s.length()).toLowerCase();
	    if (s.equals("true") ||  s.equals("t") ||  s.equals("yes") ||  s.equals("y")) ignoreErrors = true;
	  } else {
	    throw new IllegalArgumentException("Input argument '" + s
					       + "' is not recognized - run with no args to see usage");
	  }

	}

        if (inputDir == null)
          throw new IllegalArgumentException("Input directory must be specified via 'inputDir=some_directory'");
        if (outputDir == null)
          throw new IllegalArgumentException("Output directory must be specified via 'outputDir=some_directory'");

	SegyUnzip segyUnzip = new SegyUnzip(inputDir, outputDir, volumeName,
					    segyExtension, seispegExtension,
					    minVolume, maxVolume, volumeInc,
					    minFrame, maxFrame, frameInc,
					    minTrace, maxTrace, traceInc,
					    maxSample, ignoreErrors);

	segyUnzip.unzipAllFiles();

	// We have to call System.exit() because SeisPEG has threads running.
	System.exit(0);

      } catch (Exception e) {

	e.printStackTrace();
	System.exit(-1);

      }

    }

  }

}

