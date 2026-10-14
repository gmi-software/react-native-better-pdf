import Foundation

extension NativePdfSource {
  /// Why this source's headers cannot be sent, or nil: every name must be an
  /// HTTP token (RFC 9110) and no value may contain a control character
  /// other than tab, e.g. a line break. Checked before any request because
  /// the platforms disagree on the rest: URLSession drops such a value
  /// without an error and sends such a name as is, Android throws. Values
  /// never appear in the message: they may hold a token.
  func invalidHeaderFailure() -> PdfLoadFailure? {
    let headers = self.headers ?? [:]
    for name in headers.keys.sorted() {
      if name.isEmpty || !name.unicodeScalars.allSatisfy(Self.tokenCharacters.contains) {
        return PdfLoadFailure(code: .invalidSource, message: "Invalid request header name: \"\(name)\"")
      }
      // Scalars, not Characters: "\r\n" is a single Character.
      if headers[name, default: ""].unicodeScalars.contains(where: { ($0.value < 0x20 && $0 != "\t") || $0.value == 0x7F }) {
        return PdfLoadFailure(code: .invalidSource, message: "Invalid value for request header \"\(name)\"")
      }
    }
    return nil
  }

  private static let tokenCharacters = CharacterSet(
    charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&'*+-.^_`|~"
  )
}
