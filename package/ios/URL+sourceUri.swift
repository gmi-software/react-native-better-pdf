import Foundation

extension URL {
  /// Encodes characters a URL cannot contain (a space) the same way on every
  /// iOS version: `URL(string:)` returns nil for them before iOS 17, and from
  /// iOS 17 also re-encodes escapes next to them (`%20` becomes `%2520`).
  init?(sourceUri uri: String) {
    let encoded = Self.encodingInvalidCharacters(uri)
    guard let url = Self.strict(uri) ?? Self.strict(encoded) ?? URL(string: encoded) else { return nil }
    self = url
  }

  /// Encodes what follows the host as iOS 17's `URL(string:)` does, but keeps
  /// escapes.
  static func encodingInvalidCharacters(_ uri: String) -> String {
    var start = uri.startIndex
    if let separator = uri.range(of: "://") {
      start = uri[separator.upperBound...].firstIndex { "/?#".contains($0) } ?? uri.endIndex
    }
    let rest = Array(uri[start...].utf8)
    var encoded = Array(uri[..<start].utf8)
    var isInFragment = false
    for (index, byte) in rest.enumerated() {
      if byte == UInt8(ascii: "#") && !isInFragment {
        isInFragment = true
        encoded.append(byte)
      } else if byte == UInt8(ascii: "%") && index + 2 < rest.count && isHexDigit(rest[index + 1]) && isHexDigit(rest[index + 2]) {
        encoded.append(byte)
      } else if allowedBytes.contains(byte) {
        encoded.append(byte)
      } else {
        encoded.append(contentsOf: [UInt8(ascii: "%"), hexDigits[Int(byte >> 4)], hexDigits[Int(byte & 0xF)]])
      }
    }
    return String(decoding: encoded, as: UTF8.self)
  }

  /// `URL(string:)` without the encoding iOS 17 adds.
  private static func strict(_ string: String) -> URL? {
    if #available(iOS 17, *) { return URL(string: string, encodingInvalidCharacters: false) }
    return URL(string: string)
  }

  private static let allowedBytes = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/?".utf8)
  private static let hexDigits = Array("0123456789ABCDEF".utf8)

  private static func isHexDigit(_ byte: UInt8) -> Bool {
    return hexDigits.contains(byte) || (UInt8(ascii: "a")...UInt8(ascii: "f")).contains(byte)
  }
}
