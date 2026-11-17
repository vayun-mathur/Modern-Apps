#!/usr/bin/env python3
"""Generate a demo .mavpn bundle with fictional servers.

Usage:
    python3 vpn/tools/generate_sample_mavpn.py [output.mavpn]

Defaults to vpn/tools/sample.mavpn. Also used to (re)generate the parser
test fixture at vpn/src/test/resources/mavpn/demo.mavpn:

    python3 vpn/tools/generate_sample_mavpn.py vpn/src/test/resources/mavpn/demo.mavpn

Every endpoint is under example.invalid and every key is a clearly fake,
EXAMPLE-labelled placeholder derived from a fixed byte pattern. Never use
this bundle to connect anywhere. ZIP timestamps are pinned so the output is
byte-for-byte reproducible.
"""

import base64
import json
import sys
import zipfile

FIXED_DATE = (2020, 1, 1, 0, 0, 0)


def example_key(seed: int) -> str:
    """A well-formed 44-char WireGuard-shaped key that is obviously fake."""
    raw = bytes((seed + i) % 256 for i in range(32))
    return base64.b64encode(raw).decode()


def conf(server_id: str, host: str, seed: int) -> str:
    return (
        "# EXAMPLE fake WireGuard config for the .mavpn demo bundle.\n"
        "# EXAMPLE keys only - not real, never use to connect.\n"
        "[Interface]\n"
        f"PrivateKey = {example_key(seed)}\n"
        "Address = 10.66.0.2/32\n"
        "DNS = 1.1.1.1\n"
        "\n"
        "[Peer]\n"
        f"PublicKey = {example_key(seed + 100)}\n"
        "AllowedIPs = 0.0.0.0/0, ::/0\n"
        f"Endpoint = {host}:51820\n"
        "PersistentKeepalive = 25\n"
    )


SERVERS = [
    ("de-fra-1", "DE", "Frankfurt", "fra1.example.invalid", 11),
    ("de-ber-1", "DE", "Berlin", "ber1.example.invalid", 22),
    ("us-nyc-1", "US", "New York", "nyc1.example.invalid", 33),
]


def main() -> None:
    out = sys.argv[1] if len(sys.argv) > 1 else "vpn/tools/sample.mavpn"
    manifest = {
        "format": "mavpn/1",
        "provider": "EXAMPLE Demo VPN (fictional)",
        "providerId": "example-demo-vpn",
        "revision": 1,
        "issuedAt": "2026-01-01T00:00:00Z",
        "expiresAt": "2027-01-01T00:00:00Z",
        "updateUrl": "https://example.invalid/mavpn-update.json",
        "servers": [
            {
                "id": server_id,
                "country": country,
                "city": city,
                "file": f"servers/{server_id}.conf",
                "endpoint": f"{host}:51820",
            }
            for server_id, country, city, host, _ in SERVERS
        ],
    }
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as bundle:
        def write(name: str, text: str) -> None:
            info = zipfile.ZipInfo(name, FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, text)

        write("manifest.json", json.dumps(manifest, indent=2) + "\n")
        for server_id, _, _, host, seed in SERVERS:
            write(f"servers/{server_id}.conf", conf(server_id, host, seed))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
