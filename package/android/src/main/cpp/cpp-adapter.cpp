#include <jni.h>
#include <fbjni/fbjni.h>
#include "NitroBetterPdfOnLoad.hpp"
#include "NativeBase64Document.hpp"
#include "NativePageCoverage.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return facebook::jni::initialize(vm, []() {
    margelo::nitro::betterpdf::registerAllNatives();
    margelo::nitro::betterpdf::registerNativeBase64Document();
    margelo::nitro::betterpdf::registerNativePageCoverage();
  });
}
