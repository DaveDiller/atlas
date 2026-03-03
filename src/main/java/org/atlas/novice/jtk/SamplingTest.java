
package org.atlas.novice.jtk;

import edu.mines.jtk.dsp.Sampling;

/**
 * Demonstrates basic use of the edu.mines.jtk.dsp.Sampling class.
 */
public class SamplingTest {

  public static void main(String[] args) {

    // Uniform sampling: 101 samples, delta=0.01, first value=0.0
    Sampling uniform = new Sampling(101, 0.01, 0.0);
    System.out.println("=== Uniform Sampling ===");
    System.out.println("Count: " + uniform.getCount());
    System.out.println("Delta: " + uniform.getDelta());
    System.out.println("First: " + uniform.getFirst());
    System.out.println("Last:  " + uniform.getLast());
    System.out.println("isUniform: " + uniform.isUniform());
    System.out.println("Value at index 50: " + uniform.getValue(50));

    // Index lookup
    System.out.println("\n=== Index Lookup ===");
    double target = 0.73;
    System.out.println("indexOf(" + target + "): " + uniform.indexOf(target));
    System.out.println("indexOfNearest(" + target + "): " + uniform.indexOfNearest(target));
    System.out.println("valueOfNearest(" + target + "): " + uniform.valueOfNearest(target));

    // Bounds checking
    System.out.println("\n=== Bounds Checking ===");
    System.out.println("isInBounds(50): " + uniform.isInBounds(50));
    System.out.println("isInBounds(200): " + uniform.isInBounds(200));
    System.out.println("isInBounds(0.5): " + uniform.isInBounds(0.5));
    System.out.println("isInBounds(5.0): " + uniform.isInBounds(5.0));

    // Derived samplings
    System.out.println("\n=== Derived Samplings ===");
    Sampling shifted = uniform.shift(1.0);
    System.out.println("Shifted first: " + shifted.getFirst());
    System.out.println("Shifted last:  " + shifted.getLast());

    Sampling decimated = uniform.decimate(2);
    System.out.println("Decimated count: " + decimated.getCount());
    System.out.println("Decimated delta: " + decimated.getDelta());

    Sampling interpolated = uniform.interpolate(3);
    System.out.println("Interpolated count: " + interpolated.getCount());
    System.out.println("Interpolated delta: " + interpolated.getDelta());

    // Non-uniform sampling from explicit values
    double[] values = {0.0, 1.0, 3.0, 6.0, 10.0};
    Sampling nonUniform = new Sampling(values);
    System.out.println("\n=== Non-Uniform Sampling ===");
    System.out.println("Count: " + nonUniform.getCount());
    System.out.println("isUniform: " + nonUniform.isUniform());
    System.out.println("indexOfNearest(4.0): " + nonUniform.indexOfNearest(4.0));
    System.out.println("valueOfNearest(4.0): " + nonUniform.valueOfNearest(4.0));

    // Compatibility check
    System.out.println("\n=== Compatibility ===");
    Sampling s1 = new Sampling(51, 0.01, 0.0);
    Sampling s2 = new Sampling(51, 0.01, 0.5);
    System.out.println("s1 compatible with s2: " + s1.isCompatible(s2));
    System.out.println("s1 equivalent to s2:   " + s1.isEquivalentTo(s2));
    System.out.println("s1 equivalent to s1:   " + s1.isEquivalentTo(s1));
  }
}
