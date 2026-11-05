import Foundation
import UIKit
import SwiftUI
import UniformTypeIdentifiers

/// Exports a JSON snapshot of the DB (users, waypoints, locationValues, temporaryLinks)
/// and re-imports it. Keeps things simple — no zip dependency, just one JSON file.
enum BackupManager {
    struct Snapshot: Codable {
        var users: [User]
        var waypoints: [Waypoint]
        var locationValues: [LocationValue]
        var temporaryLinks: [TemporaryLink]
        var userid: Int64
    }

    @MainActor
    static func export() -> URL? {
        let snap = Snapshot(
            users: Database.shared.usersSubject.value,
            waypoints: Database.shared.waypointsSubject.value,
            locationValues: Database.shared.locationValuesSubject.value,
            temporaryLinks: Database.shared.temporaryLinksSubject.value,
            userid: Networking.shared.userid
        )
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            encoder.dateEncodingStrategy = .secondsSince1970
            let data = try encoder.encode(snap)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("findfamily-backup-\(Int(Date().timeIntervalSince1970)).json")
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            print("Backup export error: \(error)")
            return nil
        }
    }

    static func `import`(from url: URL) {
        do {
            let needsRelease = url.startAccessingSecurityScopedResource()
            defer { if needsRelease { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .secondsSince1970
            let snap = try decoder.decode(Snapshot.self, from: data)
            for u in snap.users { Database.shared.upsertUser(u) }
            for w in snap.waypoints { _ = Database.shared.upsertWaypoint(w) }
            for l in snap.locationValues { Database.shared.insertLocationValue(l) }
            for t in snap.temporaryLinks { _ = Database.shared.upsertTemporaryLink(t) }
        } catch {
            print("Backup import error: \(error)")
        }
    }
}

// MARK: - Document picker wrappers

struct ExportDocumentPicker: UIViewControllerRepresentable {
    let url: URL
    let onDismiss: () -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let vc = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        vc.delegate = context.coordinator
        return vc
    }
    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onDismiss: onDismiss) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onDismiss: () -> Void
        init(onDismiss: @escaping () -> Void) { self.onDismiss = onDismiss }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onDismiss() }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onDismiss() }
    }
}

struct ImportDocumentPicker: UIViewControllerRepresentable {
    let onPicked: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let vc = UIDocumentPickerViewController(forOpeningContentTypes: [.json], asCopy: true)
        vc.delegate = context.coordinator
        return vc
    }
    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPicked: (URL) -> Void
        init(onPicked: @escaping (URL) -> Void) { self.onPicked = onPicked }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if let u = urls.first { onPicked(u) }
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {}
    }
}
