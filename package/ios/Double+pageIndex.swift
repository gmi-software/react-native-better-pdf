import Foundation

extension Double {
  /// A page number from JS as an index, rounded; nil when not finite.
  /// Clamped to the `Int32` range first, since `Int(_:)` traps on NaN,
  /// infinities and magnitudes from 2^63 (callers clamp to the document).
  var pageIndex: Int? {
    guard isFinite else { return nil }
    return Int(min(max(rounded(), Double(Int32.min)), Double(Int32.max)))
  }
}
