# IPO Data Sync

Standalone Node 20 + TypeScript scraper that collects Indian IPO catalog, GMP, and live subscription data from Chittorgarh, InvestorGain, IPOWatch, and (optionally) NSE, merges and validates the records, and writes a single compact bundle document (`ipo_live_bundle/current`, always `< 900 KB`) to the named Firestore database without requiring the Firebase Blaze plan or Cloud Functions.

## Firestore Targets

- **Database ID**: `ai-studio-android-ipoguruj-a24800ae-d4aa-41d1-bac5-770bc39e5023` (named database — never `(default)`).
- **Bundle Document**: `ipo_live_bundle/current`
- **Sync Metadata Document**: `ipo_live_meta/sync` (updated on every run; if all sources fail, `ipo_live_bundle/current` is left untouched and only `ipo_live_meta/sync` records `sourcesFailed` and `lastError`).

## Prerequisites

- Node.js **20+**
- A Firebase **Service Account** JSON key with Cloud Datastore / Firestore write permission for the target Firebase project.
  - **Security warning**: Never commit any service account JSON file or `.env` file to git.

## Local Setup & Run

```bash
cd ipo-data-sync
npm ci
```

Set environment variables in your shell (or copy `.env.example` to `.env` for local tooling, never commit `.env`):

```bash
export FIREBASE_SERVICE_ACCOUNT='{"type":"service_account",...}'
export RUN_MODE=all            # optional: all | catalog | gmp | subscription
export GEMINI_API_KEY=''       # optional: free-tier fallback when HTML table yields 0 rows
export GEMINI_MODEL='gemini-2.5-flash'

npm run sync
```

### Type-Check & Unit Tests

```bash
npm run build
npm test
```

## GitHub Actions Setup

Two workflows are included in `/.github/workflows/`:

1. **`sync.yml`** — Runs every 20 minutes from `02:00` to `16:59` UTC (~`07:30`–`22:29` IST) and supports manual `workflow_dispatch` with a `run_mode` selector (`all`, `catalog`, `gmp`, `subscription`).
2. **`keepalive.yml`** — Runs on the 1st of every month to update `ipo-data-sync/heartbeat.txt`, preventing GitHub from disabling scheduled workflows after 60 days of repository inactivity.

### Configure Repository Secrets

In GitHub, go to **Settings → Secrets and variables → Actions → Secrets** and add:

1. **`FIREBASE_SERVICE_ACCOUNT`** *(required)*: Paste the entire minified JSON contents of your Firebase service account key.
2. **`GEMINI_API_KEY`** *(optional)*: A free-tier Google AI Studio Gemini API key. If omitted, the Gemini HTML fallback is skipped silently.

Optionally, under **Variables**, you may set **`GEMINI_MODEL`** (defaults to `gemini-2.5-flash`).

## Run Mode Schedule (`RUN_MODE=all`)

When `RUN_MODE=all`:
- **Catalog** runs at most once per hour (tracked via `lastCatalogSyncAtMs` in `ipo_live_meta/sync`).
- **GMP** runs on every invocation.
- **Subscription** runs only on weekdays (Mon–Fri) between `10:00` and `18:00` IST and is applied only to IPOs that are `LIVE` or closed within the last 2 days.

## Validation & Merge Guarantees

- **Strict company matching**: Only legal suffixes (`ltd`, `limited`, `pvt`, `private`, `llp`, `inc`, `plc`, `corp`, `corporation`, `co`, `company`) are stop words, and `boardType` (`MAINBOARD` vs `SME`) must match. `"Alpha Industries Ltd"` and `"Alpha Technologies Ltd"` never merge.
- **GMP merge**: Uses the most recently updated value, or the median if sources disagree by more than 30%.
- **Dates / Price band / Lot size**: Accepted when at least two independent source families agree or when reported by NSE/BSE.
- **No data degradation**: Reads existing `ipo_live_bundle/current` before writing, never overwrites good fields with empty/zero values, and skips writes when the SHA-256 hash of `ipos` is unchanged.
- **Retention & size limit**: Retains all upcoming + current IPOs plus the 25 most recent listed IPOs, trimming oldest listed entries if needed to stay under 900 KB.
