
#include "org_atlas_seiszip_BlockCompressor2.h"
#include "BlockCompressor2.h"

#define USE_GET_ARRAY_CRITICAL 1  // Turning off GetArrayCritical causes a different kind of deadlock, with the processors running flat out.


JNIEXPORT jlong JNICALL Java_org_atlas_seiszip_BlockCompressor2_blockCompressorAlloc_1native
(JNIEnv* env, jobject caller) {

  BlockCompressor2* blockCompressor = new BlockCompressor2();

  return (jlong)blockCompressor;
}


JNIEXPORT void JNICALL Java_org_atlas_seiszip_BlockCompressor2_blockCompressorFree_1native
(JNIEnv* env, jobject caller, jlong blockCompressorNative) {

  BlockCompressor2* blockCompressor = (BlockCompressor2*)blockCompressorNative;

  delete blockCompressor;
}


JNIEXPORT jint JNICALL Java_org_atlas_seiszip_BlockCompressor2_dataEncode_1native
(JNIEnv* env, jobject caller, jlong blockCompressorNative, jfloatArray jdata, jint nsamps, jfloat distortion, jbyteArray jencodedData,
jint index, jint outputBufferSize) {

  jboolean isCopy1, isCopy2;

  float* data;
  jbyte* encodedData;
  if (USE_GET_ARRAY_CRITICAL) {
    data = (float*)env->GetPrimitiveArrayCritical(jdata, &isCopy1);
    encodedData = (jbyte*)env->GetPrimitiveArrayCritical(jencodedData, &isCopy2);
  } else {
    data = env->GetFloatArrayElements(jdata, &isCopy1);
    encodedData = env->GetByteArrayElements(jencodedData, &isCopy2);
  }

  BlockCompressor2* blockCompressor = (BlockCompressor2*)blockCompressorNative;
  int nBytes = blockCompressor->dataEncode(data, nsamps, distortion, (char*)encodedData, index, outputBufferSize);

  if (isCopy1 == JNI_TRUE) {
    if (USE_GET_ARRAY_CRITICAL) {
      env->ReleasePrimitiveArrayCritical(jdata, data, JNI_ABORT);
    } else {
      env->ReleaseFloatArrayElements(jdata, data, JNI_ABORT);
    }
  }
  if (isCopy2 == JNI_TRUE) {
    if (USE_GET_ARRAY_CRITICAL) {
      env->ReleasePrimitiveArrayCritical(jencodedData, encodedData, 0);
    } else {
      env->ReleaseByteArrayElements(jencodedData, encodedData, 0);
    }
  }

  return nBytes;
}


JNIEXPORT jint JNICALL Java_org_atlas_seiszip_BlockCompressor2_dataDecode_1native
(JNIEnv* env, jobject caller, jlong blockCompressorNative, jbyteArray jencodedData, jint index, jbyteArray jworkBuffer,
jint workBufferSize, jint nsamps, jfloatArray jdata) {

  jboolean isCopy1, isCopy2, isCopy3;

  jbyte* encodedData;
  jbyte* workBuffer;
  float* data;
  if (USE_GET_ARRAY_CRITICAL) {
    encodedData = (jbyte*)env->GetPrimitiveArrayCritical(jencodedData, &isCopy1);
    workBuffer = (jbyte*)env->GetPrimitiveArrayCritical(jworkBuffer, &isCopy2);
    data = (float*)env->GetPrimitiveArrayCritical(jdata, &isCopy3);
  } else {
    encodedData = env->GetByteArrayElements(jencodedData, &isCopy1);
    workBuffer = env->GetByteArrayElements(jworkBuffer, &isCopy2);
    data = env->GetFloatArrayElements(jdata, &isCopy3);
  }

  BlockCompressor2* blockCompressor = (BlockCompressor2*)blockCompressorNative;
  int nBytes = blockCompressor->dataDecode((char*)encodedData, index, (char*)workBuffer, workBufferSize, nsamps, data);

  if (isCopy1 == JNI_TRUE) {
    if (USE_GET_ARRAY_CRITICAL) {
      env->ReleasePrimitiveArrayCritical(jencodedData, encodedData, JNI_ABORT);
    } else {
      env->ReleaseByteArrayElements(jencodedData, encodedData, JNI_ABORT);
    }
  }
  if (isCopy2 == JNI_TRUE) {
    if (USE_GET_ARRAY_CRITICAL) {
      env->ReleasePrimitiveArrayCritical(jworkBuffer, workBuffer, JNI_ABORT);
    } else {
      env->ReleaseByteArrayElements(jworkBuffer, workBuffer, JNI_ABORT);
    }
  }
  if (isCopy3 == JNI_TRUE) {
    if (USE_GET_ARRAY_CRITICAL) {
      env->ReleasePrimitiveArrayCritical(jdata, data, 0);
    } else {
      env->ReleaseFloatArrayElements(jdata, data, 0);
    }
  }

  return nBytes;
}


JNIEXPORT void JNICALL Java_org_atlas_seiszip_BlockCompressor2_setManualDelta_1native
(JNIEnv* env, jobject caller, jlong blockCompressorNative, jfloat delta) {

  BlockCompressor2* blockCompressor = (BlockCompressor2*)blockCompressorNative;
  blockCompressor->setManualDelta(delta);
}


JNIEXPORT void JNICALL Java_org_atlas_seiszip_BlockCompressor2_unsetManualDelta_1native
(JNIEnv* env, jobject caller, jlong blockCompressorNative) {

  BlockCompressor2* blockCompressor = (BlockCompressor2*)blockCompressorNative;
  blockCompressor->unsetManualDelta();
}
