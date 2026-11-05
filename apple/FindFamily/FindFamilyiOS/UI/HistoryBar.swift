import SwiftUI

/// Vertical slider + ± buttons + date picker for browsing a user's historical locations.
struct HistoryBar: View {
    let userid: Int64
    @Binding var isShowingPresent: Bool
    @Binding var historicalPosition: Coord?

    @State private var pickedDate: Date = Date()
    @State private var pickedSecondsOfDay: Float = Float(Self.currentSecondsOfDay())
    @State private var showDatePicker = false

    static func currentSecondsOfDay() -> Int {
        let comps = Calendar.current.dateComponents([.hour, .minute, .second], from: Date())
        return (comps.hour ?? 0) * 3600 + (comps.minute ?? 0) * 60 + (comps.second ?? 0)
    }

    private var simulatedTimestamp: Date {
        let startOfDay = Calendar.current.startOfDay(for: pickedDate)
        return startOfDay.addingTimeInterval(TimeInterval(pickedSecondsOfDay))
    }

    private var maxSeconds: Float {
        let today = Calendar.current.isDateInToday(pickedDate)
        return today ? Float(Self.currentSecondsOfDay()) : 86399.9
    }

    private func bump(_ delta: Float) {
        let v = min(maxSeconds, max(0, pickedSecondsOfDay + delta))
        pickedSecondsOfDay = v
    }

    private func updateHistoricalPosition() {
        let locs = Database.shared.locationsFor(userid: userid)
        guard !locs.isEmpty else { return }
        let target = simulatedTimestamp
        let closest = locs.min { abs($0.timestamp.timeIntervalSince(target)) < abs($1.timestamp.timeIntervalSince(target)) }
        historicalPosition = closest?.coord
    }

    var body: some View {
        VStack(spacing: 4) {
            if !isShowingPresent {
                // Vertical slider (rotated horizontal slider).
                GeometryReader { geo in
                    Slider(value: $pickedSecondsOfDay, in: 0...86400)
                        .rotationEffect(.degrees(-90))
                        .frame(width: geo.size.height, height: geo.size.width)
                        .position(x: geo.size.width / 2, y: geo.size.height / 2)
                        .onChange(of: pickedSecondsOfDay) { _, _ in updateHistoricalPosition() }
                }
                .frame(maxHeight: .infinity)

                HStack {
                    Button(Strings.historyRewindLarge) { bump(-5 * 60) }
                    Spacer()
                    Button(Strings.historyForwardLarge) { bump(5 * 60) }
                }
                HStack {
                    Button(Strings.historyRewindMedium) { bump(-60) }
                    Spacer()
                    Button(Strings.historyForwardMedium) { bump(60) }
                }
                HStack {
                    Button(Strings.historyRewindSmall) { bump(-10) }
                    Spacer()
                    Button(Strings.historyForwardSmall) { bump(10) }
                }
                .font(.caption)

                Text(TimeFormatting.amPmTimeSeconds.string(from: simulatedTimestamp))
                    .font(.caption2)

                Button(TimeFormatting.dayMonth.string(from: pickedDate)) {
                    showDatePicker = true
                }
                .font(.caption2)
            }

            Button(isShowingPresent ? Strings.historyButton : Strings.hideButton) {
                isShowingPresent.toggle()
                if !isShowingPresent {
                    pickedDate = Date()
                    pickedSecondsOfDay = Float(Self.currentSecondsOfDay())
                    updateHistoricalPosition()
                } else {
                    historicalPosition = nil
                }
            }
            .font(.caption2)
            .frame(maxWidth: .infinity)
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 10).fill(.ultraThinMaterial))
        .sheet(isPresented: $showDatePicker) {
            VStack {
                DatePicker(
                    "Pick Date",
                    selection: $pickedDate,
                    in: ...Date(),
                    displayedComponents: .date
                )
                .datePickerStyle(.graphical)
                Button(Strings.okButton) {
                    showDatePicker = false
                    updateHistoricalPosition()
                }
                .buttonStyle(.borderedProminent)
                .padding()
            }
            .padding()
        }
    }
}
