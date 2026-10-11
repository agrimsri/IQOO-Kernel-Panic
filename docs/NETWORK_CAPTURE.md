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

`CaptureBus.deepCapture` (default `true`) selects full-relay for new audits; `false` gives the previous DNS-only
tunnel byte for byte. Saved audits without `flows` open exactly as before.

## What is captured

| Traffic | Captured |
|---|---|
| DNS to the audited app's resolver address | Question, answers, CNAME chain (unchanged) |
| DNS sent straight to another resolver (UDP 53) | Question recorded, packet forwarded unchanged |
| TCP | Destination, port, timing, bytes up/down per phase |
| TLS (HTTPS) | Server name (SNI), ALPN; **not** the request contents |
| TLS with Encrypted Client Hello | Flagged `ECH_HIDDEN`; destination address only |
| Plain HTTP/1.x | Method, host, path+query and an allow-list of headers, **redacted**; response status |
| QUIC / UDP | Address, port, bytes; QUIC labelled by the long-header bit |
| DoH / DoT | Visible as ordinary TLS flows; flagged by port 853 or a known resolver name/address |
| Direct-IP connections | Recorded; flagged "no lookup" when no DNS answer named the address |

Never stored: request or response bodies, cookies, `Authorization`, tokens. Redaction replaces e-mail addresses, phone
numbers, UUID-style device ids, 15-digit ids, coordinates and long tokens, and records only the **kind** of value seen.

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
