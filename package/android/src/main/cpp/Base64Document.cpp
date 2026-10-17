#include "Base64Document.hpp"

namespace margelo::nitro::betterpdf {

namespace {

  bool isWhitespace(uint8_t c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
  }

  uint8_t lowercase(uint8_t c) {
    return c >= 'A' && c <= 'Z' ? static_cast<uint8_t>(c + ('a' - 'A')) : c;
  }

  std::string invalid(const std::string& reason) {
    return "The base64 source is not valid base64: " + reason;
  }

  std::string unexpectedCharacter(const uint8_t* text, size_t length, size_t index) {
    size_t start = 0;
    while (start < length && isWhitespace(text[start])) start++;
    static constexpr char kScheme[] = "data:";
    bool isDataUri = length - start >= 5;
    for (size_t i = 0; isDataUri && i < 5; i++) {
      isDataUri = lowercase(text[start + i]) == static_cast<uint8_t>(kScheme[i]);
    }
    if (isDataUri) return "The base64 source is a data: URI; pass only the part after \"base64,\"";
    if (text[index] == '-' || text[index] == '_') {
      return "The base64 source is URL-safe base64 (- or _ at index " + std::to_string(index) +
             "); use the standard alphabet (+ and /)";
    }
    return invalid("unexpected character at index " + std::to_string(index));
  }

} // namespace

Base64Check checkBase64Document(const uint8_t* text, size_t length) {
  size_t symbols = 0;
  size_t padding = 0;
  for (size_t index = 0; index < length; index++) {
    uint8_t c = text[index];
    if (base64detail::kValues[c] >= 0) {
      if (padding > 0) return {invalid("wrong length or padding")};
      symbols++;
    } else if (c == '=') {
      padding++;
    } else if (!isWhitespace(c)) {
      return {unexpectedCharacter(text, length, index)};
    }
  }
  if (symbols == 0 && padding == 0) return {"The base64 source is empty"};
  size_t remainder = symbols % 4;
  if (remainder == 1 || (padding > 0 && (remainder == 0 || padding != 4 - remainder))) {
    return {invalid("wrong length or padding")};
  }
  return {std::nullopt, symbols / 4 * 3 + (remainder > 1 ? remainder - 1 : 0)};
}

} // namespace margelo::nitro::betterpdf
