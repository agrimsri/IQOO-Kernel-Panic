# Scoring science: how the consent-readiness score is built and verified

This page states the TraceVeil score as mathematics, proves its main properties, and lists the tests that check each
property against the production code. It also defines the **robustness interval** (how far AI or documentation readings
could move a result) and reports a sensitivity analysis of the thresholds and of other ways to score.

Nothing on this page changed a weight, threshold or band rule. Every number below comes from a test in
`core/src/test/kotlin/com/dpdpxray/core/rules/science/`, which writes its results to `core/build/reports/score-science/`.

> **Update, 11 October 2026: C16 joins the scored checks.** C16 (personal data in an unencrypted HTTP request, High,
> observed or inferred; see [NETWORK_CAPTURE.md](NETWORK_CAPTURE.md)) is a 14th check that can carry points. The same
> tests, unchanged in method, now enumerate 2³ × 3⁷ × 2² × 3 (C14) × 3 (C16) = **629,856** assignments (120,932,352 finding
> sets with the 192 zero-weight combinations) and pass: 7,243,344 lattice edges; 8,188,128 zero-weight sets in the
> factored run; 5,038,848 lens views; all 33 check variants produced by the real engine (W1 and W2, the website
> check's zero-weight cookie notes, are the two added since; they are not in the app score space). Bands: Ready 643, Needs work
> 24,452, At risk 604,761. The gate now moves exactly **10** assignments: an observed C1, NC, C3, C4 or C16 as the only
> finding with points, with or without C14 Info. No weight, threshold or band rule changed. The tables and figures below
> are the 10 October measurements of the 13-check space, kept as recorded; the new counts are written to
> `core/build/reports/score-science/` on every run.

## 1. Summary

| What | Result |
|---|---|
| Property tests (13 properties, seeded, printed seed) | 25,000 random finding sets per property, 0 failures |
| Every combination of the checks that carry points, in every severity and evidence label the engine can give | 209,952 assignments, 0 failures |
| Every edge of that lattice (add a finding, confirm an inferred one) | 2,204,496 edges, score and band never improve |
| With the 7 checks that never carry points | 40,310,784 sets in all; factored check by default; the full run passed twice |
| Engine witness and fuzz | all 29 check variants produced by the real engine; 3,000 random audits emit nothing else, each check at most once |
| Legal lens views | 1,679,616 views, a lens score is never below the headline score |
| Flaws found | none: every property held. Two design observations are listed in section 9 |

The score is a **penalty from 100**, like the Mozilla HTTP Observatory; a **cap on the top band** when a serious problem is
seen, like the grade caps in the Qualys SSL Labs rating guide; and **four severities**, like the CVSS qualitative scale.
Those sources describe their own scores; none of them reviews or endorses TraceVeil.

## 2. The score in one page

A finding *f* has a severity weight *w(f)* and an evidence label *e(f)*:

| Severity | Critical | High | Medium | Low | Info |
|---|---:|---:|---:|---:|---:|
| weight *w* | 30 | 15 | 8 | 3 | 0 |

| Evidence | OBSERVED (seen on the phone) | INFERRED (AI screen review or SDK documentation) |
|---|---:|---:|
| multiplier *m* | 1 | ½ |

For a set of findings *F*:

- penalty **P(F) = Σ_{f ∈ F} w(f) · m(e(f))**
- score **s(F) = ⌊ max(0, 100 − P(F)) + ½ ⌋** (Kotlin `roundToInt()` is `Math.round`, which rounds halves up)
- serious problem seen **σ(F) = ∃ f ∈ F : e(f) = OBSERVED ∧ w(f) ≥ 15**
- band **b(F) = Ready** if s ≥ 85 and not σ; **Needs work** if s ≥ 60 otherwise; **At risk** if s < 60
- inconclusive **ι(F) = V0 ∈ F ∨ (U1 ∈ F naming tracker entities)**; then there is no score and the band is Inconclusive.

Bands are ordered **At risk ≺ Needs work ≺ Ready**. Inconclusive is outside the order.

Each check fires at most once per audit. The checks and the variants the engine can give them
(`CheckVariants.kt`, verified by `CheckVariantWitnessTest`):

| Check | Severity | Evidence the engine can give |
|---|---|---|
| C1, NC | High | OBSERVED |
| C2 | Critical | OBSERVED |
| C3, C4 | High | OBSERVED or INFERRED (tree-confirmed field or not) |
| C5, C6, C7 | Medium | OBSERVED or INFERRED |
| C8 | Low | OBSERVED or INFERRED |
| C9 | Critical | OBSERVED (declared child-facing) or INFERRED (screen review signal) |
| C10 | Medium | INFERRED (vendor documentation) |
| C13 | Low | INFERRED (vendor documentation) |
| C14 | Low or Info | OBSERVED |
| V0, U1, C11, C12, C15, V1 | Info | OBSERVED (U1 with or without tracker entities) |
| S1 | Info | INFERRED |

## 3. Properties and short proofs

Each property is proved here and tested in `ScorePropertiesTest` (P-numbers) and on the whole enumerated space in
`ScoreExhaustiveTest`.

**P1 Bounds.** 0 ≤ s(F) ≤ 100. *Proof:* w ≥ 0 and m > 0, so P ≥ 0 and max(0, 100 − P) ∈ [0, 100]; rounding keeps it in
[0, 100].

**P2 Determinism, P3 permutation invariance.** P is a sum over the findings and σ, ι are "there exists", so the order of
findings cannot matter. In floating point this is exact too: every term w·m is a multiple of ½ and every partial sum is
a small multiple of ½, which a double represents exactly, so addition order cannot change the result.

**P4 Monotonicity.** If F ⊆ G and G is conclusive, s(G) ≤ s(F). *Proof:* P(G) = P(F) + P(G \ F) ≥ P(F), and
x ↦ ⌊max(0, 100 − x) + ½⌋ is non-increasing.

**P5 Band monotonicity.** If F ⊆ G and G is conclusive, b(G) ⪯ b(F). *Proof:* b is non-decreasing in s and non-increasing
in σ; s(G) ≤ s(F) (P4) and σ(F) ⇒ σ(G).

**P6 Evidence monotonicity.** Turning an INFERRED finding into an OBSERVED one never improves score or band: P grows by
w/2 and σ can only become true.

**P7 The gate.** σ(F) ⇒ b(F) ≠ Ready, by definition. *Why it exists:* one observed High alone gives exactly
100 − 15 = 85, the Ready threshold, so points alone would call such an app Ready. Over all 209,952 assignments the gate
changes the band of exactly **8**: an observed C1, NC, C3 or C4 as the only finding with points (with or without the
Info variant of C14). In every other case points already keep a serious problem out of Ready.

**P8 Inferred findings never decide alone, but always cost.** If no observed finding has w ≥ 15, then
b(F) = Ready ⇔ s(F) ≥ 85. And since the smallest positive weight is 3, an inferred finding costs at least 1.5 points:
adding one lowers the score by at least 1 unless the score is already 0.

**P9 Exact half weight.** P(F ∪ {f_INFERRED}) = P(F) + w(f)/2. Two inferred findings of one severity cost exactly one
observed finding of that severity. The test compares every score with an integer-only oracle written independently:
with p₂ = Σ (2w for observed, w for inferred) in half-points, s = max(0, ⌊(201 − p₂) / 2⌋).

**P10 Rounding.** Weights are integers and the multiplier is 1 or ½, so 100 − P is a whole or a half number. Halves
round up (towards +∞): 92.5 → 93 (one inferred High) and 7.5 → 8 (LeakyShop with the AI screen reading:
100 − 75 − 17.5). The band is decided on the shown, rounded score, so the number and the band never disagree.

**P11 Inconclusive precedence.** If ι(F), there is no score, whatever else F contains. ι is "there exists", so it is also
monotone: adding findings never makes an inconclusive result conclusive. U1 that names no tracker is only a note.

**P12 Empty set.** s(∅) = 100 and b(∅) = Ready (zero-weight notes keep it).

**P13 Robustness sandwich** (section 6): worst ≤ score ≤ best, with the bands in the same order.

**Dominance.** Each weight is larger than all lower weights together: 30 > 15 + 8 + 3 = 26, 15 > 8 + 3 = 11, 8 > 3.
*Meaning:* one finding of a severity costs more than one finding of each lower severity combined (observed: one Critical
gives 70, one High + one Medium + one Low gives 74). It is **not** a lexicographic order: two Highs (70) tie one
Critical, and four Mediums (68) score below one Critical. Section 7 measures how much this matters.

**Corollary (legal lenses).** A lens view scores only the findings it does not mark not-applicable, and no lens drops V0
or U1. By P4 and P5 a lens score is never below the headline score and its band never worse; checked on all
1,679,616 lens views of the enumerated space.

## 4. Exhaustive verification

**The space.** The 13 checks that can carry points (C1, NC, C2, C3–C9, C10, C13, C14) are each absent or in one of their
engine variants: 2³ (C1, NC, C2) × 3⁷ (C3–C9: absent, observed, inferred) × 2² (C10, C13) × 3 (C14: absent, Info, Low)
= **209,952** assignments, numbered in mixed radix. The 7 checks that never carry points add 2 (V0) × 3 (U1: absent, no
trackers, trackers) × 2⁵ (S1, C11, C12, C15, V1) = 192 combinations, so the whole space has **40,310,784** finding sets.

The space is a superset of what the engine can reach (it also contains, for example, NC together with C2, which the
engine never emits together), so an invariant proved on it holds on every real audit. Its counts are counts of
combinations, not frequencies in real apps.

**What is checked.**

1. Every one of the 209,952 assignments: bounds, the integer oracle, the band rule and the gate.
2. Every lattice edge (2,204,496): adding a finding, confirming an inferred one, or raising C14 from Info to Low never
   improves score or band. By transitivity this covers every superset.
3. The zero-weight checks. `ScoreCalculator` reads a set only through three per-finding folds (any V0 or U1 with trackers;
   the sum of w·m; any observed serious finding), and a zero-weight check adds 0 to the sum and is never serious. The
   default run therefore checks each assignment with each of the 8 zero-weight variants alone, with all of them together
   (with and without V0 / U1 with trackers) and with one seeded random combination: 2,729,376 sets in about 2–5 s. The
   full product of all **40,310,784** sets was also run (`TRACEVEIL_SCORE_FULL=1`; 80.4 s and 13.8 s in two runs on the
   team laptop) and passed both times on 10 October 2026.

**Band distribution** over the 209,952 assignments (each conclusive one appears 64 times in the full space, so the
proportions are the same there):

| Band | Assignments | Share | Lowest score | Highest score |
|---|---:|---:|---:|---:|
| Ready | 559 | 0.27% | 85 | 100 |
| Needs work | 14,309 | 6.82% | 60 | 85 |
| At risk | 195,084 | 92.92% | 0 | 59 |

Other counts: 100 distinct scores (98 is never reachable: it needs a penalty of 2 or 2.5 points, which no combination gives); 39,830 assignments
clamped at 0; 85,367 with a half point rounded up, of which 45 reach Ready only by rounding 84.5 to 85 and 384 reach
Needs work by rounding 59.5 to 60. A Needs work score of 85 is always the gate (P7).

## 5. Property-based tests

`ScorePropertiesTest` uses `kotlin.random.Random` with seed 20261010 (printed on every run; set `TRACEVEIL_SCORE_SEED`
to another value to explore, or to the printed one to reproduce a failure). Each property runs on 12,500 sets from each
of two generators:

- **realistic:** each check at most once, in a variant the engine can give;
- **free:** 0 to 25 findings with any check, severity and evidence label, duplicates allowed, penalties far above 100.

Every assertion message carries the seed, the set number and the set. Counts are written to `properties.json`.

`CheckVariantWitnessTest` ties the table above to the real engine: a small audit for each of the 29 variants makes
`RulesEngine` emit exactly that check, severity and evidence; and 3,000 seeded random audits through the engine emit
only table variants, never a check twice, with `result.score` equal to `ScoreCalculator.compute(findings)`.

## 6. The robustness interval

**Question it answers:** "How much could AI or documentation readings move this result?"

Split F into its observed part O and inferred part I. Define

- **worst case** W = O ∪ {f confirmed as OBSERVED : f ∈ I} — every inferred finding counted at full weight, and an inferred
  High or Critical then closes the Ready gate like an observed one;
- **best case** B = O — every inferred finding dropped as wrong.

`ScoreCalculator.robustness(findings)` returns s(W), s(B), their bands, `bandStable` and the inferred checks. Both ends are
computed by `compute` itself. It never changes the score, is never stored (no change to the `traceveil.audit.v1`
format) and is null for an inconclusive result (V0 and U1 are OBSERVED, so neither end can change that).

**R1 Sandwich.** s(W) ≤ s(F) ≤ s(B) and b(W) ⪯ b(F) ⪯ b(B). *Proof:* B ⊆ F (P4, P5) and W is F with inferred findings
confirmed (P6).

**R2 Stable band.** The band is stable exactly when b(W) = b(B), because b(F) is squeezed between them.

**R3 Whole-number ends.** Observed weights and full inferred weights are integers, so
s(B) = max(0, 100 − ΣO w) and s(W) = max(0, 100 − ΣO w − ΣI w) need no rounding.

**R4 Midpoint.** Before clamping and rounding, the score is exactly the midpoint of the interval:
100 − ΣO w − ½ ΣI w = ½ (s(B) + s(W)). When s(W) > 0, s(F) = ⌈(s(W) + s(B)) / 2⌉. The half weight is the expected
penalty if each inferred reading were right with probability ½; that is a reading of the rule, not a measured accuracy.

R1–R4 hold on all 209,952 assignments and on 25,000 random sets.

**Examples (real fixtures through the engine, `ScoreRobustnessTest`):**

| Audit | Observed | Inferred | Score | Worst | Best | Band |
|---|---|---|---:|---:|---:|---|
| LeakyShop, rules only | C2 30, C1 15, C3 15, C4 15 = 75 | C10 8 | 21 | 17 | 25 | stays At risk |
| LeakyShop, with the AI screen reading | the same 75 | C5, C6, C7, C10 8 each, C8 3 = 35 | 8 (7.5 rounded up) | 0 (−10 clamped) | 25 | stays At risk |
| LeakyShop fixed build | none | none | 100 | 100 | 100 | not shown |
| Recorded Facebook Quick check (audit-1791603680860) | none with points (U1 note, no trackers) | none | 100 | 100 | 100 | not shown |
| Child audience read only by the screen review | none | C9 30 | 85, Ready | 70, Needs work | 100, Ready | could change |

**Across the enumerated space:** 192,180 of 209,952 assignments have an interval that can move; for 45,874 the band could
change. Of the 559 Ready assignments, 535 contain an inferred finding with points and **429 could leave Ready** if those
readings were confirmed. That is why the interval is shown: Ready is often decided with AI readings in the mix.

**Where it appears.**

- Result screen, under "Consent readiness" (English or Hindi, following the guide language), only when inferred findings
  can move the score and the saved score is the one today's rules give:
  - "AI and documentation readings can move this between 0 and 25 · the band stays At risk" (calm colour);
  - "AI and documentation readings can move this between 70 and 100 · the band could change (Needs work to Ready)" (warn
    colour);
  - behind "More": "The 5 findings marked INFERRED (C5, C6, C7, C8, C10) come from AI screen review or SDK documentation
    and count half. If all were confirmed on the phone the score would be 0 (At risk); if all were wrong, 25 (At risk).
    The score shown does not change."
  - Hindi: "AI और दस्तावेज़ों से लिए गए निष्कर्ष इस स्कोर को 0 से 25 के बीच ले जा सकते हैं · श्रेणी जोखिम में ही रहती है".
- `traceveil-ci check`: a `Range:` line and a `robustness` JSON object next to `currentResult` ([CI mode](CI_MODE.md)).
- The copilot's score answer ends with the same range sentence.

## 7. Sensitivity analysis

All on the 209,952 assignments (`ScoreSensitivityTest`).

**Thresholds.** For the Ready threshold R ∈ 75..95 and the Needs-work threshold N ∈ 50..70 (gate unchanged), count the
assignments whose band differs from R = 85, N = 60. Since R > N always, R moves only the Ready / Needs-work line and N
only the Needs-work / At-risk line, so the 21 × 21 grid is exactly the sum of its two margins (checked in the test).

| R | 75 | 80 | 83 | 84 | **85** | 86 | 87 | 90 | 95 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| changed | 1,724 | 720 | 258 | 118 | **0** | 116 | 174 | 397 | 520 |

| N | 50 | 55 | 58 | 59 | **60** | 61 | 62 | 65 | 70 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| changed | 16,741 | 7,485 | 2,911 | 1,495 | **0** | 928 | 2,220 | 5,253 | 9,260 |

*Reading:* moving either threshold by one point changes at most 1,495 assignments (0.71%); moving R by one changes about
0.06%. The counts grow smoothly with the distance, with no cliff, so 85/60 are not a knife edge. They are also not a
special plateau: any nearby pair would behave similarly. R = 85 has a structural reason: it is 100 minus one High, so a
single observed High sits exactly on the line (and the gate decides), while R = 86 would also push a lone inferred
Critical (85) out of Ready.

**Alternative schemes.** Each scheme gives every assignment a "badness". Agreement is Kendall's τ_b (Kendall 1938; ties:
Kendall 1945), implemented in the test with Knight's O(n log n) method and checked against direct pair counting, against
a **severity-first lexicographic reference**: compare by the number of observed Critical findings, then observed High,
Medium, Low, then inferred Critical, High, Medium, Low (6,720 distinct keys; 22,039,816,176 pairs). The last column counts
lattice edges (add a finding, confirm an inferred one) where the scheme says a worse set is better.

| Scheme | τ_b | Distinct values | Tied pairs | Worse looks better |
|---|---:|---:|---:|---:|
| **TraceVeil score** (100 − P, clamped at 0, rounded) | **0.7361** | 100 | 4.60% | 0 of 2,134,512 |
| TraceVeil band only (3 levels) | 0.3430 | 3 | 86.80% | 0 |
| Unclamped penalty P | 0.7390 | 298 | 0.57% | 0 |
| Weighted average over all checks, Lighthouse-style, 100 · (1 − P / 161) | 0.7390 | 298 | 0.57% | 0 |
| Mean severity of the findings present | 0.6221 | 1,024 | 0.28% | **934,538** |
| Highest severity only | 0.6747 | 8 | 54.37% | 0 |
| Simple count of findings with points | 0.3501 | 14 | 16.08% | 0 |
| Multiplicative, 100 · Π (1 − w·m / 100) | **0.7683** | 4,704 | 0.07% | 0 |

*Reading, honestly:*

- The **multiplicative** scheme agrees best with the severity-first reference (0.768 against 0.736). Each extra finding
  removes a share of what is left, so many small findings count for less next to one large one, and it never saturates.
- The clamp at 0 costs TraceVeil 0.003 (0.7390 → 0.7361): 39,830 assignments tie at 0.
- A Lighthouse-style weighted average over all checks ranks exactly like the unclamped penalty (it is a linear rescaling).
- **Highest severity only** has almost no discordant pairs but cannot tell one Critical from five (54% of pairs tied).
- **Mean severity** is not monotone: in 43.8% of the edges adding a problem improves the result (a Low next to a High
  lowers the average). It fails a basic requirement.
- **Count** ignores severity.

TraceVeil keeps the additive penalty for transparency: anyone can recompute 100 − 30 − 15 − 15 − 15 − 4 = 21 by hand
from the findings list, the bands read as points lost, it is monotone, and the robustness interval has whole-number ends
and an exact midpoint (R3, R4). The multiplicative scheme would buy 0.03 of τ_b at the cost of products of decimals. The
reference ordering is itself a judgement, so τ_b measures agreement with one reasonable ordering, not correctness.

## 8. Research grounding

Each quote was fetched on 10 October 2026 and copied verbatim from the page named.

**Mozilla HTTP Observatory**, *Tests and scoring* —
<https://developer.mozilla.org/en-US/observatory/docs/tests_and_scoring> (also
<https://raw.githubusercontent.com/mozilla/http-observatory/main/httpobs/docs/scoring.md>)

> "All websites start with a baseline score of 100, which is then modified with penalties and/or bonuses resulting from
> the tests."
>
> "The minimum score is 0, and the highest possible score in the HTTP Observatory is currently 145."
>
> "The letter grade ranges and modifiers are essentially arbitrary, however, they are based on feedback from industry
> professionals on how important passing or failing a given test is likely to be."

Grade chart: 100+ A+, 90–99 A, 85–89 A−, 80–84 B+, 70–79 B, 65–69 B−, 60–64 C+, 50–59 C, 45–49 C−, 40–44 D+, 30–39 D,
25–29 D−, 0–24 F.
*In TraceVeil:* the same penalty-from-100 with a floor at 0. *Differences:* no bonuses (an absent finding is not evidence of
good practice, so nothing scores above 100) and three bands instead of thirteen grades. Like the Observatory's, the
weights are expert-set, not fitted to data; section 7 measures how much the bands depend on that choice.

**Qualys SSL Labs**, *SSL Server Rating Guide* (version 2009r) —
<https://github.com/ssllabs/research/wiki/SSL-Server-Rating-Guide>

> "We then apply a series of rules (documented in the Changes section) to handle some aspects of server configuration
> that cannot be expressed via numerical scoring. Most rules will reduce the grade (to A-, B, C, D, E, or F) if they
> encounter an unwanted feature."
>
> "If the server supports TLS 1.0 or TLS 1.1 then grade will be capped to B."

Numeric score: protocol support 30%, key exchange 30%, cipher strength 40%; grades A ≥ 80, B ≥ 65, C ≥ 50, D ≥ 35,
E ≥ 20, F < 20.
*In TraceVeil:* the Ready gate is such a cap: an observed High or Critical holds the band at Needs work whatever the
points. *Differences:* SSL Labs also forces a zero for some certificate problems ("A zero in any category will push the
overall score to zero"); TraceVeil never zeroes a score for one finding, and says Inconclusive instead of giving a number
when evidence is too thin. Only OBSERVED findings cap; an inferred one costs points but never caps alone.

**CVSS v3.1 and v4.0**, FIRST specification documents —
<https://www.first.org/cvss/v3.1/specification-document> (Table 14),
<https://www.first.org/cvss/v4.0/specification-document> (Table 22)

Qualitative rating: None 0.0, Low 0.1–3.9, Medium 4.0–6.9, High 7.0–8.9, Critical 9.0–10.0.

> "The use of these qualitative severity ratings is optional, and there is no requirement to include them when publishing
> CVSS scores. They are intended to help organizations properly assess and prioritize their vulnerability management
> processes."
>
> "Roundup returns the smallest number, specified to 1 decimal place, that is equal to or higher than its input."

*In TraceVeil:* the same four named severities plus Info (CVSS "None"). *Differences:* CVSS computes the severity of one
vulnerability from metrics; TraceVeil gives each check a fixed severity and adds many findings. CVSS rounds its severity
score up (towards more severe); TraceVeil rounds a half point of its readiness score up (towards a better score) —
see section 9.

**OWASP Risk Rating Methodology** — <https://owasp.org/www-community/OWASP_Risk_Rating_Methodology>

> "The 0 to 9 scale is split into three parts:" (0 to <3 LOW, 3 to <6 MEDIUM, 6 to 9 HIGH)
>
> "The model above assumes that all the factors are equally important. You can weight the factors to emphasize the
> factors that are more significant for the specific business."

*In TraceVeil:* risk combines impact with likelihood; TraceVeil combines severity (impact) with evidence certainty
(OBSERVED 1, INFERRED ½). *Differences:* OWASP combines levels through a severity matrix; TraceVeil multiplies and adds,
which keeps the score recomputable by hand.

**Google Lighthouse**, *Performance scoring* —
<https://developer.chrome.com/docs/lighthouse/performance/performance-scoring>

> "The Performance score is a weighted average of the metric scores."
>
> "The scoring distribution is a log-normal distribution derived from the performance metrics of real website performance
> data on HTTP Archive."

Colour bands: 0–49 poor, 50–89 needs improvement, 90–100 good (Lighthouse 10 weights: FCP 10%, SI 10%, LCP 25%, TBT 30%,
CLS 25%).
*In TraceVeil:* also weighted, also three bands. *Differences:* there is no public population data on consent behaviour to
fit log-normal curves to, so TraceVeil uses fixed weights and thresholds; a Lighthouse-style weighted average over all
checks ranks exactly like TraceVeil's unclamped penalty (section 7).

**Kendall's tau.** M. G. Kendall, "A New Measure of Rank Correlation", *Biometrika* 30(1–2):81–93, 1938,
doi:10.1093/biomet/30.1-2.81. M. G. Kendall, "The Treatment of Ties in Ranking Problems", *Biometrika* 33(3):239–251,
1945, doi:10.1093/biomet/33.3.239. τ_b = (n_c − n_d) / √((n₀ − n₁)(n₀ − n₂)) as given in
<https://en.wikipedia.org/wiki/Kendall_rank_correlation_coefficient> (the 1945 paper itself was not accessible).

## 9. Observations and limits

**No flaw was found**: every property held on every set tested. Two design observations, not changed:

1. **Rounding direction.** A half point rounds towards a better score. This decides the band of 45 assignments
   (84.5 → 85, Ready) and 384 (59.5 → 60, Needs work) of 209,952. CVSS rounds towards more severe. If the team wants that
   conservatism, rounding halves down would move exactly those assignments; the robustness interval is unaffected (R3).
2. **Inferred findings never decide alone.** A lone inferred Critical (C9 read only from the screen) scores 85 and is
   Ready. This is the stated rule (P8); the robustness line now shows that the band could change (70 to 100).

**What this does not show:** that each finding is detected correctly (detection accuracy is a separate question), that the
weights are the "right" ones (they are expert-set, as at the Observatory), or how often each combination occurs in real
apps (the enumeration counts combinations). The fuzz covers random audits, not every possible capture. The default
zero-weight check relies on the three-fold structure of `ScoreCalculator`; the full 40,310,784-set run is available with
`TRACEVEIL_SCORE_FULL=1`. The robustness interval gives extremes, not probabilities: the accuracy of the AI screen review
was not measured here.

## 10. Reproduce

```bash
./gradlew :core:test --tests "com.dpdpxray.core.rules.science.*"     # about 30 s
TRACEVEIL_SCORE_SEED=12345 ./gradlew :core:test --tests "com.dpdpxray.core.rules.science.ScorePropertiesTest"
TRACEVEIL_SCORE_FULL=1 ./gradlew :core:test --tests "com.dpdpxray.core.rules.science.ScoreExhaustiveTest"
```

Reports: `core/build/reports/score-science/{properties,exhaustive,sensitivity,witness}.json` and `exhaustive.md`,
`sensitivity.md`.
