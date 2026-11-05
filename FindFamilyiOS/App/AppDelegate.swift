import SwiftUI
import UIKit
import CoreLocation
import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate {
    var isLaunchedFromLocationEvent = false

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        isLaunchedFromLocationEvent = launchOptions?[.location] != nil
        UIDevice.current.isBatteryMonitoringEnabled = true

        // Request notification permission early (best-effort).
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }

        Task { @MainActor in
            // Kick off the network / identity layer.
            await Networking.shared.start()
            // Subscribe to sharing-state changes first so a toggle that fires
            // before the DB finishes loading is still picked up, then start
            // location work only when we're actually sharing with someone —
            // this is the iOS analog of LocationTrackingService on Android.
            // The same gate runs on background relaunch from location events.
            LocationsHandler.shared.beginMonitoringSharing()
            LocationsHandler.shared.startLocationUpdatesIfSharing()
        }
        return true
    }
}
