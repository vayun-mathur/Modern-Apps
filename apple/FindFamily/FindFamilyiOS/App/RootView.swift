import SwiftUI

/// Top level switch between the permissions gate and the main UI.
struct RootView: View {
    @StateObject private var permissions = PermissionsController()
    @State private var showDataClearedNotice = !UserDefaults.standard.bool(forKey: Self.dataClearedNoticeKey)

    private static let dataClearedNoticeKey = "dataClearedNoticeShown_v1"

    var body: some View {
        Group {
            if permissions.hasForeground && permissions.hasBackground {
                MainView()
            } else {
                PermissionsGate(controller: permissions)
            }
        }
        .onAppear { permissions.refresh() }
        .alert("Data Cleared", isPresented: $showDataClearedNotice) {
            Button("OK") {
                UserDefaults.standard.set(true, forKey: Self.dataClearedNoticeKey)
            }
        } message: {
            Text("All data has been reset as significant improvements to core systems have been made. We apologize for the inconvenience.")
        }
    }
}
