import Foundation

/// Downloaded copies of remote documents, shared by every view and every
/// `PdfCache` call in the process: serves a copy that is fresh enough
/// (`CacheMetadata.isFresh`), else downloads or revalidates it. Concurrent
/// requests for the same copy share one `SharedDownload`, which stops once
/// nobody waits for it any more.
///
/// `fetch` returns at once; disk work runs on `workQueue`, downloads on
/// URLSession's delegate queue, and listeners are called on either.
final class DocumentCache {
  static let shared = DocumentCache(store: DocumentCacheStore())

  struct Listener {
    /// Download progress, unthrottled.
    let onProgress: (_ loadedBytes: Int64, _ totalBytes: Int64?, _ isComplete: Bool) -> Void
    /// The copy to open. `isStale`: older than the source's `maxAgeMs`,
    /// shown because the server could not be reached (only with `allowStale`).
    let onReady: (_ copy: CachedCopy, _ isStale: Bool) -> Void
    let onFailed: (PdfLoadFailure) -> Void
  }

  final class Subscriber {
    let source: NativePdfSource
    let allowStale: Bool
    let listener: Listener
    // Guarded by the cache's lock.
    fileprivate var isCancelled = false
    fileprivate var download: SharedDownload?

    fileprivate init(source: NativePdfSource, allowStale: Bool, listener: Listener) {
      self.source = source
      self.allowStale = allowStale
      self.listener = listener
    }
  }

  /// Stops listening; the download stops once nobody else waits for it.
  /// Idempotent.
  final class Subscription {
    private let onCancel: () -> Void

    fileprivate init(onCancel: @escaping () -> Void) {
      self.onCancel = onCancel
    }

    func cancel() { onCancel() }
  }

  let store: DocumentCacheStore
  private let clock: () -> Int64
  private let workQueue = DispatchQueue(label: "com.betterpdf.cache", qos: .userInitiated, attributes: .concurrent)
  private let lock = NSLock()
  private var inFlight: [String: SharedDownload] = [:]

  init(store: DocumentCacheStore, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
    self.store = store
    self.clock = clock
  }

  /// Delivers the copy of the `http(s)://` `source` to `listener`. With
  /// `allowStale`, an existing copy is delivered (as stale) when the server
  /// cannot be reached to revalidate it.
  func fetch(source: NativePdfSource, allowStale: Bool, listener: Listener) -> Subscription {
    let subscriber = Subscriber(source: source, allowStale: allowStale, listener: listener)
    workQueue.async { self.start(subscriber) }
    return Subscription { [weak self] in self?.cancel(subscriber) }
  }

  private func start(_ subscriber: Subscriber) {
    let source = subscriber.source
    guard let url = URL(sourceUri: source.uri), let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
      return deliverFailure(subscriber, .invalidSource(source.uri))
    }
    if let failure = source.invalidHeaderFailure() {
      return deliverFailure(subscriber, failure)
    }
    let key = source.storeKey()
    let copy = store.find(key: key)
    if let copy, copy.metadata.isFresh(maxAgeMs: source.maxAgeMs, nowMs: clock()) {
      return deliverReady(subscriber, copy, isStale: false)
    }
    let newDownload = lock.withLock { () -> SharedDownload? in
      if subscriber.isCancelled { return nil }
      if let download = inFlight[key] {
        download.subscribers.append(subscriber)
        subscriber.download = download
        return nil
      }
      let download = SharedDownload(
        key: key,
        url: url,
        headers: source.headers ?? [:],
        existing: copy,
        mode: source.cache == .persistent || copy?.mode == .persistent ? .persistent : .session,
        store: store,
        clock: clock,
        onProgress: { [weak self] download, loadedBytes, totalBytes, isComplete in
          self?.reportProgress(download, loadedBytes, totalBytes, isComplete)
        },
        onFinished: { [weak self] download, outcome in self?.finish(download, outcome) }
      )
      inFlight[key] = download
      download.subscribers.append(subscriber)
      subscriber.download = download
      return download
    }
    // Outside the lock; a cancel in between makes start() a no-op.
    newDownload?.start()
  }

  private func cancel(_ subscriber: Subscriber) {
    let abandoned = lock.withLock { () -> SharedDownload? in
      subscriber.isCancelled = true
      guard let download = subscriber.download else { return nil }
      subscriber.download = nil
      download.subscribers.removeAll { $0 === subscriber }
      guard download.subscribers.isEmpty, inFlight[download.key] === download else { return nil }
      inFlight[download.key] = nil
      return download
    }
    abandoned?.cancel()
  }

  private func reportProgress(_ download: SharedDownload, _ loadedBytes: Int64, _ totalBytes: Int64?, _ isComplete: Bool) {
    let subscribers = lock.withLock { download.subscribers }
    for subscriber in subscribers {
      subscriber.listener.onProgress(loadedBytes, totalBytes, isComplete)
    }
  }

  private func finish(_ download: SharedDownload, _ outcome: DownloadOutcome) {
    let subscribers = lock.withLock { () -> [Subscriber] in
      if inFlight[download.key] === download { inFlight[download.key] = nil }
      let waiting = download.subscribers
      waiting.forEach { $0.download = nil }
      download.subscribers = []
      return waiting
    }
    for subscriber in subscribers {
      switch outcome {
      case .stored(let copy):
        deliverReady(subscriber, copy, isStale: false)
      case .failed(let failure, let isServerUnreachable):
        if subscriber.allowStale, isServerUnreachable, let stale = store.find(key: download.key) {
          deliverReady(subscriber, stale, isStale: true)
        } else {
          deliverFailure(subscriber, failure)
        }
      }
    }
  }

  private func deliverReady(_ subscriber: Subscriber, _ copy: CachedCopy, isStale: Bool) {
    var delivered = copy
    if subscriber.source.cache == .persistent {
      do {
        delivered = try store.promote(copy)
      } catch {
        return deliverFailure(subscriber, PdfLoadFailure(
          code: .networkError,
          message: "Could not store the downloaded PDF",
          nativeDescription: error.localizedDescription
        ))
      }
    }
    if !isCancelled(subscriber) { subscriber.listener.onReady(delivered, isStale) }
  }

  private func deliverFailure(_ subscriber: Subscriber, _ failure: PdfLoadFailure) {
    if !isCancelled(subscriber) { subscriber.listener.onFailed(failure) }
  }

  private func isCancelled(_ subscriber: Subscriber) -> Bool {
    return lock.withLock { subscriber.isCancelled }
  }
}
