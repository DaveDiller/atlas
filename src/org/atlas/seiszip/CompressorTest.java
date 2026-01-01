
package org.atlas.seiszip;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Random;
import java.util.logging.Logger;

/**
 * This class provides unit tests for SeisPEG format.
 */

public class CompressorTest {

  private static final Logger LOG =
    Logger.getLogger("org.atlas.seiszip");

  // private static final int BLOCK_SIZE = 1024;
  // TODO: I don't have a particularly good rational for using 10 milliseconds.
  private static final int SLEEP_MS = 10;
  private static final int NRETRIES = 30000;  // 5 minutes.

  private static final int SIZEOF_FLOAT = 4;
  private static final int SIZEOF_INT = 4;

  // Extra samples for byte-swap flag and checksum.
  /*package*/ static final int NATIVE_HDRLEN = 2;
  private static final int SWAP_FLAG  = 1;
  private static final int SWAP_FLAG_REVERSED = 16777216;

  /**
   * Writes an array of float arrays to an <code>RandomAccessFile</code> efficiently.
   * Handles endianess differences between machines.  Ignores Nans.
   * THIS METHOD IS USED ONLY BY TESTS.
   *
   * @param  out  an output stream
   * @param  data  an array of float arrays.
   * @param  n  number of samples to write for all arrays.
   * @param  nTraces  the number of traces to write.
   */
  public static void writeFloats(ObjectOutputStream out, float[][] data, int n, int nTraces)
  throws IOException {

    if (data.length < 1  ||  nTraces < 1) return;
    byte[] cTrace = new byte[(n+NATIVE_HDRLEN)*SIZEOF_FLOAT];
    ByteBuffer byteBuffer = ByteBuffer.wrap(cTrace);
    byteBuffer.order(ByteOrder.nativeOrder());
    IntBuffer intBuffer = byteBuffer.asIntBuffer();
    intBuffer.put(0, SWAP_FLAG);
    // Skip the checksum.
    FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
    for (int j=0; j<nTraces; j++) {
      for (int i=0; i<n; i++) floatBuffer.put(NATIVE_HDRLEN+i, data[j][i]);
      out.write(cTrace);
    }
  }

  /**
   * Writes an array of int arrays to an <code>RandomAccessFile</code> efficiently.
   * Handles endianess differences between machines.  Ignores Nans.
   * This method is used only by tests.
   *
   * @param  out  an output stream
   * @param  data  an array of int arrays.
   * @param  n  number of samples to write for all arrays.
   * @param  nTraces  the number of traces to write.
   */
  public static void writeInts(RandomAccessFile out, int[][] data, int n, int nTraces)
  throws IOException {

    if (data.length < 1  ||  nTraces < 1) return;
    byte[] cTrace = new byte[(n+NATIVE_HDRLEN)*SIZEOF_INT];
    ByteBuffer byteBuffer = ByteBuffer.wrap(cTrace);
    byteBuffer.order(ByteOrder.nativeOrder());
    IntBuffer intBuffer = byteBuffer.asIntBuffer();
    intBuffer.put(0, SWAP_FLAG);
    // Skip the checksum.
    for (int j=0; j<nTraces; j++) {
      for (int i=0; i<n; i++) intBuffer.put(NATIVE_HDRLEN+i, data[j][i]);
      out.write(cTrace);
    }
  }

  /*
   * Reads an array of float arrays from an <code>RandomAccessFile</code> efficiently.
   * Handles endianess differences between machines.  Ignores Nans.
   * This method is used only by tests.
   *
   * @param  in  an input stream
   * @param  data  an array of float arrays.
   * @param  n  number of samples to read for all arrays.
   */
  // This method is only kept around for testing purposes.
  // It is completely deprecated by java.nio.
  /*package*/ static void readFloats(ObjectInputStream in, float[][] data, int n, int nTraces)
  throws IOException {

    if (data.length < 1) return;
    byte[] cTrace = new byte[(n+NATIVE_HDRLEN)*SIZEOF_FLOAT];
    ByteBuffer byteBuffer = ByteBuffer.wrap(cTrace);
    // The test data was written little endian.
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
    // Skip the checksum.
    IntBuffer intBuffer = byteBuffer.asIntBuffer();
    FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
    for (int j=0; j<nTraces; j++) {
      in.readFully(cTrace);
      if (j == 0) {
        int iswap = intBuffer.get(0);
        if (iswap != SWAP_FLAG)  // Byte reversal problems, or corrupted data.
          throw new DataCorruptedException("Swap flag is not recognized ("
                                           + iswap + "!=" + SWAP_FLAG + ")");
      }
      for (int i=0; i<n; i++) data[j][i] = floatBuffer.get(NATIVE_HDRLEN+i);
    }
  }


  /*
   * Reads an array of int arrays from an <code>RandomAccessFile</code> efficiently.
   * Handles endianess differences between machines.  Ignores Nans.
   * This method is used only by tests.
   *
   * @param  in  an input stream
   * @param  data  an array of int arrays.
   * @param  n  number of samples to read for all arrays.
   */
  // This method is only kept around for testing purposes.
  // It is completely deprecated by java.nio.
  /*package*/ static void readInts(RandomAccessFile in, int[][] data, int n, int nTraces)
  throws IOException {

    if (data.length < 1) return;
    byte[] cTrace = new byte[(n+NATIVE_HDRLEN)*SIZEOF_INT];
    ByteBuffer byteBuffer = ByteBuffer.wrap(cTrace);
    // The test data was written little endian.
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
    // Skip the checksum.
    IntBuffer intBuffer = byteBuffer.asIntBuffer();
    for (int j=0; j<nTraces; j++) {
      in.readFully(cTrace);
      if (j == 0) {
        if (intBuffer.get(0) != SWAP_FLAG)  // Byte reversal problems, or corrupted data.
          throw new DataCorruptedException("Swap flag is not recognized");
      }
      for (int i=0; i<n; i++) data[j][i] = intBuffer.get(NATIVE_HDRLEN+i);
    }
  }


  private static void originalTest(boolean writingData) throws Exception {

    float distortion = 0.00001F;
    int n2 = 0;
    int n1 = 0;
    int verticalBlockSize = 0;
    int horizontalBlockSize = 0;
    int verticalTransLength = 0;
    int horizontalTransLength = 0;

    long randomSeed = 23148L;

    int div = 1;
    int testDataCount = 0;

    int ntests = 9;
    for (int kTest=0; kTest<ntests; kTest++) {

      if (kTest == 0) {
        n2 = 127;
        n1 = 513;
        verticalBlockSize = 8;
        horizontalBlockSize = 8;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else if (kTest == 1  ||  kTest == 2) {
        n2 = 112;
        n1 = 528;
        verticalBlockSize = 16;
        horizontalBlockSize = 16;
        if (kTest == 1) {
          verticalTransLength = 16;
          horizontalTransLength = 16;
        } else if (kTest == 2) {
          verticalTransLength = 8;
          horizontalTransLength = 8;
        }
      } else if (kTest == 3) {
        n2 = 4096*2+8;
        n1 = 8;
        // verticalBlockSize = 24;
        verticalBlockSize = 8;
        // horizontalBlockSize = 24;
        horizontalBlockSize = 8;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else if (kTest == 4) {
        n2 = 2400;
        n1 = 24;
        verticalBlockSize = 24;
        horizontalBlockSize = 24;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else if (kTest == 5) {
        n2 = 128;
        n1 = 128;
        verticalBlockSize = 32;
        horizontalBlockSize = 16;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else if (kTest == 6) {
        n2 = 127;
        n1 = 129;
        verticalBlockSize = 16;
        horizontalBlockSize = 16;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else if (kTest == 7) {
        n2 = 353;
        n1 = 235;
        verticalBlockSize = 32;
        horizontalBlockSize = 32;
        verticalTransLength = 16;
        horizontalTransLength = 16;
      } else if (kTest == 8) {
        n2 = 100;
        n1 = 501;
        verticalBlockSize = 64;
        horizontalBlockSize = 64;
        verticalTransLength = 16;
        horizontalTransLength = 16;
        distortion = 0.00001F;
      } else {
        assert false;
      }

      SeisPEG c = new SeisPEG(n1, n2, distortion,
                              verticalBlockSize, horizontalBlockSize,
                              verticalTransLength, horizontalTransLength);

      float[][] data = c.uncompressedBufferAlloc();
      CompressedData compressedData = c.compressedBufferAlloc();

      // We loop 3 times:
      //   0 - to test compress/uncompress
      //   1 - to test the SeisPEG(CompressedData) constructor
      //   2 - to test the reuse of the compression object.

      for (int testN=0; testN<3; testN++) {

        // The integrity test will only work if the trace values are
        // actually equal to integers.
        int icount = 0;
        if (kTest == 4) {
          for (int j=0; j<data.length; j++) {
            for (int i=0; i<data[0].length; i++) {
              data[j][i] = -(float)(icount/div);
              icount++;
            }
          }
        } else if (kTest == 6) {
          Random random = new Random(randomSeed);
          for (int j=0; j<data.length; j++) {
            for (int i=0; i<data[0].length; i++) {
              data[j][i] = (random.nextFloat() - 0.5F) * 100.0F;
            }
          }
        } else {
          for (int j=0; j<data.length; j++) {
            for (int i=0; i<data[0].length; i++) {
              data[j][i] = (icount/div);
              icount++;
            }
          }
        }

        compressedData = c.compress(data, compressedData);
        compressedData.checkIntegrity();
        if (testN == 2) {
          // See if there are any buffers that need to be reinitialized.
          compressedData = c.compress(data, compressedData);
          compressedData.checkIntegrity();
        }

        if (testN == 1) {
          // Test the SeisPEG(CompressedData) constructor.
          c = new SeisPEG(compressedData);
        }
	  
        c.uncompress(compressedData, data);
        compressedData.checkIntegrity();
        if (testN == 2) {
          // See if there are any buffers that need to be reinitialized.
          c.uncompress(compressedData, data);
          compressedData.checkIntegrity();
        }

        String fileName = "TestData" + testDataCount;
        if (testN == 0  &&  writingData) {
          // Write a copy of the data (only have to do this once to create the test).
          ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(fileName));
          CompressorTest.writeFloats(out, data, data[0].length, data.length);
          out.close();
        } else if (!writingData  &&  testDataCount != 8) {
          // Read the data and verify that the results are exactly the same.
          ObjectInputStream in = new ObjectInputStream(new FileInputStream(fileName));
          float[][] readData = new float[data.length][data[0].length];
          CompressorTest.readFloats(in, readData, readData[0].length, readData.length);
          for (int j=0; j<data.length; j++) {
            for (int i=0; i<data[0].length; i++) {
              // This test must be relaxed as the code changes because of floating
              // pointing roundoff errors.
              // assert data[j][i] == readData[j][i]:
              if (Math.abs(readData[j][i]) > 1.0F) {
                assert Math.abs((data[j][i]-readData[j][i])/readData[j][i]) < 0.0005F:
                  "Expected uncompressed data value " + readData[j][i]
                  + " but got value " + data[j][i];
              } else {
                // RELAXED FOR JAVA
                // assert Math.abs(data[j][i]-readData[j][i]) < 0.0001F:
                assert Math.abs(data[j][i]-readData[j][i]) < 0.0004F:
                  "Expected uncompressed data value " + readData[j][i]
                  + " but got value " + data[j][i] + "(j=" + j + ",i=" + i + ")";
              }
            }
          }
          in.close();
        }

        // float tolerableError = 0.06F;  Empirical value is too small
        float tolerableError = 0.07F;
        // if (kTest == 6) tolerableError = 0.0001F;  Empirical value is too small
        if (kTest == 6) tolerableError = 0.001F;
        if (kTest == 7) tolerableError = 0.09F;

        icount = 0;
        Random random = new Random(randomSeed);
        for (int j=0; j<data.length; j++) {
          for (int i=0; i<data[0].length; i++) {
            float expectedVal;
            if (kTest == 4) {
              expectedVal = -(float)(icount/div);
            } else if (kTest == 6) {
              expectedVal = (random.nextFloat() - 0.5F) * 100.0F;
            } else {
              expectedVal = (icount/div);
            }
            float err;
            if (Math.abs(expectedVal) < 1.0) {
              err = Math.abs(data[j][i] - expectedVal);
            } else {
              err = Math.abs((data[j][i] - expectedVal) / expectedVal);
            }
            if (icount != 0  &&  err > tolerableError) {
              throw new Exception("data["+j+"]["+i+"]= " + data[j][i]
                                  + " which is not close enough to " + expectedVal + " in test " + kTest);
            }
            icount++;
          }
        }

      }  // Loop over subtests.

      testDataCount++;

    }  // Loop over individual tests.

    LOG.info("org.atlas.seiszip.CompressorTest.originalTest ***** SUCCESS *****");
  }


  private static void integrityTest() throws DataCorruptedException {

    Transformer.c_integrityTest = true;
		
    int paddedN2 = 0, paddedN1 = 0, verticalBlockSize = 0, horizontalBlockSize = 0, i;
    int verticalTransLength = 0;
    int horizontalTransLength = 0;

    int div = 1;

    int ntests = 5;
    for (int j=0; j<ntests; j++) {

      if (j == 0) {
        paddedN2 = 128;
        paddedN1 = 512;
        verticalBlockSize = 8;
        horizontalBlockSize = 8;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else if (j == 1  ||  j == 2) {
        paddedN2 = 112;
        paddedN1 = 528;
        verticalBlockSize = 16;
        horizontalBlockSize = 16;
        if (j == 1) {
          verticalTransLength = 16;
          horizontalTransLength = 16;
        } else if (j == 2) {
          verticalTransLength = 8;
          horizontalTransLength = 8;
        }
      } else if (j == 3) {
        paddedN2 = 4096*2+8;
        paddedN1 = 8;
        verticalBlockSize = 8;
        horizontalBlockSize = 8;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else if (j == 4) {
        paddedN2 = 2400;
        paddedN1 = 24;
        verticalBlockSize = 24;
        horizontalBlockSize = 24;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else {
        throw new RuntimeException("Invalid test");
      }

      SeisPEG seisPEG = new SeisPEG();

      int nsampsTotal = paddedN2 * paddedN1;

      // TODO: We will need to add some extra space inside our class too.
      // Note that we pad by a factor or 2 (not sure why, but it's just a test).
      float[] traces = new float[nsampsTotal * 4];

      // Assume that the data doesn't grow during compression by more than
      // a factor of 2.
      // TODO: We will need to be conservative inside our class too.
      int outputBufferSize = nsampsTotal * SIZEOF_FLOAT * 4;
      byte[] cfsData =  new byte[outputBufferSize];

      // The integrity test will only work if the trace values are
      // actually integers.
      if (j == 4) {
        for (i=0; i<nsampsTotal; i++) traces[i] = -(float)(i/div);
      } else {
        for (i=0; i<nsampsTotal; i++) traces[i] = (i/div);
      }

      float distortion = 0.1F;
      float ftGainExponent = 0.0F;
      int nbytes = seisPEG.compress2D(traces, paddedN1, paddedN2, paddedN1, paddedN2,
                                      distortion, ftGainExponent, verticalBlockSize, horizontalBlockSize,
                                      verticalTransLength, horizontalTransLength, cfsData,
                                      outputBufferSize);
      assert nbytes != 0;

      seisPEG.uncompress2D(cfsData, outputBufferSize, traces);

      int nwrong = 0;
      for (i=0; i<nsampsTotal; i++) {
        if (j==4  &&  traces[i] != -(float)(i/div)
            ||  j!=4  &&  traces[i] != (i/div)) {
          LOG.info("traces["+i+"]= " + traces[i] + " which is != " + (float)i);
          nwrong++;
        }
        if (nwrong == 5) {
          if (j == 4) {
            assert traces[i] == -(float)(i/div);
          } else {
            assert traces[i] == (i/div);
          }
        }
      }

    }

    LOG.info("org.atlas.seiszip.CompressorTest.integrityTest ***** SUCCESS *****");
  }


  private static void badAmplitudeTest() throws Exception {

    float distortion = 0.3F;
    int n2 = 0;
    int n1 = 0;
    int verticalBlockSize = 0;
    int horizontalBlockSize = 0;
    int verticalTransLength = 0;
    int horizontalTransLength = 0;

    CompressedData compressedData = null;

    int ntests = 2;
    for (int kTest=0; kTest<ntests; kTest++) {

      if (kTest == 0) {
        n2 = 127;
        n1 = 513;
        verticalBlockSize = 8;
        horizontalBlockSize = 8;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else if (kTest == 1) {
        n2 = 128;
        n1 = 512;
        verticalBlockSize = 16;
        horizontalBlockSize = 16;
        verticalTransLength = 16;
        horizontalTransLength = 16;
      } else {
        assert false;
      }

      SeisPEG c = new SeisPEG(n1, n2, distortion,
                              verticalBlockSize, horizontalBlockSize,
                              verticalTransLength, horizontalTransLength);

      float[][] traces = c.uncompressedBufferAlloc();

      if (kTest == 0) compressedData = c.compressedBufferAlloc();

      for (int j=0; j<traces.length; j++) {
        for (int i=0; i<traces[j].length; i++) {
          if (j == traces.length/2  &&  i == traces[j].length/2) {
            // Insert a bad value in the middle.
            if (kTest == 0) {
              traces[j][i] = (float)1.0E16;
            } else {
              traces[j][i] = (float)-1.0E16;
            }
          } else {
            traces[j][i] = ((j+1) * 1000 + (i+1));
          }
        }
      }

      compressedData = c.compress(traces, compressedData);

      if (kTest == 0) {
        c.uncompress(compressedData, traces);
      } else {
        // This only works because the uncompressed buffer is large enough from the first time.
        c.uncompress(compressedData.getData(), compressedData.getDataLength(), traces);
      }

      for (int j=0; j<traces.length; j++) {
        for (int i=0; i<traces[j].length; i++) {
          float expectedValue;
          if (j == 0  &&  i < SeisPEG.LEN_HDR_INFO) {
            expectedValue = 0.0F;
          } else if (j == traces.length/2  &&  i == traces[j].length/2) {
            // Insert a bad value in the middle.
            if (kTest == 0) {
              expectedValue = (float)1.0E16;
            } else {
              expectedValue = (float)-1.0E16;
            }
          } else {
            expectedValue = ((j+1) * 1000 + (i+1));
          }
          assert traces[j][i] == expectedValue:
            traces[j][i] + "!=" + expectedValue + " " + j + " " + i;
        }
      }



    }

    LOG.info("org.atlas.seiszip.CompressorTest.badAmplitudeTest ***** SUCCESS *****");
  }


  private static void combinedTest() throws Exception {

    float distortion = 0.3F;
    int n2 = 0;
    int n1 = 0;
    int verticalBlockSize = 0;
    int horizontalBlockSize = 0;
    int verticalTransLength = 0;
    int horizontalTransLength = 0;

    int hdrLength = 73;

    int ntests = 4;
    for (int kTest=0; kTest<ntests; kTest++) {

      if (kTest == 0  ||  kTest == 1) {
        n2 = 127;
        n1 = 513;
        verticalBlockSize = 8;
        horizontalBlockSize = 8;
        verticalTransLength = 8;
        horizontalTransLength = 8;
      } else if (kTest == 2  ||  kTest == 3) {
        n2 = 128;
        n1 = 512;
        verticalBlockSize = 16;
        horizontalBlockSize = 16;
        verticalTransLength = 16;
        horizontalTransLength = 16;
      } else {
        assert false;
      }

      SeisPEG c = new SeisPEG(n1, n2, distortion,
                              verticalBlockSize, horizontalBlockSize,
                              verticalTransLength, horizontalTransLength);

      float[][] traces = c.uncompressedBufferAlloc();

      for (int j=0; j<traces.length; j++) {
        for (int i=0; i<traces[j].length; i++) {
          if (j == traces.length/2  &&  i == traces[j].length/2) {
            if (kTest == 0) {
              // Insert a bad value in the middle.
              traces[j][i] = (float)1.0E16;
            } else if (kTest == 1) {
              // Insert a bad value in the middle.
              traces[j][i] = (float)-1.0E16;
            } else {
              traces[j][i] = ((j+1) * 1000 + (i+1));
            }
          } else {
            if (i < j+1) {
              traces[j][i] = 0.0F;  // A mute.
            } else {
              traces[j][i] = ((j+1) * 1000 + (i+1));
            }
          }
        }
      }

      byte[] byteArray = new byte[traces.length*hdrLength*SIZEOF_INT];
      ByteBuffer byteBuffer = ByteBuffer.wrap(byteArray);
      IntBuffer hdrIntBuffer = byteBuffer.asIntBuffer();

      java.util.Random random = new java.util.Random(28252388L);
      int count = 0;
      for (int j=0; j<traces.length; j++) {
        for (int i=0; i<hdrLength; i++) {
          int value = 0;
          if (kTest == 0) {
            value = i * j;
          } else if (kTest == 1) {
            value = i;
          } else if (kTest == 2) {
            value = (int)(random.nextDouble() * 100000.0);
          } else if (kTest == 3  ||  kTest == 4) {
            value = (i+1) * (j+1);
          }
          hdrIntBuffer.put(count, value);
          count++;
        }
      }

      byte[] outputData = new byte[traces.length*traces[0].length*SIZEOF_FLOAT
                                   + HdrCompressor.getOutputBufferSize(hdrLength, traces.length)];

      int nBytes = c.compress(traces, traces.length, hdrIntBuffer, hdrLength, outputData);

      int nLive = c.uncompress(outputData, nBytes, traces, hdrIntBuffer);

      for (int j=0; j<traces.length; j++) {
        for (int i=0; i<traces[j].length; i++) {
          float expectedValue;
          if (j == 0  &&  i < SeisPEG.LEN_HDR_INFO) {
            expectedValue = 0.0F;
          } else if (j == traces.length/2  &&  i == traces[j].length/2) {
            // Insert a bad value in the middle.
            if (kTest == 0) {
              expectedValue = (float)1.0E16;
            } else if (kTest == 1) {
              expectedValue = (float)-1.0E16;
            } else {
              expectedValue = ((j+1) * 1000 + (i+1));
            }
          } else {
            if (i < j+1) {
              expectedValue = 0.0F;  // A mute.
            } else {
              expectedValue = ((j+1) * 1000 + (i+1));
            }
          }
          if (kTest == 0  ||  kTest == 1) {
            assert traces[j][i] == expectedValue:
              traces[j][i] + "!=" + expectedValue;
          } else {
            if (i < j+1) {
              // We have changed the data a lot, but we can check the remute.
              assert traces[j][i] == expectedValue;
            }
          }
        }
      }

      random = new java.util.Random(28252388L);
      count = 0;
      for (int j=0; j<traces.length; j++) {
        for (int i=0; i<hdrLength; i++) {
          int expectedValue = 0;
          if (kTest == 0) {
            expectedValue = i * j;
          } else if (kTest == 1) {
            expectedValue = i;
          } else if (kTest == 2) {
            expectedValue = (int)(random.nextDouble() * 100000.0);
          } else if (kTest == 3  ||  kTest == 4) {
            expectedValue = (i+1) * (j+1);
          }
          assert hdrIntBuffer.get(count) == expectedValue;
          count++;
        }
      }

    }

    LOG.info("org.atlas.seiszip.CompressorTest.combinedTest ***** SUCCESS *****");
  }


  private static void hdrRunLengthTest() {

    HdrCompressor hdrCompressor = new HdrCompressor();

    int[] encodedValues = new int[1024];  // Plenty big.
    int[] outValues = new int[1024];  // Plenty big.

    int runSymbolConst   = 34324234;
    int runSymbolAscend  = 45235923;
    int runSymbolDescend = 25834568;
    int runSymbolDelta   = 58234832;
    int runSymbolFloats  = 68343921;
    int endOfData = runSymbolConst;

    int nTests = 14;
    int[][] inValues = new int[nTests][];
    int[] expectedOutputLengths = new int[nTests];

    // Note that the expected output lengths do not include the endOfData value.

    inValues[0] = new int[]{0,1,-1,2,-2,3,-3,4,-4,endOfData};
    expectedOutputLengths[0] = inValues[0].length - 1 + HdrCompressor.HDR_LENGTH;  // No runs.
    inValues[1] = new int[]{0,0,0,1,-1,2,-2,3,3,3,-3,4,-4,endOfData};
    expectedOutputLengths[1] = inValues[1].length - 1 + HdrCompressor.HDR_LENGTH;  // No runs over 3.
    inValues[2] = new int[]{0,0,0,0,1,-1,2,-2,3,3,3,-3,4,-4,endOfData};
    expectedOutputLengths[2] = inValues[2].length - 1 -1 + HdrCompressor.HDR_LENGTH;  // One run of 4.
    inValues[3] = new int[]{0,0,0,0,1,2,3,4,-1,2,-2,-3,-4,-5,3,3,3,-3,4,-4,endOfData};
    expectedOutputLengths[3] = inValues[3].length - 3 -1 + HdrCompressor.HDR_LENGTH;  // Three runs of 4.
    inValues[4] = new int[]{0,0,0,0,0,0,1,2,3,4,5,6,-1,2,-2,-3,-4,-5,-6,3,3,3,-3,4,-4,endOfData};
    expectedOutputLengths[4] = expectedOutputLengths[3];  // Longer/different runs.
    inValues[5] = new int[]{0,1,2,3,4,5,1,2,3,4,5,6,-1,2,-2,-3,-4,-5,-6,3,3,3,-3,4,-4,endOfData};
    expectedOutputLengths[5] = expectedOutputLengths[3];  // Longer/different runs.
    inValues[6] = new int[]{6,5,4,3,2,1,1,2,3,4,5,6,-1,2,-2,-3,-4,-5,-6,3,3,3,-3,4,-4,endOfData};
    expectedOutputLengths[6] = expectedOutputLengths[3];  // Longer/different runs.

    // Delta tests.
    inValues[7] = new int[]{-2,0,2,4,6,8,10,12,14,16,endOfData};
    expectedOutputLengths[7] = 4 + HdrCompressor.HDR_LENGTH;  // One ascending delta=2 run.
    inValues[8] = new int[]{-2,-5,-8,-11,-14,-17,-20,-23,-26,-29,endOfData};
    expectedOutputLengths[8] = 4 + HdrCompressor.HDR_LENGTH;  // One descending delta=-3 run.
    inValues[9] = new int[]{1,2,4,6,8,10,12,14,16,17,endOfData};
    expectedOutputLengths[9] = 4 + 2 + HdrCompressor.HDR_LENGTH;  // One ascending delta=2 run plus 2 other values

    // Float tests.
    inValues[10] = new int[]{Float.floatToIntBits(1.0F), Float.floatToIntBits(11.0F),
                             Float.floatToIntBits(21.0F), Float.floatToIntBits(31.0F),
                             Float.floatToIntBits(41.0F), Float.floatToIntBits(51.0F),
                             Float.floatToIntBits(61.0F), Float.floatToIntBits(71.0F), endOfData};
    expectedOutputLengths[10] = 4 + HdrCompressor.HDR_LENGTH;  // One ascending delta=10.0 float run.
    inValues[11] = new int[]{Float.floatToIntBits(10000010.0F), Float.floatToIntBits(10000110.0F),
                             Float.floatToIntBits(10000210.0F), Float.floatToIntBits(10000310.0F),
                             Float.floatToIntBits(10000410.0F), Float.floatToIntBits(10000510.0F),
                             Float.floatToIntBits(10000610.0F), Float.floatToIntBits(10000710.0F),
                             endOfData};
    expectedOutputLengths[11] = 4 + HdrCompressor.HDR_LENGTH;  // One ascending delta=100.0 float run.
    // NOTE: These floats are too large to compress!
    inValues[12] = new int[]{Float.floatToIntBits(100000010.0F), Float.floatToIntBits(100000110.0F),
                             Float.floatToIntBits(100000210.0F), Float.floatToIntBits(100000310.0F),
                             Float.floatToIntBits(100000410.0F), Float.floatToIntBits(100000510.0F),
                             Float.floatToIntBits(100000610.0F), Float.floatToIntBits(100000710.0F),
                             endOfData};
    expectedOutputLengths[12] = 8 + HdrCompressor.HDR_LENGTH;  // One ascending delta=100.0 float run.
    inValues[13] = new int[]{Float.floatToIntBits(-1.0F), Float.floatToIntBits(-101.0F),
                             Float.floatToIntBits(-201.0F), Float.floatToIntBits(-301.0F),
                             Float.floatToIntBits(-401.0F), Float.floatToIntBits(-501.0F),
                             Float.floatToIntBits(-601.0F), Float.floatToIntBits(-701.0F), endOfData};
    expectedOutputLengths[13] = 4 + HdrCompressor.HDR_LENGTH;  // One ascending delta=10.0 float run.


    for (int j=0; j<inValues.length; j++) {
      int nCompressed = hdrCompressor.runLengthEncode(inValues[j], runSymbolConst, runSymbolAscend,
                                                      runSymbolDescend, runSymbolDelta,
                                                      runSymbolFloats, endOfData, encodedValues);
      assert nCompressed == expectedOutputLengths[j]:
        nCompressed + "!=" +  expectedOutputLengths[j] + " " + j;

      int nUncompressed = hdrCompressor.runLengthDecode(encodedValues, outValues);
      assert nUncompressed == inValues[j].length - 1:
        nUncompressed + "!=" + (inValues[j].length-1) + " " + j;

      for (int i=0; i<nUncompressed; i++)
        assert outValues[i] == inValues[j][i];
    }

    LOG.info("org.atlas.seiszip.CompressorTest.hdrRunLengthTest ***** SUCCESS *****");
  }


  private static void hdrIntBufferTest() throws Exception {

    HdrCompressor hdrCompressor = new HdrCompressor();

    // TODO: Check the random case.

    int offset = 123;
    int nTests = 5;
    int[] hdrLength = new int[]{55,  55,  550, 72, 1};
    int[] nTraces =   new int[]{100, 100, 100, 1,  72};

    for (int iTest=0; iTest<nTests; iTest++) {

      int bufferByteSize = hdrLength[iTest] * nTraces[iTest] * SIZEOF_INT;

      byte[] byteArray = new byte[offset+bufferByteSize];
      ByteBuffer byteBuffer = ByteBuffer.wrap(byteArray);
      IntBuffer hdrIntBuffer = byteBuffer.asIntBuffer();

      java.util.Random random = new java.util.Random(28252388L);
      int count = 0;
      for (int j=0; j<nTraces[iTest]; j++) {
        for (int i=0; i<hdrLength[iTest]; i++) {
          int value = 0;
          if (iTest == 0) {
            value = i * j;
          } else if (iTest == 1) {
            value = i;
          } else if (iTest == 2) {
            value = (int)(random.nextDouble() * 100000.0);
          } else if (iTest == 3  ||  iTest == 4) {
            value = (i+1) * (j+1);
          }
          hdrIntBuffer.put(count, value);
          count++;
        }
      }

      int nBytes = hdrCompressor.compress(hdrIntBuffer, hdrLength[iTest],
                                          (float[][])null, nTraces[iTest],
                                          byteArray, offset);

      // Zero everything beyond the compressed data.
      for (int i=nBytes; i<bufferByteSize; i++) byteArray[i+offset] = (byte)0;

      int nLive = hdrCompressor.uncompress(byteArray, offset, nBytes,
                                           hdrIntBuffer, (float[][])null);
      assert nLive == nTraces[iTest];

      // Check the values.
      random = new java.util.Random(28252388L);
      count = 0;
      for (int j=0; j<nTraces[iTest]; j++) {
        for (int i=0; i<hdrLength[iTest]; i++) {
          int expectedValue = 0;
          if (iTest == 0) {
            expectedValue = i * j;
          } else if (iTest == 1) {
            expectedValue = i;
          } else if (iTest == 2) {
            expectedValue = (int)(random.nextDouble() * 100000.0);
          } else if (iTest == 3  ||  iTest == 4) {
            expectedValue = (i+1) * (j+1);
          }
          assert hdrIntBuffer.get(count) == expectedValue;
          count++;
        }
      }

    }

    LOG.info("org.atlas.seiszip.CompressorTest.hdrIntBufferTest ***** SUCCESS *****");
  }


  private static void hdrFullMethodTest() throws Exception {

    HdrCompressor hdrCompressor = new HdrCompressor();

    int nTraces = 120;
    int hdrLength = 62;

    int[][] hdrs = new int[nTraces][hdrLength];

    Random random = new Random(53453653L);
    for (int i=0; i<hdrLength; i++) {
      for (int j=0; j<nTraces; j++) {
        if (i < 5) {
          hdrs[j][i] = j;
        } else if (i < 10) {
          hdrs[j][i] = Float.floatToIntBits(j);
        } else if (i < 15) {
          hdrs[j][i] = i;
        } else {
          hdrs[j][i] = (int)(random.nextDouble() * 100.0);
        }
      }
    }

    int offset = 100;  // Test the offset parameter.
    byte[] encodedBytes = new byte[offset+HdrCompressor.getOutputBufferSize(hdrLength, nTraces)];
    int nBytes = hdrCompressor.compress(hdrs, hdrLength, (float[][])null, nTraces,
                                        encodedBytes, offset);

    int nLive = hdrCompressor.uncompress(encodedBytes, offset, nBytes, hdrs, (float[][])null);
    assert nLive == nTraces;

    random = new Random(53453653L);
    for (int i=0; i<hdrLength; i++) {
      for (int j=0; j<nTraces; j++) {
        int expectedVal;
        if (i < 5) {
          expectedVal = j;
        } else if (i < 10) {
          expectedVal = Float.floatToIntBits(j);
        } else if (i < 15) {
          expectedVal = i;
        } else {
          expectedVal = (int)(random.nextDouble() * 100.0);
        }
        assert hdrs[j][i] == expectedVal;
      }
    }

    LOG.info("org.atlas.seiszip.CompressorTest.hdrFullMethodTest ***** SUCCESS *****");
  }


  /**
   * Entry point for test harness.
   * You must run this once with the -write flag to create the reference data files,
   * if you don't already have them.
   */
  public static void main(String[] args) throws Exception {

    CompressorTest.originalTest(true);
    CompressorTest.originalTest(false);
    CompressorTest.integrityTest();
    CompressorTest.badAmplitudeTest();
    CompressorTest.combinedTest();

    // Header compressor tests.
    CompressorTest.hdrRunLengthTest();
    CompressorTest.hdrIntBufferTest();
    CompressorTest.hdrFullMethodTest();

    System.exit(0);
  }

}
