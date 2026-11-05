import SwiftUI

/// Two-step permission flow: foreground "When in Use" first, then "Always".
struct PermissionsGate: View {
    @ObservedObject var controller: PermissionsController

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Text(Strings.appName)
                .font(.largeTitle.bold())

            Button {
                controller.requestForeground()
            } label: {
                Text(controller.hasForeground ? Strings.permissionLocationGranted : Strings.permissionGrantLocation)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(controller.hasForeground)

            Button {
                controller.requestBackground()
            } label: {
                Text(controller.hasBackground ? Strings.permissionAlwaysGranted : Strings.permissionGrantAlways)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!controller.hasForeground || controller.hasBackground)

            if controller.hasForeground && !controller.hasBackground {
                Text(Strings.permissionAlwaysExplanation)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
            Spacer()
        }
        .padding()
        .onAppear { controller.refresh() }
    }
}
