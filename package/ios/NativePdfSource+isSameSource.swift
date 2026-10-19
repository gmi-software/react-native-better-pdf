import NitroModules

extension NativePdfSource {
  /// Whether two sources show the same document, so a re-sent source never
  /// reloads. Sources with a `cacheKey` are the same document when their
  /// keys are equal, whatever their `uri` and headers (signed URLs, rotating
  /// tokens). Cache options alone never reload; a changed password does.
  /// Base64 sources compare by buffer.
  func isSameSource(as other: NativePdfSource) -> Bool {
    guard isSameBuffer(base64, other.base64) else { return false }
    guard (password ?? "") == (other.password ?? "") else { return false }
    if cacheKey != nil || other.cacheKey != nil { return cacheKey == other.cacheKey }
    return uri == other.uri && (headers ?? [:]) == (other.headers ?? [:])
  }
}

extension Optional where Wrapped == [NativePdfSource] {
  /// Whether two lists of sources show the same documents, in order.
  func isSameSources(as other: [NativePdfSource]?) -> Bool {
    switch (self, other) {
    case (nil, nil):
      return true
    case let (lhs?, rhs?):
      return lhs.count == rhs.count && zip(lhs, rhs).allSatisfy { $0.isSameSource(as: $1) }
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
