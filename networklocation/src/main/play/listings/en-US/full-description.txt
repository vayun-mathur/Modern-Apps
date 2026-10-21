A privileged Wi-Fi and cell network location and geocoder provider

Features:
- Supplies the system network location provider, so apps get a fix without GPS
- Supplies the system geocoder, resolved from an offline database
- Your position is solved on device by native Rust, never by a server
- Wi-Fi and cell beacons are resolved entirely offline, with no positioning service
- No beacon identifiers or positions ever leave the device
- Always on, with nothing to configure

Internet only used for: downloading the offline databases
