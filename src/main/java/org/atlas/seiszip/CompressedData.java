
package org.atlas.seiszip;

/**
 * This class provided a container for a compressed data.
 */

public class CompressedData {

  private byte[] _compressedData;
  private int _dataLength;

  /**
   * Constructor for a compressed data container.
   *
   * @param  compressedData  an array to store the compressed data.
   * @param  dataLength  the number of bytes of data in the array.
   */
  /*package*/ CompressedData(byte[] compressedData, int dataLength) {
    _compressedData = compressedData;
    _dataLength = dataLength;
  }

  /**
   * Returns the byte array of compressed data.
   *
   * @return  the byte array of compressed data.
   */
  public byte[] getData() {
    return _compressedData;
  }

  /**
   * Sets the number of bytes in the buffer that are actually live data (the data
   * buffer may be larger).
   */
  /*package*/ void setDataLength(int dataLength) {
    _dataLength = dataLength;
  }

  /**
   * Returns the number of bytes in the buffer that are actually live data (the data
   * buffer may be larger).
   *
   * @return  the number of bytes in the buffer that are actually live data (the data
   *          buffer may be larger).
   */
  public int getDataLength() {
    return _dataLength;
  }

  /**
   * Checks the integrity of the data (e.g. for the existence of the cookied).
   * @throws DataCorruptedException 
   */
  /*package*/ void checkIntegrity() throws DataCorruptedException {
    SeisPEG.checkDataIntegrity(_compressedData);
  }

}
