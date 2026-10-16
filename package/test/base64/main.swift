// Runs ios/Base64Document.swift for base64.test.ts, like harness.cpp.
import Foundation

// Stand-ins for the types Nitro generates.
enum PdfErrorCode { case invalidSource, invalidPdf }
struct PdfLoadFailure: Error {
  let code: PdfErrorCode
  let message: String
}

func fromHex(_ hex: Substring) -> [UInt8] {
  var bytes: [UInt8] = []
  var index = hex.startIndex
  while index < hex.endIndex, let next = hex.index(index, offsetBy: 2, limitedBy: hex.endIndex) {
    bytes.append(UInt8(hex[index..<next], radix: 16)!)
    index = next
  }
  return bytes
}

func run(_ text: [UInt8]) -> Result<Data, PdfLoadFailure> {
  return text.withUnsafeBufferPointer { Base64Document.decode($0) }
}

/// FNV-1a, 64 bits; the same in harness.cpp.
struct Digest {
  var value: UInt64 = 0xcbf2_9ce4_8422_2325
  mutating func add<S: Sequence>(_ bytes: S) where S.Element == UInt8 {
    for byte in bytes { value = (value ^ UInt64(byte)) &* 0x0000_0100_0000_01b3 }
  }
}

let arguments = CommandLine.arguments
if arguments.count == 4, arguments[1] == "exhaustive" {
  let alphabet = fromHex(Substring(arguments[2]))
  let maxLength = Int(arguments[3])!
  var digest = Digest()
  var count = 0
  for length in 0...maxLength {
    var digits = [Int](repeating: 0, count: length)
    var text = [UInt8](repeating: 0, count: length)
    while true {
      for i in 0..<length { text[i] = alphabet[digits[i]] }
      digest.add(text)
      switch run(text) {
      case .success(let data):
        digest.add([UInt8(ascii: "o")])
        digest.add(data)
      case .failure(let failure):
        digest.add([UInt8(ascii: "e")])
        digest.add(failure.message.utf8)
      }
      digest.add([0xff])
      count += 1
      var i = 0
      while i < length {
        digits[i] += 1
        if digits[i] < alphabet.count { break }
        digits[i] = 0
        i += 1
      }
      if i == length { break }
    }
  }
  print(count, String(format: "%016llx", digest.value))
} else {
  while let line = readLine() {
    switch run(fromHex(Substring(line))) {
    case .success(let data):
      print("ok " + data.map { String(format: "%02x", $0) }.joined())
    case .failure(let failure):
      print("error " + failure.message)
    }
  }
}
