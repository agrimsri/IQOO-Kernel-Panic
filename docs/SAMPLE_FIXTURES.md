# TraceVeil sample audit fixtures

The sample audits let the team develop and inspect evidence screens without a VPN, an AI model or a live DNS response. Every saved sample is marked `sample=true`. These are synthetic examples, not observations of the phone or a real app.

## Open a sample

On Home, **Open sample audit** opens the original sample. **More sample evidence** opens a picker with **Alias sample** and **Alias + IP sample**. The picker explains that the addresses do not identify real server locations. Results display **Sample data**; saved Markdown and generated PDF reports begin with a synthetic-evidence warning.

The same fixtures are available in Kotlin:

```kotlin
SampleAudit.sessions(nowMs)
SampleAudit.sessions(nowMs, SampleAudit.Variant.ALIAS)
SampleAudit.sessions(nowMs, SampleAudit.Variant.ENDPOINTS)
```

All variants contain the existing SILENT, REJECT and ACCEPT plans with six lookup events per plan. The default retains its original identifiers, timestamps, hosts and score of 8. That in-app sample includes additional inferred notice findings; it is not the separate live LeakyShop regression with a target score of 21.

## Evidence fields

| Variant | Session identifiers | Alias and endpoint fields |
| --- | --- | --- |
| Basic sample | `sample-c`, `sample-b`, `sample-a` | Original hosts; empty `cnameChain` and `answerIps` |
| Alias sample | `sample-alias-c`, `sample-alias-b`, `sample-alias-a` | Four synthetic aliased events; no endpoint addresses |
| Alias + IP sample | `sample-endpoints-c`, `sample-endpoints-b`, `sample-endpoints-a` | The same aliases plus documentation addresses on three hosts |

The two additional variants replace each `graph.facebook.com` lookup with `metrics.leakyshop.example` and set `cnameChain` to `["metrics.leakyshop.example", "graph.facebook.com"]`. In the REJECT plan, the user choice remains at 8.3 seconds and the aliased lookups remain at 0.8 and 9.4 seconds, providing events before consent and after rejection.

The endpoint variant sets:

- `api.leakyshop.example`: `192.0.2.10`
- `app-measurement.com`: `198.51.100.20`
- `metrics.leakyshop.example`: `203.0.113.30`, `2001:db8::30`

Other hosts keep an empty address list: their endpoint is unknown. These addresses come from documentation-only ranges in the [IANA IPv4 registry](https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry.xhtml) and [IANA IPv6 registry](https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry.xhtml). The fixture does not resolve hosts, infer countries or bundle a geography dataset.

`disguisedVia` remains unset; Agrim's classifier owns that output. `captureHealth` remains null for every variant because there was no measured capture. No zero-byte measurement or confidence grade is fabricated by these fixtures.

## Integration boundaries

Agrim can feed the alias fixture into classification tests after A3 is integrated. Yashvi can use the endpoint fixture for parsing and UI tests; geography tests should inject a test resolver rather than assign real countries to documentation addresses. Health and Network Proof acceptance still require A4 counters and a real device audit.

The app-owned `SampleReport` prefix preserves the synthetic notes through the saved-report export path without changing the shared core report builder. Existing report-body assertions about zero bytes are not measurements; replacing those assertions belongs to the measured Network Proof/report integration. Sample findings and scores must not be presented as legal conclusions or live capture evidence.

## Verification

`SampleAuditTest` covers the unchanged default, aliases across all plans, rejection timing, known and unknown endpoint fields, JSON round trips, unknown health and report-warning preservation. Run:

```text
gradlew.bat --offline :core:test :app:testDebugUnitTest :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --rerun-tasks --console=plain
```

