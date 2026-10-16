import Foundation

/// The downloaded copies of remote documents on disk, by cache key
/// (`NativePdfSource.storeKey()`): `<key>.pdf` plus `<key>.json`
/// (`CacheMetadata`) in the session or the persistent directory. A key has at
/// most one current copy; a persistent copy wins over a session one.
///
/// - Session: `Caches/BetterPdf/session`.
/// - Persistent: `Application Support/BetterPdf/documents`, which the OS
///   never purges, excluded from backups (the copies can be downloaded
///   again).
/// - Pins: `Caches/BetterPdf/open`, links that keep a displayed copy's file
///   reachable by path after it was replaced or removed.
///
/// The first use in a process deletes the previous process's session copies,
/// unfinished copies and pins, and the flat layout of earlier versions in
/// `Caches/BetterPdf` (never its subdirectories: `renders` belongs to the
/// app).
///
/// Thread-safe; every method blocks on disk I/O, none on the network.
final class DocumentCacheStore {
  private let sessionDirectory: URL
  private let persistentDirectory: URL
  private let partialDirectory: URL
  private let pinDirectory: URL
  private let legacyDirectory: URL
  private let fileManager = FileManager.default
  private let lock = NSLock()
  private var isPrepared = false

  init() {
    let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("BetterPdf", isDirectory: true)
    let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("BetterPdf", isDirectory: true)
    sessionDirectory = caches.appendingPathComponent("session", isDirectory: true)
    partialDirectory = caches.appendingPathComponent("partial", isDirectory: true)
    pinDirectory = caches.appendingPathComponent("open", isDirectory: true)
    legacyDirectory = caches
    persistentDirectory = support.appendingPathComponent("documents", isDirectory: true)
  }

  func find(key: String) -> CachedCopy? {
    return lock.withLock {
      prepare()
      return copy(in: persistentDirectory, key: key, mode: .persistent)
        ?? copy(in: sessionDirectory, key: key, mode: .session)
    }
  }

  /// Moves the downloaded `file` in as the copy of `key`, replacing any
  /// previous one (open documents keep reading the old file). A persistent
  /// copy replaces the session one too.
  func commit(key: String, file: URL, mode: PdfCacheMode, metadata: CacheMetadata) throws -> CachedCopy {
    return try lock.withLock {
      prepare()
      let directory = try createDirectory(for: mode)
      let target = pdfFile(in: directory, key: key)
      try moveReplacing(file, to: target)
      try writeMetadata(metadata, in: directory, key: key)
      if mode == .persistent { deleteCopy(in: sessionDirectory, key: key) }
      return CachedCopy(fileURL: target, mode: mode, metadata: metadata)
    }
  }

  /// Records that the server confirmed `copy` unchanged.
  func markValidated(_ copy: CachedCopy, metadata: CacheMetadata) throws -> CachedCopy {
    return try lock.withLock {
      prepare()
      try writeMetadata(metadata, in: copy.fileURL.deletingLastPathComponent(), key: copy.key)
      return CachedCopy(fileURL: copy.fileURL, mode: copy.mode, metadata: metadata)
    }
  }

  /// Keeps a session `copy` across launches. The session file stays where it
  /// is (a hard link, so no second copy of the data) until the next launch,
  /// so paths already handed out stay valid.
  func promote(_ copy: CachedCopy) throws -> CachedCopy {
    if copy.mode == .persistent { return copy }
    return try lock.withLock {
      prepare()
      let directory = try createDirectory(for: .persistent)
      let target = pdfFile(in: directory, key: copy.key)
      try? fileManager.removeItem(at: target)
      do {
        try fileManager.linkItem(at: copy.fileURL, to: target)
      } catch {
        try fileManager.createDirectory(at: partialDirectory, withIntermediateDirectories: true)
        let partial = partialDirectory.appendingPathComponent(UUID().uuidString)
        try fileManager.copyItem(at: copy.fileURL, to: partial)
        try moveReplacing(partial, to: target)
      }
      try writeMetadata(copy.metadata, in: directory, key: copy.key)
      return CachedCopy(fileURL: target, mode: .persistent, metadata: copy.metadata)
    }
  }

  /// Deletes every copy of `key`; false when there was none.
  func remove(key: String) -> Bool {
    return lock.withLock {
      prepare()
      let removedPersistent = deleteCopy(in: persistentDirectory, key: key)
      let removedSession = deleteCopy(in: sessionDirectory, key: key)
      return removedPersistent || removedSession
    }
  }

  /// Deletes every copy. Downloads in progress still store their result, and
  /// displayed documents keep their pins.
  func clear() throws {
    try lock.withLock {
      prepare()
      for directory in [sessionDirectory, persistentDirectory] where fileManager.fileExists(atPath: directory.path) {
        try fileManager.removeItem(at: directory)
      }
    }
  }

  /// Bytes of all current copies; a session copy linked to a persistent one
  /// counts once.
  func byteSize() -> Int64 {
    return lock.withLock {
      prepare()
      let persistent = pdfFiles(in: persistentDirectory)
      let persistentKeys = Set(persistent.map { $0.deletingPathExtension().lastPathComponent })
      let session = pdfFiles(in: sessionDirectory)
        .filter { !persistentKeys.contains($0.deletingPathExtension().lastPathComponent) }
      return (persistent + session).reduce(0) { $0 + $1.fileSize() }
    }
  }

  /// A path to `copy`'s current file that stays valid until `unpin`, even
  /// when the copy is replaced or removed meanwhile (a hard link).
  func pin(_ copy: CachedCopy) throws -> URL {
    return try lock.withLock {
      prepare()
      try fileManager.createDirectory(at: pinDirectory, withIntermediateDirectories: true)
      let pin = pinDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("pdf")
      try fileManager.linkItem(at: copy.fileURL, to: pin)
      return pin
    }
  }

  /// A pin holding a decoded `{ base64 }` source: its only copy.
  func pin(data: Data) throws -> URL {
    let pin = try lock.withLock { () throws -> URL in
      prepare()
      try fileManager.createDirectory(at: pinDirectory, withIntermediateDirectories: true)
      return pinDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("pdf")
    }
    do {
      try data.write(to: pin)
    } catch {
      unpin(pin)
      throw error
    }
    return pin
  }

  func unpin(_ pin: URL) {
    try? fileManager.removeItem(at: pin)
  }

  /// Whether `file` is in one of the store's directories or below them,
  /// where writing it would replace a copy or a pin, or be deleted with
  /// them. `file` need not exist; its directory does when it is written.
  func contains(_ file: URL) -> Bool {
    let directory = Self.resolvedComponents(of: file.standardizedFileURL.deletingLastPathComponent())
    return [sessionDirectory, persistentDirectory, partialDirectory, pinDirectory].contains {
      directory.starts(with: Self.resolvedComponents(of: $0))
    }
  }

  // MARK: - Private (called with the lock held)

  private func prepare() {
    if isPrepared { return }
    isPrepared = true
    for directory in [sessionDirectory, partialDirectory, pinDirectory] {
      try? fileManager.removeItem(at: directory)
    }
    let legacy = (try? fileManager.contentsOfDirectory(at: legacyDirectory, includingPropertiesForKeys: nil)) ?? []
    for file in legacy where Self.isLegacyFile(file.lastPathComponent) {
      try? fileManager.removeItem(at: file)
    }
  }

  private func createDirectory(for mode: PdfCacheMode) throws -> URL {
    let directory = mode == .persistent ? persistentDirectory : sessionDirectory
    try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    if mode == .persistent {
      var root = persistentDirectory.deletingLastPathComponent()
      var values = URLResourceValues()
      values.isExcludedFromBackup = true
      try root.setResourceValues(values)
    }
    return directory
  }

  private func copy(in directory: URL, key: String, mode: PdfCacheMode) -> CachedCopy? {
    let file = pdfFile(in: directory, key: key)
    guard fileManager.fileExists(atPath: file.path) else { return nil }
    let metadata = readMetadata(in: directory, key: key) ?? CacheMetadata(
      validatedAtMs: Int64(((try? file.resourceValues(forKeys: [.contentModificationDateKey]))?
        .contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000)
    )
    return CachedCopy(fileURL: file, mode: mode, metadata: metadata)
  }

  @discardableResult
  private func deleteCopy(in directory: URL, key: String) -> Bool {
    try? fileManager.removeItem(at: metadataFile(in: directory, key: key))
    return (try? fileManager.removeItem(at: pdfFile(in: directory, key: key))) != nil
  }

  private func readMetadata(in directory: URL, key: String) -> CacheMetadata? {
    guard let data = try? Data(contentsOf: metadataFile(in: directory, key: key)) else { return nil }
    return try? JSONDecoder().decode(CacheMetadata.self, from: data)
  }

  private func writeMetadata(_ metadata: CacheMetadata, in directory: URL, key: String) throws {
    try JSONEncoder().encode(metadata).write(to: metadataFile(in: directory, key: key), options: .atomic)
  }

  /// `rename(2)`: atomic, and replaces `target` without disturbing open
  /// descriptors of the old file.
  private func moveReplacing(_ source: URL, to target: URL) throws {
    if rename(source.path, target.path) != 0 {
      throw CocoaError(.fileWriteUnknown, userInfo: [
        NSFilePathErrorKey: target.path,
        NSUnderlyingErrorKey: POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO),
      ])
    }
  }

  private func pdfFile(in directory: URL, key: String) -> URL {
    return directory.appendingPathComponent(key).appendingPathExtension("pdf")
  }

  private func metadataFile(in directory: URL, key: String) -> URL {
    return directory.appendingPathComponent(key).appendingPathExtension("json")
  }

  private func pdfFiles(in directory: URL) -> [URL] {
    let files = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
    return files.filter { $0.pathExtension == "pdf" }
  }

  private static func resolvedComponents(of directory: URL) -> [String] {
    return directory.standardizedFileURL.resolvingSymlinksInPath().pathComponents
  }

  /// Downloads (`<sha256>.pdf`) of versions before the cache API.
  private static func isLegacyFile(_ name: String) -> Bool {
    guard name.hasSuffix(".pdf") else { return false }
    let stem = name.dropLast(4)
    return stem.count == 64 && stem.allSatisfy { $0.isHexDigit && !$0.isUppercase }
  }
}
