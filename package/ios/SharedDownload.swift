import Foundation

/// One HTTP request for one cache key, shared by everyone waiting for that
/// copy (`DocumentCache` owns `subscribers`). With an `existing` copy it is a
/// conditional request: `304` keeps the copy, `200` replaces it.
///
/// Redirects, progress and completion arrive on URLSession's delegate queue;
/// `cancel()` is safe from any thread and ends the download without an
/// outcome.
final class SharedDownload {
  let key: String
  /// Guarded by the `DocumentCache`'s lock.
  var subscribers: [DocumentCache.Subscriber] = []

  private let url: URL
  private let headers: [String: String]
  private let existing: CachedCopy?
  /// Where a new copy is stored; joiners that want it persistent promote it.
  private let mode: PdfCacheMode
  private let store: DocumentCacheStore
  private let clock: () -> Int64
  private let onProgress: (SharedDownload, Int64, Int64?, Bool) -> Void
  private let onFinished: (SharedDownload, DownloadOutcome) -> Void

  private let lock = NSLock()
  private var isCancelled = false
  private var task: URLSessionDownloadTask?
  private var progressObservation: NSKeyValueObservation?
  // Used on URLSession's delegate queue only.
  private var redirects: RedirectPolicy
  private var hasTooManyRedirects = false

  /// Shorter than URLSession's default, so a dead network shows the
  /// existing copy soon.
  private static let revalidationTimeout: TimeInterval = 10

  init(
    key: String,
    url: URL,
    headers: [String: String],
    existing: CachedCopy?,
    mode: PdfCacheMode,
    store: DocumentCacheStore,
    clock: @escaping () -> Int64,
    onProgress: @escaping (SharedDownload, Int64, Int64?, Bool) -> Void,
    onFinished: @escaping (SharedDownload, DownloadOutcome) -> Void
  ) {
    self.key = key
    self.url = url
    self.headers = headers
    self.redirects = RedirectPolicy(first: url)
    self.existing = existing
    self.mode = mode
    self.store = store
    self.clock = clock
    self.onProgress = onProgress
    self.onFinished = onFinished
  }

  func start() {
    let task = URLSession.shared.downloadTask(with: request(for: url, withHeaders: true)) { [self] tempURL, response, error in
      // The temp file is deleted when this handler returns: finish here.
      progressObservation?.invalidate()
      if cancelled { return }
      onFinished(self, outcome(tempURL: tempURL, response: response, error: error))
    }
    task.delegate = RedirectHandler { [weak self] response, proposed in self?.redirect(response, to: proposed) }
    progressObservation = task.observe(\.countOfBytesReceived, options: []) { [weak self] observed, _ in
      guard let self else { return }
      let loadedBytes = observed.countOfBytesReceived
      self.onProgress(self, loadedBytes, Self.reportedTotalBytes(observed.countOfBytesExpectedToReceive, loaded: loadedBytes), false)
    }
    let started = lock.withLock { () -> Bool in
      if isCancelled { return false }
      self.task = task
      return true
    }
    if started {
      task.resume()
    } else {
      progressObservation?.invalidate()
    }
  }

  func cancel() {
    let task = lock.withLock { () -> URLSessionDownloadTask? in
      isCancelled = true
      return self.task
    }
    task?.cancel()
  }

  private var cancelled: Bool { lock.withLock { isCancelled } }

  private func request(for url: URL, withHeaders: Bool) -> URLRequest {
    var request = URLRequest(url: url)
    // The copies in the store are the cache; URLCache must neither answer
    // instead of the server nor turn a 304 into a cached 200.
    request.cachePolicy = .reloadIgnoringLocalCacheData
    if withHeaders {
      for (name, value) in headers {
        request.setValue(value, forHTTPHeaderField: name)
      }
    }
    if let existing {
      request.timeoutInterval = Self.revalidationTimeout
      if let etag = existing.metadata.etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
      if let lastModified = existing.metadata.lastModified {
        request.setValue(lastModified, forHTTPHeaderField: "If-Modified-Since")
      }
    }
    return request
  }

  /// The request that follows `response`, built anew: URLSession's proposal
  /// keeps the caller's headers on every origin but drops `Authorization`
  /// even on the same one.
  private func redirect(_ response: HTTPURLResponse, to proposed: URLRequest) -> URLRequest? {
    guard let from = response.url, let to = proposed.url else { return nil }
    switch redirects.next(from: from, to: to) {
    case .follow:
      return request(for: to, withHeaders: redirects.sendsHeaders)
    case .refuse:
      return nil
    case .tooMany:
      hasTooManyRedirects = true
      return nil
    }
  }

  private func outcome(tempURL: URL?, response: URLResponse?, error: Error?) -> DownloadOutcome {
    let url = self.url.absoluteString
    if hasTooManyRedirects {
      return .failed(PdfLoadFailure(
        code: .networkError,
        message: "Downloading \(url) failed: more than \(RedirectPolicy.maxRedirects) redirects"
      ), isServerUnreachable: false)
    }
    if let error {
      return .failed(PdfLoadFailure(
        code: .networkError,
        message: "Downloading \(url) failed",
        nativeDescription: error.localizedDescription
      ), isServerUnreachable: true)
    }
    guard let http = response as? HTTPURLResponse else {
      return .failed(PdfLoadFailure(code: .networkError, message: "Downloading \(url) produced no HTTP response"), isServerUnreachable: true)
    }
    if http.statusCode == 304, let existing {
      let metadata = CacheMetadata(
        validatedAtMs: clock(),
        etag: http.value(forHTTPHeaderField: "ETag") ?? existing.metadata.etag,
        lastModified: http.value(forHTTPHeaderField: "Last-Modified") ?? existing.metadata.lastModified
      )
      return storing { try store.markValidated(existing, metadata: metadata) }
    }
    guard (200..<300).contains(http.statusCode) else {
      let reason = RedirectPolicy.statuses.contains(http.statusCode) ? ": the redirect is not followed" : ""
      return .failed(PdfLoadFailure(
        code: .httpError,
        message: "Downloading \(url) failed with HTTP \(http.statusCode)\(reason)",
        httpStatus: http.statusCode
      ), isServerUnreachable: http.statusCode >= 500)
    }
    guard let tempURL else {
      return .failed(PdfLoadFailure(code: .networkError, message: "Download produced no file"), isServerUnreachable: true)
    }
    let downloadedBytes = tempURL.fileSize()
    onProgress(self, downloadedBytes, Self.reportedTotalBytes(http.expectedContentLength, loaded: downloadedBytes), true)
    guard tempURL.hasPdfHeader() else {
      return .failed(PdfLoadFailure(
        code: .invalidPdf,
        message: "The server did not return a PDF document for \(url)"
      ), isServerUnreachable: false)
    }
    let metadata = CacheMetadata(
      validatedAtMs: clock(),
      etag: http.value(forHTTPHeaderField: "ETag"),
      lastModified: http.value(forHTTPHeaderField: "Last-Modified")
    )
    return storing { try store.commit(key: key, file: tempURL, mode: mode, metadata: metadata) }
  }

  /// Runs a store write; a failure (e.g. a full disk) counts as unreachable,
  /// so a view can still show an existing copy.
  private func storing(_ write: () throws -> CachedCopy) -> DownloadOutcome {
    do {
      return .stored(try write())
    } catch {
      return .failed(PdfLoadFailure(
        code: .networkError,
        message: "Could not store the downloaded PDF",
        nativeDescription: error.localizedDescription
      ), isServerUnreachable: true)
    }
  }

  /// The server-reported size, unless unknown (-1) or already exceeded
  /// (transparently decompressed responses report the compressed size).
  private static func reportedTotalBytes(_ expected: Int64, loaded: Int64) -> Int64? {
    return expected >= 0 && expected >= loaded ? expected : nil
  }
}

/// Hands each redirect of a task to `decide`; nil ends the task with the
/// redirect response.
private final class RedirectHandler: NSObject, URLSessionTaskDelegate {
  private let decide: (HTTPURLResponse, URLRequest) -> URLRequest?

  init(_ decide: @escaping (HTTPURLResponse, URLRequest) -> URLRequest?) {
    self.decide = decide
  }

  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse,
    newRequest request: URLRequest,
    completionHandler: @escaping (URLRequest?) -> Void
  ) {
    completionHandler(decide(response, request))
  }
}
