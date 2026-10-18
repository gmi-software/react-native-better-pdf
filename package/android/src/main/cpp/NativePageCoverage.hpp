#pragma once

namespace margelo::nitro::betterpdf {

/** Registers `NativePageCoverage.fromRgba` and `toRgba`; call from `JNI_OnLoad`. */
void registerNativePageCoverage();

} // namespace margelo::nitro::betterpdf
