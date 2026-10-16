#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>

namespace margelo::nitro::betterpdf {

/** Same rules and messages as `ios/Base64Document.swift`. */
struct Base64Check {
  std::optional<std::string> problem;
  size_t decodedSize = 0;
};

Base64Check checkBase64Document(const uint8_t* text, size_t length);

/** The chunk `NativeBase64Document` decodes into, also tested at this size. */
inline constexpr size_t kBase64ChunkSize = 256 * 1024;

namespace base64detail {
  inline constexpr std::array<int8_t, 256> kValues = [] {
    std::array<int8_t, 256> values{};
    values.fill(-1);
    for (int c = 0; c < 26; c++) {
      values['A' + c] = static_cast<int8_t>(c);
      values['a' + c] = static_cast<int8_t>(26 + c);
    }
    for (int c = 0; c < 10; c++) values['0' + c] = static_cast<int8_t>(52 + c);
    values['+'] = 62;
    values['/'] = 63;
    return values;
  }();
} // namespace base64detail

/** Decodes a checked text, `chunkSize` (at least 3) bytes per `write` at most. */
template <typename Write>
bool decodeBase64Document(const uint8_t* text, size_t length, uint8_t* chunk, size_t chunkSize, Write&& write) {
  size_t filled = 0;
  uint32_t bits = 0;
  int pending = 0;
  for (size_t index = 0; index < length; index++) {
    int symbol = base64detail::kValues[text[index]];
    if (symbol < 0) continue;
    bits = (bits << 6) | static_cast<uint32_t>(symbol);
    if (++pending == 4) {
      if (chunkSize - filled < 3) {
        if (!write(chunk, filled)) return false;
        filled = 0;
      }
      chunk[filled++] = static_cast<uint8_t>(bits >> 16);
      chunk[filled++] = static_cast<uint8_t>(bits >> 8);
      chunk[filled++] = static_cast<uint8_t>(bits);
      bits = 0;
      pending = 0;
    }
  }
  if (pending > 1 && chunkSize - filled < static_cast<size_t>(pending - 1)) {
    if (!write(chunk, filled)) return false;
    filled = 0;
  }
  if (pending == 2) {
    chunk[filled++] = static_cast<uint8_t>(bits >> 4);
  } else if (pending == 3) {
    chunk[filled++] = static_cast<uint8_t>(bits >> 10);
    chunk[filled++] = static_cast<uint8_t>(bits >> 2);
  }
  return filled == 0 || write(chunk, filled);
}

} // namespace margelo::nitro::betterpdf
