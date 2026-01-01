
package org.atlas.seiszip;

/**
 * This class provides a gated gain method that is useful to apply before compression.
 * It is similar to a long removeable AGC, except that it does not blow up pre-firstbreak noise.
 */

public class GatedTraceGain {


  private static final int APPLY = 1;
  private static final int REMOVE = -1;
  private static final int FRONT = 0;
  private static final int BACK = 1;
  private static final float RMAXINT = 32766.0F;

  private static final int NSAMPS_PER_GATE = 256;   // Was 64 in original ESI code.
  private static final int HALF_GATE = 128;         // Was 32 in original ESI code.


  /*
   * Returns number of gates per trace.
   *
   * @param  samplesPerTrace  samples per trace.
   * @return  number of gates per trace.
   */
  public static int nGatesPerTrace(int samplesPerTrace) {
    return (samplesPerTrace-1) / NSAMPS_PER_GATE + 1;
  }


  private int _samplesPerTrace;
  private float _ampMax;
  private short[] _shortGainVals;
  private int _ngates;
  private float[] _aveAmpsWork;
  private float[] _interpolatedAmps = null;


  /**
   * Constructor.
   *
   * @param  samplesPerTrace  samples per trace.
   */
  public GatedTraceGain(int samplesPerTrace) {

    _samplesPerTrace = samplesPerTrace;
    _ngates = GatedTraceGain.nGatesPerTrace(samplesPerTrace);
    _shortGainVals = new short[_ngates];
    _aveAmpsWork = new float[_ngates];
    _interpolatedAmps = new float[samplesPerTrace];
  }


  /**
   * Computes the gain for a trace.
   *
   * @param  trace  a seismic trace.
   */
  public void computeGain(float[] trace) {
    
    for (int j=0; j<_ngates; j++) {

      int ifirst = this.firstSampleInGate(j);
      int ilast = this.lastSampleInGate(j, _samplesPerTrace);
      double sum = 0.0;
      int nlive = 0;

      for (int i=ifirst; i<=ilast; i++) {
	if (trace[i] != 0.0F) {
	  nlive++;
	  if (trace[i] > 0.0F) {
	    sum += trace[i];
	  } else {
	    sum += (-trace[i]);
	  }
	}
      }

      if ( nlive > 0 ) {
	_aveAmpsWork[j] = (float)(sum / nlive);
      } else {
	_aveAmpsWork[j] = 0.0F;
      }
    }

    for (int j=0; j<_ngates; j++) {
      float dampingScalar = 1.0F + (float)j / (float)(_ngates-1);
      _aveAmpsWork[j] *= dampingScalar;
    }

    /* We will assume that the amplitudes of geophysical signals
     * can only decay.  This will prevent blowing up the noise
     * before the first breaks.   Of course it will also tend to
     * kill all of the amplitudes above a noise burst. */
    _ampMax = _aveAmpsWork[_ngates-1];
    for (int j=_ngates-2; j>=0; j--) {
      if (_aveAmpsWork[j] < _ampMax) _aveAmpsWork[j] = _ampMax;
      if (_aveAmpsWork[j] > _ampMax) _ampMax = _aveAmpsWork[j];
    }

    // Quantize to 16 bits.
    for (int j=0; j<_ngates; j++) {
      _shortGainVals[j] = (short)Math.round(_aveAmpsWork[j] / _ampMax * RMAXINT);
      // We can't tolerate any hard zeros.
      if (_shortGainVals[j] == 0) _shortGainVals[j] = 1;
    }

    if (_ampMax == 0.0) _ampMax = 1.0F;
  }


  private int firstSampleInGate(int jgate) {
    return jgate * NSAMPS_PER_GATE;
  }


  private int lastSampleInGate(int jgate, int samplesPerTrace) {
    return (jgate == GatedTraceGain.nGatesPerTrace(samplesPerTrace)-1) ? (samplesPerTrace-1)
      : (jgate*NSAMPS_PER_GATE+NSAMPS_PER_GATE-1);
  }


  /**
   * Applies the gain for a trace.
   *
   * @param  trace  a seismic trace.
   */
  public void applyGain(float[] trace) {

    float[] interpolatedAmps = this.interpolateAveAmps();
    for (int i=0; i<_samplesPerTrace; i++) {
      trace[i] /= interpolatedAmps[i];
    }
  }


  /**
   * Removes the gain for a trace (presumably after uncompression).
   *
   * @param  trace  a seismic trace.
   */
  public void removeGain(float[] trace) {

    float[] interpolatedAmps = this.interpolateAveAmps();
    for (int i=0; i<_samplesPerTrace; i++) {
      trace[i] *= interpolatedAmps[i];
    }
  }


  private float[] interpolateAveAmps() {

    // Unquantize the gain.
    for (int j=0; j<_ngates; j++) {
      _aveAmpsWork[j] = _shortGainVals[j] * _ampMax / RMAXINT;
    }

    for (int j=0; j<_ngates; j++) {
      int ifirst = this.firstSampleInGate(j);
      int ilast = this.lastSampleInGate(j, _samplesPerTrace);
      float ampFirst = this.interpolateAtASample(ifirst, _samplesPerTrace, _aveAmpsWork);
      float ampLast = this.interpolateAtASample(ilast, _samplesPerTrace, _aveAmpsWork);
      float delta;
      if (ifirst == ilast) {
	delta = 0.0F;
      } else {
	delta = (ampLast - ampFirst) / (ilast - ifirst);
      }
      float amp = ampFirst;
      for (int i=ifirst; i<=ilast; i++) {
	_interpolatedAmps[i] = amp;
	amp += delta;
      }
    }

    return _interpolatedAmps;
  }


  private float interpolateAtASample(int isamp, int samplesPerTrace, float[] aveAmpsWork) {

    int upperGate = 0;
    int lowerGate = 0;

    // Given the sample, determine which gate it is in.
    int jgate = isamp / NSAMPS_PER_GATE;

    // Given the gate, determine the middle sample number.
    int imid = jgate * NSAMPS_PER_GATE + HALF_GATE;

    if (isamp <= imid) {
      upperGate = jgate - 1;
      if (upperGate < 0) {
	// We are in the upper half of the first gate.
	return aveAmpsWork[0];
      }
      lowerGate = jgate;
    } else {
      upperGate = jgate;
      lowerGate = jgate + 1;
      if (lowerGate >= _ngates) {
	// We are in the lower half of the last gate.
	return aveAmpsWork[_ngates-1];
      }
    }

    // Linearly interpolate.
    int imidUpper = upperGate * NSAMPS_PER_GATE + HALF_GATE;
    int imidLower = lowerGate * NSAMPS_PER_GATE + HALF_GATE;
    float distLower = (imidLower - isamp);
    float weightUpper = distLower / (imidLower-imidUpper);
    float weightLower = 1.0F - weightUpper;
  
    return weightUpper*aveAmpsWork[upperGate] + weightLower*aveAmpsWork[lowerGate];
  }


  /** Simple test harness. */
  public static void main(String[] args) {

    int NSAMPS = 1051;
    float[] trace = new float[NSAMPS];
    float[] traceCopy = new float[NSAMPS];

    // Create a trace with rapidly decaying amplitudes.
    for (int i=0; i<NSAMPS; i++) {
      trace[i] = (NSAMPS-i) * 1000.0F;
      traceCopy[i] = trace[i];
    }

    GatedTraceGain traceGain = new GatedTraceGain(NSAMPS);
    traceGain.computeGain(trace);
    traceGain.applyGain(trace);
    traceGain.removeGain(trace);

    // The values should be within 1% of original.
    for (int i=0; i<NSAMPS; i++) {
      assert Math.abs(trace[i]-traceCopy[i])
	/ Math.abs(trace[i]+traceCopy[i]) < .005F;
    }

    // If we apply a fairly flat trace, the gained values should
    // be very close to 1.0 (with some departure near the ends).
    for (int i=0; i<NSAMPS; i++) {
      trace[i] = 20000.0F + (NSAMPS-i) * 10.0F;
      if (i % 2 == 0) trace[i] = (-trace[i]);
    }

    traceGain = new GatedTraceGain(NSAMPS);
    traceGain.computeGain(trace);
    traceGain.applyGain(trace);
    for (int i=0; i<NSAMPS; i++) {
      assert Math.abs(Math.abs(trace[i])-1.0) < 0.04F;
    }

    System.out.println( "org.atlas.seiszip.GatedTraceGain ***** SUCCESS *****\n" );
  }

}
