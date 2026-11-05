import SwiftUI

// MARK: - User picture

struct UserPicture: View {
    let user: User
    let size: CGFloat

    var body: some View {
        ZStack {
            if let photo = user.photo,
               let data = photo.imageDataFromB64DataURI(),
               let ui = UIImage(data: data) {
                Image(uiImage: ui)
                    .resizable()
                    .scaledToFill()
            } else {
                Circle().fill(Color(.systemTeal))
                Text(String(user.name.first ?? "?"))
                    .font(.system(size: size * 0.45, weight: .bold))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay(Circle().stroke(Color.accentColor, lineWidth: 2))
    }
}

private extension String {
    func imageDataFromB64DataURI() -> Data? {
        guard let range = self.range(of: ";base64,") else { return nil }
        let b64 = String(self[range.upperBound...])
        return Data(base64Encoded: b64)
    }
}

// MARK: - Battery bar

struct BatteryBar: View {
    let percent: Float
    let width: CGFloat = 30
    let height: CGFloat = 15

    var fillColor: Color {
        if percent > 50 { return .green }
        if percent > 20 { return .yellow }
        return .red
    }

    var body: some View {
        HStack(spacing: 4) {
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 4)
                    .stroke(Color.accentColor, lineWidth: 2)
                    .frame(width: width, height: height)
                RoundedRectangle(cornerRadius: 3)
                    .fill(fillColor)
                    .frame(width: max(0, width * CGFloat(percent / 100)), height: height)
            }
            Text(Strings.batteryPercentage(Int(percent)))
                .font(.caption2)
        }
    }
}

// MARK: - User card

struct UserCard: View {
    let user: User
    let latest: LocationValue?
    let showSupporting: Bool
    let onTap: () -> Void

    var lastUpdatedString: String {
        if let ts = latest?.timestamp { return TimeFormatting.timestring(ts, future: false) }
        return Strings.lastUpdatedNever
    }
    var sinceString: String {
        if user.locationName == "Unnamed Location" { return "" }
        let since = Date().timeIntervalSince(user.lastLocationChangeTime)
        if since < 60 { return Strings.sinceJustNow }
        if since < 900 { return Strings.sinceMinutesAgo(Int64(since) / 60) }
        let dayDiff = Calendar.current.dateComponents([.day], from: user.lastLocationChangeTime, to: Date()).day ?? 0
        let datePart: String
        switch dayDiff {
        case 0: datePart = Strings.today
        case 1: datePart = Strings.yesterday
        default: datePart = TimeFormatting.dayMonth.string(from: user.lastLocationChangeTime)
        }
        return Strings.sinceTimeDate(TimeFormatting.amPmTime.string(from: user.lastLocationChangeTime), datePart)
    }
    var speedString: String {
        TimeFormatting.formatSpeed(latest?.speed ?? 0)
    }

    var body: some View {
        Button(action: showSupporting ? onTap : {}) {
            HStack(alignment: .top, spacing: 12) {
                VStack(spacing: 4) {
                    UserPicture(user: user, size: 56)
                    if let b = latest?.battery {
                        BatteryBar(percent: b)
                    }
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(user.name).font(.headline).lineLimit(1)
                    if showSupporting {
                        Text(Strings.userCardStatus(lastUpdatedString, user.locationName, sinceString))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if showSupporting {
                    Text(speedString).font(.caption2).foregroundStyle(.secondary)
                }
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
        }
        .buttonStyle(.plain)
        .disabled(!showSupporting)
    }
}

// MARK: - Awaiting request card

struct AwaitingRequestCard: View {
    let id: Int64
    let onTap: () -> Void
    var body: some View {
        HStack {
            Text(Strings.requestFrom(Base26.encode(id)))
            Spacer()
            Button(action: onTap) { Image(systemName: "plus.circle.fill").font(.title2) }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
    }
}

// MARK: - Temporary link card

struct TemporaryLinkCard: View {
    let link: TemporaryLink
    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(link.name).font(.subheadline.bold())
                Text(Strings.expires(TimeFormatting.timestring(link.deleteAt, future: true)))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                // The fragment carries the link's secret and never hits the server. New links
                // send just the 32-byte ML-KEM seed (`#s=`) with a Base26 id, which fits in an
                // SMS; links minted before that still carry their full private bundle.
                if let seed = link.pqcSeed {
                    Platform.copy("https://findfamily.cc/view/\(Base26.encode(link.id))#s=\(seed)")
                } else if let pqcKey = link.pqcKey {
                    Platform.copy("https://findfamily.cc/view/\(UInt64(bitPattern: link.id))#pqc_key=\(pqcKey)")
                }
            } label: { Image(systemName: "doc.on.doc") }
            Button(role: .destructive) {
                Database.shared.deleteTemporaryLink(link)
            } label: { Image(systemName: "trash") }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
    }
}

// MARK: - Waypoint card

struct WaypointCard: View {
    let waypoint: Waypoint
    let users: [User]
    let onTap: () -> Void

    var here: [User] { users.filter { $0.locationName == waypoint.name } }
    var hereString: String {
        switch here.count {
        case 0: return Strings.nobodyHere
        case 1: return Strings.userIsHere(here[0].name)
        default: return Strings.usersAreHere(here.map { $0.name }.joined(separator: ", "))
        }
    }

    var body: some View {
        Button(action: onTap) {
            HStack {
                VStack(alignment: .leading) {
                    Text(waypoint.name).font(.subheadline.bold())
                    Text(hereString).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "square.and.pencil")
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
        }
        .buttonStyle(.plain)
    }
}
