// Checks PageCoverage.cpp for coverage.test.ts: prints "ok" or the first
// failed case.
#include "PageCoverage.hpp"

#include <cstdint>
#include <cstdio>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

using namespace margelo::nitro::betterpdf;

namespace {

  constexpr uint8_t kUntouched = 0xa5;

  /** xorshift32: the same pixels on every run. */
  struct Random {
    uint32_t state;
    uint8_t next() {
      state ^= state << 13;
      state ^= state >> 17;
      state ^= state << 5;
      return static_cast<uint8_t>(state >> 24);
    }
  };

  struct Image {
    size_t width, rows, rgbaStride, coverageStride;
    std::vector<uint8_t> rgba;
    std::vector<uint8_t> coverage;

    /** Random opaque greys; padding and coverage start as kUntouched. */
    Image(size_t width, size_t rows, size_t rgbaPadding, size_t coveragePadding, uint32_t seed)
        : width(width), rows(rows), rgbaStride(width * 4 + rgbaPadding), coverageStride(width + coveragePadding),
          rgba(rgbaStride * rows, kUntouched), coverage(coverageStride * rows, kUntouched) {
      Random random{seed};
      for (size_t y = 0; y < rows; y++) {
        for (size_t x = 0; x < width; x++) {
          uint8_t grey = random.next();
          uint8_t* pixel = &rgba[y * rgbaStride + 4 * x];
          pixel[0] = pixel[1] = pixel[2] = grey;
          pixel[3] = 0xff;
        }
      }
    }

    uint8_t* pixel(size_t x, size_t y) { return &rgba[y * rgbaStride + 4 * x]; }

    bool toCoverage() { return rgbaToCoverage(rgba.data(), rgbaStride, coverage.data(), coverageStride, width, rows); }

    /** Coverage of rows [0, rows) is 255 − r, later rows and all padding untouched. */
    std::string checkCoverage(size_t writtenRows) const {
      for (size_t y = 0; y < rows; y++) {
        for (size_t x = 0; x < coverageStride; x++) {
          uint8_t actual = coverage[y * coverageStride + x];
          uint8_t expected = y < writtenRows && x < width ? static_cast<uint8_t>(0xff - rgba[y * rgbaStride + 4 * x]) : kUntouched;
          if (actual != expected) {
            return "coverage at " + std::to_string(x) + "," + std::to_string(y) + " is " + std::to_string(actual) + ", expected " +
                   std::to_string(expected);
          }
        }
      }
      return "";
    }
  };

  std::string fail(const std::string& name, const std::string& problem) { return name + ": " + problem; }

  std::string checkGreys() {
    // Odd widths and padded strides, so no row is a whole number of vectors.
    for (auto [width, rows, rgbaPadding, coveragePadding] : {std::tuple<size_t, size_t, size_t, size_t>{1083, 37, 12, 5},
                                                             {1, 1, 0, 0},
                                                             {17, 3, 4, 3},
                                                             {1080, 2, 0, 0}}) {
      std::string name = "greys " + std::to_string(width) + "x" + std::to_string(rows);
      Image image(width, rows, rgbaPadding, coveragePadding, static_cast<uint32_t>(width * 31 + rows));
      if (!image.toCoverage()) return fail(name, "reported a colour");
      std::string problem = image.checkCoverage(rows);
      if (!problem.empty()) return fail(name, problem);

      // Back to RGBA: the same pixels, padding untouched.
      std::vector<uint8_t> back(image.rgbaStride * rows, kUntouched);
      coverageToRgba(image.coverage.data(), image.coverageStride, back.data(), image.rgbaStride, width, rows);
      for (size_t i = 0; i < back.size(); i++) {
        size_t x = (i % image.rgbaStride) / 4;
        uint8_t expected = x < width ? image.rgba[i] : kUntouched;
        if (back[i] != expected) return fail(name, "RGBA byte " + std::to_string(i) + " is " + std::to_string(back[i]));
      }
    }
    return "";
  }

  std::string checkExtremes() {
    Image image(2, 1, 0, 0, 1);
    image.pixel(0, 0)[0] = image.pixel(0, 0)[1] = image.pixel(0, 0)[2] = 0x00;
    image.pixel(1, 0)[0] = image.pixel(1, 0)[1] = image.pixel(1, 0)[2] = 0xff;
    if (!image.toCoverage()) return fail("black and white", "reported a colour");
    if (image.coverage[0] != 0xff || image.coverage[1] != 0x00) return fail("black and white", "wrong coverage");
    return "";
  }

  /** One pixel that is not opaque grey, at every corner of the image and in each channel. */
  std::string checkColours() {
    const size_t width = 61, rows = 9;
    const std::pair<size_t, size_t> positions[] = {{0, 0}, {width - 1, 0}, {30, 4}, {0, rows - 1}, {width - 1, rows - 1}};
    for (auto [x, y] : positions) {
      for (size_t channel = 0; channel < 4; channel++) {
        std::string name = "channel " + std::to_string(channel) + " at " + std::to_string(x) + "," + std::to_string(y);
        Image image(width, rows, 8, 3, static_cast<uint32_t>(x * 7 + y * 13 + channel + 1));
        uint8_t* pixel = image.pixel(x, y);
        // Off by one in this channel only: the smallest difference.
        pixel[channel] = static_cast<uint8_t>(channel == 3 ? 0xfe : pixel[channel] ^ 1);
        if (image.toCoverage()) return fail(name, "reported grey");
        // Rows before the coloured one are complete, later ones untouched;
        // the coloured row itself may be written, so it is not compared.
        for (size_t i = 0; i < image.width; i++) image.coverage[y * image.coverageStride + i] = kUntouched;
        std::string problem = image.checkCoverage(y);
        if (!problem.empty()) return fail(name, problem);
      }
    }
    return "";
  }

  std::string checkEmpty() {
    std::vector<uint8_t> rgba(16, kUntouched), coverage(16, kUntouched);
    if (!rgbaToCoverage(rgba.data(), 16, coverage.data(), 4, 0, 4)) return fail("no columns", "reported a colour");
    if (!rgbaToCoverage(rgba.data(), 16, coverage.data(), 4, 4, 0)) return fail("no rows", "reported a colour");
    coverageToRgba(coverage.data(), 4, rgba.data(), 16, 0, 4);
    coverageToRgba(coverage.data(), 4, rgba.data(), 16, 4, 0);
    for (uint8_t byte : rgba) {
      if (byte != kUntouched) return fail("empty", "wrote RGBA");
    }
    for (uint8_t byte : coverage) {
      if (byte != kUntouched) return fail("empty", "wrote coverage");
    }
    return "";
  }

} // namespace

int main() {
  for (auto check : {checkGreys, checkExtremes, checkColours, checkEmpty}) {
    std::string problem = check();
    if (!problem.empty()) {
      std::printf("%s\n", problem.c_str());
      return 1;
    }
  }
  std::printf("ok\n");
  return 0;
}
