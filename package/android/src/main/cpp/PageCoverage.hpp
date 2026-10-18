#pragma once

#include <cstddef>
#include <cstdint>

namespace margelo::nitro::betterpdf {

/**
 * Writes the coverage (255 − r) of `rows` rows of RGBA pixels (r, g, b, a
 * bytes, Android's ARGB_8888; strides in bytes). Returns false at the first
 * row with a pixel that is not opaque grey; later rows are not written.
 */
bool rgbaToCoverage(const uint8_t* rgba, size_t rgbaStride, uint8_t* coverage, size_t coverageStride, size_t width, size_t rows);

/** Opaque grey RGBA pixels from coverage: the reverse of `rgbaToCoverage`. */
void coverageToRgba(const uint8_t* coverage, size_t coverageStride, uint8_t* rgba, size_t rgbaStride, size_t width, size_t rows);

} // namespace margelo::nitro::betterpdf
