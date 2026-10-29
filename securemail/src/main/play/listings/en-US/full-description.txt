End-to-end encrypted mail with PGP, over a live relay

Features:
- Claim an @maemail.org address with an on-device PGP key (Ed25519 + X25519)
- Live relay socket: mail arrives instantly, queued when offline
- Per-recipient encryption with explicit unencrypted opt-in fallback
- Interoperates with ProtonMail and any OpenPGP client via standard PGP/MIME
- Biometric-gated private key; server sees ciphertext only

Internet only used for: relay socket, key directory, and external mail delivery
