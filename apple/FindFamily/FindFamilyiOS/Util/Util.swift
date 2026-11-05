import Foundation
import UIKit
import UserNotifications
import Contacts
import ContactsUI
import SwiftUI

// MARK: - Strings (English only for v1)

enum Strings {
    static let appName = "FindFamily"
    static let permissionGrantLocation = "Grant Location Permission"
    static let permissionLocationGranted = "Location Permission Granted"
    static let permissionGrantAlways = "Enable Always-On Location"
    static let permissionAlwaysGranted = "Always-On Location Granted"
    static let permissionAlwaysExplanation = "FindFamily needs Always-On Location so it can update your family while you're moving and the app is in the background."

    static let sectionLocationSharingRequests = "Location Sharing Requests"
    static let sectionTemporaryLinks = "Temporary Links"
    static let sectionSavedPlaces = "Saved Places"
    static let fabPerson = "Add Person"
    static let fabLocation = "Add Place"
    static let fabLink = "Add Link"

    static let nobodyHere = "Nobody is currently here"
    static func userIsHere(_ name: String) -> String { "\(name) is currently here" }
    static func usersAreHere(_ names: String) -> String { "\(names) are currently here" }

    static let shareYourLocation = "Share your location"
    static let changeConnectedContact = "Change connected contact"
    static let lastUpdatedNever = "Never updated"
    static let today = "today"
    static let yesterday = "yesterday"

    static let sinceJustNow = "Since just now"
    static func sinceMinutesAgo(_ m: Int64) -> String { "Since \(m) minutes ago" }
    static func sinceTimeDate(_ time: String, _ date: String) -> String { "Since \(time) \(date)" }
    static func userCardStatus(_ updated: String, _ place: String, _ since: String) -> String {
        "\(updated)\nAt \(place)\(since.isEmpty ? "" : "\n" + since)"
    }
    static func batteryPercentage(_ p: Int) -> String { "\(p)%" }

    static let timeJustNow = "just now"
    static func timeMinutesAgo(_ m: Int64) -> String { "\(m) minutes ago" }
    static func timeHoursAgo(_ h: Int64) -> String { "\(h) hours ago" }
    static func timeDaysAgo(_ d: Int64) -> String { "\(d) days ago" }
    static let timeVerySoon = "very soon"
    static func timeInMinutes(_ m: Int64) -> String { "in \(m) minutes" }
    static func timeInHours(_ h: Int64) -> String { "in \(h) hours" }
    static func timeInDays(_ d: Int64) -> String { "in \(d) days" }

    static func expires(_ s: String) -> String { "Expires \(s)" }
    static func requestFrom(_ id: String) -> String { "Request from \(id)" }

    static let historyButton = "History"
    static let hideButton = "Hide"
    static let historyRewindSmall = "<"
    static let historyForwardSmall = ">"
    static let historyRewindMedium = "<<"
    static let historyForwardMedium = ">>"
    static let historyRewindLarge = "<<<"
    static let historyForwardLarge = ">>>"

    static let waypointNameBlankError = "Name is required"
    static let waypointRangeError = "Range must be a number"
    static let waypointRangeSuffix = "meters"

    static let missingFeaturesTitle = "Missing features"
    static let missingFeaturesExplanation = "Your device is missing the geocoder or network location provider. Some features may not work."

    static let addLinkTitle = "Create Temporary Link"
    static let addLinkLabelField = "Link Label (to differentiate links)"
    static let addLinkExpiryField = "Expiry Time"
    static let addLinkSubmit = "Create Link"

    static let addPersonYourID = "Your FindFamily ID"
    static let addPersonTheirID = "Contact's FindFamily ID"
    static let addPersonName = "Contact's Name"
    static let addPersonSubmit = "Request Location"
    static let addPersonAccept = "Accept Location Request"
    static let shareInviteLink = "Share invite link"
    static let addPersonAwaitingRequest = "This person has requested your location"
    static let addPersonSelf = "Cannot share your location with yourself"
    static let addPersonAlreadyMutual = "Already sharing location with this person"
    static let addPersonAlreadyRequested = "Already requested to share with this person"

    static let backupExport = "Export Backup"
    static let backupImport = "Import Backup"

    static let notifSharingRequestTitle = "Location Requested"
    static func notifSharingRequestBody(_ id: String) -> String { "ID \(id) wants to see your location." }
    static let notifBatteryLowTitle = "Battery Low"
    static func notifBatteryLowBody(_ name: String, _ pct: Int) -> String { "\(name)'s battery is at \(pct)%" }
    static let notifWaypointEnterTitle = "Place Update"
    static func notifWaypointEnterBody(_ name: String, _ place: String) -> String { "\(name) is at \(place)" }
    static let notifWaypointExitTitle = "Place Update"
    static func notifWaypointExitBody(_ name: String, _ place: String) -> String { "\(name) left \(place)" }

    static let okButton = "OK"
    static let cancelButton = "Cancel"
    static let doneButton = "Done"

    // Auto-toggle sharing ("Disable/Enable after" timer)
    static let autoToggleNever = "Never"
    static let disableAfter = "Disable after"
    static let enableAfter = "Enable after"

    // Duration options (shared with temporary-link expiry)
    static let duration15Minutes = "15 minutes"
    static let duration30Minutes = "30 minutes"
    static let duration1Hour = "1 hour"
    static let duration2Hours = "2 hours"
    static let duration4Hours = "4 hours"
    static let duration6Hours = "6 hours"
    static let duration12Hours = "12 hours"
    static let duration1Day = "1 day"
    static let duration2Days = "2 days"
    static let duration1Week = "1 week"

    // Security code (safety number) verification
    static let securityCodeTitle = "Security Code"
    static func securityCodeCompare(_ name: String) -> String {
        "Compare this code with the one shown on \(name)'s device. If they match, your connection is verified and no one is intercepting it."
    }
    static let securityCodeComputing = "Computing…"
    static func securityCodeUnavailable(_ name: String) -> String {
        "Couldn't compute the code yet — \(name)'s key isn't available. Try again once they've shared their location."
    }

    // Shown when connecting to a peer whose app is too old for post-quantum encryption.
    static let outdatedPeerTitle = "Update needed"
    static let outdatedPeerMessage = "This person is using an older version of FindFamily that doesn't support quantum-safe encryption. Ask them to update their app, then try connecting again."

    // Shown when a peer's key doesn't match the fingerprint their invite link vouched for.
    static let keyMismatchTitle = "Couldn't verify this person"
    static let keyMismatchMessage = "The encryption key for this person doesn't match the one their invite link listed, so they weren't added. Check that you opened the whole link, and ask them to send a new one."
}

// MARK: - Time formatting

enum TimeFormatting {
    static let dayMonth: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d"
        return f
    }()
    static let amPmTimeSeconds: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "h:mm:ss a"
        return f
    }()
    static let amPmTime: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "h:mm a"
        return f
    }()

    static func timestring(_ timestamp: Date, future: Bool) -> String {
        let secs = abs(Int64(Date().timeIntervalSince(timestamp)))
        let mins = secs / 60
        let hours = mins / 60
        let days = hours / 24
        if !future {
            if secs < 60 { return Strings.timeJustNow }
            if mins < 60 { return Strings.timeMinutesAgo(mins) }
            if hours < 24 { return Strings.timeHoursAgo(hours) }
            return Strings.timeDaysAgo(days)
        } else {
            if secs < 60 { return Strings.timeVerySoon }
            if mins < 60 { return Strings.timeInMinutes(mins) }
            if hours < 24 { return Strings.timeInHours(hours) }
            return Strings.timeInDays(days)
        }
    }

    /// Formatted speed in m/s -> mph or km/h (uses imperial when locale is US).
    static func formatSpeed(_ speedMS: Float) -> String {
        let usesImperial = Locale.current.measurementSystem == .us
        if usesImperial {
            let mph = Int((speedMS * 2.23694).rounded())
            return "\(mph) mph"
        } else {
            let kmh = Int((speedMS * 3.6).rounded())
            return "\(kmh) km/h"
        }
    }
}

// MARK: - Notifications

enum NotificationsUtil {
    static func send(title: String, body: String, category: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.categoryIdentifier = category
        content.sound = .default
        let req = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }
}

// MARK: - Deep links

/// A tapped invite link: the sender's id, plus the bundle fingerprint it vouched for.
struct AddInvite: Equatable {
    let id: Int64
    /// The `k=` value, or nil for links from builds before it existed.
    let fingerprint: String?
}

/// Routes incoming `findfamily://add/<base26>[?k=<fingerprint>]` invites to the UI. The
/// link carries the sender's public id and a hash of their public key — never a key and
/// never a secret — so prefilling the Add Person dialog from it is safe; the user still
/// explicitly accepts, and the fingerprint is checked against the relay's key on connect.
final class DeepLinkRouter: ObservableObject {
    static let shared = DeepLinkRouter()
    private init() {}

    /// Decoded invite awaiting presentation, or nil once consumed.
    @Published var pendingAddInvite: AddInvite?

    func handle(_ url: URL) {
        guard url.scheme == "findfamily", url.host == "add" else { return }
        let segment = (url.pathComponents.last { $0 != "/" } ?? "")
            .trimmingCharacters(in: .whitespaces)
            .uppercased()
        guard !segment.isEmpty,
              segment.allSatisfy({ $0.isASCII && $0.isLetter }),
              let id = Base26.decode(segment) else { return }
        pendingAddInvite = AddInvite(id: id, fingerprint: Self.fingerprint(from: url))
    }

    /// The `k` query value, accepted only if it looks like base64url. Anything else is
    /// dropped, which leaves the invite unverified rather than failing the whole link.
    private static func fingerprint(from url: URL) -> String? {
        guard let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "k" })?.value?
            .trimmingCharacters(in: .whitespaces), !value.isEmpty else { return nil }
        let isBase64url = value.allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_")
        }
        return isBase64url ? value : nil
    }
}

// MARK: - Platform (clipboard, share, contact picker)

enum Platform {
    static func copy(_ text: String) {
        UIPasteboard.general.string = text
    }
    static func share(_ text: String) {
        guard let vc = topViewController() else { return }
        let av = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        vc.present(av, animated: true)
    }

    /// Presents `CNContactPickerViewController` imperatively via UIKit on top of any
    /// SwiftUI sheets. Using a SwiftUI `.sheet` here causes the *parent* sheet to be
    /// dismissed when the picker closes; presenting via UIKit avoids that.
    static func presentContactPicker(onPicked: @escaping (String, String?) -> Void) {
        guard let vc = topViewController() else { return }
        let handler = ContactPickerHandler(onPicked: onPicked)
        let picker = CNContactPickerViewController()
        picker.delegate = handler
        // Retain the delegate for the picker's lifetime.
        objc_setAssociatedObject(picker, &Self.handlerKey, handler, .OBJC_ASSOCIATION_RETAIN)
        vc.present(picker, animated: true)
    }

    private static var handlerKey: UInt8 = 0

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes.first as? UIWindowScene
        guard var vc = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
                ?? scene?.windows.first?.rootViewController
        else { return nil }
        while let presented = vc.presentedViewController { vc = presented }
        return vc
    }
}

private final class ContactPickerHandler: NSObject, CNContactPickerDelegate {
    let onPicked: (String, String?) -> Void
    init(onPicked: @escaping (String, String?) -> Void) { self.onPicked = onPicked }

    func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
        let name = [contact.givenName, contact.familyName].filter { !$0.isEmpty }.joined(separator: " ")
        var photoB64: String?
        if let data = contact.imageData {
            photoB64 = "data:image/jpeg;base64," + data.base64EncodedString()
        }
        DispatchQueue.main.async {
            self.onPicked(name.isEmpty ? "Unnamed" : name, photoB64)
        }
    }
    func contactPickerDidCancel(_ picker: CNContactPickerViewController) {}
}

// MARK: - Contact picker (legacy SwiftUI sheet wrapper)
//
// Kept for backwards compatibility but not used — prefer `Platform.presentContactPicker`.
struct ContactPicker: UIViewControllerRepresentable {
    let onPicked: (String, String?) -> Void

    func makeUIViewController(context: Context) -> CNContactPickerViewController {
        let vc = CNContactPickerViewController()
        vc.delegate = context.coordinator
        return vc
    }
    func updateUIViewController(_ uiViewController: CNContactPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    final class Coordinator: NSObject, CNContactPickerDelegate {
        let onPicked: (String, String?) -> Void
        init(onPicked: @escaping (String, String?) -> Void) { self.onPicked = onPicked }

        func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
            let name = [contact.givenName, contact.familyName].filter { !$0.isEmpty }.joined(separator: " ")
            var photoB64: String?
            if let data = contact.imageData {
                photoB64 = "data:image/jpeg;base64," + data.base64EncodedString()
            }
            onPicked(name.isEmpty ? "Unnamed" : name, photoB64)
        }
        func contactPickerDidCancel(_ picker: CNContactPickerViewController) {}
    }
}

// MARK: - Permissions controller

import CoreLocation

final class PermissionsController: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var hasForeground = false
    @Published var hasBackground = false

    private let manager: CLLocationManager

    override init() {
        self.manager = CLLocationManager()
        super.init()
        self.manager.delegate = self
        refresh()
    }

    func refresh() {
        let s = manager.authorizationStatus
        hasForeground = (s == .authorizedWhenInUse || s == .authorizedAlways)
        hasBackground = (s == .authorizedAlways)
    }

    func requestForeground() {
        manager.requestWhenInUseAuthorization()
    }

    func requestBackground() {
        manager.requestAlwaysAuthorization()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in self.refresh() }
    }
}
