# Namma MedMate — Public Landing Page Spec

| Field | Value |
|-------|-------|
| Status | Draft — content & IA contract |
| Audience | Marketing / design / frontend (marketing site only) |
| Scope | Public marketing landing page — **not** Partner Console, Customer App, Admin HQ, or API portal |
| Replaces | Deprecated site at [namma-medmate.netlify.app/namma](https://namma-medmate.netlify.app/namma) |
| Source of truth | `docs/requirements/INDEX.md` + cited EPICs |
| Last updated | 2026-08-11 |

---

## 1. Purpose

This document defines **what must, may, and must not** appear on the public landing page, grounded in backend requirements. It is a content and information-architecture contract — not a visual design system and not a wireframe.

**Product one-liner (from requirements):**  
Namma MedMate is a **hyperlocal medicine delivery marketplace + Pharmacy ERP SaaS**.

Legal entity (footer): **Medmate India Technology Private Limited**.

---

## 2. What this page is / is not

| Is | Is not |
|----|--------|
| Acquisition surface for patients and pharmacies | Login / dashboard / POS / admin |
| Promise of value + clear CTAs into apps / signup / demo | Feature dump of every EPIC |
| Trust + compliance at consumer-readable depth | Ops runbooks, commission math, internal SLAs |
| Dual-sided story (patient ↔ neighbourhood pharmacy) | Pharmacy-only ERP brochure (deprecated framing) |

Deep product work happens after CTA (app install, pharmacy register, demo booking). Landing sells the **outcome**; portals deliver the **workflow**.

---

## 3. Why the deprecated site is wrong for the product

| Topic | Deprecated site | Requirements |
|-------|-----------------|--------------|
| Positioning | Pharmacy ERP only | Marketplace **+** Pharmacy ERP SaaS (`INDEX.md`) |
| Online delivery | “Launching soon” | Core P0: orders, riders, 12–30 min (`EPIC-010`, `EPIC-011`) |
| Plans | ₹299–₹1,499 | Free ₹0 · Starter ₹699 · Growth/Retail Pro ₹1,499 · Pro ₹2,999 **or** Enterprise custom |
| Audiences | Chemists only | Patients **and** pharmacies (+ optional rider recruit) |
| Social proof | “750+ partner pharmacies” | **No pharmacy count in docs** — do not reuse without verified data |
| Geography | Bengaluru-heavy | Docs allow India / Bengaluru zone examples; city claims need GTM sign-off |

Direction for the new page: **futuristic dual-sided platform** (neighbourhood care network), not “another billing tool launching online later.”

---

## 4. Audiences & priority

| Priority | Audience | Goal of visit | Primary CTA |
|----------|----------|---------------|-------------|
| P0 | **Patient / family caregiver** | Fast, trusted medicine + optional free consult | Get the app / Order in 12–30 min |
| P0 | **Pharmacy owner** | Run shop + join demand network | Start free / Book a demo |
| P1 | **Pharmacy staff influencer** | Understand day-to-day console | See console / Book demo |
| P2 | **Rider** (optional section or `/riders`) | Earn delivering hyperlocal orders | Become a delivery partner |
| — | Doctor marketplace signup | **Out of scope v1** — doctors are in-house/contract (`EPIC-009`) | None |
| — | Admin / ops / investor deep-dive | Not this page | Separate surfaces |

**IA rule:** First viewport and primary nav speak to **patients and pharmacies equally** (toggle or dual CTA), not pharmacy-only.

---

## 5. Message architecture

### 5.1 Brand promise (hero-level)

Use one of these locked lines (or a tight rewrite that preserves claims):

1. **Primary:** Medicines from your neighbourhood pharmacy — in **12–30 minutes**.  
2. **Supporting:** Pharmacies run the whole shop on one console; patients get care, not just carts.  
3. **System:** One network. Counter + online. ERP + delivery.

Avoid: “ERP software your pharmacy actually needs” as the **sole** hero — that was the deprecated single-sided frame.

### 5.2 Claim tiers (compliance for copy)

| Tier | Meaning | Examples |
|------|---------|----------|
| **A — Ship freely** | Explicit in `INDEX` / EPICs | 12–30 min delivery; ₹5 handling; ₹25 delivery (free ≥ ₹199); Free plan; GST-ready POS; Schedule H workflow; free teleconsult e-Rx |
| **B — Soften** | In docs but needs legal/GTM tone | “Built for Indian pharmacy & data-protection rules (DPDP)”; KYC-verified pharmacies |
| **C — Do not invent** | Not in requirements | “750+ pharmacies”, ISO 27001 certified, pan-India live, 1,180+ pin codes, specific brand partnership “Live” badges unless GTM confirms |
| **D — Never on landing** | Internal platform | Admin HQ, rules engine, self-healing, commission %, TCS ops, COD float, kill switches |

### 5.3 Tone (futuristic, still precise)

- Calm confidence, neighbourhood trust, realtime systems — **not** crypto/neon/AI-hype.  
- Prefer concrete SLAs and workflows over vague “AI-powered.”  
- If AI appears later (forecasting, Rx assist — Phase 4 blueprint), mark as **roadmap**, never as live.

---

## 6. Site map (marketing site)

Landing may be one long page **or** a thin home + deep pages. Minimum routes:

| Route | Required | Content |
|-------|----------|---------|
| `/` | Yes | Dual-sided landing (this spec) |
| `/pharmacies` or `#pharmacies` | Yes | ERP + marketplace for owners |
| `/patients` or `#patients` | Yes | App value + fees + care features |
| `/pricing` | Yes | SaaS plans (locked catalogue — §10) |
| `/how-it-works` | Recommended | Patient journey + pharmacy journey |
| `/trust` or `/compliance` | Recommended | GST, Schedule H/H1/X, KYC, privacy |
| `/demo` | Yes | Lead form → CRM pipeline (`EPIC-014` STORY-004) |
| `/partners` | Optional | Ecosystem status (Live / Rolling out / Soon) |
| `/riders` | Optional | Recruit only |
| `/stories` | Optional | Testimonials — only with real quotes |
| `/about`, `/contact` | Yes | Company + support |
| Legal | Yes | Terms, Privacy, Refund |

Portal URLs (CTAs only, not built here): customer app / pharmacy console (e.g. `app.nammamedmate.com`).

---

## 7. Section contract (home page)

Build in this order. Each section has **one job**. Mark maturity: **Live claim** | **Soft** | **Roadmap**.

### S0 — Global chrome

| Element | Spec |
|---------|------|
| Logo / wordmark | Namma MedMate |
| Nav | Patients · Pharmacies · Pricing · How it works · Trust · Contact |
| Locale / city | Soft: “Live in select Bengaluru zones” only if GTM confirms; else “Hyperlocal · India” |
| CTAs (persistent) | **Order medicines** · **For pharmacies** (or Start free) |
| Banner (optional) | Platform promo codes only if live (`NAMMA25`, `FLAT50`, `FREEDEL` — `INDEX.md`) |

### S1 — Hero (first viewport)

| Slot | Content rules |
|------|----------------|
| Eyebrow | Hyperlocal medicine network · Bengaluru (or India) |
| H1 | Dual promise: speed for patients **and** control for pharmacies — one network |
| Sub | OTC + prescription path; free doctor consult when needed; pharmacy ERP from day one |
| CTA group | Primary: Get the app / Order now · Secondary: Start free as a pharmacy · Tertiary: Book a demo |
| Trust chips | Free to start (pharmacy) · 12–30 min · GST-ready · Schedule-H aware |
| Visual | Futuristic but real: map pulse + counter console + phone tracking — **one composition**, not a dashboard collage of every module |
| Forbidden in hero | Pricing tables, pin-code lists, fake metrics, “launching soon” for delivery |

**Doc-backed hero facts:** delivery target 12–30 min; handling ₹5; delivery ₹25 / free ≥ ₹199 (`INDEX.md`).

### S2 — Audience switcher (“Who are you?”)

Two equal cards:

1. **I need medicines** → jump to patient journey + app CTA.  
2. **I run a pharmacy** → jump to ERP + marketplace + Start free / Demo.

### S3 — Patient value (EPIC-010, 008, 009, 011, 012, 013, 018)

| Block | Live claims |
|-------|-------------|
| Order paths | OTC smart pharmacy select **or** upload Rx → quote broadcast (nearby pharmacies) → choose → pay |
| Care | Free teleconsult → digitally signed e-prescription (patient-facing; no doctor signup CTA) |
| Fulfilment | Live tracking + OTP handover |
| Pay | UPI / Card / COD / wallet (“Namma Money”) |
| Keep coming back | Coupons, referral (₹100 / ₹100 after first **delivered** order), loyalty, medicine schedules + Care Circle, refill alerts |
| Fees | Transparent fee strip: ₹5 handling · ₹25 delivery · free delivery ≥ ₹199 |

**Do not** show Admin order tools, reassignment, or dispute internals.

### S4 — Pharmacy value (EPIC-003, 006, 007, 008, 010, 014)

Structure as **two layers** (futuristic dual engine):

| Layer | Modules to name (user language) | Plan note |
|-------|----------------------------------|-----------|
| **Console (ERP)** | POS & GST billing, inventory / batch / FEFO & expiry, purchases, Rx queue, khata/credit, reports, CRM | Free → paid unlocks |
| **Network (Marketplace)** | Online visibility, accept & pack orders, rider handoff, demand from nearby patients | Online store / visibility Growth+ per `INDEX` / EPIC gates |

Feature clusters (landing bullets, not epic IDs):

1. Counter POS + GST invoices (PDF / WhatsApp / SMS share)  
2. Batch inventory, FEFO, expiry & restock signals  
3. Purchases / goods-inward; reorder & distributors (higher plans)  
4. Prescription queue + Schedule H / H1 / X awareness  
5. Customer CRM & khata (Starter+)  
6. Online orders from the same stock truth (Growth+)  
7. Hospital / IPD / kiosk (Pro — only if Pro catalogue locked)  
8. Self-serve KYC onboarding (Drug Licence, GSTIN, etc.)

Setup story (soft): register → Free plan auto · optional **14-day Starter trial** · upgrade (`EPIC-014`). Avoid promising “live in 7 days” unless GTM owns that SLA (not in INDEX business rules).

### S5 — How it works (dual timeline)

**Patient (4 steps):** Open app → search or upload Rx → pay → track to door (OTP).  
**Pharmacy (4 steps):** Register & KYC → bill & stock on console → go online (plan) → fulfil neighbourhood orders.

Optional third rail (P2): **Rider** — register, KYC, go online, earn.

### S6 — Differentiation table

Replace deprecated “paper vs generic vs delivery apps” with a **platform** table:

| Capability | Delivery-only apps | Billing-only software | Namma MedMate |
|------------|--------------------|------------------------|---------------|
| 12–30 min hyperlocal delivery | Partial | — | Yes |
| Patient keeps neighbourhood pharmacy | Often no | — | Yes |
| GST POS + inventory + Rx compliance | — | Partial | Yes |
| Free teleconsult → e-Rx into order | Rare | — | Yes |
| One stock truth: counter + online | — | — | Yes |
| Pharmacy keeps customer relationship | Weak | Strong offline | Strong both |

### S7 — Pricing (pharmacies)

See **§10** — publish only after catalogue lock. Until then, show:

- Free to start  
- Plans from **₹699/mo**  
- Annual ≈ 2 months free (~17% off)  
- CTA: See plans / Start free / Book demo  

**Do not** publish deprecated ₹299 / ₹499 / ₹999 / ₹1,499 Flagship ladder.

Consumer fees stay in patient section, not SaaS pricing.

### S8 — Trust & compliance

| Claim | Source | Landing wording |
|-------|--------|-----------------|
| GST-ready pharmacy invoices | `EPIC-007` | GST-ready billing with HSN / tax split |
| Schedule H / H1 / X | `INDEX`, `EPIC-008` | Prescription & statutory register workflows for regulated meds |
| Pharmacy KYC | `EPIC-003`, `EPIC-022` | Verified licences & GST before selling on network |
| Payments | `EPIC-012`, `EPIC-022` | UPI & cards via payment partner; secure checkout |
| Privacy | `EPIC-008` (DPDP cited) | Soft: designed for Indian data-protection expectations — **no “certified”** without legal |
| Settlements | `EPIC-012` | Soft for pharmacies: weekly settlement cycle (no TCS lecture) |

### S9 — Ecosystem / integrations

Status labels only — never imply all are Live:

| Status | Items (from `EPIC-022` + product) |
|--------|-----------------------------------|
| **Live / core** | Cashfree payments, Maps/geo, WhatsApp/SMS/push channels (as rolled out) |
| **Onboarding / phased** | GSTN / DigiLocker / licence / FSSAI KYC APIs, NIC e-invoice, Tally / Zoho |
| **Roadmap** | Insurance cashless, full pharma catalogue federation — only if GTM agrees |

Brand logos: “integrations we support or are onboarding” — same honesty rule as deprecated site footer note, but aligned to EPIC-022.

### S10 — Social proof

| Allowed | Forbidden until verified |
|---------|--------------------------|
| Named chemist quotes with permission | “750+ pharmacies”, fake avatars |
| Demo video / console walkthrough | Fabricated ₹58k sales widgets as “trust” |
| Press / partner marks with approval | Unsourced pin-code coverage maps |

If no real proof yet: **“Early partners in Bengaluru — stories coming”** or omit section.

### S11 — FAQ (doc-aligned answers)

| Question | Answer spine |
|----------|--------------|
| How fast is delivery? | Target **12–30 minutes** in live zones. |
| What are delivery fees? | ₹5 handling; ₹25 delivery; free when cart ≥ ₹199. |
| Do I need a prescription? | Schedule H and above — yes; upload Rx or use free consult for e-Rx. |
| Is the doctor consult free? | Yes for eligible consults that produce e-Rx into the order flow. |
| Can my pharmacy start free? | Yes — Free plan on registration; upgrade when you need more modules. |
| Is online separate software? | No — same console / stock; marketplace visibility by plan. |
| Do you replace my distributors? | No — purchases & reorders with your suppliers; catalogues via integrations over time. |
| Which cities? | Only GTM-approved list; docs exemplify Bengaluru zones. |
| Is my data safe? | Encryption, role access, export — soft DPDP language, link to Privacy. |

### S12 — Final CTA band

Dual close:

1. Patients: Order medicines in 12–30 min · Free consult when you need an Rx  
2. Pharmacies: Start free · Book a demo · (optional) 14-day trial  

### S13 — Footer

Product · For pharmacies · For patients · Company · Legal · © Medmate India Technology Private Limited · Bengaluru, Karnataka.

---

## 8. CTAs → product funnel mapping

| Landing CTA | Downstream (requirements) |
|-------------|---------------------------|
| Get the app / Order | Customer OTP auth (`EPIC-001`), cart/order (`EPIC-010`) |
| Free doctor consult | Teleconsult (`EPIC-009`) — patient only |
| Referral | `signup?ref=` pattern (`EPIC-013` STORY-005) |
| Start free / Register pharmacy | Pharmacy registration + KYC (`EPIC-003`), Free plan (`EPIC-014`) |
| Book a demo | Lead pipeline: NEW → CONTACTED → **DEMO** → TRIAL → WON (`EPIC-014` STORY-004) |
| Start trial | 14-day Starter trial (`EPIC-014` STORY-002) |
| Become a rider | Rider KYC (`EPIC-011`) — optional page |

Demo form fields (minimum): name, phone, pharmacy name, city/pincode, preferred time, source (`ORGANIC` / `AD` / etc.).

---

## 9. Explicit exclusions (do not put on landing)

1. Admin HQ, finance console, compliance audit UI  
2. Automation / rules engine / self-healing / observability  
3. Commission rates (default 8%), TCS/TDS filing instructions  
4. Rider COD float ₹2,000, payout formulas, zone surge toggles  
5. Internal SLAs (KYC review hours, GSTR-8 calendar, dunning days)  
6. Open “join as doctor” marketplace  
7. Inter-city or scheduled delivery (out of scope in `EPIC-011`)  
8. Deprecated plan ladder and unverified pharmacy counts  
9. “Online store launching soon” as the headline for delivery (delivery is core product)

---

## 10. Pricing block — lock before publish

Requirements currently disagree. **Landing must pick one catalogue after product sign-off.**

### Option A — Public names in `INDEX.md` (preferred for marketing language)

| Plan | ₹/mo | Users | Headline modules |
|------|------|-------|------------------|
| Free | 0 | 2 | POS, inventory, purchases, invoice settings |
| Starter | 699 | 2 | + Rx, customers, credit/khata |
| Growth | 1,499 | 5 | + online store, reports, CRM, reorder, distributors |
| Pro | 2,999 | Unlimited | + hospital/IPD, self-order kiosk |

### Option B — Billing catalogue in `EPIC-014`

| Plan | ₹/mo | Seats | Notes |
|------|------|-------|-------|
| FREE | 0 | 1 | Invoice cap 100/mo |
| STARTER | 699 | 2 | Cap 500/mo |
| RETAIL_PRO | 1,499 | 5 | Unlimited invoices |
| ENTERPRISE | Custom | Unlimited | Custom |

**Shared publishable facts (both):** annual = 10× monthly (~2 months free); SaaS GST 18% (SAC 9983) can live in fine print; add-ons exist (e-invoice ₹199, WhatsApp ₹299, extra seat ₹149, API ₹499, branch ₹399, analytics ₹249) — show on `/pricing` detail, not hero.

**Until lock:** homepage shows Free + “from ₹699” only; full table on `/pricing` behind “subject to final plan names.”

---

## 11. Futuristic design direction (constraints, not Figma)

Goal: feel like a **realtime neighbourhood care network**, not a 2018 SaaS template and not the deprecated brochure.

| Do | Don’t |
|----|-------|
| One bold composition per viewport | Card grids in the hero |
| Motion: map pulse, ETA countdown, stock→order sync metaphor | Purple glow / generic AI blobs |
| Typography with character; dark or deep teal/ink with warm accent optional | Default Inter + purple gradient cliché |
| Real product UI in motion (console + app) | Fake metrics chrome |
| Dual-sided visual language (patient phone ↔ pharmacy glass console) | Pharmacy-only screenshots |

Accessibility: WCAG AA contrast; CTA labels that state audience (“For pharmacies”).

---

## 12. Content inventory checklist (ship gate)

Before launch, every public claim must pass:

- [ ] Dual audience clear in hero  
- [ ] 12–30 min + fee strip accurate  
- [ ] Free teleconsult described without doctor signup  
- [ ] Pharmacy Free plan + demo/trial CTAs wired  
- [ ] Pricing matches **locked** catalogue (§10)  
- [ ] No “750+” / pin-code theatre without data  
- [ ] No “delivery launching soon” contradiction  
- [ ] Compliance section uses Tier A/B wording only  
- [ ] Integrations labelled Live / Phased / Roadmap  
- [ ] Demo form lands in lead pipeline fields  
- [ ] Legal pages linked  
- [ ] Deprecated Netlify page redirected or de-indexed  

---

## 13. Suggested homepage outline (copy skeleton)

Use as briefing for designers/writers — rewrite voice, keep claims.

1. **Hero** — Neighbourhood medicines in 12–30 minutes. Pharmacies: one console for counter and online.  
2. **Who are you?** — Patient | Pharmacy  
3. **For patients** — Order · Rx upload / free consult · Track · Care circle & reminders · Fees  
4. **For pharmacies** — ERP today · Network demand · KYC onboarding  
5. **How it works** — Dual timeline  
6. **Why Namma** — Differentiation table  
7. **Pricing teaser** — Free · from ₹699 · See plans  
8. **Trust** — GST · Schedule drugs · Verified KYC · Privacy  
9. **FAQ**  
10. **Close CTA** — Order | Start free | Book demo  

---

## 14. Traceability (requirements → sections)

| Landing section | Primary sources |
|-----------------|-----------------|
| Product definition | `docs/requirements/INDEX.md` |
| Patient order / fees | `INDEX.md`, `EPIC-010`, `EPIC-011`, `EPIC-012` |
| Teleconsult | `EPIC-009` |
| Schedules / care circle | `EPIC-018` |
| Coupons / referral / loyalty | `EPIC-013`, `INDEX.md` |
| Pharmacy ERP | `EPIC-006`, `EPIC-007`, `EPIC-008` |
| Onboarding / KYC | `EPIC-003`, `EPIC-022` |
| SaaS plans / trial / leads | `EPIC-014` (+ resolve vs `INDEX` § Pharmacy SaaS Plans) |
| Integrations | `EPIC-022` |
| Exclusions (admin/automation) | `INDEX` sides 3–4, `EPIC-019`, `EPIC-020` |

---

## 15. Open decisions (blockers for final copy)

1. **Plan catalogue:** INDEX (Growth/Pro) vs EPIC-014 (RETAIL_PRO/ENTERPRISE) + Free seats 2 vs 1.  
2. **GTM geography:** which cities/zones are publicly “live.”  
3. **Social proof:** real partner count and testimonials.  
4. **App store / console URLs** and deep links for CTAs.  
5. **Primary launch narrative:** marketplace-first, ERP-first, or true dual (recommended: dual).

---

*This file is the marketing landing contract. Portal UX, API shapes, and Admin HQ are out of scope. Update this doc when `INDEX.md` plan tables or GTM claims change.*
