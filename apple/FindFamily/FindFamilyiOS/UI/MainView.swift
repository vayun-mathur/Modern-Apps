import SwiftUI
import MapKit
import Combine

/// Top-level screen. Mirrors Modern-Apps `MainPage.kt`: a top bar, a map, a bottom panel,
/// and a floating add menu. Selection state (user or waypoint) drives the panels.
@MainActor
final class MainViewModel: ObservableObject {
    @Published var users: [User] = []
    @Published var waypoints: [Waypoint] = []
    @Published var temporaryLinks: [TemporaryLink] = []
    @Published var latestLocations: [Int64: LocationValue] = [:]

    private var bag = Set<AnyCancellable>()

    init() {
        Database.shared.usersSubject
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.users = $0 }
            .store(in: &bag)
        Database.shared.waypointsSubject
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.waypoints = $0 }
            .store(in: &bag)
        Database.shared.temporaryLinksSubject
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.temporaryLinks = $0 }
            .store(in: &bag)
        Database.shared.latestLocationsSubject
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.latestLocations = $0 }
            .store(in: &bag)
    }
}

struct MainView: View {
    @StateObject private var vm = MainViewModel()
    @ObservedObject private var deepLink = DeepLinkRouter.shared

    // Selection state
    @State private var selectedUserId: Int64?
    @State private var selectedWaypointId: Int64?  // 0 means a new waypoint being created
    @State private var isShowingPresent = true
    @State private var historicalPosition: Coord?

    // Waypoint editor state
    @State private var waypointName = ""
    @State private var waypointRange = "100"
    @State private var waypointCoord = Coord.zero

    // Dialogs
    @State private var showAddPersonDialog = false
    @State private var addPersonId: Int64?
    /// Fingerprint from the invite link that set `addPersonId`, when it came from one.
    @State private var addPersonFingerprint: String?
    @State private var showAddLinkDialog = false
    @State private var showMissingFeaturesDialog = false
    @State private var showSecurityCode = false

    @State private var mapCenter = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
        latitudinalMeters: 4000, longitudinalMeters: 4000
    )
    @State private var animateToCoord: Coord?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Map fills remaining space, with FAB and HistoryBar overlaid.
                ZStack(alignment: .bottomTrailing) {
                    MapContainer(
                        users: vm.users,
                        latestLocations: vm.latestLocations,
                        waypoints: vm.waypoints,
                        selectedUserId: selectedUserId,
                        selectedWaypoint: selectedWaypointPreview,
                        historicalPosition: isShowingPresent ? nil : historicalPosition,
                        onUserTap: { id in
                            selectedUserId = id
                            selectedWaypointId = nil
                            isShowingPresent = true
                        },
                        onMapTap: {
                            if selectedWaypointId == 0 {
                                // Don't deselect during waypoint creation.
                            } else {
                                selectedUserId = nil
                                selectedWaypointId = nil
                            }
                        },
                        onCenterChanged: { coord in
                            if selectedWaypointId == 0 {
                                waypointCoord = coord
                            }
                        },
                        animateToCoord: animateToCoord
                    )
                    if selectedUserId != nil {
                        HistoryBar(
                            userid: selectedUserId!,
                            isShowingPresent: $isShowingPresent,
                            historicalPosition: $historicalPosition
                        )
                        .frame(width: 105, height: 360, alignment: .bottomTrailing)
                        .padding(8)
                    }
                    addFAB
                        .padding(.trailing, 16)
                        .padding(.bottom, 16)
                }
                bottomPanel
            }
            .navigationTitle(Text(headerTitle))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { topBarToolbar }
            .sheet(isPresented: $showAddPersonDialog, onDismiss: {
                addPersonId = nil
                addPersonFingerprint = nil
            }) {
                AddPersonDialog(
                    presetID: addPersonId,
                    fingerprint: addPersonFingerprint,
                    isPresented: $showAddPersonDialog
                )
            }
            .sheet(isPresented: $showAddLinkDialog) {
                AddLinkDialog(isPresented: $showAddLinkDialog)
            }
            .sheet(isPresented: $showSecurityCode) {
                if let uid = selectedUserId, let user = vm.users.first(where: { $0.id == uid }) {
                    SecurityCodeView(user: user, isPresented: $showSecurityCode)
                }
            }
            .alert(Strings.missingFeaturesTitle, isPresented: $showMissingFeaturesDialog) {
                Button(Strings.okButton, role: .cancel) {}
            } message: { Text(Strings.missingFeaturesExplanation) }
            .navigationDestination(for: User.self) { peer in
                RangingView(peer: peer)
            }
        }
        .onChange(of: selectedUserId) { _, new in
            if let id = new, let coord = vm.latestLocations[id]?.coord {
                animateToCoord = coord
            }
        }
        .onChange(of: historicalPosition) { _, new in
            if !isShowingPresent, let coord = new { animateToCoord = coord }
        }
        .onChange(of: selectedWaypointId) { _, new in
            if let id = new, id != 0,
               let wp = vm.waypoints.first(where: { $0.id == id }) {
                animateToCoord = wp.coord
            }
        }
        // A tapped findfamily://add/<id> invite prefills the Add Person dialog. @Published
        // replays its current value on subscribe, so this catches cold-start links too.
        .onReceive(deepLink.$pendingAddInvite.compactMap { $0 }) { invite in
            addPersonId = invite.id
            addPersonFingerprint = invite.fingerprint
            showAddPersonDialog = true
            deepLink.pendingAddInvite = nil
        }
    }

    // MARK: - Top bar

    @ToolbarContentBuilder
    private var topBarToolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            if selectedUserId != nil || selectedWaypointId != nil {
                Button {
                    selectedUserId = nil
                    selectedWaypointId = nil
                } label: { Image(systemName: "chevron.backward") }
            }
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            if selectedUserId == nil && (selectedWaypointId == nil || selectedWaypointId == 0) {
                BackupButtons()
            } else if let uid = selectedUserId, uid != Networking.shared.userid,
                      let user = vm.users.first(where: { $0.id == uid }) {
                Button {
                    showSecurityCode = true
                } label: { Image(systemName: "checkmark.shield") }
                Button(role: .destructive) {
                    Database.shared.deleteUser(user)
                    selectedUserId = nil
                } label: { Image(systemName: "trash") }
            } else if let wid = selectedWaypointId, wid != 0,
                      let wp = vm.waypoints.first(where: { $0.id == wid }) {
                Button(role: .destructive) {
                    Database.shared.deleteWaypoint(wp)
                    selectedWaypointId = nil
                } label: { Image(systemName: "trash") }
            }
        }
    }

    private var headerTitle: String {
        if selectedUserId == nil && selectedWaypointId == nil { return Strings.appName }
        return ""
    }

    // MARK: - FAB

    @ViewBuilder
    private var addFAB: some View {
        if selectedUserId == nil && selectedWaypointId == nil {
            AddMenu(
                onAddPerson: {
                    addPersonId = nil
                    addPersonFingerprint = nil
                    showAddPersonDialog = true
                },
                onAddWaypoint: {
                    selectedWaypointId = 0
                    waypointName = ""
                    waypointRange = "100"
                    waypointCoord = Coord(
                        lat: mapCenter.center.latitude,
                        lon: mapCenter.center.longitude
                    )
                },
                onAddLink: {
                    showAddLinkDialog = true
                }
            )
        } else if let wid = selectedWaypointId {
            Button {
                guard let rng = Double(waypointRange), !waypointName.isBlank else { return }
                let existing = vm.waypoints.first(where: { $0.id == wid }) ?? Waypoint.newWaypoint
                var w = existing
                w.id = wid
                w.name = waypointName
                w.range = rng
                w.coord = waypointCoord
                _ = Database.shared.upsertWaypoint(w)
                selectedWaypointId = nil
            } label: {
                Image(systemName: "checkmark")
                    .font(.title2)
                    .frame(width: 56, height: 56)
                    .background(Color.accentColor)
                    .foregroundStyle(.white)
                    .clipShape(Circle())
                    .shadow(radius: 4)
            }
        }
    }

    // MARK: - Bottom panel

    /// Maximum height the bottom panel will grow to before scrolling.
    private let bottomPanelMaxHeight: CGFloat = 360

    @ViewBuilder
    private var bottomPanel: some View {
        Group {
            if let uid = selectedUserId, let user = vm.users.first(where: { $0.id == uid }) {
                UserDetailPanel(
                    user: user,
                    latest: vm.latestLocations[uid],
                    onChangeContact: { name, photo in
                        var u = user
                        u.name = name
                        u.photo = photo
                        Database.shared.upsertUser(u)
                    },
                    onToggleSending: { send in
                        // Manual toggle clears any pending auto-toggle (atomic, no clobber).
                        Database.shared.setSendingEnabledAndClearToggle(id: user.id, enabled: send)
                    },
                    onSetAutoToggle: { seconds in
                        let at = seconds.map { Date().addingTimeInterval($0) }
                        Database.shared.setSharingAutoToggleAt(id: user.id, at: at)
                    }
                )
                .padding(.vertical, 12)
                .fixedSize(horizontal: false, vertical: true)
            } else if selectedWaypointId != nil {
                WaypointEditorPanel(name: $waypointName, range: $waypointRange)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ScrollView {
                    DefaultList(
                        users: vm.users,
                        latestLocations: vm.latestLocations,
                        temporaryLinks: vm.temporaryLinks,
                        waypoints: vm.waypoints,
                        onSelectUser: { id in
                            selectedUserId = id
                            isShowingPresent = true
                        },
                        onSelectWaypoint: { wp in
                            selectedWaypointId = wp.id
                            waypointName = wp.name
                            waypointRange = String(Int(wp.range))
                            waypointCoord = wp.coord
                        },
                        onTapRequest: { id in
                            // An inbound sharing request, not an invite link, so there is
                            // no fingerprint to check against.
                            addPersonId = id
                            addPersonFingerprint = nil
                            showAddPersonDialog = true
                        }
                    )
                }
                .frame(maxHeight: bottomPanelMaxHeight)
            }
        }
        .background(Color(.systemBackground))
    }

    private var selectedWaypointPreview: (coord: Coord, range: Double)? {
        guard let wid = selectedWaypointId else { return nil }
        let range = Double(waypointRange) ?? 0
        if wid == 0 { return (waypointCoord, range) }
        if let wp = vm.waypoints.first(where: { $0.id == wid }) {
            return (waypointCoord == .zero ? wp.coord : waypointCoord, range)
        }
        return nil
    }
}

private extension String {
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
