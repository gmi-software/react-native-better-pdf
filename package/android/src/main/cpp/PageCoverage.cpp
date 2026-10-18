#include "PageCoverage.hpp"

namespace margelo::nitro::betterpdf {

bool rgbaToCoverage(const uint8_t* rgba, size_t rgbaStride, uint8_t* coverage, size_t coverageStride, size_t width, size_t rows) {
  for (size_t y = 0; y < rows; y++) {
    const uint8_t* in = rgba + y * rgbaStride;
    uint8_t* out = coverage + y * coverageStride;
    // Checked once per row, so the loop has no branch and vectorizes.
    uint8_t notGrey = 0;
    for (size_t x = 0; x < width; x++) {
      uint8_t r = in[4 * x];
      uint8_t g = in[4 * x + 1];
      uint8_t b = in[4 * x + 2];
      uint8_t a = in[4 * x + 3];
      notGrey |= static_cast<uint8_t>((r ^ g) | (g ^ b) | (a ^ 0xff));
      out[x] = static_cast<uint8_t>(0xff - r);
    }
    if (notGrey != 0) return false;
  }
  return true;
}

void coverageToRgba(const uint8_t* coverage, size_t coverageStride, uint8_t* rgba, size_t rgbaStride, size_t width, size_t rows) {
  for (size_t y = 0; y < rows; y++) {
    const uint8_t* in = coverage + y * coverageStride;
    uint8_t* out = rgba + y * rgbaStride;
    for (size_t x = 0; x < width; x++) {
      uint8_t grey = static_cast<uint8_t>(0xff - in[x]);
      out[4 * x] = grey;
      out[4 * x + 1] = grey;
      out[4 * x + 2] = grey;
      out[4 * x + 3] = 0xff;
    }
  }
}

} // namespace margelo::nitro::betterpdf
