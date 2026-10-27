import Foundation

/// The redirects one download follows, the same as Android's
/// `RedirectPolicy`: at most `maxRedirects`, to `http(s)` only, never from
/// https to http, and the caller's headers only until the first hop to
/// another origin.
struct RedirectPolicy {
  enum Next {
    case follow
    /// The redirect response is the answer.
    case refuse
    case tooMany
  }

  static let maxRedirects = 10
  /// The statuses that redirect in URLSession.
  static let statuses: Set<Int> = [301, 302, 303, 307, 308]

  private let origin: Origin
  private var followed = 0
  private(set) var sendsHeaders = true

  init(first: URL) {
    origin = Origin(first)
  }

  mutating func next(from: URL, to: URL) -> Next {
    let scheme = to.scheme?.lowercased()
    guard scheme == "http" || scheme == "https" else { return .refuse }
    if from.scheme?.lowercased() == "https", scheme == "http" { return .refuse }
    if followed == Self.maxRedirects { return .tooMany }
    followed += 1
    if Origin(to) != origin { sendsHeaders = false }
    return .follow
  }

  private struct Origin: Equatable {
    let scheme: String
    let host: String
    let port: Int

    init(_ url: URL) {
      scheme = url.scheme?.lowercased() ?? ""
      host = url.host?.lowercased() ?? ""
      port = url.port ?? (scheme == "https" ? 443 : 80)
    }
  }
}
