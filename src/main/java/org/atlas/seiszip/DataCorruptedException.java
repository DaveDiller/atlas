
package org.atlas.seiszip;

import org.atlas.util.SeisException;

/**
 * This class extends java.io.IOException for the special case of corrupted SeisPEG data.
 */

public class DataCorruptedException extends SeisException {

  /**
   * Constructor.
   *
   * @param  reason  the reason for the exception.
   */
  public DataCorruptedException(String reason) {
    super(reason);
  }

}
