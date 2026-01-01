
package org.atlas.seiszip;

import java.util.Random;

/**
 * This class compares Transformer.java to the C/C++ version(s).
 *
 * This class also proves that the Java and C++ versions produce the same results, except that the results are slightly different
 * when SSE is used.
 */

public class Transformer2VersionTest {

  private static void basicTest(int nLoops) {

    Random random = new Random(22348234L);

    boolean useNativeCode = false;
    boolean useSSE = false;
    org.atlas.seiszip.Transformer2 transformer2_seiszip_Java = new org.atlas.seiszip.Transformer2(useNativeCode, useSSE);

    useNativeCode = true;
    useSSE = false;
    org.atlas.seiszip.Transformer2 transformer2_seiszip_Cpp = new org.atlas.seiszip.Transformer2(useNativeCode, useSSE);

    useNativeCode = true;
    useSSE = true;
    org.atlas.seiszip.Transformer2 transformer2_seiszip_CppSse = new org.atlas.seiszip.Transformer2(useNativeCode, useSSE);

    long fwdElapsedTimeNs4 = 0L;
    long fwdElapsedTimeNs5 = 0L;
    long fwdElapsedTimeNs6 = 0L;
    long revElapsedTimeNs4 = 0L;
    long revElapsedTimeNs5 = 0L;
    long revElapsedTimeNs6 = 0L;
    
    // int maxN = 4000000;
    int maxN = 5000000;
    float[] x4 = new float[maxN];  // Always long enough.
    float[] x5 = new float[maxN];  // Always long enough.
    float[] x6_sse = new float[maxN];  // Always long enough.

    for (int l=0; l<nLoops; l++) {

      int samplesPerTrace = 1000 + Math.round(random.nextFloat() * 1000.0f);
      int tracesPerFrame = 1000 + Math.round(random.nextFloat() * 1000.0f);

      for (int k=0; k<2; k++) {

        Transformer2.Policy policy;
        if (k == 0) {
          // No Cabron, we don't want to work on optimizing this.
          // policy = Transformer.Policy.FASTEST;
          policy = Transformer2.Policy.MAX_COMPRESSION;
        } else {
          policy = Transformer2.Policy.MAX_COMPRESSION;
        }

        int verticalBlockSize = Transformer2.computeBlockSize(samplesPerTrace, policy, Transformer2.BlockSizeDirection.VERTICAL);
        int verticalTransLength = Transformer2.computeTransformLength(verticalBlockSize, policy);
        int horizontalBlockSize = Transformer2.computeBlockSize(tracesPerFrame, policy, Transformer2.BlockSizeDirection.HORIZONTAL);
        int paddedN1 = Transformer2.computePaddedLength(samplesPerTrace, verticalBlockSize);
        int paddedN2 = Transformer2.computePaddedLength(tracesPerFrame, horizontalBlockSize);
        int nblocksVertical = paddedN1 / verticalBlockSize;

        float[] scratch = new float[paddedN1 + verticalBlockSize];

        int n = paddedN1 * paddedN2;
        assert n < maxN: n + " >= " + maxN;

        for (int i=0; i<n; i++) {
          x4[i] = (random.nextFloat() - 0.5f) * 200.0f;  // Range -100 to 100.
          x5[i] = x4[i];
          x6_sse[i] = x4[i];
        }

        for (int j=0; j<paddedN2; j++) {
          int index = j * paddedN1;

	  long startTimeNs4 = System.nanoTime();
          transformer2_seiszip_Java.lotFwd(x4, index, verticalBlockSize, verticalTransLength, nblocksVertical, scratch);
          fwdElapsedTimeNs4 += (System.nanoTime() - startTimeNs4);

          long startTimeNs5 = System.nanoTime();
          transformer2_seiszip_Cpp.lotFwd(x5, index, verticalBlockSize, verticalTransLength, nblocksVertical, scratch);
          fwdElapsedTimeNs5 += (System.nanoTime() - startTimeNs5);

	  long startTimeNs6 = System.nanoTime();
          transformer2_seiszip_CppSse.lotFwd(x6_sse, index, verticalBlockSize, verticalTransLength, nblocksVertical, scratch);
          fwdElapsedTimeNs6 += (System.nanoTime() - startTimeNs6);
	}

	// float maxError = 0.00006f;  FAILS
	float maxError = 0.00007f;

	// Because we are using SSE instructions, the multiply/add operations are not done exactly the same, so the results are slightly different.
        for (int i=0; i<n; i++) {
	  assert x5[i] == x4[i]:  x5[i] + " != " + x4[i];
	  assert Math.abs(x6_sse[i] - x4[i]) < maxError:  x6_sse[i] + " != " + x4[i];
        }

        for (int j=0; j<paddedN2; j++) {
          int index = j * paddedN1;

	  long startTimeNs4 = System.nanoTime();
          transformer2_seiszip_Java.lotRev(x4, index, verticalBlockSize, verticalTransLength, nblocksVertical, scratch);
          revElapsedTimeNs4 += (System.nanoTime() - startTimeNs4);

          long startTimeNs5 = System.nanoTime();
          transformer2_seiszip_Cpp.lotRev(x5, index, verticalBlockSize, verticalTransLength, nblocksVertical, scratch);
          revElapsedTimeNs5 += (System.nanoTime() - startTimeNs5);

	  long startTimeNs6 = System.nanoTime();
          transformer2_seiszip_CppSse.lotRev(x6_sse, index, verticalBlockSize, verticalTransLength, nblocksVertical, scratch);
          revElapsedTimeNs6 += (System.nanoTime() - startTimeNs6);
	}

	// Because we are using SSE instructions, the multiply/add operations are not done exactly the same, so the results are slightly different.
        for (int i=0; i<n; i++) {
	  assert x5[i] == x4[i]:  x5[i] + " != " + x4[i];
	  assert Math.abs(x6_sse[i] - x4[i]) < maxError:  x6_sse[i] + " != " + x4[i];
        }

      }

    }

    transformer2_seiszip_Java.free();
    transformer2_seiszip_Cpp.free();
    transformer2_seiszip_CppSse.free();

    System.out.println();
    System.out.println("TransformerVersionTest:    seiszip reverse    Java code elapsedTime= " + ((double)revElapsedTimeNs4 / 1000000000.0));
    System.out.println("TransformerVersionTest:    seiszip reverse     C++ code elapsedTime= " + ((double)revElapsedTimeNs5 / 1000000000.0));    
    System.out.println("TransformerVersionTest:    seiszip reverse C++ SSE code elapsedTime= " + ((double)revElapsedTimeNs6 / 1000000000.0));
    System.out.println();

    System.out.println("org.atlas.seiszip.TransformerVersionTest.basicTest ****** SUCCESS ******");
  }


  public static void main(String[] args) {

    int nTests = 2;
    if (args.length > 0)
      nTests = Integer.parseInt(args[0]);

    int nLoops = 200;
    if (args.length > 1)
      nLoops = Integer.parseInt(args[1]);

    for (int k=0; k<nTests; k++)
      Transformer2VersionTest.basicTest(nLoops);
  }

}
