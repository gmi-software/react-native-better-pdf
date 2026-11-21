// Stand-in for NitroModules, for loader.test.ts.
public final class ArrayBuffer {
  public let data: UnsafeMutablePointer<UInt8>
  public let size: Int
  public let isOwner = true

  public init(data: UnsafeMutablePointer<UInt8>, size: Int) {
    self.data = data
    self.size = size
  }
}
