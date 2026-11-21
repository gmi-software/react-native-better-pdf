import Foundation

extension String {
  /// This URI as error messages show it: `scheme://host[:port]/path`, `?…` in
  /// place of a query, no user-info or fragment. Apps log messages, and those
  /// parts often hold a token. Anything but a `scheme://` URI is not shown.
  /// Split by hand, the same way as on Android, so both platforms show the
  /// same text.
  var redactedUri: String {
    let bytes = Array(utf8)
    guard let authorityStart = Self.authorityStart(bytes) else { return "<not a URI>" }
    let authorityEnd = bytes[authorityStart...].firstIndex { "/?#".utf8.contains($0) } ?? bytes.endIndex
    let hostStart = bytes[authorityStart..<authorityEnd].lastIndex(of: UInt8(ascii: "@")).map { $0 + 1 } ?? authorityStart
    let pathEnd = bytes[authorityEnd...].firstIndex { "?#".utf8.contains($0) } ?? bytes.endIndex
    let query = pathEnd < bytes.endIndex && bytes[pathEnd] == UInt8(ascii: "?") ? "?…" : ""
    return String(decoding: bytes[..<authorityStart] + bytes[hostStart..<pathEnd], as: UTF8.self) + query
  }

  /// The index after `scheme://`, if `bytes` starts with one.
  private static func authorityStart(_ bytes: [UInt8]) -> Int? {
    guard let first = bytes.first, isLetter(first) else { return nil }
    var index = 1
    while index < bytes.count, isLetter(bytes[index]) || (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(bytes[index]) || "+.-".utf8.contains(bytes[index]) {
      index += 1
    }
    return bytes[index...].starts(with: "://".utf8) ? index + 3 : nil
  }

  private static func isLetter(_ byte: UInt8) -> Bool {
    return (UInt8(ascii: "a")...UInt8(ascii: "z")).contains(byte | 0x20)
  }
}
