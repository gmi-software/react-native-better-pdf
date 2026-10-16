// Runs Base64Document.cpp for base64.test.ts: hex cases on stdin, decoded
// 3 bytes per write (or `production`: kBase64ChunkSize), `chunk-size`, or
// `exhaustive <hex> N` for a digest of every text of up to N bytes.
#include "Base64Document.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <iostream>
#include <string>
#include <vector>

using namespace margelo::nitro::betterpdf;

namespace {

  std::vector<uint8_t> fromHex(const std::string& hex) {
    std::vector<uint8_t> bytes;
    for (size_t i = 0; i + 1 < hex.size(); i += 2) bytes.push_back(static_cast<uint8_t>(std::stoi(hex.substr(i, 2), nullptr, 16)));
    return bytes;
  }

  bool run(const std::vector<uint8_t>& text, size_t chunkSize, std::vector<uint8_t>& bytes, std::string& problem) {
    Base64Check check = checkBase64Document(text.data(), text.size());
    if (check.problem.has_value()) {
      problem = *check.problem;
      return false;
    }
    // The bytes past `chunkSize` keep 0xa5 unless the decoder overruns it.
    std::vector<uint8_t> chunk(chunkSize + 16, 0xa5);
    bool fits = true;
    bytes.clear();
    decodeBase64Document(text.data(), text.size(), chunk.data(), chunkSize, [&](const uint8_t* data, size_t count) {
      fits = fits && count <= chunkSize;
      bytes.insert(bytes.end(), data, data + count);
      return true;
    });
    if (!fits || std::any_of(chunk.begin() + static_cast<std::ptrdiff_t>(chunkSize), chunk.end(), [](uint8_t b) { return b != 0xa5; })) {
      problem = "wrote past the chunk";
      return false;
    }
    if (bytes.size() != check.decodedSize) {
      problem = "decodedSize " + std::to_string(check.decodedSize) + " != " + std::to_string(bytes.size());
      return false;
    }
    return true;
  }

  /** FNV-1a, 64 bits; the same in main.swift. */
  struct Digest {
    uint64_t value = 0xcbf29ce484222325ULL;
    void add(const uint8_t* data, size_t count) {
      for (size_t i = 0; i < count; i++) value = (value ^ data[i]) * 0x100000001b3ULL;
    }
    void add(uint8_t byte) { add(&byte, 1); }
  };

} // namespace

int main(int argc, char** argv) {
  std::vector<uint8_t> bytes;
  std::string problem;
  if (argc == 2 && std::string(argv[1]) == "chunk-size") {
    std::printf("%zu\n", kBase64ChunkSize);
    return 0;
  }
  if (argc == 4 && std::string(argv[1]) == "exhaustive") {
    std::vector<uint8_t> alphabet = fromHex(argv[2]);
    size_t maxLength = std::stoul(argv[3]);
    Digest digest;
    size_t count = 0;
    for (size_t length = 0; length <= maxLength; length++) {
      std::vector<size_t> digits(length, 0);
      std::vector<uint8_t> text(length);
      while (true) {
        for (size_t i = 0; i < length; i++) text[i] = alphabet[digits[i]];
        digest.add(text.data(), text.size());
        if (run(text, 3, bytes, problem)) {
          digest.add('o');
          digest.add(bytes.data(), bytes.size());
        } else {
          digest.add('e');
          digest.add(reinterpret_cast<const uint8_t*>(problem.data()), problem.size());
        }
        digest.add(0xff);
        count++;
        size_t i = 0;
        while (i < length && ++digits[i] == alphabet.size()) digits[i++] = 0;
        if (i == length) break;
      }
    }
    std::printf("%zu %016llx\n", count, static_cast<unsigned long long>(digest.value));
    return 0;
  }
  size_t chunkSize = argc == 2 && std::string(argv[1]) == "production" ? kBase64ChunkSize : 3;
  std::string line;
  while (std::getline(std::cin, line)) {
    if (run(fromHex(line), chunkSize, bytes, problem)) {
      std::printf("ok ");
      for (uint8_t byte : bytes) std::printf("%02x", byte);
      std::printf("\n");
    } else {
      std::printf("error %s\n", problem.c_str());
    }
  }
  return 0;
}
