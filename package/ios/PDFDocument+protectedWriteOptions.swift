import Foundation
import PDFKit

extension PDFDocument {
  /// Options that keep the protection of the file this was opened from:
  /// `unlockPassword` opens the copy, with the same permissions; an unknown
  /// owner password is replaced by one nobody knows. Without options PDFKit
  /// decrypts some files and writes others that no password opens.
  func protectedWriteOptions(unlockPassword: String?) -> [PDFDocumentWriteOption: Any] {
    guard isEncrypted else { return [:] }
    let ownerPassword = permissionsStatus == .owner ? unlockPassword : nil
    var options: [PDFDocumentWriteOption: Any] = [
      .ownerPasswordOption: ownerPassword ?? UUID().uuidString,
      .accessPermissionsOption: accessPermissions.rawValue,
    ]
    if let unlockPassword { options[.userPasswordOption] = unlockPassword }
    return options
  }
}
