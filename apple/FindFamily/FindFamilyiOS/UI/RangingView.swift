import SwiftUI
import simd

/// Full-screen Precision Finding view. Drives the [RangingSession] lifecycle
/// (start on appear, invalidate on disappear) and renders distance + a
/// directional arrow rotated by the peer's bearing.
struct RangingView: View {
    let peer: User

    @StateObject private var session = RangingSession()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 32) {
            // Peer header
            UserCard(user: peer, latest: nil, showSupporting: false) {}
                .padding(.top, 8)

            Spacer(minLength: 8)

            // Distance + arrow body
            switch session.phase {
            case .idle, .starting:
                StatusBlock(main: "Setting up Precision Finding…", showProgress: true)
            case .waitingForPeer:
                StatusBlock(main: "Waiting for the other device…", showProgress: true)
            case .peerLost:
                StatusBlock(main: "Lost the other device. Move closer.", showProgress: false)
            case .unsupported(let why):
                StatusBlock(main: "Precision Finding isn't supported on this device.", detail: why, showProgress: false)
            case .failed(let why):
                StatusBlock(main: "Precision Finding failed.", detail: why, showProgress: false)
            case .ranging:
                RangingBody(distance: session.distance, direction: session.direction)
            }

            Spacer()
        }
        .padding(24)
        .navigationTitle(peer.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { dismiss() } label: { Image(systemName: "xmark") }
            }
        }
        .onAppear {
            // If this view was opened from a notification, prefer the pending
            // REQUEST envelope from this peer; otherwise initiate as controller.
            if let pending = UwbInbox.shared.consumePendingRequest(from: peer.id) {
                session.accept(request: pending, peer: peer)
            } else {
                session.start(peer: peer)
            }
        }
        .onDisappear { session.invalidate() }
    }
}

private struct StatusBlock: View {
    let main: String
    var detail: String? = nil
    let showProgress: Bool

    var body: some View {
        VStack(spacing: 12) {
            if showProgress {
                ProgressView()
                    .progressViewStyle(.circular)
                    .scaleEffect(1.4)
            }
            Text(main)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            if let d = detail {
                Text(d)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

private struct RangingBody: View {
    let distance: Float?
    let direction: simd_float3?

    /// Azimuth in radians derived from the direction unit vector.
    /// The direction vector is in the device's reference frame: +X = right,
    /// +Y = up, -Z = into the screen (toward the user). We project onto the
    /// XZ plane and atan2 to get the "yaw" angle the on-screen arrow should
    /// rotate to.
    private var azimuthDegrees: Double? {
        guard let d = direction else { return nil }
        return Double(atan2(d.x, -d.z)) * 180.0 / .pi
    }

    var body: some View {
        VStack(spacing: 32) {
            Text(distance.map { String(format: "%.1f m", $0) } ?? "—")
                .font(.system(size: 96, weight: .bold, design: .rounded))
                .foregroundStyle(distance ?? 999 < 1 ? .green : .primary)
                .monospacedDigit()

            Image(systemName: "arrow.up")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 180, height: 180)
                .foregroundStyle(Color.accentColor)
                .opacity(azimuthDegrees != nil ? 1.0 : 0.25)
                .rotationEffect(.degrees(azimuthDegrees ?? 0))
                .animation(.easeOut(duration: 0.15), value: azimuthDegrees)

            if azimuthDegrees == nil {
                Text("Move your phone around to find a direction signal")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }
}
