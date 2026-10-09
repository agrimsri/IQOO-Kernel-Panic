# Y3 endpoint map: design notes and hand-off

Status: implemented on `yashvi/phone-experience`; mount, theme mapping and owner data are pending (below).

## What it shows

One card, two modes, the same row layout in both (title, evidence chip, labelled facts, each fact with its own chip):

| Mode | Question it answers | Facts |
| --- | --- | --- |
| **Source country / DNS** | Where were this audit's tracker lookups answered? | Hosts by consent phase (Observed); DNS answers and alias chains (Observed); the country the IP table places the address in (Inferred); the country the address block is registered in, when different (Inferred); unknown hosts with the reason (Unknown) |
| **Owner / legal authority** | Who runs it, and what law may apply? | Where its lookups were answered (Inferred); domain registration (always "Not looked up", Unknown); company headquarters (Sourced only with a cited source, else "Not established"); legal authority: DPDP Act section 3, quoted (Sourced) |

Four evidence classes, shown by label and by shape (Unknown is hollow), never colour alone: **Observed** (seen in this audit's DNS), **Inferred** (derived by a lookup or catalogue match), **Sourced** (a cited document), **Unknown** (no evidence; never filled with a default).

Server location, domain registration, company headquarters and applicable law are separate facts. No one is derived from another, and the UI never calls any of them a transfer or a violation.

## Data it depends on

- **IP to country:** the GeoLite2-Country file on the phone (`files/geo/`, pushed with `adb`). Without it the card still lists every lookup, with "No IP table available" instead of countries. `country` (placed) and `registered_country` stay separate; anycast addresses often have only a registration, which is shown as a registration, not a location.
- **Owner headquarters:** `core/src/main/resources/geo/owners.json`. **Currently empty**, so every headquarters shows "Not established". An entry loads only with a valid ISO country, an `https` source, a source kind and an `asOf` date; any bad entry fails the whole load (tested).
- **DNS answer status:** `main` has no `NetEvent.answerStatus`, so an empty `answerIps` cannot yet separate "reply not read" from "reply had no address" and is shown as *No address recorded*. Agrim's draft PR #3 adds `answerStatus` and `rcode`; when it merges, one adapter change in `EndpointGeoAggregator` can split those cases.

## For Nitya (mount and theme)

1. Mount on Results (one call; it reads the optional GeoLite2 file itself and does no network I/O):
   `EndpointMapCard(bundle.sessions, graph.catalog())` from `com.dpdpxray.app.ui.screens.map`.
2. No manifest, Gradle or navigation change is needed for the card.
3. Theme (optional): the ten brand colours are in `Brand` (`ui/screens/map/BrandPalette.kt`). To use them app-wide, map in `XrayTheme`'s `darkColorScheme`: `primary = Brand.IcyAqua`, `secondary = Brand.TangerineDream`, `tertiary = Brand.BlushRose`, `surfaceVariant = Film.raised`. Each text/background pair used by the card is checked at 4.5:1 and each graphic at 3:1 in `BrandPaletteTest`.

## Network Proof wording (Y2), preserved

Agrim's PR #3 notes that the byte counters, the 28-byte header per datagram, the samples and the rule not to clamp unknowns to zero are in that PR, and that it has not been measured whether `TrafficStats` also counts TraceVeil's tunnel writes or other traffic. Network Proof therefore says, in every measured result: the per-app counter may include other TraceVeil traffic and possibly TraceVeil's own writes into the tunnel, which has not been measured; the remainder is an estimate, not proof; unexplained bytes do not show that data was sent anywhere; a zero or near-zero figure does not prove the absence of uploads. Tests assert each phrase and that no wording mentions theft or leaks.

## Not done, needs a decision

- **Owner headquarters data.** A reliable approach is: only entries with a cited source, preferring a company document, then a public registry, then Wikidata (CC0, labelled "community database, not verified"). Researching ~30 vendors needs read-only requests to public sources. Not run; waiting for approval.
- **GeoLite2 redistribution.** Pushing the database to a demo phone is covered by the instruction to use it; committing it to the public repository is not, per the EULA.
