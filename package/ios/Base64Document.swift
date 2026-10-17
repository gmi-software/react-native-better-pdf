import Foundation

/// Same rules and messages as `android/src/main/cpp/Base64Document.cpp`.
enum Base64Document {
  static func decode(_ text: UnsafeBufferPointer<UInt8>) -> Result<Data, PdfLoadFailure> {
    if let data = decodePadded(text) { return .success(data) }
    return decode(checking: text)
  }

  /// Foundation's faster decoder is more lenient than these rules, so it only
  /// gets padded input and its result counts only at the expected size.
  private static func decodePadded(_ bytes: UnsafeBufferPointer<UInt8>) -> Data? {
    let count = bytes.count
    guard count > 0, count % 4 == 0, let base = bytes.baseAddress else { return nil }
    var padding = 0
    if let found = memchr(base, Int32(UInt8(ascii: "=")), count) {
      padding = count - UnsafeRawPointer(base).distance(to: UnsafeRawPointer(found))
      guard padding == 1 || (padding == 2 && bytes[count - 1] == UInt8(ascii: "=")) else { return nil }
    }
    let encoded = Data(bytesNoCopy: UnsafeMutableRawPointer(mutating: base), count: count, deallocator: .none)
    guard let data = Data(base64Encoded: encoded), data.count == count / 4 * 3 - padding else { return nil }
    return data
  }

  private static func decode(checking bytes: UnsafeBufferPointer<UInt8>) -> Result<Data, PdfLoadFailure> {
    var symbols = 0
    var padding = 0
    for index in bytes.indices {
      let byte = bytes[index]
      if isWhitespace(byte) { continue }
      if byte == UInt8(ascii: "=") {
        padding += 1
      } else if symbolValue(byte) < 0 {
        return .failure(unexpectedCharacter(in: bytes, at: index))
      } else if padding > 0 {
        return .failure(invalid("wrong length or padding"))
      } else {
        symbols += 1
      }
    }
    if symbols == 0 && padding == 0 {
      return .failure(PdfLoadFailure(code: .invalidSource, message: "The base64 source is empty"))
    }
    let remainder = symbols % 4
    if remainder == 1 || (padding > 0 && (remainder == 0 || padding != 4 - remainder)) {
      return .failure(invalid("wrong length or padding"))
    }

    var data = Data(count: symbols / 4 * 3 + max(remainder - 1, 0))
    data.withUnsafeMutableBytes { raw in
      let output = raw.bindMemory(to: UInt8.self)
      var bits: UInt32 = 0
      var pending = 0
      var position = 0
      for byte in bytes {
        let symbol = symbolValue(byte)
        if symbol < 0 { continue }
        bits = bits << 6 | UInt32(symbol)
        pending += 1
        if pending == 4 {
          output[position] = UInt8(truncatingIfNeeded: bits >> 16)
          output[position + 1] = UInt8(truncatingIfNeeded: bits >> 8)
          output[position + 2] = UInt8(truncatingIfNeeded: bits)
          position += 3
          bits = 0
          pending = 0
        }
      }
      if pending == 2 {
        output[position] = UInt8(truncatingIfNeeded: bits >> 4)
      } else if pending == 3 {
        output[position] = UInt8(truncatingIfNeeded: bits >> 10)
        output[position + 1] = UInt8(truncatingIfNeeded: bits >> 2)
      }
    }
    return .success(data)
  }

  private static func isWhitespace(_ byte: UInt8) -> Bool {
    return byte == 0x20 || byte == 0x09 || byte == 0x0D || byte == 0x0A
  }

  private static func symbolValue(_ byte: UInt8) -> Int {
    switch byte {
    case UInt8(ascii: "A")...UInt8(ascii: "Z"): return Int(byte - UInt8(ascii: "A"))
    case UInt8(ascii: "a")...UInt8(ascii: "z"): return Int(byte - UInt8(ascii: "a")) + 26
    case UInt8(ascii: "0")...UInt8(ascii: "9"): return Int(byte - UInt8(ascii: "0")) + 52
    case UInt8(ascii: "+"): return 62
    case UInt8(ascii: "/"): return 63
    default: return -1
    }
  }

  private static func unexpectedCharacter(in bytes: UnsafeBufferPointer<UInt8>, at index: Int) -> PdfLoadFailure {
    let start = bytes.firstIndex { !isWhitespace($0) } ?? index
    let prefix = bytes[start..<min(start + 5, bytes.endIndex)].map { (0x41...0x5A).contains($0) ? $0 + 0x20 : $0 }
    if prefix == Array("data:".utf8) {
      return PdfLoadFailure(code: .invalidSource, message: "The base64 source is a data: URI; pass only the part after \"base64,\"")
    }
    let byte = bytes[index]
    if byte == UInt8(ascii: "-") || byte == UInt8(ascii: "_") {
      return PdfLoadFailure(
        code: .invalidSource,
        message: "The base64 source is URL-safe base64 (- or _ at index \(index)); use the standard alphabet (+ and /)"
      )
    }
    return invalid("unexpected character at index \(index)")
  }

  private static func invalid(_ reason: String) -> PdfLoadFailure {
    return PdfLoadFailure(code: .invalidSource, message: "The base64 source is not valid base64: \(reason)")
  }
}
