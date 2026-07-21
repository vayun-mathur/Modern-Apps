# Google Voice Calling Integration Plan

## Executive Summary

This document outlines the implementation plan to add **full in-app VoIP calling** to the Messages app in Modern-Apps, enabling Google Voice voice calls via SIP protocol — matching the functionality of the official Google Voice Android app.

**Current Status:**
- ✅ APK pulled from device and decompiled (base.apk + split_config.arm64_v8a.apk)
- ✅ Decompiled with apktool and jadx
- ✅ Existing GVoiceClient handles SMS/MMS but NOT voice calling
- ✅ EndpointGetSIPRegisterInfo constant exists but is unused
- ⚠️ Protobuf definitions for SIP registration are missing

---

## 1. Reverse Engineering Findings

### 1.1 APK Analysis

**Package:** `com.google.android.apps.googlevoice`
**Size:** 23.9 MB base + 8.0 MB arm64 split
**Key Components Found:**

```
AndroidManifest.xml permissions:
  - CALL_PHONE, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS
  - MANAGE_OWN_CALLS, READ_PHONE_STATE
  - FOREGROUND_SERVICE_PHONE_CALL, FOREGROUND_SERVICE_MICROPHONE
  - FOREGROUND_SERVICE_MEDIA_PLAYBACK

Key Services:
  - com.google.android.apps.voice.voip.telephony.connectionservice.selfmanaged.VoipConnectionService
    (extends android.telecom.ConnectionService)
  - com.google.android.apps.voice.voip.ui.callservice.CallForegroundService
  - com.google.android.apps.voice.voip.ui.VoipCallActivity

Key UI:
  - VoipCallActivity (in-call screen)
  - CallForegroundService (foreground service for ongoing calls)
  - OngoingCallActionReceiver (handles answer/hangup/decline actions)
```

### 1.2 SIP Architecture Discovered

**SIP Server:** `voice.sip.google.com` (hardcoded in decompiled code: `mbb.java:39`)

**SIP Stack:** Google uses **Birdsong** — a proprietary SIP/WebRTC implementation:
- `com.google.third_party.resiprocate.src.apps.birdsong.ReferState`
- `com.google.android.apps.voice.voip.telephony.birdsongimpl.BirdsongTelephonyImpl`
- References to `NetworkQualityMetrics$NetworkQualityMeasurementResult`

**Key Decompiled Classes:**
- `VoipConnectionService.java` — Android Telecom ConnectionService implementation
- `BirdsongTelephonyImpl` (obfuscated as `mbb.java`) — Core SIP/WebRTC engine
- Uses WebRTC for audio: `org.webrtc.audio.WebRtcAudioTrack`, `WebRtcAudioRecord`

### 1.3 Missing Pieces

The existing Modern-Apps codebase has:
- ✅ `EndpointGetSIPRegisterInfo` constant defined
- ❌ No protobuf definition for SIP register request/response
- ❌ No SIP client implementation
- ❌ No WebRTC/SIP library integration
- ❌ No ConnectionService implementation
- ❌ No in-call UI

---

## 2. Technical Architecture

### 2.1 High-Level Flow

```
User taps call button in ConversationScreen
    ↓
MessagesViewModel.startVoiceCall(phoneNumber)
    ↓
GVoiceClient.startCall(phoneNumber)
    ↓
1. Call GetSIPRegisterInfo API to get SIP credentials
2. Initialize SIP stack (PJSIP or similar) with credentials
3. Register with voice.sip.google.com
4. Place SIP INVITE to destination
    ↓
Android Telecom ConnectionService displays system call UI
    ↓
WebRTC handles audio stream
    ↓
Call ends → SIP BYE → unregister
```

### 2.2 Components to Build

#### A. Protobuf Definitions (Priority: HIGH)
Add to `messages/src/main/proto/`:

```protobuf
// requests.proto additions
message ReqGetSIPRegisterInfo {
    // Empty or minimal fields - needs verification via network capture
}

// responses.proto additions  
message RespGetSIPRegisterInfo {
    SIPRegisterInfo sip_register_info = 1;
}

message SIPRegisterInfo {
    string username = 1;           // SIP username
    string password = 2;           // SIP password / auth token
    string domain = 3;             // voice.sip.google.com
    string proxy = 4;              // SIP proxy server
    int32 expires = 5;             // Registration expiry seconds
    string display_name = 6;
    string authorization_id = 7;
    // Additional fields TBD via network capture
}
```

#### B. SIP Client Layer (Priority: HIGH)

**Library Choice:** Use **PJSIP** (pjsip.org) via JNI wrapper or **baresip** — both are mature open-source SIP stacks for Android.

Recommended: **PJSIP Android** (`org.pjsip:pjsua2`)
- Mature, well-documented
- Supports TLS, SRTP, WebRTC audio
- Android NDK integration available
- Alternative: Use WebRTC directly with SIP over WebSocket (more complex)

**New files:**
- `messages/src/main/java/com/vayunmathur/messages/gvoice/sip/SipManager.kt` — SIP registration and call control
- `messages/src/main/java/com/vayunmathur/messages/gvoice/sip/SipCall.kt` — Per-call state machine
- `messages/src/main/java/com/vayunmathur/messages/gvoice/sip/SipAccount.kt` — SIP account configuration

#### C. Android Telecom Integration (Priority: HIGH)

**New files:**
- `messages/src/main/java/com/vayunmathur/messages/gvoice/voice/GVoiceConnectionService.kt`
  - Extends `android.telecom.ConnectionService`
  - Handles `onCreateOutgoingConnection()`, `onCreateIncomingConnection()`
  - Manages call lifecycle with system UI

- `messages/src/main/java/com/vayunmathur/messages/gvoice/voice/GVoiceConnection.kt`
  - Extends `android.telecom.Connection`
  - Per-call connection object
  - Handles answer, reject, hold, mute, disconnect

- `messages/src/main/java/com/vayunmathur/messages/gvoice/voice/GVoicePhoneAccountRegistrar.kt`
  - Registers PhoneAccount with TelecomManager
  - Required for system to route calls through our app

#### D. In-Call UI (Priority: MEDIUM)

**New files:**
- `messages/src/main/java/com/vayunmathur/messages/ui/VoiceCallScreen.kt`
  - Compose UI for active call
  - Mute, speaker, keypad, hangup buttons
  - Call duration timer
  - Audio device selector

- Update `AndroidManifest.xml`:
  - Add `VoipCallActivity` equivalent
  - Add `ConnectionService` declaration
  - Add required permissions (already present in GV APK manifest as reference)

#### E. GVoiceClient Extensions (Priority: HIGH)

Extend existing `GVoiceClient.kt`:
```kotlin
suspend fun getSIPRegisterInfo(): SIPCredentials?
suspend fun startVoiceCall(phoneNumber: String): Boolean
suspend fun endVoiceCall(callId: String): Boolean
fun observeIncomingCalls(): Flow<IncomingCallEvent>
```

#### F. UI Integration (Priority: MEDIUM)

Update `ConversationScreen.kt`:
- Add call button in top app bar (next to edit contact button)
- Only show for Voice conversations with peer phone number
- Launch call via ViewModel

Update `MessagesViewModel.kt`:
```kotlin
fun startVoiceCall(conversationId: String, onResult: (Boolean) -> Unit = {})
fun endVoiceCall(onResult: (Boolean) -> Unit = {})
val activeCallState: StateFlow<CallState?>
```

---

## 3. Implementation Phases

### Phase 1: Protocol Discovery (1-2 days)
**Goal:** Understand SIP registration API response format

Tasks:
1. Add protobuf definitions for ReqGetSIPRegisterInfo / RespGetSIPRegisterInfo
2. Implement `GVoiceClient.getSIPRegisterInfo()` method
3. Test API call with existing cookie auth — log response structure
4. Document SIP credentials format (username, password, domain, proxy, etc.)
5. Verify SIP server is `voice.sip.google.com` and test connectivity

**Deliverable:** Documented SIP credentials API response format

### Phase 2: SIP Stack Integration (3-5 days)
**Goal:** Establish SIP registration and basic call capability

Tasks:
1. Add PJSIP Android dependency to `messages/build.gradle.kts`
2. Create JNI wrapper or use pjsua2 Java bindings
3. Implement `SipManager` singleton:
   - Initialize PJSIP endpoint
   - Configure audio devices (WebRTC AEC, NS)
   - Register SIP account with credentials from Phase 1
   - Handle registration state changes
4. Implement basic outgoing call flow (SIP INVITE)
5. Test SIP registration succeeds

**Deliverable:** Working SIP registration, logs show "registered" state

### Phase 3: Android Telecom Integration (2-3 days)
**Goal:** System call UI integration

Tasks:
1. Create `GVoiceConnectionService` extending `ConnectionService`
2. Create `GVoiceConnection` extending `Connection`
3. Implement `GVoicePhoneAccountRegistrar`
4. Register PhoneAccount on app startup (if Voice is connected)
5. Test: placing call shows system incoming/outgoing call UI
6. Handle call actions (answer, hangup, hold, mute) via Connection callbacks

**Deliverable:** Tapping call button shows Android system call screen

### Phase 4: Audio Pipeline (2-3 days)
**Goal:** Two-way audio works

Tasks:
1. Configure PJSIP audio with WebRTC AEC/NS (matches GV app's approach)
2. Handle audio focus and routing (earpiece, speaker, bluetooth)
3. Implement mute/unmute
4. Test audio quality, echo cancellation
5. Handle audio device changes during call

**Deliverable:** Working two-way voice call with clear audio

### Phase 5: In-Call UI & Polish (2 days)
**Goal:** Complete user experience

Tasks:
1. Build `VoiceCallScreen` Compose UI (or rely on system UI via Telecom)
2. Add call button to ConversationScreen top bar
3. Handle incoming call notifications
4. Add call history to conversation thread (already partially supported via message types)
5. Permissions handling (RECORD_AUDIO, etc.)
6. Foreground service for ongoing calls

**Deliverable:** End-to-end calling UX matching Google Voice app

### Phase 6: Testing & Edge Cases (2 days)
Tasks:
1. Test incoming calls
2. Test call hold/resume
3. Test network switching (WiFi ↔ cellular)
4. Test Bluetooth headset
5. Test with Do Not Disturb
6. Battery optimization handling
7. Reconnection logic

---

## 4. Dependencies to Add

### build.gradle.kts (messages module)
```kotlin
dependencies {
    // PJSIP for SIP stack
    implementation("org.pjsip:pjsua2:2.13") // or latest
    
    // Alternative: WebRTC for audio processing
    implementation("org.webrtc:google-webrtc:1.0.32006")
    
    // Android Telecom already available via SDK
}
```

### AndroidManifest.xml additions
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<service
    android:name=".gvoice.voice.GVoiceConnectionService"
    android:permission="android.permission.BIND_TELECOM_CONNECTION_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.telecom.ConnectionService" />
    </intent-filter>
</service>
```

---

## 5. Key Technical Challenges

### Challenge 1: SIP Credentials API
**Risk:** GetSIPRegisterInfo response format unknown
**Mitigation:** Phase 1 is dedicated to discovering this via actual API call with logging. The endpoint constant already exists in codebase, suggesting it's the right path.

### Challenge 2: PJSIP Integration Complexity  
**Risk:** PJSIP JNI setup is complex on Android
**Mitigation:** Use pre-built pjsua2 AAR if available, or evaluate alternative SIP stacks (baresip, sipdroid-lib). Fallback: implement minimal SIP over WebSocket if PJSIP proves too heavy.

### Challenge 3: Audio Quality
**Risk:** Echo, latency, or poor audio quality
**Mitigation:** Google Voice APK uses WebRTC audio processing (AEC, NS). We'll configure PJSIP to use WebRTC audio device or similar echo cancellation.

### Challenge 4: Background Execution
**Risk:** Android kills SIP connection in background
**Mitigation:** Use foreground service with FOREGROUND_SERVICE_PHONE_CALL type (as GV app does). Request battery optimization exemption.

### Challenge 5: Incoming Calls
**Risk:** Receiving incoming SIP calls requires persistent connection
**Mitigation:** Maintain SIP registration via foreground service. Use FCM push as fallback wake-up (GV app uses FCM per manifest).

---

## 6. Files to Modify/Create

### New Files (15)
```
messages/src/main/proto/sip.proto                              # SIP register request/response
messages/src/main/java/com/vayunmathur/messages/gvoice/sip/
    SipManager.kt                                               # SIP stack singleton
    SipAccount.kt                                               # SIP account config
    SipCall.kt                                                  # Per-call state
    SipCredentials.kt                                           # Credentials data class
messages/src/main/java/com/vayunmathur/messages/gvoice/voice/
    GVoiceConnectionService.kt                                  # Telecom ConnectionService
    GVoiceConnection.kt                                         # Telecom Connection
    GVoicePhoneAccountRegistrar.kt                              # PhoneAccount registration
    CallState.kt                                                # Call state enum/sealed class
    VoiceCallManager.kt                                         # High-level call orchestration
messages/src/main/java/com/vayunmathur/messages/ui/
    VoiceCallScreen.kt                                          # In-call Compose UI (optional)
    VoiceCallNotificationManager.kt                             # Ongoing call notification
```

### Modified Files (6)
```
messages/src/main/java/com/vayunmathur/messages/gvoice/
    Constants.kt                                                # Already has endpoint
    GVoiceClient.kt                                             # Add startCall(), getSIPRegisterInfo()
    GVoiceRpcClient.kt                                          # No changes needed (generic)

messages/src/main/java/com/vayunmathur/messages/util/
    MessagesSessionManager.kt                                   # Route call requests to GVoiceClient
    MessagesViewModel.kt                                        # Expose startVoiceCall() to UI

messages/src/main/java/com/vayunmathur/messages/ui/
    ConversationScreen.kt                                       # Add call button to top bar

messages/build.gradle.kts                                       # Add PJSIP/WebRTC dependencies
messages/src/main/AndroidManifest.xml                           # Add ConnectionService, permissions
```

---

## 7. Testing Strategy

1. **Unit tests:** SIP credentials parsing, call state machine
2. **Integration tests:** SIP registration flow with mock server, then real GV API
3. **Manual tests:**
   - Outgoing call to real phone number
   - Incoming call reception
   - Audio quality test
   - Bluetooth headset test
   - Network switch test
   - Background/foreground transitions

---

## 8. Open Questions for Next Steps

1. **PJSIP vs alternatives:** Should we evaluate baresip or Linphone SDK as alternatives to PJSIP for simpler Android integration?
2. **Incoming call push:** Does GV use FCM to wake app for incoming calls, or rely on persistent SIP connection? (Manifest shows FCM service)
3. **STUN/TURN:** Does voice.sip.google.com require STUN/TURN servers for NAT traversal? Need to discover via network capture.
4. **Call recording:** Out of scope for v1, but note GV app may support it per legal requirements.

---

## 9. Success Criteria

- [ ] User can tap call button in Voice conversation
- [ ] System call UI appears showing outgoing call
- [ ] Remote party's phone rings
- [ ] Two-way audio works with acceptable quality
- [ ] User can end call via system UI or in-app button
- [ ] Call appears in conversation history as "Voice call • X min Y sec"
- [ ] Incoming calls ring and can be answered
- [ ] Works on WiFi and cellular data
- [ ] Bluetooth headset audio routing works

---

## 10. Estimated Timeline

| Phase | Duration | Cumulative |
|-------|----------|------------|
| Protocol Discovery | 1-2 days | 2 days |
| SIP Stack Integration | 3-5 days | 7 days |
| Telecom Integration | 2-3 days | 10 days |
| Audio Pipeline | 2-3 days | 13 days |
| UI & Polish | 2 days | 15 days |
| Testing | 2 days | 17 days |
| **Total** | **~2.5 weeks** | |

---

## Appendix A: Decompiled Code References

**APK Location:** `/Users/vayun/Documents/Modern-Apps/reverse-engineering/gvoice-re/`

**Key findings:**
- `jadx-out/sources/com/google/android/apps/voice/voip/` — Full VoIP implementation
- `mbb.java:39` — SIP server hardcoded as `"voice.sip.google.com"`
- Uses Birdsong SIP stack (Google internal, based on reSIProcate)
- WebRTC for audio processing
- Android Telecom ConnectionService for system integration

**Next action:** Run Phase 1 to discover GetSIPRegisterInfo response format via live API call.
