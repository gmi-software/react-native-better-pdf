#include "NativePageCoverage.hpp"
#include "PageCoverage.hpp"

#include <android/bitmap.h>
#include <fbjni/fbjni.h>

namespace margelo::nitro::betterpdf {

using namespace facebook;

namespace {

  struct JBitmap : jni::JavaClass<JBitmap> {
    static constexpr auto kJavaDescriptor = "Landroid/graphics/Bitmap;";
  };

  /** A bitmap's pixels while locked; throws IllegalArgumentException for another format. */
  class LockedPixels {
  public:
    LockedPixels(jobject bitmap, int32_t format, const char* name) : bitmap_(bitmap) {
      JNIEnv* env = jni::Environment::current();
      if (AndroidBitmap_getInfo(env, bitmap, &info_) != ANDROID_BITMAP_RESULT_SUCCESS || info_.format != format) {
        jni::throwNewJavaException("java/lang/IllegalArgumentException", "%s has the wrong format", name);
      }
      if (AndroidBitmap_lockPixels(env, bitmap, &pixels_) != ANDROID_BITMAP_RESULT_SUCCESS || pixels_ == nullptr) {
        jni::throwNewJavaException("java/lang/IllegalStateException", "%s could not be locked", name);
      }
    }
    ~LockedPixels() { AndroidBitmap_unlockPixels(jni::Environment::current(), bitmap_); }
    LockedPixels(const LockedPixels&) = delete;
    LockedPixels& operator=(const LockedPixels&) = delete;

    uint8_t* row(uint32_t y) const { return static_cast<uint8_t*>(pixels_) + static_cast<size_t>(y) * info_.stride; }
    const AndroidBitmapInfo& info() const { return info_; }

  private:
    jobject bitmap_;
    AndroidBitmapInfo info_{};
    void* pixels_ = nullptr;
  };

  void checkRegion(jint width, jint rows, jint top, const AndroidBitmapInfo& rgba, const AndroidBitmapInfo& coverage) {
    if (width < 0 || rows < 0 || top < 0 || static_cast<uint32_t>(width) > rgba.width || static_cast<uint32_t>(width) > coverage.width ||
        static_cast<uint32_t>(rows) > rgba.height || static_cast<uint32_t>(top) + static_cast<uint32_t>(rows) > coverage.height) {
      jni::throwNewJavaException("java/lang/IllegalArgumentException", "The region is outside the bitmaps");
    }
  }

  jboolean fromRgba(jni::alias_ref<jni::JClass>, jni::alias_ref<JBitmap> rgba, jint width, jint rows, jni::alias_ref<JBitmap> coverage,
                    jint top) {
    LockedPixels in(rgba.get(), ANDROID_BITMAP_FORMAT_RGBA_8888, "The rendered bitmap");
    LockedPixels out(coverage.get(), ANDROID_BITMAP_FORMAT_A_8, "The coverage bitmap");
    checkRegion(width, rows, top, in.info(), out.info());
    return rgbaToCoverage(in.row(0), in.info().stride, out.row(static_cast<uint32_t>(top)), out.info().stride, static_cast<size_t>(width),
                          static_cast<size_t>(rows))
               ? JNI_TRUE
               : JNI_FALSE;
  }

  void toRgba(jni::alias_ref<jni::JClass>, jni::alias_ref<JBitmap> coverage, jint width, jint rows, jni::alias_ref<JBitmap> rgba) {
    LockedPixels in(coverage.get(), ANDROID_BITMAP_FORMAT_A_8, "The coverage bitmap");
    LockedPixels out(rgba.get(), ANDROID_BITMAP_FORMAT_RGBA_8888, "The rendered bitmap");
    checkRegion(width, rows, 0, out.info(), in.info());
    coverageToRgba(in.row(0), in.info().stride, out.row(0), out.info().stride, static_cast<size_t>(width), static_cast<size_t>(rows));
  }

} // namespace

void registerNativePageCoverage() {
  jni::findClassStatic("com/margelo/nitro/betterpdf/NativePageCoverage")
      ->registerNatives({makeNativeMethod("fromRgba", fromRgba), makeNativeMethod("toRgba", toRgba)});
}

} // namespace margelo::nitro::betterpdf
