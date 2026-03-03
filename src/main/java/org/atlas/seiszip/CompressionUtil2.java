
package org.atlas.seiszip;

import java.nio.ByteOrder;

/**
 * This class provides utility methods, mostly for copying values between different types.
 */

public class CompressionUtil2 {

  public static final int SIZEOF_INT = 4;
  public static final int SIZEOF_CHAR = 1;
  public static final int SIZEOF_FLOAT = 4;

  
  public static boolean c_littleEndian = true;
  static {
    if (ByteOrder.LITTLE_ENDIAN.equals(ByteOrder.nativeOrder())) {
      c_littleEndian = true;
    } else {
      c_littleEndian = false;
    }
  }


  // Faster than Math.round().
  public static int NINT(double a) {

    return (a > 0.0) ? (int)(a+0.5f) : (int)(a-0.5f);
  }


    public static void stuffIntInBytes(int ival, byte[] bvals, int offset) {

    bvals[offset]   = (byte)(ival >> 24);
    bvals[1+offset] = (byte)((ival << 8) >> 24);
    bvals[2+offset] = (byte)((ival << 16) >> 24);
    bvals[3+offset] = (byte)((ival << 24) >> 24);
  }


  public static void stuffFloatInBytes(float fval, byte[] bvals, int offset) {

    stuffIntInBytes(Float.floatToIntBits(fval), bvals, offset);
  }


  // This method violates endianess neutrality.
  /*package*/ static void copyIntInBytes_DONT_USE(int ival, byte[] bvals, int offset) {

    if (c_littleEndian) {
      bvals[3+offset] = (byte)(ival >> 24);
      bvals[2+offset] = (byte)((ival << 8) >> 24);
      bvals[1+offset] = (byte)((ival << 16) >> 24);
      bvals[offset]   = (byte)((ival << 24) >> 24);           
    } else {
      stuffIntInBytes(ival, bvals, offset);
    }
  }


  // This method violates endianess neutrality.
  /*package*/ static int copyBytesToInt_DONT_USE(byte[] bvals, int offset) {

    if (c_littleEndian) {
      int i0 = ((int)(bvals[offset+3])) << 24;
      int i1 = (unsignedByte(bvals[offset+2])) << 16;
      int i2 = (unsignedByte(bvals[offset+1])) << 8;
      int i3 = unsignedByte(bvals[offset]);
      return i0 + i1 + i2 + i3;        
    } else {
      return stuffBytesInInt(bvals, offset);
    }
  }


  public static int unsignedByte(byte i) {

    if (i < 0) {
      return (int)i + 256;
    } else {
      return (int)i;
    }
  }
                  

  public static int stuffBytesInInt(byte[] bvals, int index) {

    int i0 = ((unsignedByte(bvals[index]))) << 24;
    int i1 = ((unsignedByte(bvals[1+index]))) << 16;
    int i2 = ((unsignedByte(bvals[2+index]))) << 8;
    int i3 = unsignedByte(bvals[3+index]);        
                
    return i0 + i1 + i2 + i3;
  }


  public static float stuffBytesInFloat(byte[] bvals, int index) {

    int ival = stuffBytesInInt(bvals, index);
    return Float.intBitsToFloat(ival);
  }


  public static void stuffShortInBytes(short uval, byte[] bvals, int index) {

    int ival = (int)uval;
    bvals[index] = (byte)((ival << 16) >> 24);
    bvals[1+index] = (byte)((ival << 24) >> 24);
  }


  public static int stuffBytesInShort(byte[] bvals, int index) {

    int i0 = (short)(((int)(bvals[index])) << 8);
    int i1 = (short)unsignedByte(bvals[1+index]);
                
    return (i0 + i1);
  }
        

  public static int unsignedShort(short i) {

    if (i < 0) {
      return (int)i + 65536;
    } else {
      return (int)i;
    }
  }


  public static void bytesToChars(byte[] byteArray, int byteArrayStartIndex, char[] outputCharValues, int charNvals) {

    for (int i=0; i<charNvals; i++) {
      short sVal = (short)stuffBytesInShort(byteArray, byteArrayStartIndex+i*2);
      outputCharValues[i] = (char)sVal;  // Yes, this cast is lossless.
    }
  }


  public static void charsToBytes(char[] charValues, int charNvals, byte[] outputByteArray, int byteArrayStartIndex) {

    for (int i=0; i<charNvals; i++) {
      short sVal = (short)charValues[i];    // Yes, this cast is lossless.
      stuffShortInBytes(sVal, outputByteArray, byteArrayStartIndex+i*2);
    }
  }


  public static String bytesToString(byte[] byteArray, int nBytes, char[] charWorkArray) {

    int nChars = nBytes / 2;
    if (nChars*2 < nBytes)
      nChars++;
    if (charWorkArray.length < nChars)
      throw new IllegalArgumentException("charWorkArray.length < nChars");
    bytesToChars(byteArray, 0, charWorkArray, nChars);
    return new String(charWorkArray, 0, nChars);
  }


  public static void stringToBytes(String s, byte[] outputByteArray) {

    char[] charArray = s.toCharArray();
    charsToBytes(charArray, charArray.length, outputByteArray, 0);
  }


  public static byte[] stringToBytes(String s) {

    char[] charArray = s.toCharArray();
    byte[] outputByteArray = new byte[charArray.length*2];
    charsToBytes(charArray, charArray.length, outputByteArray, 0);
    return outputByteArray;
  }

}
