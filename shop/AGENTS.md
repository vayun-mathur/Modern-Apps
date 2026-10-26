# Shop (:shop)

Scope: this module plus library/network, library/image and library/ui as needed.
Package roots: ui, data, domain, platform, network.
Entry types: MainActivity, Route, Navigation. Navigation remains a root-only graph.

## Behavior
- Anonymous catalog and checkout preparation using supported UCP 2026-08-25 and 2026-04-08 profiles.
- Select one complete advertised profile and exact capability/service versions; do not merge releases.
- Merchant services must advertise a supported REST or MCP binding and a public HTTPS endpoint.
- Empty merchant input uses Shopify Global Catalog subject to its access policy; manual merchant input remains supported.
- The Merchants tab explicitly loads the public UCPList directory. Cache/filter locally and validate live discovery on selection.
- Directory status labels are informational leads, never access grants or independently verified integrations.
- MCP handles negotiated sessions and bounded JSON/SSE responses; unsafe endpoints and redirects are rejected.
- REST uses the selected dated operation schema and app-owned platform identity, not MCP wrapper bodies.
- Buyer/fulfillment review is ephemeral. Merchant totals and legal links are authoritative.
- Checkout writes require explicit user action and a stable idempotency key per intent. Never replay an uncertain mutation automatically.
- Preserve cart lines through partial failures and browser handoffs. A session or browser return is not a paid order.
- No raw card entry, wallet tokenization, completion or authenticated order-read integration while provider authorization is unavailable.
- Only supported integer quantities can be added. Detail refresh sends the product ID and selected options, and must confirm the exact variant before cart actions. If a valid detail response returns only another featured option, one fresh advertised merchant search may confirm the exact SKU. Cached snapshots and empty lookup responses are not verification; denied/rate-limited calls are never retried automatically.
- Use variant seller identity for Global Catalog routing, not the shop.app product-page host. Display the publishing store name when provided and its domain otherwise.
- Product HTML is converted to inert styled text without a WebView; only validated public HTTPS description links may open externally.
- Product responses remain session-scoped; persisted cart snapshots omit remote image URLs.
- Merchant image hosts and cdn.shopify.com are allowed; other image hosts are dropped.
- Await AppInit readiness before network operations so the standard trust bundle is initialized.

## Responsibilities
- Network adapters own dated discovery, REST/MCP transport, error envelopes and merchant directory parsing.
- ShopViewModel owns explicit cart and per-merchant native-review state. Buyer PII must never enter logs or persisted history.
- Local stores retain pending checkout records for browser recovery, not order confirmations.
- UI uses shared app scaffolds and controls; one public composable per file. Metadata previews are class members with PreviewTest and Preview.

## Verify
Run scoped Shop compilation, unit tests, lint and metadata rendering. If shared networking changes, run its focused tests and lint too.
Use fake transports/fixtures for checkout writes and stream lifecycle tests. No live checkout creation/update/completion or order-read checks without an authorized sandbox.

The sibling server hosts app-owned platform identities at https://maproj.org/.well-known/ucp?version=2026-08-25 and the April equivalent. The original profile URL and pinned routes remain available. Validate both profiles against dated official platform schemas and deploy aliases before exercising the updated client identity.
Never borrow a third party's profile, infer permissions from platform labels, or assert that every directory entry interoperates.
