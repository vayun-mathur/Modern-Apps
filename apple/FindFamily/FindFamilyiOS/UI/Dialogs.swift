import SwiftUI

// MARK: - AddPersonDialog

struct AddPersonDialog: View {
    let presetID: Int64?
    /// The `k=` fingerprint from the invite link that prefilled `presetID`, if any.
    let fingerprint: String?
    @Binding var isPresented: Bool

    @State private var theirIDStr: String = ""
    @State private var contactName: String?
    @State private var contactPhoto: String?
    @State private var justCopied = false
    @State private var showNeedsUpdate = false
    @State private var showKeyMismatch = false

    private var myIDStr: String { Base26.encode(Networking.shared.userid) }

    private var theirID: Int64? {
        if let preset = presetID { return preset }
        return Base26.decode(theirIDStr)
    }

    private var existingUser: User? {
        guard let id = theirID else { return nil }
        return Database.shared.user(id: id)
    }

    private var status: RequestStatus? { existingUser?.requestStatus }

    private var statusMessage: (String, Bool)? {
        guard let id = theirID else { return nil }
        if id == Networking.shared.userid { return (Strings.addPersonSelf, true) }
        guard let status = status else { return nil }
        switch status {
        case .awaitingRequest: return (Strings.addPersonAwaitingRequest, false)
        case .mutualConnection: return (Strings.addPersonAlreadyMutual, true)
        case .awaitingResponse: return (Strings.addPersonAlreadyRequested, true)
        }
    }

    private var submitEnabled: Bool {
        guard let id = theirID, id != Networking.shared.userid,
              let name = contactName, !name.isEmpty else { return false }
        if let s = status, s == .mutualConnection || s == .awaitingResponse { return false }
        return true
    }

    private var submitLabel: String {
        status == .awaitingRequest ? Strings.addPersonAccept : Strings.addPersonSubmit
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(Strings.addPersonYourID) {
                    Button {
                        copyMyID()
                    } label: {
                        HStack {
                            Text(myIDStr).font(.body.monospaced()).foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: justCopied ? "checkmark" : "doc.on.doc")
                                .foregroundStyle(justCopied ? .green : .accentColor)
                                .contentTransition(.symbolEffect(.replace))
                            if justCopied {
                                Text("Copied")
                                    .font(.caption)
                                    .foregroundStyle(.green)
                                    .transition(.opacity)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    // Share sheet instead of copy/paste: sends an https link that bounces
                    // to findfamily://add/<myId>?k=<fingerprint>, which prefills the
                    // recipient's Add Person dialog. See Networking.inviteLink() for why
                    // the web hop exists and what `k` is.
                    Button {
                        Platform.share(Networking.shared.inviteLink())
                    } label: {
                        HStack {
                            Image(systemName: "square.and.arrow.up")
                            Text(Strings.shareInviteLink)
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                }
                Section(Strings.addPersonTheirID) {
                    if presetID != nil {
                        Text(Base26.encode(presetID!)).font(.body.monospaced())
                    } else {
                        TextField("ABCDE", text: $theirIDStr)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .font(.body.monospaced())
                    }
                    if let (msg, isError) = statusMessage {
                        Text(msg).font(.caption).foregroundStyle(isError ? .red : .secondary)
                    }
                }
                Section(Strings.addPersonName) {
                    Button {
                        Platform.presentContactPicker { name, photo in
                            contactName = name
                            contactPhoto = photo
                        }
                    } label: {
                        HStack {
                            Text(contactName ?? "Pick a contact").foregroundStyle(contactName == nil ? .secondary : .primary)
                            Spacer()
                            Image(systemName: "person.crop.circle")
                        }
                    }
                }
                Section {
                    Button(submitLabel) { submit() }
                        .disabled(!submitEnabled)
                        .frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("Add Person")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Strings.cancelButton) { isPresented = false }
                }
            }
            .alert(Strings.outdatedPeerTitle, isPresented: $showNeedsUpdate) {
                Button(Strings.okButton, role: .cancel) {}
            } message: {
                Text(Strings.outdatedPeerMessage)
            }
            .alert(Strings.keyMismatchTitle, isPresented: $showKeyMismatch) {
                Button(Strings.okButton, role: .cancel) {}
            } message: {
                Text(Strings.keyMismatchMessage)
            }
        }
    }

    private func submit() {
        guard let id = theirID, let name = contactName else { return }
        // Check the peer's post-quantum capability "when connecting". FindFamily is PQC-only, so a
        // peer who registered only a classic key is on an outdated app: prompt them to update
        // instead of adding them. Unknown (not-yet-registered) peers are allowed through — they may
        // register with PQC once they install the app.
        //
        // The fingerprint only vouches for the id that arrived in the link, and the field is
        // read-only whenever there is a preset, so it applies exactly when one is present.
        let inviteFingerprint = presetID != nil ? fingerprint : nil
        Task { @MainActor in
            let check = await Networking.shared.peerCryptoStatus(
                forUserid: id,
                expectedFingerprint: inviteFingerprint
            )
            if check.status == .needsUpdate {
                showNeedsUpdate = true
                return
            }
            if check.status == .keyMismatch {
                showKeyMismatch = true
                return
            }
            let isAccepting = (status == .awaitingRequest)
            let newStatus: RequestStatus = isAccepting ? .mutualConnection : .awaitingResponse
            let user = User(
                id: id,
                name: name,
                photo: contactPhoto,
                locationName: "Unknown Location",
                sendingEnabled: true,
                requestStatus: newStatus,
                lastLocationChangeTime: Date(),
                encryptionKey: nil,
                // Store the fingerprint-checked key with the row itself, so the key that
                // was verified is the one used from here on rather than a fresh lookup.
                pqcEncryptionKey: check.verifiedBundleB64
            )
            Database.shared.upsertUser(user)
            isPresented = false
        }
    }

    private func copyMyID() {
        Platform.copy(myIDStr)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        withAnimation(.easeInOut(duration: 0.2)) { justCopied = true }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            withAnimation(.easeInOut(duration: 0.2)) { justCopied = false }
        }
    }
}

// MARK: - SecurityCodeView

/// Displays the RSA safety number for a connection so both users can compare it out-of-band
/// (mirrors Android's SecurityCodeDialog). The code is computed off the main actor since it
/// runs SHA-256 ×4000. If the peer's key isn't available yet, an explanatory state is shown.
struct SecurityCodeView: View {
    let user: User
    @Binding var isPresented: Bool

    @State private var code: String?
    @State private var loading = true

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Text(Strings.securityCodeCompare(user.name))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                if loading {
                    ProgressView().padding(.top, 8)
                    Text(Strings.securityCodeComputing).font(.caption).foregroundStyle(.secondary)
                } else if let code = code {
                    Text(code)
                        .font(.title2.monospaced())
                        .multilineTextAlignment(.center)
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
                } else {
                    Text(Strings.securityCodeUnavailable(user.name))
                        .font(.callout)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                }
                Spacer()
            }
            .padding()
            .navigationTitle(Strings.securityCodeTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(Strings.doneButton) { isPresented = false }
                }
            }
            .task {
                let result = await Networking.shared.securityCode(for: user)
                code = result
                loading = false
            }
        }
    }
}

struct AddLinkDialog: View {
    @Binding var isPresented: Bool

    @State private var name: String = ""
    @State private var expirySeconds: TimeInterval = 60 * 60   // 1h default
    @State private var working = false

    private let expiryOptions: [(label: String, seconds: TimeInterval)] = [
        ("15 minutes", 15 * 60),
        ("30 minutes", 30 * 60),
        ("1 hour", 60 * 60),
        ("2 hours", 2 * 60 * 60),
        ("4 hours", 4 * 60 * 60),
        ("6 hours", 6 * 60 * 60),
        ("12 hours", 12 * 60 * 60),
        ("1 day", 24 * 60 * 60),
        ("2 days", 2 * 24 * 60 * 60),
        ("1 week", 7 * 24 * 60 * 60),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section(Strings.addLinkLabelField) {
                    TextField("Label", text: $name)
                }
                Section(Strings.addLinkExpiryField) {
                    Picker("Expiry", selection: $expirySeconds) {
                        ForEach(expiryOptions, id: \.seconds) { opt in
                            Text(opt.label).tag(opt.seconds)
                        }
                    }
                }
                Section {
                    Button(Strings.addLinkSubmit) { submit() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || working)
                        .frame(maxWidth: .infinity)
                }
            }
            .navigationTitle(Strings.addLinkTitle)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Strings.cancelButton) { isPresented = false }
                }
            }
        }
    }

    private func submit() {
        working = true
        Task {
            do {
                // Links are post-quantum only: generate an ML-KEM link key. There is no RSA
                // fallback, so this fails the creation rather than downgrading to classic crypto.
                let linkKey = try PQCKeyManager.shared.generateLinkKey()
                let link = TemporaryLink(
                    // A link id is the server-side recipient id (what `/view/<id>` resolves to)
                    // and shares the random 64-bit namespace of userids. Room-style autoincrement
                    // made every device's first link id 1, so every `/view/1` collided on one
                    // server queue. Use a random positive Int64 instead (mirrors Android
                    // newTemporaryLinkId()); this routes through the explicit-id INSERT OR REPLACE.
                    id: Int64.random(in: 1...Int64.max),
                    name: name.trimmingCharacters(in: .whitespaces),
                    key: "",
                    publicKey: "",
                    deleteAt: Date().addingTimeInterval(expirySeconds),
                    pqcPublicKey: linkKey.publicB64,
                    pqcKey: nil,
                    pqcSeed: linkKey.seedB64Url
                )
                _ = Database.shared.upsertTemporaryLink(link)
            } catch {
                print("AddLink: PQC link key unavailable (native not linked?): \(error.localizedDescription)")
            }
            working = false
            isPresented = false
        }
    }
}
