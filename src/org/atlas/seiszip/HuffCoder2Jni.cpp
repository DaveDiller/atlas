
#include <stdlib.h>
#include <stdio.h>

#include "org_atlas_seiszip_HuffCoder2.h"
#include "HuffCoder2.h"


JNIEXPORT jlong JNICALL Java_org_atlas_seiszip_HuffCoder2_huffCoderAlloc_1native
(JNIEnv* env, jobject caller) {

  HuffCoder2* huffCoder = new HuffCoder2();

  return (jlong)huffCoder;
}


JNIEXPORT jlong JNICALL Java_org_atlas_seiszip_HuffCoder2_huffCoderAlloc_1native___3I
(JNIEnv* env, jobject caller, jintArray jhuffTable) {

  jboolean isCopy1;

  int* huffTable = (int*)env->GetPrimitiveArrayCritical(jhuffTable, &isCopy1);

  HuffCoder2* huffCoder = new HuffCoder2(huffTable);

  if (isCopy1 == JNI_TRUE) {
    env->ReleasePrimitiveArrayCritical(jhuffTable, huffTable, JNI_ABORT);
  }
  
  return (jlong)huffCoder;
}


JNIEXPORT void JNICALL Java_org_atlas_seiszip_HuffCoder2_huffCoderFree_1native
(JNIEnv* env, jobject caller, jlong huffCoderNative) {

  HuffCoder2* huffCoder = (HuffCoder2*)huffCoderNative;
  delete huffCoder;
}


JNIEXPORT jint JNICALL Java_org_atlas_seiszip_HuffCoder2_huffEncode_1native__J_3BI_3BII
(JNIEnv* env, jobject caller, jlong huffCoderNative, jbyteArray jrunLengthEncodedData, jint ninputBytes,
jbyteArray jhuffEncodedData, jint index, jint outputBufferSize) {

  jboolean isCopy1, isCopy2;

  jbyte* runLengthEncodedData = (jbyte*)env->GetPrimitiveArrayCritical(jrunLengthEncodedData, &isCopy1);
  jbyte* huffEncodedData = (jbyte*)env->GetPrimitiveArrayCritical(jhuffEncodedData, &isCopy2);

  HuffCoder2* huffCoder = (HuffCoder2*)huffCoderNative;
  int nBytes = huffCoder->huffEncode((char*)runLengthEncodedData, ninputBytes, (char*)huffEncodedData, index, outputBufferSize);

  if (isCopy1 == JNI_TRUE) {
    env->ReleasePrimitiveArrayCritical(jrunLengthEncodedData, runLengthEncodedData, JNI_ABORT);
  }
  if (isCopy2 == JNI_TRUE) {
    env->ReleasePrimitiveArrayCritical(jhuffEncodedData, huffEncodedData, 0);
  }

  return nBytes;
}


JNIEXPORT jint JNICALL Java_org_atlas_seiszip_HuffCoder2_huffEncode_1native__J_3II_3BII
(JNIEnv* env, jobject caller, jlong huffCoderNative, jintArray jrunLengthEncodedData, jint ninputInts,
jbyteArray jhuffEncodedData, jint index, jint outputBufferSize) {

  jboolean isCopy1, isCopy2;

  int* runLengthEncodedData = (int*)env->GetPrimitiveArrayCritical(jrunLengthEncodedData, &isCopy1);
  jbyte* huffEncodedData = (jbyte*)env->GetPrimitiveArrayCritical(jhuffEncodedData, &isCopy2);

  HuffCoder2* huffCoder = (HuffCoder2*)huffCoderNative;
  int nBytes = huffCoder->huffEncode(runLengthEncodedData, ninputInts, (char*)huffEncodedData, index, outputBufferSize);

  if (isCopy1 == JNI_TRUE) {
    env->ReleasePrimitiveArrayCritical(jrunLengthEncodedData, runLengthEncodedData, JNI_ABORT);
  }
  if (isCopy2 == JNI_TRUE) {
    env->ReleasePrimitiveArrayCritical(jhuffEncodedData, huffEncodedData, 0);
  }

  return nBytes;
}


JNIEXPORT jint JNICALL Java_org_atlas_seiszip_HuffCoder2_huffDecode_1native
(JNIEnv* env, jobject caller, jlong huffCoderNative, jbyteArray jhuffEncodedData, jint index, jbyteArray jcHout, jint outputBufferSize) {

  jboolean isCopy1, isCopy2;

  jbyte* huffEncodedData = (jbyte*)env->GetPrimitiveArrayCritical(jhuffEncodedData, &isCopy1);
  jbyte* cHout = (jbyte*)env->GetPrimitiveArrayCritical(jcHout, &isCopy2);

  HuffCoder2* huffCoder = (HuffCoder2*)huffCoderNative;
  int nBytes = huffCoder->huffDecode((char*)huffEncodedData, index, (char*)cHout, outputBufferSize);

  if (isCopy1 == JNI_TRUE) {
    env->ReleasePrimitiveArrayCritical(jhuffEncodedData, huffEncodedData, JNI_ABORT);
  }
  if (isCopy2 == JNI_TRUE) {
    env->ReleasePrimitiveArrayCritical(jcHout, cHout, 0);
  }

  return nBytes;
}
