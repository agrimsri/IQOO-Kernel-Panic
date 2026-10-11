# Network capture and network intelligence (branch `yashvi/network-capture`)

TraceVeil's audit started as a DNS-only capture. This branch adds a **full-relay** capture so an audit also knows which
connections an app opened, how many bytes moved before consent, after Accept and after Reject, and, for plain HTTP,
what the request looked like. It is built in phases; each can be tested and demonstrated on its own.

## Provenance and licences

- The relay, parsers and rules are written from the protocol RFCs (793, 768, 8446 §4.1.2, 9112 for HTTP/1) and the
  Android `VpnService` documentation.
- PCAPdroid and TrackerControl were read for **design ideas only** (an L4 proxy behind a `VpnService`; how pinning
  failures show up). Both are **GPL-3.0**; this repository is **MIT**. **No code was copied or ported from either**, and
  neither is a dependency. Do not paste from them into this branch.
- No new third-party dependency was added. `kotlinx-serialization-json` (already used) parses the model's reply.

## Phases

| Phase | What | Where | Tested by |
|---|---|---|---|
| 1 | Packet codec (IPv4 TCP/UDP + checksums), TLS ClientHello reader (SNI, ALPN, ECH flag), HTTP/1 head parser, redaction, bounded flow tracker with per-consent-phase byte buckets | `core/.../netcapture/{Packets,TlsHello,HttpHead,Redaction,FlowModel,FlowTracker}.kt` | `NetCaptureTest.kt` (JVM) |
| 2 | User-space TCP/UDP relay (`TunRelay`) over an `UpstreamConnector` | `core/.../netcapture/TunRelay.kt` | `TunRelayTest.kt` (JVM, fake upstream) |
| 3 | Android wiring: all IPv4 of the audited app enters the tunnel and is forwarded through `protect()`-ed sockets; flows saved with the audit (`AuditSession.flows`, `captureDepth`) | `app/.../capture/{XrayVpnService,SocketConnector,CaptureBus}.kt`, `AuditOrchestrator.kt` | device run (see below) |
| 4 | Findings: deterministic rules, redacted model brief, minimal Results section | `core/.../netcapture/{NetFindings,NetModelBrief}.kt`, `app/.../ai/NetIntelligence.kt`, `ui/screens/NetworkFindingsSection.kt` | `NetFindingsTest.kt` (JVM) |
| 5 | HTTPS decryption | **not implemented** (see below) | n/a |
| 6 | Beyond DNS, everywhere: QUIC server names, the quarantine sinkhole, personal-data field names, C16 and V1, connection lists in Results and `traceveil-ci check` | see "Phase 6" below | `QuicInitialTest`, `SinkholeRelayTest`, `CleartextPersonalDataTest`, `QuarantineSinkholeTest`, `ConnectionChecksTest`, `NetworkSummaryTest`, `ConnectionListTextTest` (JVM) |

`CaptureBus.deepCapture` (default `true`) selects full-relay for new audits; `false` gives the previous DNS-only
tunnel byte for byte. Saved audits without `flows` open exactly as before.

## What is captured

| Traffic | Captured |
|---|---|
| DNS to the audited app's resolver address | Question, answers, CNAME chain (unchanged) |
| DNS sent straight to another resolver (UDP 53) | Question recorded, packet forwarded unchanged |
| TCP | Destination, port, timing, bytes up/down per phase |
| TLS (HTTPS) | Server name (SNI), ALPN; **not** the request contents |
| TLS with Encrypted Client Hello | Flagged `ECH_HIDDEN`, visible name kept and labelled "may not be the real server" (real and placeholder "GREASE" ECH look alike) |
| Plain HTTP/1.x | Method, host, path+query and an allow-list of headers, **redacted**; response status |
| QUIC (HTTP/3) | Server name and ALPN from the client's version 1 Initial packets (phase 6); other versions: address, port, bytes |
| Other UDP | Address, port, bytes |
| DoH / DoT | Visible as ordinary TLS flows; flagged by port 853 or a known resolver name/address |
| Direct-IP connections | Recorded; flagged "no lookup" when no DNS answer named the address |

Never stored: request or response bodies, cookies, `Authorization`, tokens. Redaction replaces e-mail addresses, phone
numbers, UUID-style device ids, 15-digit ids, coordinates and long tokens, and records only the **kind** of value seen.
Since phase 6 it also checks percent-encoded values in decoded form, and a query field whose **name** says it holds
personal data (first_name, address, dob, aadhaar, password, …) keeps no part of its value: only the kinds seen in it, or
`[personal]`.

## Network intelligence

1. Rules (`NetFindings`) turn flows into candidate findings. Each needs observed evidence and carries its own
   confidence and a plain statement of what could not be inspected.
2. If an on-device model is loaded, it receives **only the redacted candidate summaries** (`NetModelBrief.prompt`): no
   packet captures, bodies or full logs. It may reword the explanation, rank candidates, and drop low-severity
   *inferred* candidates it judges ordinary. It cannot add a finding, change a destination, number or confidence, or
   remove a rule-proven tracking finding. Replies that claim theft, illegality or a breach are discarded.
3. There is no remote-model path. If one is ever added it must disclose and get explicit consent before sending
   anything.

The Results screen shows a risk level, up to five findings (expandable for evidence), and the capture's limits. Full
flows remain in the saved audit JSON for the developer export.

## Limitations (read before demoing)

- **HTTPS contents are not readable.** Decrypting needs a TLS-terminating proxy with a CA the user installs by hand,
  and only apps that trust user CAs would be readable; most Play Store apps do not (Android 7+ ignores user CAs by
  default; many pin). We do not bypass pinning. This is the main gap against the request and is deliberately not faked.
- IPv4 only: the tunnel gets no IPv6 route, so IPv6 flows bypass it (not captured, not broken).
- No retransmission toward the app, no congestion control: the tunnel is lossless and in order, but a stalled app window
  eventually resets that connection (30 s).
- QUIC is relayed, not inspected. HTTP/2 over TLS is opaque; cleartext HTTP/2 is not parsed.
- ICMP is not forwarded.
- Phase attribution of bytes uses 0.5 s buckets: a burst straddling the Accept/Reject tap can land in either phase.
- Hostnames come from TLS SNI, HTTP `Host`, or the app's own DNS answers. A shared CDN address can be named after
  another site; confidence reflects this.
- Verification status is stated in the commit messages and the final report, not here.

## Phase 6: beyond DNS, everywhere (branch `nitya/traffic-sni`)

Built on the types above (`TunRelay`, `FlowTracker`, `TlsHello`, `Redaction`, `FlowRecord`); no second engine or parser.

**The honest boundary.** HTTPS contents stay unreadable. Reading them would need a man-in-the-middle with a CA the user
installs: that breaks certificate pinning, is a security risk for the phone, and Android 7+ apps ignore user CAs by
default anyway. TraceVeil installs no certificate and decrypts no TLS. What it reads is what any network observer sees:
the TLS or QUIC **ClientHello** (server name, ALPN), the destination address and port, timing and byte counts, and for
plain HTTP the request line and a few headers with every value replaced by its kind.

### What was added

| Piece | What it does | Where |
|---|---|---|
| QUIC server names | A QUIC v1 client Initial is protected with keys derived from its own connection ID and a salt published in RFC 9001 §5.2, so its CRYPTO frames can be read without any secret; the ClientHello inside (split over packets or out of order) is reassembled and read with `TlsHello`. Used by the relay in audits and in quarantine. Nothing after the handshake is readable. | `netcapture/QuicInitial.kt`, `FlowTracker.quicDatagram` |
| Quarantine sinkhole | Quarantine used to drop every connection, so it saw bare addresses. Now IPv4 TCP connections and QUIC datagrams of the quarantined app go to `TunRelay` over `SinkholeConnector`: the handshake is answered **on the phone**, the first message is read, and the connection is closed (FIN) about 1.5 s after it (8 s when the app sends nothing). **Nothing is forwarded**; no socket is opened. DNS is still answered NXDOMAIN; ports 53/853, the phone's own resolver and IPv6 stay dropped. Each attempt stays one blocked SYN, so the blocked list means what it did. | `netcapture/SinkholeConnector.kt`, `quarantine/QuarantineSinkhole.kt` (`SinkholeTap`), `XrayVpnService.startQuarantine` |
| Quarantine report | `observations.connections` (with `connectionsRead`; null for older reports), summary fields (connections answered, server names, names never looked up, plain-HTTP requests, personal-data fields, ECH count), timeline `CONNECTION` lines, new limits. The screen lists "Connections answered on the phone" with a tracker-catalogue match, and signals for unencrypted personal data and names never looked up. | `QuarantineObservations`, `QuarantineSummary`, `QuarantineReport`, `QuarantineScreen` |
| Personal-data fields | `CleartextPersonalData` reads an already-redacted request: e-mail and phone shapes anywhere (query, path, header); UUIDs, 15-digit numbers and coordinates only under a matching field name; any other value under a personal field name as name-only evidence. Names match across styles (userEmail, e_mail, EMAIL_ADDRESS); bare `name`, `id`, `long`, `phone_model`, `advertiser_id` do not count. Field names and kinds only. | `netcapture/CleartextPersonalData.kt` |
| C16 | **"Personal data in an unencrypted (HTTP) request"**, High, scored. OBSERVED when a value had a personal-data shape, INFERRED when only field names matched. The scored, legally mapped form of the network finding "Sensitive value in an unencrypted request" (which stays in Network behaviour). Legal mapping: DPDP **s.8(5)** (reasonable security safeguards to prevent a personal data breach; the DPDP Rules 2025 name encryption among them) as CONTEXT under the India lens, not a verdict; no penalty band. Fix template `net-https-only`. Request bodies are not read, so personal data in a body is not counted. | `rules/ExtendedChecks.kt`, `model/Model.kt` |
| V1 lines | "Some lookups may be hidden" now also lists, for full-relay plans, servers the connections named themselves at addresses no DNS answer gave and that no lookup asked for (the app's own DNS such as DNS over HTTPS, or cached or built-in addresses), Encrypted Client Hello connections, and unnamed QUIC connections. INFO. | `ExtendedChecks.connectionLines`, `netcapture/ConnectionSummary.kt` |
| Results | Under "Where it connects": **Connections** (this audit's flows: destination, port, protocol, consent phase, "no lookup seen", tracker match, redacted request line) and **Connections (quarantine)** (the latest saved quarantine run for the same package, dated, marked as a separate blocked run outside the score). | `ui/screens/ConnectionsCards.kt` |
| CLI | `traceveil-ci check` prints `Network:` (and a `network` JSON object) for bundles that recorded their capture depth; nothing for older bundles. C16 is reported NOT ESTABLISHED when no plan captured connections. | `cli/CheckReports.kt`, `cli/Gate.kt` |

**LeakyShop.** Its stand-ins send no plain HTTP (a TLS handshake to graph.facebook.com after Reject, DNS over HTTPS to
1.1.1.1 by address, a bare TCP connection), so C16 never fires for it and the 21/100 and 100/100 regressions are
unchanged; `ConnectionChecksTest` adds those flows to the leaky shapes and checks the score stays the same.

**Scoring science.** C16 is a 14th scored check: the exhaustive tests now enumerate 629,856 assignments and the gate moves
10 (see the update note in [SCORING_SCIENCE.md](SCORING_SCIENCE.md)).

### Fixtures and their sources

- RFC 9001 Appendix A.2, the protected 1200-byte client Initial (`core/src/test/resources/netcapture/rfc9001-client-initial.hex`,
  copied byte for byte from rfc-editor.org; the file header names the section). The test also checks the Appendix A.1 key,
  IV and header-protection key.
- Hand-built TLS hellos (`NetTestData.clientHello`), HTTP requests and TCP segments (`PacketCodec`), labelled as such.
  No device capture is committed.

### Normal audits and "IPs never resolved through us"

The full-relay capture already sees every IPv4 connection and marks `noLookup` when no DNS answer gave the address, so
`/proc/net` and `TrafficStats` are not used for this: `/proc/net` is not readable by apps targeting Android 10+, and
`TrafficStats` gives per-app byte totals, not destinations.

### Not done, and why

- **IPv6** is not relayed (audits give the tunnel no IPv6 route; quarantine keeps IPv6 attempts address-only).
- **QUIC versions other than 1** (for example v2, RFC 9369) are not decrypted; they stay address-only.
- **Request bodies** are never read, so personal data in a POST body is not counted by C16.
- **Placeholder DNS answers in quarantine** are not implemented: lookups still get NXDOMAIN, so most apps open
  connections only to built-in or cached addresses or their own DoH servers. Answering lookups with a local placeholder
  address would make every looked-up name show its TLS or HTTP first message; it changes what the app is told and needs
  its own review first.
- **Real versus placeholder ECH** cannot be told apart from a hello; such names are labelled "may not be the real server".
- A TLS hello **without SNI** (for example HTTPS to an IP literal such as 1.1.1.1) is recorded as an unnamed connection.

### Phone test steps (need a human; not run by the agent)

1. Install the debug APK and the leaky LeakyShop. Turn Private DNS off. Make sure no other VPN is on.
2. **Quarantine, LeakyShop:** APK Guard or the app picker → LeakyShop → Run in quarantine → Start. Use the app for
   30 s (open it, tap Reject all), then Stop. Expected: names looked up (all "does not exist"); under "Connections
   answered on the phone" one row for **1.1.1.1 · port 443** with no name (the Branch stand-in's DNS over HTTPS, an
   IP-literal TLS hello), and the existing "Tried to reach addresses without looking them up" signal for 1.1.1.1. The
   graph.facebook.com handshake does not happen, because its lookup failed. Nothing else in the phone's network changes
   for other apps.
3. **Quarantine, a real app** the team agreed on (not banking, payments or chat): same steps. Rows show server names read
   from TLS or QUIC hellos for connections to built-in or cached addresses or the app's own DNS-over-HTTPS server; a
   plain-HTTP row, if any, shows its request line with values replaced. Export the report (JSON) and check that no
   value (e-mail, phone, name) appears in it.
4. **Full check, LeakyShop (deep capture on):** run a Full check. Results → Where it connects → **Connections**: expect
   graph.facebook.com (TLS, after Reject) and 1.1.1.1:443 (no lookup). The score stays 21/100 (no C16). Results →
   **Connections (quarantine)** shows the run from step 2 with its date.
5. `traceveil-ci check` on the exported JSON of step 4: a `Network:` line with the counts; no C16.
