# The `.mavpn` bundle format (`mavpn/1`)

A `.mavpn` file is how a VPN provider ships a whole catalogue of WireGuard
servers to this app in one file, instead of one hand-imported `.conf` at a
time. Importing it adds every server to the tunnel list (with provider,
country and city attached) and to the *Browse by location* picker.

## Layout

A `.mavpn` file is a plain ZIP archive:

```
manifest.json
servers/<name>.conf
servers/<name>.conf
...
```

- `manifest.json` — at the ZIP root, UTF-8 JSON (below).
- `servers/*.conf` — stock WireGuard *wg-quick* configs, one per server,
  exactly as `wg-quick`, the WireGuard apps and this app's `.conf` import
  already accept (`[Interface]` + `[Peer]`). Do not invent a new config
  dialect: the app feeds these files to its existing WireGuard parser.

## `manifest.json`

```json
{
  "format": "mavpn/1",
  "provider": "Example VPN",
  "servers": [
    {
      "id": "de-fra-1",
      "country": "DE",
      "city": "Frankfurt",
      "file": "servers/de-fra-1.conf",
      "endpoint": "fra1.example.invalid:51820"
    },
    {
      "id": "us-nyc-1",
      "country": "US",
      "city": "New York",
      "file": "servers/us-nyc-1.conf"
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | Must be `"mavpn/1"`. A different major is rejected, so `mavpn/2` can break compatibility safely. Unknown extra fields are ignored within major 1. |
| `provider` | yes | Human-readable provider name, shown in the tunnel list and picker. |
| `servers[].id` | yes | Stable, unique per bundle: letters, digits, `.`, `_`, `-`. Re-importing a bundle updates the server with the same provider + `id` instead of duplicating it. |
| `servers[].country` | yes | ISO 3166-1 alpha-2 country code, upper-case (`"DE"`, `"US"`). Drives the flag and the localised country name in the picker. |
| `servers[].city` | yes | Human-readable city name, shown as-is. |
| `servers[].file` | yes | The server's config inside the ZIP: `servers/<name>.conf`. Must exist. |
| `servers[].endpoint` | no | Display/activation override for the config's `Endpoint`. When present it wins over the `.conf`; use it when the public endpoint differs from the one baked into the config. |

## Limits and safety (enforced on import)

- At most 256 ZIP entries, 200 servers, 1 MiB per entry, 8 MiB total
  uncompressed, 256 KiB of manifest.
- Entry paths must stay inside the bundle: absolute paths, `..`/`.` segments
  and backslash separators are rejected.
- Every referenced `.conf` must parse as a WireGuard config with an
  `[Interface]` `PrivateKey` and a `[Peer]` `PublicKey`.
- Configs are stored only in app-private storage
  (`files/mavpn-bundles/<bundle>/`) and in the app's encrypted config
  database. Private keys are never logged.

## Producing a bundle

- `vpn/tools/generate_sample_mavpn.py` writes a demo bundle with fictional
  `*.example.invalid` endpoints and clearly fake, `EXAMPLE`-labelled keys —
  never ship it as a real provider bundle.
- Providers: zip the manifest and configs with any ZIP tool, e.g.
  `zip -r provider.mavpn manifest.json servers/`.
