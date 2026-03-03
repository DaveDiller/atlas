
package org.atlas.seiszip;

import java.util.Random;

/**
 * This class compares HuffCoder.java to the C/C++ version(s).
 */

public class HuffCoder2VersionTest {

  private static void basicTest() {

    Random random = new Random(563344233L);

    boolean useNativeMethods = false;
    org.atlas.seiszip.HuffCoder2 huffCoder_seiszip_Java = new org.atlas.seiszip.HuffCoder2(useNativeMethods);
    useNativeMethods = true;
    org.atlas.seiszip.HuffCoder2 huffCoder_seiszip_Native = new org.atlas.seiszip.HuffCoder2(useNativeMethods);

    long elapsedTimeEncodeNs3 = 0L;
    long elapsedTimeEncodeNs4 = 0L;
    long elapsedTimeDecodeNs3 = 0L;
    long elapsedTimeDecodeNs4 = 0L;

    // int nTests = 5;
    // int nTests = 500;
    int nTests = 5000000;
    for (int k=0; k<nTests; k++) {

      // int ninputBytes = Math.round(random.nextFloat() * 1000.0f);
      // int ninputBytes = Math.round(random.nextFloat() * 10000.0f);
      // int ninputBytes = Math.round(random.nextFloat() * 100000.0f);
      int ninputBytes = Math.round(random.nextFloat() * 10000000.0f);
      ninputBytes = Math.min(1, ninputBytes);

      byte[] runLengthEncodedData3 = new byte[ninputBytes];
      byte[] runLengthEncodedData4 = new byte[ninputBytes];
      for (int i=0; i<runLengthEncodedData3.length; i++) {
        runLengthEncodedData3[i] = (byte)Math.round((random.nextFloat() - 0.5f) * 200.0f);  // Range -100 to 100.
        runLengthEncodedData4[i] = runLengthEncodedData3[i];
      }

      // int outputBufferSize = ninputBytes * 2;  // Not big enough.
      int outputBufferSize = ninputBytes * 10;
      byte[] huffEncodedData3 = new byte[outputBufferSize];
      byte[] huffEncodedData4 = new byte[outputBufferSize];

      int index = 0;

      long startTimeNs3 = System.nanoTime();
      int nBytes3 = huffCoder_seiszip_Java.huffEncode(runLengthEncodedData3, ninputBytes, huffEncodedData3, index, outputBufferSize);
      elapsedTimeEncodeNs3 += (System.nanoTime() - startTimeNs3);

      long startTimeNs4 = System.nanoTime();
      int nBytes4 = huffCoder_seiszip_Native.huffEncode(runLengthEncodedData4, ninputBytes, huffEncodedData4, index, outputBufferSize);
      elapsedTimeEncodeNs4 += (System.nanoTime() - startTimeNs4);
      
      assert nBytes3 != 0;
      assert nBytes3 == nBytes4;
      for (int i=0; i<nBytes3; i++) {
        assert huffEncodedData3[i] == huffEncodedData4[i];
      }
 
      byte[] cHout3 = new byte[outputBufferSize];
      byte[] cHout4 = new byte[outputBufferSize];

      long startTimeNs7 = System.nanoTime();
      int nBytes7 = huffCoder_seiszip_Java.huffDecode(huffEncodedData3, index, cHout3, outputBufferSize);
      elapsedTimeDecodeNs3 += (System.nanoTime() - startTimeNs7);

      long startTimeNs8 = System.nanoTime();
      int nBytes8 = huffCoder_seiszip_Java.huffDecode(huffEncodedData4, index, cHout4, outputBufferSize);
      elapsedTimeDecodeNs4 += (System.nanoTime() - startTimeNs8);
      
      assert nBytes7 != 0;
      assert nBytes7 == nBytes8;
      for (int i=0; i<nBytes7; i++) {
        assert cHout3[i] == cHout4[i];
      }

      assert nBytes7 == ninputBytes;
      for (int i=0; i<nBytes7; i++) {
        assert cHout3[i] == runLengthEncodedData3[i];
        assert cHout3[i] == runLengthEncodedData4[i];
      }

    }

    huffCoder_seiszip_Java.free();
    huffCoder_seiszip_Native.free();

    System.out.println("HuffCoder2VersionTest: seiszip    Java encode elapsedTime= " + ((double)elapsedTimeEncodeNs3 / 1000000000.0));
    System.out.println("HuffCoder2VersionTest: seiszip     C++ encode elapsedTime= " + ((double)elapsedTimeEncodeNs4 / 1000000000.0));
    System.out.println("HuffCoder2VersionTest: seiszip    Java decode elapsedTime= " + ((double)elapsedTimeDecodeNs3 / 1000000000.0));
    System.out.println("HuffCoder2VersionTest: seiszip     C++ decode elapsedTime= " + ((double)elapsedTimeDecodeNs4 / 1000000000.0));

    System.out.println("org.atlas.seiszip.HuffCoder2VersionTest.basicTest ****** SUCCESS ******");
  }


  public static void main(String[] args) {

    HuffCoder2VersionTest.basicTest();
  }

}
