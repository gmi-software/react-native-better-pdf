import NitroModules

extension Optional where Wrapped == NativePdfSource {
  /// Whether two sources show the same document, so a re-sent source never
  /// reloads. Sources with a `cacheKey` are the same document when their
  /// keys are equal, whatever their `uri` and headers (signed URLs, rotating
  /// tokens). Cache options alone never reload. Base64 sources compare by buffer.
  func isSameSource(as other: NativePdfSource?) -> Bool {
    switch (self, other) {
    case (nil, nil):
      return true
    case let (lhs?, rhs?):
      guard isSameBuffer(lhs.base64, rhs.base64) else { return false }
      if lhs.cacheKey != nil || rhs.cacheKey != nil { return lhs.cacheKey == rhs.cacheKey }
      return lhs.uri == rhs.uri && (lhs.headers ?? [:]) == (rhs.headers ?? [:])
    default:
      return false
    }
  }
}

private func isSameBuffer(_ lhs: ArrayBuffer?, _ rhs: ArrayBuffer?) -> Bool {
  switch (lhs, rhs) {
  case (nil, nil):
    return true
  case let (lhs?, rhs?):
    return lhs.isOwner && rhs.isOwner && lhs.data == rhs.data
  default:
    return false
  }
}
