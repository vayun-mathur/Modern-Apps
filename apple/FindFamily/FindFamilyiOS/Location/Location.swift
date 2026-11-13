import Foundation
import Combine
import CoreLocation
import UIKit
import UserNotifications
import OSLog

let logger = Logger(subsystem: "cc.findfamily.ios.swift", category: "Location")

// MARK: - LocationsHandler (CoreLocation pipeline)

/// Mirrors the `LocationsHandler` in the standalone Find-Family iOS app: uses iOS 17+
/// `CLLocationUpdate.liveUpdates()` driven by a `CLBackgroundActivitySession` plus a
/// `CLServiceSession(.always)` so updates keep flowing in the background.
@MainActor
final class LocationsHandler: ObservableObject {
    static let shared = LocationsHandler()

    private let manager = CLLocationManager()
    private var background: CLBackgroundActivitySession?
    private var session: CLServiceSession?
    private var task: Task<Void, Never>?
    private var sharingWatchers = Set<AnyCancellable>()

    private init() {
        manager.allowsBackgroundLocationUpdates = true
    }

    func authorize() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse:
            manager.requestAlwaysAuthorization()
        default:
            break
        }
    }

    func startLocationUpdates() {
        authorize()
        manager.allowsBackgroundLocationUpdates = true
        guard task == nil else { return }
        logger.info("Starting location updates")
        task = Task { [weak self] in
            do {
                self?.background = CLBackgroundActivitySession()
                self?.session = CLServiceSession(authorization: .always)
                let updates = CLLocationUpdate.liveUpdates()
                for try await update in updates {
                    if let loc = update.location {
                        BackgroundTask.shared.onLocationUpdate(loc, sleep: false)
                    }
                    // Diagnostic flags are logged on the device only (the old /api/problem
                    // report endpoint is gone).
                    if update.authorizationDenied { logger.notice("Location update: Auth denied") }
                    if update.authorizationDeniedGlobally { logger.notice("Location update: Auth denied globally") }
                    if update.authorizationRequestInProgress { logger.notice("Location update: Auth in progress") }
                    if update.authorizationRestricted { logger.notice("Location update: Auth restricted") }
                    if update.insufficientlyInUse { logger.notice("Location update: Insufficient Use") }
                    if update.locationUnavailable { logger.notice("Location update: Location Unavailable") }
                    if update.serviceSessionRequired { logger.notice("Location update: Service Session required") }
                    if update.stationary { logger.notice("Location update: Stationary") }
                }
            } catch {
                logger.error("Could not start location updates: \(error.localizedDescription)")
            }
        }
    }

    func stopLocationUpdates() {
        task?.cancel()
        task = nil
        background?.invalidate()
        background = nil
        session?.invalidate()
        session = nil
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        for region in manager.monitoredRegions {
            manager.stopMonitoring(for: region)
        }
        manager.stopMonitoringVisits()
        manager.allowsBackgroundLocationUpdates = false
        BackgroundTask.shared.stopHeartbeat()
        logger.info("Stopped location updates (not sharing with anyone)")
    }

    /// Whether we currently publish our location to anyone: any peer with
    /// `sendingEnabled`, any pending share auto-toggle timer (which may flip
    /// sharing back on by itself), or any live temporary link. Expired links
    /// are pruned here so they can't keep location alive forever.
    func isSharingWithAnyone() -> Bool {
        pruneExpiredTemporaryLinks()
        let now = Date()
        let users = Database.shared.usersSubject.value
        let me = Networking.shared.userid
        if users.contains(where: { $0.id != me && ($0.sendingEnabled || $0.sharingAutoToggleAt != nil) }) {
            return true
        }
        return Database.shared.temporaryLinksSubject.value.contains(where: { $0.deleteAt > now })
    }

    /// Start location work only when we're actually sharing with someone.
    /// Safe to call on every launch (including background relaunch): re-entrant,
    /// no-ops when updates are already running.
    func startLocationUpdatesIfSharing() {
        if isSharingWithAnyone() {
            BackgroundTask.shared.startHeartbeat()
            startLocationUpdates()
        } else {
            stopLocationUpdates()
        }
    }

    /// Re-evaluate the sharing gate whenever the sharing state changes: the
    /// per-user toggle, auto-toggle timers, and temporary links. Every write
    /// path funnels through these subjects, so this covers manual toggles,
    /// timer flips, link add/delete, and user add/delete.
    func beginMonitoringSharing() {
        guard sharingWatchers.isEmpty else { return }
        // Hop to the main thread like MainViewModel does: Database sends from
        // its serial queue, and a synchronous MainActor hop from there risks
        // deadlock against its queue.sync writes.
        Database.shared.usersSubject
            .dropFirst()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in Task { @MainActor in self?.startLocationUpdatesIfSharing() } }
            .store(in: &sharingWatchers)
        Database.shared.temporaryLinksSubject
            .dropFirst()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in Task { @MainActor in self?.startLocationUpdatesIfSharing() } }
            .store(in: &sharingWatchers)
    }

    /// Delete temporary links past their expiry so an unopened/undeleted link
    /// can't pin background location on indefinitely. (The per-tick cleanup in
    /// `runTick` only runs while sharing is active.)
    private func pruneExpiredTemporaryLinks() {
        let now = Date()
        for link in Database.shared.temporaryLinksSubject.value where link.deleteAt <= now {
            Database.shared.deleteTemporaryLink(link)
        }
    }
}

// MARK: - BackgroundTask (per-tick work)

/// Implements the per-tick logic from Modern-Apps `BackgroundTask.kt`:
/// throttled to 3s, reverse-geocodes, computes waypoint enter/exit, publishes to each
/// `sendingEnabled` user, receives + persists incoming locations, and emits
/// notifications for sharing requests + low-battery + waypoint transitions.
@MainActor
final class BackgroundTask {
    static let shared = BackgroundTask()

    static let shareIntervalSeconds: TimeInterval = 3.0

    private var lastUpdateAt: Date = .distantPast
    private var tickCount = 0
    private var lastBatteryLevels: [Int64: Float] = [:]
    private var isProcessing = false

    /// Last location delivered by CoreLocation. Cached so the timer-driven
    /// heartbeat below can keep publishing even when CLLocationUpdate goes quiet
    /// (e.g. the phone is sitting on a desk).
    private var lastKnownLocation: CLLocation?

    /// Background timer that keeps `runTick` firing every `shareIntervalSeconds`
    /// regardless of whether CoreLocation has delivered a new fix. This mirrors
    /// Modern-Apps Android `syncHeartbeat` which runs on a 30 s loop independent
    /// of location callbacks — without it, a stationary iPhone never calls
    /// `/api/location/receive` and so never sees peer locations.
    private var heartbeatTask: Task<Void, Never>?

    private let geocoder = CLGeocoder()

    private init() {}

    /// Idempotent: safe to call on every launch and every sharing-state change.
    func startHeartbeat() {
        guard heartbeatTask == nil else { return }
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                if let loc = self?.lastKnownLocation {
                    self?.onLocationUpdate(loc, sleep: false)
                }
                try? await Task.sleep(nanoseconds: UInt64(Self.shareIntervalSeconds * 1_000_000_000))
            }
        }
    }

    func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
        lastKnownLocation = nil
    }

    func onLocationUpdate(_ loc: CLLocation, sleep: Bool) {
        lastKnownLocation = loc
        let now = Date()
        if !sleep && now.timeIntervalSince(lastUpdateAt) < Self.shareIntervalSeconds { return }
        lastUpdateAt = now
        if isProcessing { return }
        isProcessing = true
        Task { [weak self] in
            await self?.runTick(loc: loc, sleep: sleep)
            self?.isProcessing = false
        }
    }

    private func runTick(loc: CLLocation, sleep: Bool) async {
        guard Networking.shared.isReady else { return }
        let battery = max(0, UIDevice.current.batteryLevel) * 100
        let coord = Coord(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)
        let me = LocationValue(
            id: Int64.random(in: Int64.min...Int64.max),
            userid: Networking.shared.userid,
            coord: coord,
            speed: Float(max(0, loc.speed)),
            acc: Float(max(0, loc.horizontalAccuracy)),
            timestamp: Date(),
            battery: battery
        )

        // Periodically (re)register so the server has our current key.
        tickCount += 1
        if tickCount % 100 == 1 { _ = await Networking.shared.ensureUserExists() }

        // 1 + 2. Persist our own fix, then compute our current waypoint (with 1.2× exit
        // hysteresis) and a display name. Uses the atomic partial update so timers/flags
        // set elsewhere (sharingAutoToggleAt / sendingEnabled) aren't clobbered.
        Database.shared.insertLocationValue(me)
        if let meUser = Database.shared.user(id: Networking.shared.userid) {
            let (currentId, resolvedName) = await resolveWaypoint(coord: coord, prevId: meUser.lastWaypointId)
            let displayName = resolvedName ?? (meUser.locationName.isEmpty ? "Unnamed Location" : meUser.locationName)
            if currentId != meUser.lastWaypointId || displayName != meUser.locationName {
                Database.shared.updateLocationMeta(
                    id: meUser.id,
                    locationName: displayName,
                    lastWaypointId: currentId,
                    lastLocationChangeTime: Date()
                )
            }
        }

        // 3. Delete expired temporary links.
        let now = Date()
        for link in Database.shared.temporaryLinksSubject.value where link.deleteAt < now {
            Database.shared.deleteTemporaryLink(link)
        }

        // 4. Apply any due auto-toggle timers, then publish. Mirrors Android: flip
        // `sendingEnabled` atomically for due timers, then publish only to peers with
        // sharing enabled (`id != me && sendingEnabled`) so the "Share your location"
        // toggle — and the auto-toggle countdown — are actually honored.
        Database.shared.applyDueAutoToggles(now: now)
        let users = Database.shared.usersSubject.value
        for u in users where u.id != Networking.shared.userid && u.sendingEnabled {
            _ = await Networking.shared.publishLocation(me, toUser: u)
        }
        for link in Database.shared.temporaryLinksSubject.value {
            _ = await Networking.shared.publishLocation(me, toLink: link)
        }

        // 5. Receive incoming peer locations. Any sender we don't already know about
        // is added as AWAITING_REQUEST (Modern-Apps uses received locations — not a
        // separate `/api/request_sharing` endpoint — to discover incoming requests).
        if let incoming = await Networking.shared.receiveLocations() {
            let knownIds = Set(users.map { $0.id } + [Networking.shared.userid])
            let newSenderIds = Set(incoming.map { $0.userid }).subtracting(knownIds)
            for id in newSenderIds {
                let user = User(
                    id: id, name: "ID \(Base26.encode(id))", photo: nil,
                    locationName: "Unknown Location", sendingEnabled: true,
                    requestStatus: .awaitingRequest, lastLocationChangeTime: Date(),
                    encryptionKey: nil
                )
                Database.shared.upsertUser(user)
                NotificationsUtil.send(
                    title: Strings.notifSharingRequestTitle,
                    body: Strings.notifSharingRequestBody(Base26.encode(id)),
                    category: "SHARING_REQUEST"
                )
            }
            for loc in incoming {
                Database.shared.insertLocationValue(loc)
                await updateUserLocationName(loc)
                await maybeNotifyBatteryDrop(loc)
            }
        }

        // 6. Trim old locations (>7 days).
        let cutoff = Date().addingTimeInterval(-7 * 24 * 60 * 60)
        Database.shared.deleteLocationValuesOlderThan(cutoff)

        // 7. Drain the UWB session-setup channel. Each envelope is published
        //    onto UwbInbox so an open Precision Finding view can react; if the
        //    envelope is a REQUEST and no view is open, post a notification.
        if let envelopes = await Networking.shared.receiveUwbMessages() {
            for env in envelopes {
                UwbInbox.shared.emit(env)
                if env.kind == UwbEnvelopeKind.request {
                    let senderId = Int64(bitPattern: env.sender)
                    let senderName = Database.shared.user(id: senderId)?.name ?? "Someone"
                    NotificationsUtil.send(
                        title: "Precision Finding request",
                        body: "\(senderName) wants to find you with Precision Finding. Tap to begin.",
                        category: "UWB_REQUEST"
                    )
                }
            }
        }
    }

    /// Computes the current waypoint id for a coordinate with 1.2× exit hysteresis and a
    /// display name, mirroring Android `syncHeartbeat`. To *enter* a waypoint you must be
    /// within its radius; to *remain* in the previously-entered waypoint you only need to be
    /// within 1.2× the radius (prevents flapping at the boundary). The returned name prefers
    /// the entered/sticky waypoint name, then a reverse-geocoded address, else nil (unresolved).
    private func resolveWaypoint(coord: Coord, prevId: Int64?) async -> (currentId: Int64?, name: String?) {
        let waypoints = Database.shared.waypointsSubject.value
        let inWaypoint = waypoints.first { haversine(coord, $0.coord) < $0.range }
        let stillInsidePrev: Bool = {
            guard let pid = prevId, let wp = waypoints.first(where: { $0.id == pid }) else { return false }
            return haversine(coord, wp.coord) < wp.range * 1.2
        }()
        let currentId: Int64? = inWaypoint?.id ?? (stillInsidePrev ? prevId : nil)
        if let name = inWaypoint?.name { return (currentId, name) }
        if let cid = currentId, let wp = waypoints.first(where: { $0.id == cid }) {
            return (currentId, wp.name)
        }
        if let placemarks = try? await geocoder.reverseGeocodeLocation(
            CLLocation(latitude: coord.lat, longitude: coord.lon)
        ), let p = placemarks.first {
            return (currentId, [p.name, p.locality].compactMap { $0 }.first)
        }
        return (currentId, nil)
    }

    private func updateUserLocationName(_ loc: LocationValue) async {
        guard let user = Database.shared.user(id: loc.userid) else { return }
        // Recompute waypoint (with hysteresis) + display name on every incoming fix. Prefer a
        // matching waypoint, otherwise reverse-geocode. Keep the existing name if we can't
        // resolve a fresh one rather than clobbering it.
        let prevId = user.lastWaypointId
        let (currentId, resolvedName) = await resolveWaypoint(coord: loc.coord, prevId: prevId)
        let resolved = resolvedName ?? user.locationName
        if currentId != prevId || resolved != user.locationName {
            // Atomic partial update — avoids a stale snapshot clobbering
            // sharingAutoToggleAt / sendingEnabled.
            Database.shared.updateLocationMeta(
                id: user.id,
                locationName: resolved,
                lastWaypointId: currentId,
                lastLocationChangeTime: Date()
            )
        }
        // Enter/exit notifications fire only on an actual waypoint transition.
        guard currentId != prevId else { return }
        if currentId != nil {
            let enteredName = Database.shared.waypointsSubject.value.first { $0.id == currentId }?.name ?? resolved
            NotificationsUtil.send(
                title: Strings.notifWaypointEnterTitle,
                body: Strings.notifWaypointEnterBody(user.name, enteredName),
                category: "WAYPOINT_ENTER_EXIT"
            )
        } else if let pid = prevId {
            let exitedName = Database.shared.waypointsSubject.value.first { $0.id == pid }?.name ?? user.locationName
            NotificationsUtil.send(
                title: Strings.notifWaypointExitTitle,
                body: Strings.notifWaypointExitBody(user.name, exitedName),
                category: "WAYPOINT_ENTER_EXIT"
            )
        }
    }

    private func maybeNotifyBatteryDrop(_ loc: LocationValue) async {
        let prev = lastBatteryLevels[loc.userid]
        lastBatteryLevels[loc.userid] = loc.battery
        guard let prev = prev else { return }
        // Edge-triggered at 15% (matches Android): one alert as a peer crosses the threshold.
        if prev > 15 && loc.battery <= 15 {
            if let user = Database.shared.user(id: loc.userid) {
                NotificationsUtil.send(
                    title: Strings.notifBatteryLowTitle,
                    body: Strings.notifBatteryLowBody(user.name, Int(loc.battery)),
                    category: "BATTERY_LOW"
                )
            }
        }
    }
}
