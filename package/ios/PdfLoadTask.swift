import Foundation

/// Handle for an in-flight load. `cancel()` is idempotent and safe from any thread.
final class PdfLoadTask {
  private let lock = NSLock()
  private var cancelled = false
  private var cancellations: [() -> Void] = []

  var isCancelled: Bool { lock.withLock { cancelled } }

  func cancel() {
    let cancellations = lock.withLock { () -> [() -> Void] in
      cancelled = true
      defer { self.cancellations = [] }
      return self.cancellations
    }
    cancellations.forEach { $0() }
  }

  /// Runs `cancellation` (on the cancelling thread) when the task is
  /// cancelled; false (and nothing attached) once cancelled.
  func attach(_ cancellation: @escaping () -> Void) -> Bool {
    return lock.withLock {
      if cancelled { return false }
      cancellations.append(cancellation)
      return true
    }
  }
}
