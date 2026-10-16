#include "NativeBase64Document.hpp"
#include "Base64Document.hpp"

#include <cerrno>
#include <cstring>
#include <fbjni/ByteBuffer.h>
#include <fbjni/fbjni.h>
#include <unistd.h>
#include <vector>

namespace margelo::nitro::betterpdf {

using namespace facebook;

namespace {

  bool writeAll(int fd, const uint8_t* data, size_t count) {
    while (count > 0) {
      ssize_t written = ::write(fd, data, count);
      if (written < 0) {
        if (errno == EINTR) continue;
        return false;
      }
      data += written;
      count -= static_cast<size_t>(written);
    }
    return true;
  }

  jni::local_ref<jni::JString> writeDecoded(jni::alias_ref<jni::JClass>, jni::alias_ref<jni::JByteBuffer> text, jint fd) {
    size_t size = text->getDirectSize();
    const uint8_t* data = size == 0 ? nullptr : text->getDirectBytes();
    Base64Check check = checkBase64Document(data, size);
    if (check.problem.has_value()) return jni::make_jstring(*check.problem);
    std::vector<uint8_t> chunk(kBase64ChunkSize);
    int error = 0;
    bool written = decodeBase64Document(data, size, chunk.data(), chunk.size(), [&](const uint8_t* bytes, size_t count) {
      if (writeAll(fd, bytes, count)) return true;
      error = errno;
      return false;
    });
    if (!written) {
      jni::throwNewJavaException("java/io/IOException", "Could not write the decoded document: %s", std::strerror(error));
    }
    return nullptr;
  }

} // namespace

void registerNativeBase64Document() {
  jni::findClassStatic("com/margelo/nitro/betterpdf/NativeBase64Document")
      ->registerNatives({makeNativeMethod("writeDecoded", writeDecoded)});
}

} // namespace margelo::nitro::betterpdf
