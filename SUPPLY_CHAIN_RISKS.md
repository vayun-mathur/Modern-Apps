# Supply Chain Risks

Apps that fetch from external servers (excluding vayunmathur.com, maproj.org, maemail.org). Fetches only — no outbound intents, accepted-link hosts, comments, test fixtures, or parser identifiers.

## Mandatory — connects during normal use, no per-service opt-in

1. **taxi** — `api.lyft.com`, `cn-geo1.uber.com` + `m.uber.com`, `api.stripe.com`, `payments.braintree-api.com`
2. **fooddelivery** — `api.deliverycollective.com`
3. **youpipe** — `youtube.com` family + `*.googlevideo.com` + `youtubei.googleapis.com` + `yt3.ggpht.com` + `suggestqueries.google.com`, `api.pipepipe.dev` (decoder/player + PO tokens), `jnn-pa.googleapis.com` (BotGuard attestation), `vimeo.com`

## Optional — no fetch until the user enables or configures the service

4. **maps** — `www.google.com` (place enrichment + reviews scraper), `maps.googleapis.com` + `streetviewpixels-pa.googleapis.com` (Street View). Search and ambient pins are OSM-only. All Google paths gated behind one services toggle: OFF by default with a first-launch consent dialog — zero Google traffic until opt-in.
5. **appstore** — only MODERN_APPS + PROPRIETARY on by default; FDROID (`f-droid.org/repo`, `verification.f-droid.org`), PLAYSTORE (`auroraoss.com`, Play backend), ACCRESCENT (`repo.accrescent.app`, `appstore-api.accrescent.app`) are opt-in via Sources settings
6. **communicate** — only after registering a line: `chat.signal.org` (WS + REST), `cdsi.signal.org`, `cdn.signal.org` / `cdn*.signal.org` + `storage.signal.org`, `sfu.voip.signal.org`, `signalcaptchas.org`; `clients6.google.com`, `voice.google.com`; `g.whatsapp.net`, `v.whatsapp.net`, `graph.whatsapp.com`, `mmg.whatsapp.net`
7. **email** — only after adding an account: `imap.gmail.com` / `smtp.gmail.com`, `outlook.office365.com` / `smtp-mail.outlook.com`, `imap.mail.yahoo.com` / `smtp.mail.yahoo.com`, `imap.aol.com` / `smtp.aol.com`, `imap.mail.me.com` / `smtp.mail.me.com`, `imap.fastmail.com` / `smtp.fastmail.com`, `login.microsoftonline.com` + `outlook.office.com` (OAuth)
8. **games/chess** — `lichess.org` (OAuth + Board API), only after sign-in
9. **web** — whichever search engine the user picks (WebView loads it; DuckDuckGo default), only on user navigation/search
