# Beyond DNS

TraceVeil's network evidence covers more than DNS lookups. The full description, limits and phone test steps are in
[NETWORK_CAPTURE.md](NETWORK_CAPTURE.md) (phases 1 to 6); this page is only the short answer.

**Seen, without decrypting anything:** the server name an app's TLS or QUIC hello states, even when the app never used
DNS (built-in addresses, its own DNS over HTTPS); the destination address, port and protocol; timing against the consent
choice and bytes sent; and for plain HTTP the request line and a few headers, with every value replaced by its kind
(e-mail, phone, ID, coordinates, a field named like personal data).

**Not seen, by design:** HTTPS contents. Reading them would need a man-in-the-middle with a user-installed certificate,
which breaks certificate pinning, weakens the phone's security, and does not work for most apps anyway (Android 7+ apps
ignore user certificates by default). TraceVeil installs no certificate and decrypts no TLS. Also not seen: request
bodies, IPv6 connections, QUIC versions other than 1, and the real name behind an Encrypted Client Hello.

**Where it shows:** Results → Where it connects (Connections; Connections (quarantine)), Network behaviour, check C16
(personal data in an unencrypted HTTP request, High) and the extra lines of V1; the quarantine run's report; and the
`Network:` line of `traceveil-ci check`.
