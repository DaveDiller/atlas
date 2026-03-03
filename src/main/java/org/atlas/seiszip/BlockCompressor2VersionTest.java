
package org.atlas.seiszip;

import java.util.Random;

/**
 * This class compares BlockCompressor2.java to the C/C++ version(s).
 */

public class BlockCompressor2VersionTest {

  private static void basicTest() {

    Random random = new Random(2534555299L);

    boolean useNativeMethods = false;
    org.atlas.seiszip.BlockCompressor2 blockCompressor2_seiszip_Java = new org.atlas.seiszip.BlockCompressor2(useNativeMethods);
    useNativeMethods = true;
    org.atlas.seiszip.BlockCompressor2 blockCompressor2_seiszip_Native = new org.atlas.seiszip.BlockCompressor2(useNativeMethods);

    long elapsedTimeEncodeNs3 = 0L;
    long elapsedTimeEncodeNs4 = 0L;
    long elapsedTimeDecodeNs3 = 0L;
    long elapsedTimeDecodeNs4 = 0L;

    float distortion = 0.2f;

    // int nTests = 50000;
    int nTests = 200000;
    for (int k=0; k<nTests; k++) {

      // int nsamps = Math.round(random.nextFloat() * 1000000.0f);
      int nsamps = Math.round(random.nextFloat() * 100000000.0f);
      nsamps = Math.min(1, nsamps);

      float[] data3 = new float[nsamps*2];
      float[] data4 = new float[nsamps*2];
      float[] data7 = new float[nsamps*2];
      float[] data8 = new float[nsamps*2];
      for (int i=0; i<data3.length; i++) {
        data3[i] = (random.nextFloat() - 0.5f) * 200.0f;  // Range -100 to 100.
        data4[i] = data3[i];
      }

      int outputBufferSize = nsamps * 50;
      byte[] encodedData3 = new byte[outputBufferSize];
      byte[] encodedData4 = new byte[outputBufferSize];
      int workBufferSize = outputBufferSize;
      byte[] workBuffer = new byte[workBufferSize];

      int index = 0;

      long startTimeNs3 = System.nanoTime();
      int nBytes3 = blockCompressor2_seiszip_Java.dataEncode(data3, nsamps, distortion, encodedData3, index, outputBufferSize);
      elapsedTimeEncodeNs3 += (System.nanoTime() - startTimeNs3);

      long startTimeNs4 = System.nanoTime();
      int nBytes4 = blockCompressor2_seiszip_Native.dataEncode(data4, nsamps, distortion, encodedData4, index, outputBufferSize);
      elapsedTimeEncodeNs4 += (System.nanoTime() - startTimeNs4);
      
      // This shows that the results are the same from all.
      assert nBytes3 != 0;
      assert nBytes3 == nBytes4;
      for (int i=0; i<nBytes3; i++) {
        assert encodedData3[i] == encodedData4[i];
      }
 
      long startTimeNs7 = System.nanoTime();
      int n3 = blockCompressor2_seiszip_Java.dataDecode(encodedData3, index, workBuffer, workBufferSize, nsamps, data7);
      elapsedTimeDecodeNs3 += (System.nanoTime() - startTimeNs7);

      long startTimeNs8 = System.nanoTime();
      int n4 = blockCompressor2_seiszip_Native.dataDecode(encodedData4, index, workBuffer, workBufferSize, nsamps, data8);
      elapsedTimeDecodeNs4 += (System.nanoTime() - startTimeNs8);
      
      assert n3 != -1;
      assert n3 == n4;

      // This shows that the results are the same from all.
      for (int i=0; i<nsamps; i++) {
        assert data7[i] == data8[i];
      }

    }

    System.out.println();
    System.out.println("BlockCompressor2VersionTest: seiszip    Java encode elapsedTime= " + ((double)elapsedTimeEncodeNs3 / 1000000000.0));
    System.out.println("BlockCompressor2VersionTest: seiszip     C++ encode elapsedTime= " + ((double)elapsedTimeEncodeNs4 / 1000000000.0));
    System.out.println("BlockCompressor2VersionTest: seiszip    Java decode elapsedTime= " + ((double)elapsedTimeDecodeNs3 / 1000000000.0));
    System.out.println("BlockCompressor2VersionTest: seiszip     C++ decode elapsedTime= " + ((double)elapsedTimeDecodeNs4 / 1000000000.0));
    System.out.println();

    System.out.println("org.atlas.seiszip.BlockCompressor2VersionTest.basicTest"
		       + " nTests=" + nTests
		       + " ****** SUCCESS ******");
  }


  public static void main(String[] args) {

    BlockCompressor2VersionTest.basicTest();
  }

}
