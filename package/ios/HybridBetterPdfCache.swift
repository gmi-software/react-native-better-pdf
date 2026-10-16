import Foundation
import NitroModules

/// iOS implementation of `BetterPdfCache`: the JS side of the process-wide
/// `DocumentCache`. Methods arrive on the JS thread and return at once; the
/// work runs on the cache's queues.
final class HybridBetterPdfCache: HybridBetterPdfCacheSpec {
  private let lock = NSLock()
  /// Downloads JS may still cancel, by request id.
  private var downloads: [Double: DocumentCache.Subscription] = [:]
  private var finishedRequests: Set<Double> = []

  func download(
    requestId: Double,
    source: NativePdfSource,
    onProgress: ((PdfLoadProgressEvent) -> Void)?
  ) throws -> Promise<PdfDownloadResult> {
    let promise = Promise<PdfDownloadResult>()
    let throttle = ProgressThrottle()
    let subscription = DocumentCache.shared.fetch(source: source, allowStale: false, listener: .init(
      onProgress: { loadedBytes, totalBytes, isComplete in
        guard let onProgress else { return }
        let nowMs = DispatchTime.now().uptimeNanoseconds / 1_000_000
        if throttle.shouldEmit(nowMs: nowMs, loadedBytes: loadedBytes, isComplete: isComplete) {
          onProgress(PdfLoadProgressEvent(loadedBytes: Double(loadedBytes), totalBytes: totalBytes.map(Double.init)))
        }
      },
      onReady: { [weak self] copy, _ in
        self?.finish(requestId)
        promise.resolve(withResult: PdfDownloadResult(entry: copy.toPdfCacheEntry(), error: nil))
      },
      onFailed: { [weak self] failure in
        self?.finish(requestId)
        promise.resolve(withResult: PdfDownloadResult(entry: nil, error: failure.toPdfError()))
      }
    ))
    lock.withLock {
      // It may have finished before it was registered.
      if finishedRequests.remove(requestId) == nil { downloads[requestId] = subscription }
    }
    return promise
  }

  func cancelDownload(requestId: Double) throws {
    let subscription = lock.withLock { downloads.removeValue(forKey: requestId) }
    subscription?.cancel()
  }

  func getEntry(source: NativePdfSource) throws -> Promise<PdfCacheEntry?> {
    return Promise.parallel(Self.queue) {
      DocumentCache.shared.store.find(key: source.storeKey())?.toPdfCacheEntry()
    }
  }

  func remove(source: NativePdfSource) throws -> Promise<Bool> {
    return Promise.parallel(Self.queue) {
      DocumentCache.shared.store.remove(key: source.storeKey())
    }
  }

  func clear() throws -> Promise<Void> {
    return Promise.parallel(Self.queue) {
      try DocumentCache.shared.store.clear()
    }
  }

  func getByteSize() throws -> Promise<Double> {
    return Promise.parallel(Self.queue) {
      Double(DocumentCache.shared.store.byteSize())
    }
  }

  private static let queue = DispatchQueue(label: "com.betterpdf.cache.api", qos: .userInitiated, attributes: .concurrent)

  private func finish(_ requestId: Double) {
    lock.withLock {
      if downloads.removeValue(forKey: requestId) == nil { finishedRequests.insert(requestId) }
    }
  }
}
