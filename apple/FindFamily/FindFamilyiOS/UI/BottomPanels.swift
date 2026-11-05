import SwiftUI

// MARK: - Default list

struct DefaultList: View {
    let users: [User]
    let latestLocations: [Int64: LocationValue]
    let temporaryLinks: [TemporaryLink]
    let waypoints: [Waypoint]
    let onSelectUser: (Int64) -> Void
    let onSelectWaypoint: (Waypoint) -> Void
    let onTapRequest: (Int64) -> Void

    var body: some View {
        VStack(spacing: 8) {
            ForEach(users.filter { $0.requestStatus == .mutualConnection || $0.requestStatus == .awaitingResponse }) { u in
                UserCard(user: u, latest: latestLocations[u.id], showSupporting: true) {
                    onSelectUser(u.id)
                }
            }
            let requests = users.filter { $0.requestStatus == .awaitingRequest }
            if !requests.isEmpty {
                Text(Strings.sectionLocationSharingRequests)
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 8)
            }
            ForEach(requests) { u in
                AwaitingRequestCard(id: u.id) { onTapRequest(u.id) }
            }
            if !temporaryLinks.isEmpty {
                Text(Strings.sectionTemporaryLinks)
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 8)
            }
            ForEach(temporaryLinks) { link in
                TemporaryLinkCard(link: link)
            }
            if !waypoints.isEmpty {
                Text(Strings.sectionSavedPlaces)
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 8)
            }
            ForEach(waypoints) { wp in
                WaypointCard(waypoint: wp, users: users) { onSelectWaypoint(wp) }
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 12)
    }
}

// MARK: - User detail panel

struct UserDetailPanel: View {
    let user: User
    let latest: LocationValue?
    let onChangeContact: (String, String?) -> Void
    let onToggleSending: (Bool) -> Void
    let onSetAutoToggle: (TimeInterval?) -> Void

    var body: some View {
        VStack(spacing: 12) {
            UserCard(user: user, latest: latest, showSupporting: true) {}
            VStack(spacing: 8) {
                HStack {
                    Text(Strings.shareYourLocation)
                    Spacer()
                    Toggle("", isOn: Binding(get: { user.sendingEnabled }, set: { onToggleSending($0) }))
                        .labelsHidden()
                }
                AutoToggleRow(user: user, onSet: onSetAutoToggle)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))

            // Precision Finding entry — pushed onto the MainView's NavigationStack.
            NavigationLink(value: user) {
                Label("Find with Precision", systemImage: "location.north.line.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)

            Button(Strings.changeConnectedContact) {
                Platform.presentContactPicker { name, photo in
                    onChangeContact(name, photo)
                }
            }
            .buttonStyle(.bordered)
        }
        .padding(.horizontal)
    }
}

// MARK: - Auto-toggle row ("Disable/Enable after" timer with live countdown)

/// Mirrors Android `AutoToggleRow`: a menu of durations that schedules an auto-flip of
/// `sendingEnabled`, plus a live 1-second countdown. "Never" cancels a pending timer.
struct AutoToggleRow: View {
    let user: User
    let onSet: (TimeInterval?) -> Void

    @State private var now = Date()

    private static let options: [(label: String, seconds: TimeInterval)] = [
        (Strings.duration15Minutes, 15 * 60),
        (Strings.duration30Minutes, 30 * 60),
        (Strings.duration1Hour, 60 * 60),
        (Strings.duration2Hours, 2 * 60 * 60),
        (Strings.duration4Hours, 4 * 60 * 60),
        (Strings.duration6Hours, 6 * 60 * 60),
        (Strings.duration12Hours, 12 * 60 * 60),
        (Strings.duration1Day, 24 * 60 * 60),
        (Strings.duration2Days, 2 * 24 * 60 * 60),
        (Strings.duration1Week, 7 * 24 * 60 * 60),
    ]

    /// Ticks once per second while a timer is pending so the countdown stays live.
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var currentLabel: String {
        guard let endAt = user.sharingAutoToggleAt else { return Strings.autoToggleNever }
        let remaining = endAt.timeIntervalSince(now)
        if remaining <= 0 { return Strings.autoToggleNever }
        return Self.formatCountdown(remaining)
    }

    private var leadingLabel: String {
        user.sendingEnabled ? Strings.disableAfter : Strings.enableAfter
    }

    var body: some View {
        HStack {
            Text(leadingLabel)
                .font(.subheadline)
            Spacer()
            Menu {
                Button(Strings.autoToggleNever) { onSet(nil) }
                ForEach(Self.options, id: \.seconds) { opt in
                    Button(opt.label) { onSet(opt.seconds) }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(currentLabel).font(.subheadline.monospacedDigit())
                    Image(systemName: "chevron.up.chevron.down").font(.caption2)
                }
                .foregroundStyle(Color.accentColor)
            }
        }
        .onReceive(ticker) { t in
            // Only advance the clock while a timer is active to avoid needless redraws.
            if user.sharingAutoToggleAt != nil { now = t }
        }
    }

    /// `H:MM:SS` when ≥1 hour remains, else `MM:SS`. Matches Android's formatter.
    static func formatCountdown(_ remaining: TimeInterval) -> String {
        let total = max(0, Int(remaining))
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}

// MARK: - Waypoint editor panel

struct WaypointEditorPanel: View {
    @Binding var name: String
    @Binding var range: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField(Strings.addLinkLabelField, text: $name)
                .textFieldStyle(.roundedBorder)
            if name.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(Strings.waypointNameBlankError).font(.caption).foregroundStyle(.red)
            }

            HStack {
                TextField(Strings.waypointRangeSuffix, text: $range)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)
                Text(Strings.waypointRangeSuffix).foregroundStyle(.secondary)
            }
            if Double(range) == nil {
                Text(Strings.waypointRangeError).font(.caption).foregroundStyle(.red)
            }
        }
        .padding()
    }
}

// MARK: - Add menu (FAB)

struct AddMenu: View {
    let onAddPerson: () -> Void
    let onAddWaypoint: () -> Void
    let onAddLink: () -> Void

    @State private var expanded = false

    var body: some View {
        VStack(spacing: 12) {
            if expanded {
                MenuButton(icon: "person.fill", text: Strings.fabPerson) {
                    expanded = false
                    onAddPerson()
                }
                MenuButton(icon: "mappin.and.ellipse", text: Strings.fabLocation) {
                    expanded = false
                    onAddWaypoint()
                }
                MenuButton(icon: "link", text: Strings.fabLink) {
                    expanded = false
                    onAddLink()
                }
            }
            Button { withAnimation { expanded.toggle() } } label: {
                Image(systemName: expanded ? "xmark" : "plus")
                    .font(.title2)
                    .frame(width: 56, height: 56)
                    .background(Color.accentColor)
                    .foregroundStyle(.white)
                    .clipShape(Circle())
                    .shadow(radius: 4)
            }
        }
    }
}

private struct MenuButton: View {
    let icon: String
    let text: String
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            HStack {
                Text(text)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(Capsule())
                Image(systemName: icon)
                    .frame(width: 44, height: 44)
                    .background(Color.accentColor)
                    .foregroundStyle(.white)
                    .clipShape(Circle())
                    .shadow(radius: 2)
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Backup buttons

struct BackupButtons: View {
    @State private var exportURL: URL?
    @State private var showExport = false
    @State private var showImport = false

    var body: some View {
        HStack {
            Button {
                if let u = BackupManager.export() {
                    exportURL = u
                    showExport = true
                }
            } label: { Image(systemName: "square.and.arrow.up") }

            Button {
                showImport = true
            } label: { Image(systemName: "square.and.arrow.down") }
        }
        .sheet(isPresented: $showExport) {
            if let u = exportURL {
                ExportDocumentPicker(url: u) { showExport = false }
            }
        }
        .sheet(isPresented: $showImport) {
            ImportDocumentPicker { url in
                BackupManager.import(from: url)
                showImport = false
            }
        }
    }
}
