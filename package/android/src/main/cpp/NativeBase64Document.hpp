#pragma once

namespace margelo::nitro::betterpdf {

/** Registers `NativeBase64Document.writeDecoded`; call from `JNI_OnLoad`. */
void registerNativeBase64Document();

} // namespace margelo::nitro::betterpdf
