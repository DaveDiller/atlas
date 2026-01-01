
package org.atlas.seiszip;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.Random;

/**
 * This class is used to research the possibility of creating better Huffman tables
 * by making small changes to the existing table then then checking for improvement
 * in the compression ratio.
 * <p>
 * This class uses the first shot record from the Magnesium dataset, which is not
 * included in the data repository because of its large size.
 */

public class HuffTableRandomBuilder {


  static int[] globalCount_ORIGINAL = {
    3,     47538, 16548, 9566,  6868,  5231,  4206,  2748,
    1106,  442,   192,   104,   85,    118,   157,   160,
    56,    38,    39,    45,    72,    64,    95,    55,
    50,    81,    113,   161,   268,   238,   182,   104,
    430,   883,   1173,  1242,  1201,  870,   574,   281,
    452,   927,   1213,  1300,  1194,  931,   791,   565,
    330,   307,   285,   306,   358,   399,   412,   366,
    138,   62,    39,    53,    67,    76,    137,   127,
    49,    11,    9,     2,     1,     1,     3,     9,
    6,     2,     1,     1,     2,     1,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     1,     2,     3,     4,     1,     3,
    2,     739,   8,     3,     8,     7,     8,     4,
    4,     6,     2,     6,     7,     12,    17,    9,
    2,     1,     1,     3,     5,     9,     14,    36,
    1,     2,     1,     1,     1,     1,     1,     2,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     2,     1,     3,     3,     17,    37,
    95,    162,   271,   385,   484,   605,   771,   964,
    1190,  1329,  1584,  1924,  2511,  3212,  4340,  5972,
    8918,  14712, 28038, 88353, 8,     88613, 28514, 14711,
    8918,  6080,  4316,  3251,  2554,  2053,  1681,  1450,
    1166,  933,   757,   695,   486,   386,   313,   162,
    102,   53,    21,    5,     3,     3,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     2,
    1,     3,     9,     11,    9,     9,     5,     3,
    1,     7,     7,     22,    41,    30,    17,    4,
    2,     4,     6,     24,    23,    23,    13,    9,
    6,     2,     3,     8,     10,    17,    25,    51,
    1
  };


  /*
   * This table was created by over 12 hours of running.  The starting ratio
   * was 11.47720818778057, the ending ratio was 11.93894563053366.
   * The parameters were:
   *   float distortion = 0.1F;
   *   int verticalBlockSize = 32;
   *   int horizontalBlockSize = 32;
   *   int verticalTransLength = 8;
   *   int horizontalTransLength = 8;
   * When re-applied with more aggressive parameters (large blocks), the initial ratio
   * went from 26.543981124839885  to 26.736746714997736, so it appears to be a fundamentally
   * better table.
   */

  // static int[] globalCount_8_5_07a = {
  static int[] globalCount = {
    40,    155459,    34503,    71011,    14891,    6404,    2566,    4740,
    5776,    7772,    2378,    1179,    15336,    5033,    4353,    1519,
    4330,    1857,    1098,    683,    1175,    774,    1053,    455,
    345,    468,    189,    528,    258,    198,    117,    139,
    44,    44,    64,    104,    33,    21,    29,    57,
    35,    28,    8,    47,    72,    66,    35,    52,
    50,    23,    71,    31,    52,    40,    29,    26,
    45,    32,    17,    7,    70,    44,    5,    33,
    57,    73,    6,    47,    6,    17,    14,    70,
    31,    2,    49,    36,    91,    49,    24,    20,
    70,    45,    28,    8,    45,    21,    29,    22,
    32,    3,    17,    1,    29,    21,    29,    7,
    70,    34,    23,    48,    27,    89,    574,    406,
    423,    76,    19,    29,    31,    10,    23,    31,
    24,    1,    25,    17,    30,    19,    4,    1,
    1,    16,    13,    28,    41,    10,    11,    12,
    29,    19,    29,    20,    18,    23,    31,    23,
    34,    36,    13,    48,    24,    34,    39,    34,
    14,    72,    33,    30,    43,    111,    49,    135,
    90,    115,    46,    120,    82,    138,    95,    393,
    164,    521,    778,    1081,    479,    1080,    965,    1562,
    1939,    3882,    2438,    4849,    14271,    14290,    9459,    20817,
    72187,    139976,    116275,    522471,    16,    203273,    79272,    62576,
    61966,    29379,    13868,    16107,    9490,    2928,    4506,    3304,
    576,    1053,    944,    767,    358,    381,    501,    331,
    419,    274,    104,    268,    37,    85,    122,    28,
    112,    31,    63,    50,    68,    48,    47,    18,
    11,    19,    20,    32,    40,    27,    7,    18,
    14,    36,    25,    18,    35,    47,    47,    14,
    29,    17,    36,    1,    3,    23,    16,    17,
    8,    13,    7,    1,    5,    70,    20,    7,
    33,    2,    16,    27,    27,    30,    63,    287,
    3817
  };


  private static void traceSampleTest() throws Exception {

    float distortion = 0.1F;

    float[][] originalData;

    File stopFile = new File("STOP_FILE");

    // The file contains a bunch of "null" (all-zero) traces.  ???
    String fileName = "MagnesiumShot0";
    int n2 = 2560;  // Traces per shot.
    int n1 = 2305;  // Samples per trace.

    ObjectInputStream in = new ObjectInputStream(new FileInputStream(fileName));
    originalData = new float[n2][n1];
    CompressorTest.readFloats(in, originalData, originalData[0].length, originalData.length);
    in.close();

    // Compression ratio= 11.47720818778057
    // Uncompression megs/sec= 1.9780110391036905
    /*
    int verticalBlockSize = 32;
    int horizontalBlockSize = 32;
    int verticalTransLength = 8;
    int horizontalTransLength = 8;
    */

    int verticalBlockSize = n1 / 8 * 8;
    if (verticalBlockSize < n1) verticalBlockSize += 8;
    int horizontalBlockSize = n2 / 8 * 8;
    if (horizontalBlockSize < n2) horizontalBlockSize += 8;
    int verticalTransLength = 8;
    int horizontalTransLength = 8;

    float[][] uncompressedData = new float[n2][n1];
    int nbytesUncompressed = n1 * n2 * 4;

    // In real life we typically only create one compressor, and then run it
    // on lots of data.

    Random random = new Random(232384L);

    CompressedData compressedData = null;
    int nbytesCompressedLast = Integer.MAX_VALUE;

    int count = 0;
    while (true) {

      int savedCount = 0;
      int index = 0;
      if (count > 0) {
        // Perterb the huffman table.
        index = (int)Math.round(random.nextDouble() * 258.0);
        if (index < 0) index = 0;
        if (index > globalCount.length-1) index = globalCount.length-1;
        savedCount = globalCount[index];
        if (count%2 == 0) {
          // This is an integer range of -50 to 50.
          int randomChange = (int)Math.round((random.nextDouble() - 0.5) * 100.0);
          globalCount[index] += randomChange;
        } else {
          double scalar = random.nextDouble() * 2.0;  // Range of 0.0 to 2.0 (exclusive);
          globalCount[index] = (int)(globalCount[index] * scalar);
        }
        if (globalCount[index] < 1) globalCount[index] = 1;
      }

      SeisPEG seisPEG = new SeisPEG(globalCount, n1, n2, distortion,
                                    verticalBlockSize, horizontalBlockSize,
                                    verticalTransLength, horizontalTransLength);

      if (count == 0)  // Only need this once.
        compressedData = seisPEG.compressedBufferAlloc();

      compressedData = seisPEG.compress(originalData, compressedData);

      seisPEG.uncompress(compressedData, uncompressedData);

      int nbytesCompressed = compressedData.getDataLength();

      if (nbytesCompressed <= nbytesCompressedLast) {
        // As good or better table.  Keep this change.
        if (nbytesCompressed < nbytesCompressedLast) {
          // It got better.  Gloat.
          if (nbytesCompressedLast != Integer.MAX_VALUE) {
            double compressionRatio = (double)nbytesUncompressed / (double)nbytesCompressed;
            System.out.println("Changed globalCount["+index+"] from "
                               + savedCount + " to " + globalCount[index]
                               + " compression ratio= " + compressionRatio
                               + " count= " + count);
          } else {
            double compressionRatio = (double)nbytesUncompressed / (double)nbytesCompressed;
            System.out.println("Compression ratio= " + compressionRatio);
          }
        }
        nbytesCompressedLast = nbytesCompressed;
      } else {
        // Oops.  Got worse.
        globalCount[index] = savedCount;
      }

      if (stopFile.exists()) {
        HuffCoder.printTable(globalCount);
        System.exit(0);
      }

      count++;
    }  // End of endless loop.

  }


  private static void traceHeaderTest() throws Exception {

    File stopFile = new File("STOP_FILE");

    int[] hdrHuffCount = HuffCoder.c_hdrHuffCount.clone();

    // int[] globalCount = (int[])HuffCoder.c_hdrHuffCount.clone();
    int[] globalCount = HuffCoder.c_hdrHuffCountImproved.clone();

    // Divide all of the values by scalar to get them in a reasonable range.
    // And count how many values there are.
    int n = 0;
    for (int i=0; i<hdrHuffCount.length-1; i++) {
      hdrHuffCount[i] /= 100;
      if (hdrHuffCount[i] < 1) hdrHuffCount[i] = 1;
      n += hdrHuffCount[i];
    }

    // Now build some data that has the representative values with the correct population.
    byte[] bvals = new byte[n];
    int count = 0;
    for (int j=0; j<hdrHuffCount.length-1; j++) {
      int value = j - 128;
      for (int i=0; i<hdrHuffCount[j]; i++) {
        bvals[count] = (byte)value;
        count++;
      }
    }

    assert count == n: count + "!=" + n;
    int nbytesUncompressed = n;

    Random random = new Random(232384L);
    int outputBufferSize = n * 2;
    byte[] huffEncodedData = new byte[outputBufferSize];
    int nbytesCompressedLast = Integer.MAX_VALUE;
    int gloatCount = 0;

    count = 0;
    while (true) {

      int savedCount = 0;
      int index = 0;
      if (count > 0) {
        // Perterb the huffman table.
        index = (int)Math.round(random.nextDouble() * 258.0);
        if (index < 0) index = 0;
        if (index > globalCount.length-1) index = globalCount.length-1;
        savedCount = globalCount[index];
        if (count%2 == 0) {
          // This is an integer range of -50 to 50.
          int randomChange = (int)Math.round((random.nextDouble() - 0.5) * 100.0);
          globalCount[index] += randomChange;
        } else {
          double scalar = random.nextDouble() * 2.0;  // Range of 0.0 to 2.0 (exclusive);
          globalCount[index] = (int)(globalCount[index] * scalar);
        }
        if (globalCount[index] < 1) globalCount[index] = 1;
      }

      HuffCoder huffCoder = new HuffCoder(globalCount);

      int nbytesCompressed = huffCoder.huffEncode(bvals, bvals.length,
                                                  huffEncodedData, 0, outputBufferSize);


      if (nbytesCompressed <= nbytesCompressedLast) {
        // As good or better table.  Keep this change.
        if (nbytesCompressed < nbytesCompressedLast) {
          // It got better.  Gloat.
          // if (gloatCount%100 == 0) {
          if (true) {
            if (nbytesCompressedLast != Integer.MAX_VALUE) {
              double compressionRatio = (double)nbytesUncompressed / (double)nbytesCompressed;
              System.out.println("Changed globalCount["+index+"] from "
                                 + savedCount + " to " + globalCount[index]
                                 + " compression ratio= " + compressionRatio
                                 + " count= " + count);
            } else {
              double compressionRatio = (double)nbytesUncompressed / (double)nbytesCompressed;
              System.out.println("Compression ratio= " + compressionRatio);
            }
          }
          gloatCount++;
        }
        nbytesCompressedLast = nbytesCompressed;
      } else {
        // Oops.  Got worse.
        globalCount[index] = savedCount;
      }

      if (stopFile.exists()) {
        HuffCoder.printTable(globalCount);
        System.exit(0);
      }

      count++;
    }  // End of endless loop.

  }


  public static void main(String[] args) throws Exception {

    // HuffTableRandomBuilder.traceSampleTest();
    HuffTableRandomBuilder.traceHeaderTest();

  }

}
