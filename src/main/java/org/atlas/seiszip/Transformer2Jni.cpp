
#include <stdlib.h>
#include <stdio.h>

#include "org_atlas_seiszip_Transformer2.h"
#include "Transformer2.h"


JNIEXPORT jlong JNICALL Java_org_atlas_seiszip_Transformer2_transformerAlloc_1native
(JNIEnv* env, jobject caller, jint useSSE) {

  Transformer2* transformer = new Transformer2(useSSE);
  return (jlong)transformer;
}


JNIEXPORT void JNICALL Java_org_atlas_seiszip_Transformer2_transformerFree_1native
(JNIEnv* env, jobject caller, jlong transformerNative) {

  Transformer2* transformer = (Transformer2*)transformerNative;
  delete transformer;
}


JNIEXPORT void JNICALL Java_org_atlas_seiszip_Transformer2_lotFwd_1native
(JNIEnv* env, jobject caller, jlong transformerNative, jfloatArray jx, jint index, jint blockSize, jint transLength, jint nblocks) {

  jboolean isCopy;

  Transformer2* transformer = (Transformer2*)transformerNative;

  // float* x = env->GetFloatArrayElements(jx, &isCopy);
  float* x = (float*)env->GetPrimitiveArrayCritical(jx, &isCopy);

  transformer->lotFwd(x, index, blockSize, transLength, nblocks);

  if (isCopy == JNI_TRUE) {
    // env->ReleaseFloatArrayElements(jx, x, 0);
    env->ReleasePrimitiveArrayCritical(jx, x, 0);  // DO copy everything back.
  }
}


JNIEXPORT void JNICALL Java_org_atlas_seiszip_Transformer2_lotRev_1native
(JNIEnv* env, jobject caller, jlong transformerNative, jfloatArray jx, jint index, jint blockSize, jint transLength, jint nblocks) {

  jboolean isCopy;

  Transformer2* transformer = (Transformer2*)transformerNative;

  // float* x = env->GetFloatArrayElements(jx, &isCopy);
  float* x = (float*)env->GetPrimitiveArrayCritical(jx, &isCopy);

  transformer->lotRev(x, index, blockSize, transLength, nblocks);

  if (isCopy == JNI_TRUE) {
    // env->ReleaseFloatArrayElements(jx, x, 0);
    env->ReleasePrimitiveArrayCritical(jx, x, 0);  // DO copy everything back.
  }
}
