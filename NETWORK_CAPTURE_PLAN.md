# Network Capture App Plan for Google Voice SIP Analysis

## Goal
Build a simple Android VPN service app that captures network packets and filters for SIP traffic to/from Google Voice servers, to discover the actual SIP server IP addresses and credential format used by the official Google Voice app.

## Approach

Android's VpnService API allows creating a local VPN that intercepts all device traffic without root. We'll build a minimal capture app that:
1. Creates VpnService to intercept packets
2. Filters for SIP traffic (UDP/TCP ports 5060, 5061, 5062 + hostnames containing sip.google.com, voice.sip, pbx.voice)
3. Logs SIP packets in human-readable format to logcat and/or file
4. User runs capture, opens Google Voice app, makes test call, stops capture, we analyze logs

## Implementation

### Module structure
- New module `:netcapture` in Modern-Apps repo
- Minimal UI: Start/Stop buttons + log display
- VpnService implementation using Android's built-in VPN API (no root, no custom CA needed for SIP since it's plaintext protocol not HTTPS)

### Key files to create
- `netcapture/src/main/java/com/vayunmathur/netcapture/MainActivity.kt` — UI
- `netcapture/src/main/java/com/vayunmathur/netcapture/PacketCaptureVpnService.kt` — VpnService implementation
- `netcapture/src/main/java/com/vayunmathur/netcapture/PacketParser.kt` — Parse IP/UDP/TCP headers, extract SIP payload
- `netcapture/src/main/AndroidManifest.xml` — VpnService declaration + BIND_VPN_SERVICE permission
- `netcapture/build.gradle.kts` — module config

### What we'll capture
- SIP REGISTER requests/responses (will show username, realm, nonce, server IP)
- SIP INVITE requests (for outgoing calls)
- DNS queries for voice.sip.google.com (to see what IPs it resolves to, if any)
- Any HTTPS API calls to GetSIPRegisterInfo endpoint (to confirm our reverse-engineered structure matches)

### Expected output format in logcat
```
[SIP] 192.168.0.135:45678 -> 142.250.x.x:5061 REGISTER sip:voice.sip.google.com SIP/2.0
[SIP] Via: SIP/2.0/TLS ...
[SIP] From: <sip:+1213... @voice.sip.google.com>;tag=...
[SIP] To: <sip:+1213... @voice.sip.google.com>
[SIP] Authorization: Digest username="...", realm="...", nonce="...", uri="...", response="..."
```

This will definitively reveal:
- Actual SIP server IP address (bypassing the DNS NXDOMAIN issue — official app must resolve it somehow)
- SIP username format
- SIP realm value
- Auth scheme (Digest vs Bearer)
- Whether it's SIP over UDP, TCP, or TLS
