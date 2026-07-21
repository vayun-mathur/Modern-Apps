# RCS Implementation for Modern-Apps Messages

## Overview
RCS (Rich Communication Services) has been added as a new MessageSource in the messages app, implementing Google Jibe-compatible client architecture based on extensive reverse engineering.

## Architecture

### Protocol Stack (per GSMA RCC.14 / RCC.15 and Google Jibe)
```
┌─────────────────────────────────────┐
│         UI Layer (Compose)          │  RcsLoginScreen.kt
├─────────────────────────────────────┤
│       RcsClient (State Machine)     │  RcsClient.kt
├──────────┬──────────────┬───────────┤
│AcsClient │ SipManager   │TachyonClient│
│(Phase 2) │ (Phase 3)    │ (Phase 4)   │
├──────────┴──────────────┴───────────┤
│         HTTP / SIP / gRPC           │
└─────────────────────────────────────┘
```

## Implementation Status by Phase

### ✅ Phase 1: Architecture & UI Skeleton - COMPLETE
**Files:**
- `MessageSource.kt` - Added `RCS` enum with idPrefix "rcs"
- `MainActivity.kt` - Added `Route.LoginRcs` navigation
- `SettingsScreen.kt` - RCS card in settings UI
- `RcsLoginScreen.kt` - Setup UI with phone number + OTP input
- `MessagesDatabase.kt` - Room version bumped to 9
- `MessagesSessionManager.kt` - Fully wired RCS client
- `SourceConnectionState.kt` - RCS state mapping
- All UI when-expressions updated for exhaustive matching

**Status:** Compiles cleanly ✅

### ✅ Phase 2: ACS Provisioning (GSMA RCC.14) - COMPLETE
**Files:**
- `RcsConfiguration.kt` - Data models for ACS XML parsing
- `AcsClient.kt` - Full HTTP ACS client implementation

**What it does:**
1. Constructs ACS URL from SIM MCC/MNC: `http://config.rcs.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org/`
2. Sends HTTP GET with device parameters per GSMA spec (vers, rcs_version, client info, IMSI, IMEI, msisdn, token)
3. Handles responses: 200 OK (parse XML), 511 (OTP required), 403, 409, 503, 307/308 redirect
4. Parses XML configuration via XmlPullParser to extract IMS/SIP credentials
5. Supports OTP resubmission flow for SMS verification

**Based on:** Shannon RCS reverse engineering (`com.shannon.rcsservice.deviceprovisioning`)

### ✅ Phase 3: SIP Stack Integration - SKELETON COMPLETE
**Files:**
- `SipManager.kt` - SIP stack manager with PJSIP integration points documented

**What it defines:**
- SIP REGISTER with Digest/AKA authentication using ACS credentials
- SIP MESSAGE for 1:1 chat (CPIM format per RFC 3862, IMDN per RFC 5438)
- SIP INVITE for MSRP file transfer sessions
- SIP SUBSCRIBE/NOTIFY for presence/capability discovery
- SIP OPTIONS for capability query
- SIP MESSAGE with isComposing XML for typing indicators (RFC 3994)

**TODO to complete:** Integrate PJSIP Android library
- Add PJSIP AAR dependency to build.gradle
- Implement actual PJSIP Endpoint, Account, Call classes in SipManager
- Handle SIP registration state callbacks
- Implement message send/receive via PJSIP APIs

**PJSIP resources:**
- https://github.com/pjsip/pjproject (official)
- https://github.com/VoiSmart/pjsip-android-builder (Android prebuilt)

### ✅ Phase 4: Tachyon gRPC Client - SKELETON COMPLETE
**Files:**
- `TachyonClient.kt` - Google Jibe Tachyon protocol client skeleton

**What was reverse engineered from GMS APK:**
- OAuth scopes: `https://www.googleapis.com/auth/tachyon`, `https://www.googleapis.com/auth/android-messages`
- gRPC endpoints (from `defpackage.jonb`):
  - `tachyon-prod-rcs-us-grpc.googleapis.com`
  - `tachyon-prod-rcs-eu-grpc.googleapis.com`
  - `tachyon-prod-rcs-ap-grpc.googleapis.com`
  - Sandbox variants with `.sandbox.googleapis.com`
- Proto package: `google.internal.communications.instantmessaging.v1`
- E2E encryption via Scytale library (Signal Protocol-like, prekey bundles found in Messages APK)

**TODO to complete (MASSIVE reverse engineering effort):**
1. Extract Tachyon .proto definitions from GMS APK
   - Decompile defpackage classes handling Tachyon RPCs
   - Or capture gRPC traffic with Frida hooks
2. Understand OAuth flow - how to obtain tachyon scope tokens
   - Likely restricted to Google-signed apps via Play Services
   - May require reverse engineering Play Services auth APIs
3. mTLS client certificate provisioning
4. E2E encryption integration with Scytale
5. Bidirectional streaming RPC implementation

**Alternative:** Since carrier uses Google Jibe, check if standard SIP works directly with Jibe P-CSCF at `*.telephony.goog` without Tachyon layer. Shannon RCS suggests standard SIP should work with carrier RCS infrastructure.

### ✅ Phase 5: Messaging Features - SKELETON COMPLETE
**Integrated into RcsClient.kt:**
- `sendMessage()` - routes to TachyonClient first, falls back to SipManager
- `sendMedia()` - routes to TachyonClient or SipManager with FT
- `sendTyping()` - via SipManager (isComposing XML)
- `sendReadReceipt()` - via SipManager (IMDN)
- `sendReaction()` - stubbed (RCS UP doesn't define reactions natively)
- `sendPoll()` - returns false (no native RCS poll support)
- `searchContacts()` - capability discovery via SIP OPTIONS (TODO)
- `fetchMessages()` - no-op (messages arrive via push)

## How to Test Current Implementation

1. Build and run the app
2. Go to Settings → tap "RCS" card → "Set up"
3. Enter phone number in E.164 format → tap "Start RCS setup"
4. App will attempt ACS provisioning:
   - Constructs ACS URL from SIM MCC/MNC
   - Sends HTTP GET to ACS server
   - If ACS returns 200: parses XML, logs SIP credentials, shows "Connected" (demo mode)
   - If ACS returns 511: shows OTP input screen
   - If ACS returns 403/409/503: shows appropriate error

**Expected behavior with real carrier:** Most carriers' ACS servers will likely return 403 (forbidden) for non-whitelisted clients, or may not respond at all to unknown User-Agents. Google Jibe ACS URL needs to be discovered via network capture from official Google Messages app.

## Next Steps to Make It Fully Functional

### Immediate (to get basic RCS working with carrier):
1. **Network capture from Google Messages:**
   - Install Google Messages on test device
   - Use mitmproxy or Burp with root CA to capture ACS provisioning traffic
   - Discover exact Jibe ACS URL and required headers/parameters
   - Note: May fail due to certificate pinning - use Frida to bypass

2. **PJSIP integration:**
   - Add PJSIP Android AAR to project dependencies
   - Implement actual SIP stack in SipManager.kt following the documented skeleton
   - Test SIP REGISTER with credentials from ACS

3. **Test with carrier RCS:**
   - If carrier exposes standard SIP interface, ACS + SIP may be sufficient
   - Send test SIP MESSAGE and verify delivery

### Advanced (for Google Jibe compatibility):
4. **Tachyon reverse engineering:**
   - Decompile GMS APK further to extract proto definitions
   - Use Frida to hook gRPC calls in Google Messages and log traffic
   - Implement TachyonClient with actual gRPC stubs
   - Figure out OAuth token acquisition (may be blocked for third-party apps)

5. **E2E encryption:**
   - Integrate Scytale or Signal Protocol library
   - Implement prekey exchange per Tachyon protocol

## Reverse Engineering References
- `reverse-engineering/messages-re/FINDINGS.md` - Google Messages APK analysis
- `reverse-engineering/shannon-re/FINDINGS.md` - Shannon RCS (GSMA reference implementation)
- `reverse-engineering/gms-re/` - Google Play Services decompiled sources

## Key Technical Decisions

1. **ACS first, then SIP, then Tachyon:** Following GSMA spec order, with Google Jibe extensions layered on top
2. **Dual transport strategy:** Try Tachyon first (Google path), fall back to SIP (standard RCS path)
3. **State machine mirrors other protocols:** Idle → NeedsSetup → Provisioning → AwaitingOtp → Connecting → Connected → Disconnected
4. **Database schema unchanged:** RCS uses same Conversation/Message tables with source=RCS and idPrefix="rcs:"
5. **UI follows existing patterns:** RcsLoginScreen mirrors SignalLoginScreen structure
