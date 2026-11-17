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

## Freshness metadata (optional, `mavpn/1`)

All fields below are optional top-level `manifest.json` fields. Bundles
without them are *legacy* bundles: they import unchanged and the app reports
their freshness as **Unknown**. Unknown extra fields remain ignored within
major 1, so no new format major was needed.

| Field | Meaning |
|---|---|
| `providerId` | Stable provider identity (`[A-Za-z0-9][A-Za-z0-9._-]*`). Survives catalogue/name changes; anchors bundle identity and update matching. |
| `revision` | Provider-monotonic non-negative integer. Bump on every published bundle change. Floats and negatives are rejected. |
| `issuedAt` | ISO-8601 UTC instant, e.g. `2026-01-01T00:00:00Z`. |
| `expiresAt` | ISO-8601 UTC instant; must be after `issuedAt` when both are present. |
| `updateUrl` | `https://` URL of `mavpn-update/1` metadata (below). `http://` is rejected. |

### How the app classifies a bundle

- **Current** — expiry metadata is in the future, or a revision is known and
  no newer remote revision has been seen. The app never calls a bundle
  current or outdated from its age alone.
- **Outdated (expired)** — `expiresAt` has passed. This is a warning only:
  servers stay connectable. (The app does not disable servers on expiry —
  there is no safety reason to strand a user whose bundle merely aged out.)
- **Update available** — the last manual check found a higher remote revision.
- **Unknown freshness** — legacy bundle, or metadata too thin to judge
  (e.g. only `issuedAt`).
- **Check failed** — the last manual check failed (network, bad metadata, or
  provider mismatch).

Every imported server row retains `providerId`, `revision`, `issuedAt`,
`expiresAt` and `updateUrl` (Room v4, migration `MIGRATION_3_4`; defaults keep
legacy rows intact). Provider identity with a `providerId` is stable across
revisions, so updates replace that provider's servers in place and drop
servers the new revision removed.

## Update metadata (`mavpn-update/1`)

Serve JSON like this from the bundle's `updateUrl`:

```json
{
  "format": "mavpn-update/1",
  "providerId": "example-vpn",
  "revision": 8,
  "issuedAt": "2026-06-01T00:00:00Z",
  "expiresAt": "2026-12-01T00:00:00Z",
  "downloadUrl": "https://provider.example/bundle.mavpn",
  "sha256": "<64 lower-case hex chars>"
}
```

`format`, `providerId` and `revision` are required; the rest are optional
and validated with the same rules as the manifest (`downloadUrl` must be
HTTPS, expiry after issue, `sha256` must be 64 hex chars).

### Publishing / rotating a bundle (provider checklist)

1. Bump `revision` (never reuse one), refresh `issuedAt`/`expiresAt`, rebuild
   the `.mavpn`, and compute its SHA-256 (`sha256sum bundle.mavpn`).
2. Upload the bundle to `downloadUrl` first, then update the metadata JSON —
   in that order, so a check never points at a missing or stale download.
3. In the app: open the tunnel detail (or Browse by location), tap
   **Check for updates**, then **Update now**. Checks are manual only; the
   app never polls in the background.
4. The app fetches over `NetworkClient` with system trust (provider hosts
   are user-supplied), verifies the SHA-256 and fully parses the new bundle
   *before* replacing anything — a failed hash or bad bundle leaves the
   installed servers untouched. Updates without `downloadUrl`+`sha256` are
   reported but not applied in-app; re-import a new `.mavpn` file instead.
