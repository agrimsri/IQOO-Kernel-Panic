# Y3 endpoint map: design notes and hand-off

Status: implemented on `yashvi/phone-experience`. Mounting on Results is Nitya's. App-wide theme changes are separate and not made here.

## What it shows

One card, two modes, the same row layout in both (title, evidence chip, labelled facts, each fact with its own chip):

| Mode | Question it answers | Facts |
| --- | --- | --- |
| **Source country / DNS** | Where were this audit's tracker lookups answered? | Hosts by consent phase (Observed); DNS answers and alias chains (Observed); the country the IP table places the returned address in (Inferred); the country the address block is registered in, when different (Inferred); hosts with no country, with the reason and the resolver's actual replies (Unknown / Observed) |
| **Owner / legal authority** | Who runs it, and what is the legal context? | Where its lookups were answered (Inferred); domain registration (always "Not looked up", Unknown); company location as stated by the company (Sourced only with a cited source, else "Not established"); legal context: DPDP Act section 3, quoted (Sourced) |

Four evidence classes, shown by label and by shape (Unknown is hollow), never by colour alone: **Observed** (seen in this audit's DNS), **Inferred** (derived by a lookup or catalogue match), **Sourced** (a cited document), **Unknown** (no evidence; never filled with a default).

Server location, IP registration, domain registration, the company's stated location and legal context are separate facts. None is derived from another, and the wording never says transfer, violation, breach, theft or "safe". Countries are described as *inferred from the IP addresses the resolver returned*, not as where data went or proof of where a server is.

## DNS answers against Agrim's A4 contract

Merged from `agrim/evidence-engine` (A4: `NetEvent.answerStatus` and `rcode`). Each lookup's outcome is kept per query type, and `null` is never treated as a result.

| Recorded outcome | Shown as | Counts as a finding? |
| --- | --- | --- |
| `COMPLETE`, addresses | Placed in a country (inferred) | yes |
| `COMPLETE`, no address, RCODE 3 | Name does not exist (NXDOMAIN) | yes, observed |
| `COMPLETE`, no address, RCODE 0 | No address records (NODATA) | yes, observed |
| `COMPLETE`, no address, other RCODE | Resolver returned an error | yes, observed |
| `PARTIAL`, addresses | Placed, **marked uncertain** ("cut short, list may be incomplete") | partly |
| `PARTIAL`, no address | Partial reply, no address: says nothing either way | **no** |
| `MALFORMED` / `MISMATCHED` | Reply not usable: says nothing about the name | **no** |
| `NO_REPLY` | No reply received | **no** |
| `null` | Reply not recorded (older audit, or not yet arrived) | **no** |

When one name has several lookups (A and AAAA), a failed one never erases a successful one, and the screen lists every type's outcome ("A: answered; AAAA: no reply from any resolver"). Addresses in private, loopback, link-local, carrier-grade NAT, multicast, documentation and other special-purpose ranges are never placed; they are named as "private or reserved" because such an answer is often a block or a sinkhole.

## Data it depends on

- **IP to country:** the GeoLite2-Country file on the phone (`files/geo/`, pushed with `adb`); see `docs/ATTRIBUTION.md` for the terms, version, update duty and the decision to keep it out of Git. Without it the card still lists every lookup, with "No IP table available". `country` (placed) and `registered_country` stay separate; anycast addresses often have only a registration, shown as a registration, not a location. The card prints MaxMind's attribution and the database build date, and notes when the file is more than 30 days old.
- **Company locations:** `core/src/main/resources/geo/owners.json`. It holds researched entries for the four demo companies only: Google, Meta, AppsFlyer (with its three-entity caveat) and CleverTap (unresolved, with the reason). Each location carries a cited https source, a source kind, a date, the source's own quote and a basis ("stated address" or "described as headquarters"). A bad entry fails the whole load (tested). Everything else shows "Not established".
- **Legal context:** `LegalContext.DPDP_INDIA` quotes section 3 and says it is not a finding. It is India-specific by design; `MapPresenter.view(..., legal = ...)` accepts another jurisdiction's context without changing anything else, so the same evidence interface can serve international users.

## For Nitya (mount and theme)

1. Mount on Results (one call; it reads the optional GeoLite2 file itself and does no network I/O):
   `EndpointMapCard(bundle.sessions, graph.catalog())` from `com.dpdpxray.app.ui.screens.map`.
   Signature: `fun EndpointMapCard(sessions: List<AuditSession>, catalog: TrackerCatalog, modifier: Modifier = Modifier, owners: OwnerRegistry = ...)`.
2. No manifest, Gradle or navigation change is needed for the card. AI Lab has a stopgap copy with an audit chooser; remove it when the card is on Results.
3. Theme (separate change, optional): the ten brand colours are in `Brand` (`ui/screens/map/BrandPalette.kt`). To use them app-wide, map in `XrayTheme`'s `darkColorScheme`: `primary = Brand.IcyAqua`, `secondary = Brand.TangerineDream`, `tertiary = Brand.BlushRose`, `surfaceVariant = Film.raised`. Each text/background pair used by the card is checked at 4.5:1 and each graphic at 3:1 in `BrandPaletteTest`.
4. Record the GeoLite and world map decision in the team register (`ATTRIBUTIONS.md`); the entries are in `docs/ATTRIBUTION.md`.

## For Agrim (A5)

One ownership mapping, not two. See "Coordination with A5" in `docs/ATTRIBUTION.md`. In short: when `hqCountry` and `hqSource` land in `trackers.json`, keep this file's rules (https source, kind, date, quote, and a basis that separates "described as headquarters" from "stated address") and the registry loader will read them.

## Network Proof wording (Y2), preserved

Agrim's PR #3 notes that the byte counters, the 28-byte header per datagram, the samples and the rule not to clamp unknowns to zero are in that PR, and that it has not been measured whether `TrafficStats` also counts TraceVeil's tunnel writes or other traffic. Network Proof therefore says, in every measured result: the per-app counter may include other TraceVeil traffic and possibly TraceVeil's own writes into the tunnel, which has not been measured; the remainder is an estimate, not proof; unexplained bytes do not show that data was sent anywhere; a zero or near-zero figure does not prove the absence of uploads. Tests assert each phrase and that no wording mentions theft or leaks.

## Not covered by automated tests

The Compose layout itself (0, 1 and 20 countries, the map, the mode switch) has no JVM UI test, because the project has no Compose or Robolectric test setup. The data shown in those cases (0, 1 and 20 country rows, empty states, repeats) is tested in the presenter; the rendering was checked by eye on the iQOO 15 with the audits available at the time, which had no DNS answer addresses, so a filled map with real countries has not yet been seen on the phone.
