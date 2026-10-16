import Foundation

/// Decides which download progress updates reach JS: at most one per
/// `intervalMs`, always the final one, never the same byte count twice.
/// Bounds `onLoadProgress` to ~10 events per second regardless of file size or
/// network speed. Locked: progress (KVO) and the final update come from
/// different URLSession queues.
final class ProgressThrottle {
  private let intervalMs: UInt64
  private let lock = NSLock()
  private var lastEmitMs: UInt64?
  private var lastLoadedBytes: Int64 = -1

  init(intervalMs: UInt64 = 100) {
    self.intervalMs = intervalMs
  }

  func shouldEmit(nowMs: UInt64, loadedBytes: Int64, isComplete: Bool) -> Bool {
    return lock.withLock {
      if loadedBytes == lastLoadedBytes { return false }
      if !isComplete, let lastEmitMs, nowMs &- lastEmitMs < intervalMs { return false }
      lastEmitMs = nowMs
      lastLoadedBytes = loadedBytes
      return true
    }
  }
}
