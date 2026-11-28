import { createHash } from "node:crypto";
import {
  addWorkingDaysIst,
  computeIpoStatus,
  DAY_MS,
  isEligibleForSubscription,
  isIpoListed,
  parseFlexibleDateMs,
  startOfIstDayMs
} from "./dates.js";
import {
  areSameCompany,
  cleanCompanyName,
  isPlaceholderCompanyName,
  isWithdrawnOrCancelled,
  makeIpoId,
  normalizeCompanyKey
} from "./normalize.js";
import type {
  BoardType,
  DroppedByReason,
  FieldCoverageCounts,
  IpoBundleItem,
  IpoLiveBundleDoc,
  IpoStatus,
  RawIpoRecord,
  RawSubscriptionRecord,
  SelectionStats
} from "./types.js";
import {
  isValidGmp,
  isValidSubscriptionValue,
  MAX_BIDDING_WINDOW_DAYS
} from "./validation.js";

export const DEFAULT_MAX_IPOS = 20;
export const MAX_UNDATED_PRICE_BAND_IPOS = 3;
export const UPCOMING_OPEN_WINDOW_DAYS = 21;
export const AWAITING_LISTING_CLOSE_WINDOW_DAYS = 10;
export const RECENTLY_LISTED_WINDOW_DAYS = 7;
export const LAST_SEEN_MERGE_WINDOW_MS = 24 * 60 * 60 * 1000;
export const MAX_BUNDLE_BYTES = 900 * 1024; // 900 KB strict cap
export const GMP_DISAGREEMENT_THRESHOLD = 0.30; // 30%

/**
 * Parses MAX_IPOS from an env/config value, falling back to DEFAULT_MAX_IPOS (20).
 */
export function parseMaxIpos(raw: string | number | undefined = process.env.MAX_IPOS): number {
  if (typeof raw === "number") {
    return Number.isInteger(raw) && raw >= 1 ? raw : DEFAULT_MAX_IPOS;
  }
  if (!raw || !raw.trim()) return DEFAULT_MAX_IPOS;
  const parsed = Number.parseInt(raw.trim(), 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    return DEFAULT_MAX_IPOS;
  }
  return parsed;
}

/**
 * Extracts independent provider family from a source name so two pages on the same
 * website (e.g. chittorgarh_list + chittorgarh_upcoming) do NOT count as two
 * independent sources agreeing.
 */
export function getSourceFamily(sourceName: string): string {
  const base = sourceName.toLowerCase().split(":")[0];
  if (base.startsWith("nse")) return "nse";
  if (base.startsWith("bse")) return "bse";
  if (base.startsWith("chittorgarh")) return "chittorgarh";
  if (base.startsWith("investorgain")) return "investorgain";
  if (base.startsWith("ipowatch")) return "ipowatch";
  return base.split("_")[0] || base;
}

function isAuthoritativeSource(sourceName: string, explicitFlag?: boolean): boolean {
  if (explicitFlag) return true;
  const family = getSourceFamily(sourceName);
  return family === "nse" || family === "bse";
}

interface SourcedValue<T> {
  value: T;
  family: string;
  authoritative: boolean;
}

/**
 * Accepts a field value ONLY when:
 * 1. An authoritative exchange source (NSE/BSE) provides it, OR
 * 2. At least two independent source families agree on the same value.
 */
function resolveAgreedValue<T>(
  candidates: SourcedValue<T>[],
  keyFn: (v: T) => string
): T | undefined {
  if (candidates.length === 0) return undefined;

  // Priority 1: Authoritative exchange source (NSE / BSE)
  const auth = candidates.find((c) => c.authoritative);
  if (auth) return auth.value;

  // Priority 2: >= 2 independent source families agreeing on the exact value
  const byKey = new Map<string, { value: T; families: Set<string> }>();
  for (const c of candidates) {
    const key = keyFn(c.value);
    const existing = byKey.get(key);
    if (existing) {
      existing.families.add(c.family);
    } else {
      byKey.set(key, { value: c.value, families: new Set([c.family]) });
    }
  }

  let best: { value: T; count: number } | undefined;
  for (const entry of byKey.values()) {
    if (entry.families.size >= 2) {
      if (!best || entry.families.size > best.count) {
        best = { value: entry.value, count: entry.families.size };
      }
    }
  }
  return best?.value;
}

interface GmpObservation {
  family: string;
  gmpRupees: number;
  updatedAtMs: number;
}

/**
 * GMP merge rule:
 * - Deduplicate to the latest observation per independent source family.
 * - If sources differ by > 30%, store the median.
 * - Otherwise store the most recently updated value.
 */
export function resolveGmp(
  observations: GmpObservation[]
): { gmpRupees: number; gmpUpdatedAtMs: number } | undefined {
  const valid = observations.filter((o) => isValidGmp(o.gmpRupees));
  if (valid.length === 0) return undefined;

  // Keep latest observation per independent source family
  const latestByFamily = new Map<string, GmpObservation>();
  for (const obs of valid) {
    const prev = latestByFamily.get(obs.family);
    if (!prev || obs.updatedAtMs >= prev.updatedAtMs) {
      latestByFamily.set(obs.family, obs);
    }
  }

  const deduped = Array.from(latestByFamily.values());
  if (deduped.length === 1) {
    return {
      gmpRupees: deduped[0].gmpRupees,
      gmpUpdatedAtMs: deduped[0].updatedAtMs
    };
  }

  const values = deduped.map((o) => o.gmpRupees).sort((a, b) => a - b);
  const min = values[0];
  const max = values[values.length - 1];
  const denom = Math.max(Math.abs(max), Math.abs(min));
  const spreadRatio = denom === 0 ? 0 : (max - min) / denom;

  const latestTs = Math.max(...deduped.map((o) => o.updatedAtMs));

  if (spreadRatio > GMP_DISAGREEMENT_THRESHOLD) {
    const mid = Math.floor(values.length / 2);
    const median =
      values.length % 2 === 1
        ? values[mid]
        : Math.round((values[mid - 1] + values[mid]) / 2);
    return {
      gmpRupees: median,
      gmpUpdatedAtMs: latestTs
    };
  }

  // Within 30%: pick most recently updated value
  let newest = deduped[0];
  for (const obs of deduped) {
    if (obs.updatedAtMs > newest.updatedAtMs) {
      newest = obs;
    }
  }
  return {
    gmpRupees: newest.gmpRupees,
    gmpUpdatedAtMs: newest.updatedAtMs
  };
}

function chooseBestCompanyName(names: Array<{ name: string; authoritative: boolean }>): string {
  const auth = names.find((n) => n.authoritative);
  if (auth) return auth.name;
  // Prefer the cleanest, longest non-all-caps company name (usually includes full legal suffix)
  return [...names]
    .sort((a, b) => {
      const aAllCaps = a.name === a.name.toUpperCase() ? 1 : 0;
      const bAllCaps = b.name === b.name.toUpperCase() ? 1 : 0;
      if (aAllCaps !== bAllCaps) return aAllCaps - bAllCaps;
      return b.name.length - a.name.length;
    })[0].name;
}

function createEmptyItem(
  companyName: string,
  normalizedKey: string,
  boardType: BoardType,
  nowMs: number
): IpoBundleItem {
  return {
    id: makeIpoId(normalizedKey, boardType),
    companyName,
    normalizedKey,
    boardType,
    status: "UPCOMING",
    openDate: "",
    closeDate: "",
    allotmentDate: "",
    listingDate: "",
    priceLow: 0,
    priceHigh: 0,
    lotSize: 0,
    issueSizeCr: 0,
    gmpRupees: 0,
    gmpUpdatedAtMs: 0,
    qibSubX: 0,
    bNiiSubX: 0,
    sNiiSubX: 0,
    retailSubX: 0,
    employeeSubX: 0,
    totalSubX: 0,
    subUpdatedAtMs: 0,
    registrarName: "",
    registrarUrl: "",
    sources: [],
    lastSeenAtMs: nowMs
  };
}

function areDatesConsistent(
  openDate: string,
  closeDate: string,
  nowMs: number
): boolean {
  if (!openDate || !closeDate) return true;
  const openMs = parseFlexibleDateMs(openDate, nowMs);
  const closeMs = parseFlexibleDateMs(closeDate, nowMs);
  if (openMs === null || closeMs === null) return false;
  if (closeMs < openMs) return false;
  const windowDays = Math.round((closeMs - openMs) / DAY_MS);
  return windowDays <= MAX_BIDDING_WINDOW_DAYS;
}

function canMergeFromExisting(
  existing: IpoBundleItem | undefined,
  nowMs: number
): existing is IpoBundleItem {
  if (!existing) return false;
  const ts = existing.lastSeenAtMs;
  if (typeof ts !== "number" || !Number.isFinite(ts) || ts <= 0) {
    return false;
  }
  const ageMs = nowMs - ts;
  return ageMs >= -5 * 60 * 1000 && ageMs <= LAST_SEEN_MERGE_WINDOW_MS;
}

function mergeSubscriptionsIntoItem(
  item: IpoBundleItem,
  subRecords: RawSubscriptionRecord[],
  nowMs: number
): IpoBundleItem {
  if (subRecords.length === 0) return item;
  if (!isEligibleForSubscription(item, nowMs)) return item;

  // Prefer authoritative (NSE) first, then most recently updated
  const ordered = [...subRecords].sort((a, b) => {
    const aAuth = isAuthoritativeSource(a.source, a.isAuthoritative) ? 1 : 0;
    const bAuth = isAuthoritativeSource(b.source, b.isAuthoritative) ? 1 : 0;
    if (aAuth !== bAuth) return bAuth - aAuth;
    return b.subUpdatedAtMs - a.subUpdatedAtMs;
  });

  const pickSub = (
    extractor: (r: RawSubscriptionRecord) => number | undefined,
    existingVal: number
  ): number => {
    for (const rec of ordered) {
      const val = extractor(rec);
      if (val !== undefined && isValidSubscriptionValue(val)) {
        // Never overwrite a positive existing subscription with 0
        if (val === 0 && existingVal > 0) continue;
        return val;
      }
    }
    return existingVal;
  };

  const qibSubX = pickSub((r) => r.qibSubX, item.qibSubX);
  const bNiiSubX = pickSub((r) => r.bNiiSubX, item.bNiiSubX);
  const sNiiSubX = pickSub((r) => r.sNiiSubX, item.sNiiSubX);
  const retailSubX = pickSub((r) => r.retailSubX, item.retailSubX);
  const employeeSubX = pickSub((r) => r.employeeSubX, item.employeeSubX);
  const totalSubX = pickSub((r) => r.totalSubX, item.totalSubX);
  const latestSubTs = Math.max(
    item.subUpdatedAtMs,
    ...ordered.map((r) => r.subUpdatedAtMs)
  );

  const mergedSources = Array.from(
    new Set([...item.sources, ...ordered.map((r) => r.source)])
  ).sort();

  return {
    ...item,
    qibSubX,
    bNiiSubX,
    sNiiSubX,
    retailSubX,
    employeeSubX,
    totalSubX,
    subUpdatedAtMs: latestSubTs,
    sources: mergedSources
  };
}

function getRecencyMs(item: IpoBundleItem, nowMs: number): number {
  return (
    parseFlexibleDateMs(item.listingDate, nowMs) ??
    parseFlexibleDateMs(item.closeDate, nowMs) ??
    parseFlexibleDateMs(item.openDate, nowMs) ??
    0
  );
}

function computeRichnessScore(item: IpoBundleItem): number {
  let score = 0;
  if (item.priceLow > 0 && item.priceHigh >= item.priceLow) score += 4;
  if (item.lotSize > 0) score += 2;
  if (item.issueSizeCr > 0) score += 2;
  if (item.gmpUpdatedAtMs > 0 || item.gmpRupees !== 0) score += 2;
  if (item.allotmentDate) score += 1;
  if (item.listingDate) score += 1;
  if (item.registrarName) score += 1;
  score += Math.min(item.sources.length, 4);
  return score;
}

function mergeDuplicateBundleItems(a: IpoBundleItem, b: IpoBundleItem): IpoBundleItem {
  // Keep richer base and backfill missing fields from the duplicate
  const primary = computeRichnessScore(a) >= computeRichnessScore(b) ? a : b;
  const secondary = primary === a ? b : a;
  return {
    ...primary,
    openDate: primary.openDate || secondary.openDate,
    closeDate: primary.closeDate || secondary.closeDate,
    allotmentDate: primary.allotmentDate || secondary.allotmentDate,
    listingDate: primary.listingDate || secondary.listingDate,
    priceLow: primary.priceLow > 0 ? primary.priceLow : secondary.priceLow,
    priceHigh: primary.priceHigh > 0 ? primary.priceHigh : secondary.priceHigh,
    lotSize: primary.lotSize > 0 ? primary.lotSize : secondary.lotSize,
    issueSizeCr: primary.issueSizeCr > 0 ? primary.issueSizeCr : secondary.issueSizeCr,
    gmpRupees:
      primary.gmpUpdatedAtMs >= secondary.gmpUpdatedAtMs
        ? primary.gmpRupees
        : secondary.gmpRupees,
    gmpUpdatedAtMs: Math.max(primary.gmpUpdatedAtMs, secondary.gmpUpdatedAtMs),
    qibSubX: primary.qibSubX > 0 ? primary.qibSubX : secondary.qibSubX,
    bNiiSubX: primary.bNiiSubX > 0 ? primary.bNiiSubX : secondary.bNiiSubX,
    sNiiSubX: primary.sNiiSubX > 0 ? primary.sNiiSubX : secondary.sNiiSubX,
    retailSubX: primary.retailSubX > 0 ? primary.retailSubX : secondary.retailSubX,
    employeeSubX: primary.employeeSubX > 0 ? primary.employeeSubX : secondary.employeeSubX,
    totalSubX: primary.totalSubX > 0 ? primary.totalSubX : secondary.totalSubX,
    subUpdatedAtMs: Math.max(primary.subUpdatedAtMs, secondary.subUpdatedAtMs),
    registrarName: primary.registrarName || secondary.registrarName,
    registrarUrl: primary.registrarUrl || secondary.registrarUrl,
    sources: Array.from(new Set([...primary.sources, ...secondary.sources])).sort(),
    lastSeenAtMs: Math.max(primary.lastSeenAtMs ?? 0, secondary.lastSeenAtMs ?? 0) || undefined
  };
}

function deduplicateBundleItems(items: IpoBundleItem[]): IpoBundleItem[] {
  const deduped: IpoBundleItem[] = [];
  for (const item of items) {
    const idx = deduped.findIndex((existing) => areSameCompany(existing, item));
    if (idx >= 0) {
      deduped[idx] = mergeDuplicateBundleItems(deduped[idx], item);
    } else {
      deduped.push({ ...item, sources: [...item.sources] });
    }
  }
  return deduped;
}

export function computeSelectionStats(
  selected: IpoBundleItem[],
  beforeCap: number,
  maxIpos: number,
  droppedByReason: DroppedByReason
): SelectionStats {
  const byStatus: Record<IpoStatus, number> = {
    LIVE: 0,
    PRE_APPLY: 0,
    UPCOMING: 0,
    CLOSED: 0
  };
  const byBoardType: Record<BoardType, number> = {
    MAINBOARD: 0,
    SME: 0
  };
  const fieldCoverage: FieldCoverageCounts = {
    gmpRupees: 0,
    openDate: 0,
    closeDate: 0,
    allotmentDate: 0,
    listingDate: 0,
    qibSubX: 0,
    bNiiSubX: 0,
    sNiiSubX: 0,
    retailSubX: 0
  };

  for (const item of selected) {
    byStatus[item.status] += 1;
    byBoardType[item.boardType] += 1;
    if (item.gmpUpdatedAtMs > 0 || item.gmpRupees !== 0) fieldCoverage.gmpRupees += 1;
    if (item.openDate.trim().length > 0) fieldCoverage.openDate += 1;
    if (item.closeDate.trim().length > 0) fieldCoverage.closeDate += 1;
    if (item.allotmentDate.trim().length > 0) fieldCoverage.allotmentDate += 1;
    if (item.listingDate.trim().length > 0) fieldCoverage.listingDate += 1;
    if (item.qibSubX > 0) fieldCoverage.qibSubX += 1;
    if (item.bNiiSubX > 0) fieldCoverage.bNiiSubX += 1;
    if (item.sNiiSubX > 0) fieldCoverage.sNiiSubX += 1;
    if (item.retailSubX > 0) fieldCoverage.retailSubX += 1;
  }

  return {
    maxIpos,
    beforeCap,
    afterCap: selected.length,
    byStatus,
    byBoardType,
    droppedByReason: { ...droppedByReason },
    fieldCoverage
  };
}

/**
 * Selects only currently relevant IPOs (all dates in IST) and enforces the hard cap:
 *
 * Eligible windows:
 * 1. LIVE: bidding open today.
 * 2. Awaiting allotment or listing: closeDate within last 10 days AND listingDate not yet passed.
 * 3. PRE_APPLY / UPCOMING:
 *    - openDate within the next 21 days, OR
 *    - no confirmed openDate ONLY if price band is announced (capped at max 3).
 * 4. Recently listed: listingDate within the last 7 days.
 *
 * Priority order when trimming to maxIpos (default 20):
 *   LIVE (closing soonest first)
 *   -> awaiting listing (listing soonest first)
 *   -> nearest upcoming openDate first (then max 3 undated price-band IPOs)
 *   -> recently listed (listingDate descending).
 */
export function selectRelevantIpos(
  candidates: IpoBundleItem[],
  nowMs: number = Date.now(),
  maxIpos: number = parseMaxIpos(process.env.MAX_IPOS),
  preDropped?: Partial<DroppedByReason>
): { ipos: IpoBundleItem[]; stats: SelectionStats } {
  const effectiveCap = parseMaxIpos(maxIpos);
  const droppedByReason: DroppedByReason = {
    tooOld: preDropped?.tooOld ?? 0,
    tooFarFuture: preDropped?.tooFarFuture ?? 0,
    noDates: preDropped?.noDates ?? 0,
    placeholder: preDropped?.placeholder ?? 0,
    withdrawn: preDropped?.withdrawn ?? 0,
    overCap: preDropped?.overCap ?? 0
  };

  const today = startOfIstDayMs(nowMs);
  const deduped = deduplicateBundleItems(candidates);

  interface BucketEntry {
    item: IpoBundleItem;
    sortPrimary: number;
    sortSecondary: number;
  }

  const liveBucket: BucketEntry[] = [];
  const awaitingBucket: BucketEntry[] = [];
  const upcomingDatedBucket: BucketEntry[] = [];
  const undatedPriceBandBucket: BucketEntry[] = [];
  const recentlyListedBucket: BucketEntry[] = [];

  for (const rawItem of deduped) {
    const cleanedName = cleanCompanyName(rawItem.companyName);
    const normalizedKey = rawItem.normalizedKey || normalizeCompanyKey(cleanedName);
    if (isPlaceholderCompanyName(cleanedName) || !normalizedKey) {
      droppedByReason.placeholder += 1;
      continue;
    }
    if (isWithdrawnOrCancelled(rawItem.companyName)) {
      droppedByReason.withdrawn += 1;
      continue;
    }

    const openMs = parseFlexibleDateMs(rawItem.openDate, nowMs);
    const closeMs = parseFlexibleDateMs(rawItem.closeDate, nowMs);
    const listingMs = parseFlexibleDateMs(rawItem.listingDate, nowMs);
    const status = computeIpoStatus(rawItem, nowMs, rawItem.status);
    const item: IpoBundleItem = {
      ...rawItem,
      companyName: cleanedName,
      normalizedKey,
      id: rawItem.id || makeIpoId(normalizedKey, rawItem.boardType),
      status
    };

    const hasPriceBand = item.priceLow > 0 && item.priceHigh >= item.priceLow;
    const listed = isIpoListed(item, nowMs);

    // 1. LIVE: bidding open today (requires at least openDate or closeDate)
    if (status === "LIVE" && (openMs !== null || closeMs !== null)) {
      liveBucket.push({
        item,
        sortPrimary: closeMs ?? Number.MAX_SAFE_INTEGER,
        sortSecondary: openMs ?? Number.MAX_SAFE_INTEGER
      });
      continue;
    }

    // 2. Recently listed: listingDate (or T+3 fallback) already reached and within last 7 days
    if (listed) {
      const effectiveListingMs =
        listingMs ?? (closeMs !== null ? addWorkingDaysIst(closeMs, 3) : null);
      if (effectiveListingMs !== null) {
        const daysSinceListing = Math.round((today - effectiveListingMs) / DAY_MS);
        if (daysSinceListing >= 0 && daysSinceListing <= RECENTLY_LISTED_WINDOW_DAYS) {
          recentlyListedBucket.push({
            item: { ...item, status: "CLOSED" },
            sortPrimary: effectiveListingMs,
            sortSecondary: closeMs ?? effectiveListingMs
          });
          continue;
        }
      }
      droppedByReason.tooOld += 1;
      continue;
    }

    // 3. Awaiting allotment or listing: closeDate within last 10 days AND listingDate not yet passed
    if (closeMs !== null && today > closeMs) {
      const daysSinceClose = Math.round((today - closeMs) / DAY_MS);
      if (daysSinceClose >= 0 && daysSinceClose <= AWAITING_LISTING_CLOSE_WINDOW_DAYS) {
        const expectedListingMs = listingMs ?? addWorkingDaysIst(closeMs, 3);
        awaitingBucket.push({
          item: { ...item, status: "CLOSED" },
          sortPrimary: expectedListingMs,
          sortSecondary: closeMs
        });
        continue;
      }
      droppedByReason.tooOld += 1;
      continue;
    }

    // 4. PRE_APPLY / UPCOMING with confirmed openDate within next 21 days
    if (openMs !== null) {
      if (today < openMs) {
        const daysUntilOpen = Math.round((openMs - today) / DAY_MS);
        if (daysUntilOpen <= UPCOMING_OPEN_WINDOW_DAYS) {
          upcomingDatedBucket.push({
            item,
            sortPrimary: openMs,
            sortSecondary: closeMs ?? openMs
          });
          continue;
        }
        droppedByReason.tooFarFuture += 1;
        continue;
      }
      // openDate is in the past without a valid closeDate or listingDate
      droppedByReason.tooOld += 1;
      continue;
    }

    // 5. No confirmed openDate (and not LIVE / awaiting / listed):
    //    keep ONLY if price band is announced, capped at MAX_UNDATED_PRICE_BAND_IPOS (3)
    if (hasPriceBand) {
      undatedPriceBandBucket.push({
        item: { ...item, status: "UPCOMING" },
        sortPrimary: computeRichnessScore(item),
        sortSecondary: item.priceHigh
      });
      continue;
    }

    // DRHP/RHP-only entry with no dates and no price band
    droppedByReason.noDates += 1;
  }

  // Sort each tier strictly per priority specification
  // Tier 0: LIVE — closing soonest first (closeDate ascending)
  liveBucket.sort((a, b) => {
    if (a.sortPrimary !== b.sortPrimary) return a.sortPrimary - b.sortPrimary;
    if (a.sortSecondary !== b.sortSecondary) return a.sortSecondary - b.sortSecondary;
    return a.item.id.localeCompare(b.item.id);
  });

  // Tier 1: Awaiting listing — soonest expected listingDate ascending
  awaitingBucket.sort((a, b) => {
    if (a.sortPrimary !== b.sortPrimary) return a.sortPrimary - b.sortPrimary;
    if (a.sortSecondary !== b.sortSecondary) return b.sortSecondary - a.sortSecondary;
    return a.item.id.localeCompare(b.item.id);
  });

  // Tier 2: Upcoming/Pre-Apply (dated) — nearest upcoming openDate ascending
  upcomingDatedBucket.sort((a, b) => {
    if (a.sortPrimary !== b.sortPrimary) return a.sortPrimary - b.sortPrimary;
    if (a.sortSecondary !== b.sortSecondary) return a.sortSecondary - b.sortSecondary;
    return a.item.id.localeCompare(b.item.id);
  });

  // Tier 3: Undated with price band — richest first, hard-capped at 3
  undatedPriceBandBucket.sort((a, b) => {
    if (a.sortPrimary !== b.sortPrimary) return b.sortPrimary - a.sortPrimary;
    if (a.sortSecondary !== b.sortSecondary) return b.sortSecondary - a.sortSecondary;
    return a.item.id.localeCompare(b.item.id);
  });
  const keptUndated = undatedPriceBandBucket.slice(0, MAX_UNDATED_PRICE_BAND_IPOS);
  const droppedUndated = Math.max(0, undatedPriceBandBucket.length - keptUndated.length);

  // Tier 4: Recently listed — listingDate descending (newest listed first)
  recentlyListedBucket.sort((a, b) => {
    if (a.sortPrimary !== b.sortPrimary) return b.sortPrimary - a.sortPrimary;
    if (a.sortSecondary !== b.sortSecondary) return b.sortSecondary - a.sortSecondary;
    return a.item.id.localeCompare(b.item.id);
  });

  const beforeCap =
    liveBucket.length +
    awaitingBucket.length +
    upcomingDatedBucket.length +
    undatedPriceBandBucket.length +
    recentlyListedBucket.length;

  const prioritized: IpoBundleItem[] = [
    ...liveBucket.map((e) => e.item),
    ...awaitingBucket.map((e) => e.item),
    ...upcomingDatedBucket.map((e) => e.item),
    ...keptUndated.map((e) => e.item),
    ...recentlyListedBucket.map((e) => e.item)
  ];

  const selected = prioritized.slice(0, effectiveCap);
  const droppedByMaxCap = Math.max(0, prioritized.length - selected.length);
  droppedByReason.overCap += droppedUndated + droppedByMaxCap;

  const stats = computeSelectionStats(selected, beforeCap, effectiveCap, droppedByReason);
  return { ipos: selected, stats };
}

/**
 * Ensures the serialized Firestore bundle JSON stays strictly under maxBytes (default 900 KB)
 * by dropping the oldest listed IPOs first.
 */
export function trimBundleToByteLimit(
  docWithoutUpdatedAt: Omit<IpoLiveBundleDoc, "updatedAt">,
  nowMs: number = Date.now(),
  maxBytes: number = MAX_BUNDLE_BYTES
): Omit<IpoLiveBundleDoc, "updatedAt"> {
  const measure = (ipos: IpoBundleItem[]): number =>
    Buffer.byteLength(
      JSON.stringify({
        ...docWithoutUpdatedAt,
        updatedAt: "2026-01-01T00:00:00.000Z",
        ipos
      }),
      "utf8"
    );

  let ipos = [...docWithoutUpdatedAt.ipos];
  if (measure(ipos) <= maxBytes) {
    return { ...docWithoutUpdatedAt, ipos };
  }

  // Build ordered list of listed item IDs from oldest to newest
  const listedOldestFirst = ipos
    .filter((item) => isIpoListed(item, nowMs))
    .sort((a, b) => {
      const diff = getRecencyMs(a, nowMs) - getRecencyMs(b, nowMs);
      return diff !== 0 ? diff : a.id.localeCompare(b.id);
    })
    .map((item) => item.id);

  for (const dropId of listedOldestFirst) {
    ipos = ipos.filter((item) => item.id !== dropId);
    if (measure(ipos) <= maxBytes) {
      break;
    }
  }

  return { ...docWithoutUpdatedAt, ipos };
}

/**
 * Computes a stable SHA-256 hash of the canonicalized ipos array to skip no-op Firestore writes.
 * Intentionally excludes lastSeenAtMs so unchanged runs do not cause spurious writes every cycle.
 */
export function computeIposHash(ipos: IpoBundleItem[]): string {
  const canonical = [...ipos]
    .sort((a, b) => a.id.localeCompare(b.id))
    .map((item) => ({
      id: item.id,
      companyName: item.companyName,
      normalizedKey: item.normalizedKey,
      boardType: item.boardType,
      status: item.status,
      openDate: item.openDate,
      closeDate: item.closeDate,
      allotmentDate: item.allotmentDate,
      listingDate: item.listingDate,
      priceLow: item.priceLow,
      priceHigh: item.priceHigh,
      lotSize: item.lotSize,
      issueSizeCr: item.issueSizeCr,
      gmpRupees: item.gmpRupees,
      gmpUpdatedAtMs: item.gmpUpdatedAtMs,
      qibSubX: item.qibSubX,
      bNiiSubX: item.bNiiSubX,
      sNiiSubX: item.sNiiSubX,
      retailSubX: item.retailSubX,
      employeeSubX: item.employeeSubX,
      totalSubX: item.totalSubX,
      subUpdatedAtMs: item.subUpdatedAtMs,
      registrarName: item.registrarName,
      registrarUrl: item.registrarUrl,
      sources: [...item.sources].sort()
    }));

  return createHash("sha256").update(JSON.stringify(canonical)).digest("hex");
}

/**
 * Full merge & selection pipeline returning both the selected IPO array and selection stats:
 * 1. Builds candidates strictly from newly scraped records — NEVER appends or carries forward old array items.
 * 2. Deduplicates companies across sources by normalized name and boardType (areSameCompany).
 * 3. Drops placeholders and withdrawn/cancelled IPOs early and tracks drop counts.
 * 4. Allows per-field fallback from existingItems ONLY for items present in the new selection
 *    whose old item was lastSeenAtMs within the last 24 hours.
 * 5. Applies GMP recency / >30% median rule, 2-source agreement / NSE authority rule,
 *    and subscription updates for LIVE or closed-within-2-days IPOs.
 * 6. Filters to strict IST relevance windows and caps at maxIpos (default 20) in priority order.
 */
export function mergeIpoCatalogWithStats(
  existingItems: IpoBundleItem[],
  newIpoRecords: RawIpoRecord[],
  newSubRecords: RawSubscriptionRecord[],
  nowMs: number = Date.now(),
  maxIpos: number = parseMaxIpos(process.env.MAX_IPOS)
): { ipos: IpoBundleItem[]; stats: SelectionStats } {
  interface CompanyGroup {
    id: string;
    normalizedKey: string;
    boardType: BoardType;
    records: RawIpoRecord[];
    hasWithdrawn: boolean;
  }

  const preDropped: Partial<DroppedByReason> = {
    placeholder: 0,
    withdrawn: 0
  };

  const groups: CompanyGroup[] = [];

  for (const rec of newIpoRecords) {
    const cleanedName = cleanCompanyName(rec.companyName);
    const normalizedKey = normalizeCompanyKey(cleanedName);
    if (isPlaceholderCompanyName(rec.companyName) || isPlaceholderCompanyName(cleanedName) || !normalizedKey) {
      preDropped.placeholder = (preDropped.placeholder ?? 0) + 1;
      continue;
    }

    const withdrawn = Boolean(rec.isWithdrawn) || isWithdrawnOrCancelled(rec.companyName);
    const groupId = makeIpoId(normalizedKey, rec.boardType);

    const existingGroup = groups.find(
      (g) =>
        g.id === groupId ||
        areSameCompany(
          { companyName: cleanedName, normalizedKey, boardType: rec.boardType },
          { companyName: g.normalizedKey, normalizedKey: g.normalizedKey, boardType: g.boardType }
        )
    );
    if (existingGroup) {
      existingGroup.records.push({ ...rec, companyName: cleanedName });
      if (withdrawn) existingGroup.hasWithdrawn = true;
    } else {
      groups.push({
        id: groupId,
        normalizedKey,
        boardType: rec.boardType,
        records: [{ ...rec, companyName: cleanedName }],
        hasWithdrawn: withdrawn
      });
    }
  }

  // Subscription-only run (e.g., RUN_MODE=subscription where no IPO catalog/GMP records were fetched):
  // build candidates strictly from companies appearing in newSubRecords (never blindly carry forward old array).
  if (groups.length === 0 && newSubRecords.length > 0) {
    for (const sub of newSubRecords) {
      const cleanedName = cleanCompanyName(sub.companyName);
      const normalizedKey = normalizeCompanyKey(cleanedName);
      if (isPlaceholderCompanyName(sub.companyName) || isPlaceholderCompanyName(cleanedName) || !normalizedKey) {
        preDropped.placeholder = (preDropped.placeholder ?? 0) + 1;
        continue;
      }
      if (isWithdrawnOrCancelled(sub.companyName)) {
        preDropped.withdrawn = (preDropped.withdrawn ?? 0) + 1;
        continue;
      }
      const groupId = makeIpoId(normalizedKey, sub.boardType);
      const exists = groups.some(
        (g) =>
          g.id === groupId ||
          areSameCompany(
            { companyName: cleanedName, normalizedKey, boardType: sub.boardType },
            { companyName: g.normalizedKey, normalizedKey: g.normalizedKey, boardType: g.boardType }
          )
      );
      if (!exists) {
        groups.push({
          id: groupId,
          normalizedKey,
          boardType: sub.boardType,
          records: [],
          hasWithdrawn: false
        });
      }
    }
  }

  const candidateItems: IpoBundleItem[] = [];

  for (const group of groups) {
    if (group.hasWithdrawn) {
      preDropped.withdrawn = (preDropped.withdrawn ?? 0) + 1;
      continue;
    }

    const matchedExisting = existingItems.find((item) =>
      areSameCompany(item, {
        companyName: group.normalizedKey,
        normalizedKey: group.normalizedKey,
        boardType: group.boardType
      })
    );

    // Per-field fallback from existing item is allowed ONLY if lastSeenAtMs is within 24 hours
    const freshExisting = canMergeFromExisting(matchedExisting, nowMs)
      ? matchedExisting
      : undefined;

    const baseName =
      group.records.length > 0
        ? chooseBestCompanyName(
            group.records.map((r) => ({
              name: r.companyName,
              authoritative: isAuthoritativeSource(r.source, r.isAuthoritative)
            }))
          )
        : freshExisting?.companyName ??
          cleanCompanyName(
            newSubRecords.find((s) =>
              areSameCompany(
                { companyName: s.companyName, boardType: s.boardType },
                {
                  companyName: group.normalizedKey,
                  normalizedKey: group.normalizedKey,
                  boardType: group.boardType
                }
              )
            )?.companyName ?? group.normalizedKey
          );

    const target: IpoBundleItem = freshExisting
      ? {
          ...freshExisting,
          id: makeIpoId(group.normalizedKey, group.boardType),
          normalizedKey: group.normalizedKey,
          sources: [...freshExisting.sources],
          lastSeenAtMs: nowMs
        }
      : createEmptyItem(baseName, group.normalizedKey, group.boardType, nowMs);

    // Upgrade display name if an authoritative source provides a better one
    if (freshExisting && group.records.length > 0) {
      const authName = group.records.find((r) =>
        isAuthoritativeSource(r.source, r.isAuthoritative)
      )?.companyName;
      if (authName) {
        target.companyName = authName;
      }
    }

    // 1) Agreed Dates, Price Band, Lot Size (2 independent sources agree OR NSE/BSE)
    const openCandidates: SourcedValue<string>[] = [];
    const closeCandidates: SourcedValue<string>[] = [];
    const allotCandidates: SourcedValue<string>[] = [];
    const listCandidates: SourcedValue<string>[] = [];
    const priceCandidates: SourcedValue<{ low: number; high: number }>[] = [];
    const lotCandidates: SourcedValue<number>[] = [];
    const gmpObservations: GmpObservation[] = [];

    for (const r of group.records) {
      const family = getSourceFamily(r.source);
      const authoritative = isAuthoritativeSource(r.source, r.isAuthoritative);

      if (r.openDate) openCandidates.push({ value: r.openDate, family, authoritative });
      if (r.closeDate) closeCandidates.push({ value: r.closeDate, family, authoritative });
      if (r.allotmentDate) allotCandidates.push({ value: r.allotmentDate, family, authoritative });
      if (r.listingDate) listCandidates.push({ value: r.listingDate, family, authoritative });

      if (
        r.priceLow !== undefined &&
        r.priceHigh !== undefined &&
        r.priceLow > 0 &&
        r.priceHigh >= r.priceLow
      ) {
        priceCandidates.push({
          value: { low: r.priceLow, high: r.priceHigh },
          family,
          authoritative
        });
      }

      if (r.lotSize !== undefined && r.lotSize > 0) {
        lotCandidates.push({ value: r.lotSize, family, authoritative });
      }

      if (r.gmpRupees !== undefined && isValidGmp(r.gmpRupees)) {
        gmpObservations.push({
          family,
          gmpRupees: r.gmpRupees,
          updatedAtMs: r.gmpUpdatedAtMs ?? nowMs
        });
      }
    }

    const agreedOpen = resolveAgreedValue(openCandidates, (v) => v);
    const agreedClose = resolveAgreedValue(closeCandidates, (v) => v);
    const agreedAllot = resolveAgreedValue(allotCandidates, (v) => v);
    const agreedListing = resolveAgreedValue(listCandidates, (v) => v);
    const agreedPrice = resolveAgreedValue(priceCandidates, (v) => `${v.low}:${v.high}`);
    const agreedLot = resolveAgreedValue(lotCandidates, (v) => String(v));

    // Apply agreed dates only if non-empty and consistent (never overwrite good data with empty)
    const candidateOpen = agreedOpen || target.openDate;
    const candidateClose = agreedClose || target.closeDate;
    if (areDatesConsistent(candidateOpen, candidateClose, nowMs)) {
      if (agreedOpen) target.openDate = agreedOpen;
      if (agreedClose) target.closeDate = agreedClose;
    } else if (agreedOpen && agreedClose && areDatesConsistent(agreedOpen, agreedClose, nowMs)) {
      target.openDate = agreedOpen;
      target.closeDate = agreedClose;
    }

    if (agreedAllot) {
      target.allotmentDate = agreedAllot;
    } else if (!target.allotmentDate && allotCandidates.length > 0 && (target.openDate || target.closeDate)) {
      // Allow single-source allotmentDate (e.g. InvestorGain BoA Dt) only when core dates are verified
      const closeMs = parseFlexibleDateMs(target.closeDate, nowMs);
      const validAllot = allotCandidates.find((c) => {
        const aMs = parseFlexibleDateMs(c.value, nowMs);
        return aMs !== null && (closeMs === null || aMs >= closeMs);
      });
      if (validAllot) {
        target.allotmentDate = validAllot.value;
      }
    }

    if (agreedListing) target.listingDate = agreedListing;

    // Apply agreed price band (never overwrite good positive prices with 0)
    if (agreedPrice && agreedPrice.low > 0 && agreedPrice.high >= agreedPrice.low) {
      target.priceLow = agreedPrice.low;
      target.priceHigh = agreedPrice.high;
    }

    // Apply agreed lot size (never overwrite good positive lotSize with 0)
    if (agreedLot !== undefined && agreedLot > 0) {
      target.lotSize = agreedLot;
    }

    // Issue size (prefer authoritative, then positive non-zero; never overwrite with 0)
    const issueSizeRec =
      group.records.find(
        (r) =>
          isAuthoritativeSource(r.source, r.isAuthoritative) &&
          r.issueSizeCr !== undefined &&
          r.issueSizeCr > 0
      ) ?? group.records.find((r) => r.issueSizeCr !== undefined && r.issueSizeCr > 0);
    if (issueSizeRec?.issueSizeCr && issueSizeRec.issueSizeCr > 0) {
      target.issueSizeCr = issueSizeRec.issueSizeCr;
    }

    // Registrar (never overwrite non-empty with empty)
    const regNameRec =
      group.records.find(
        (r) =>
          isAuthoritativeSource(r.source, r.isAuthoritative) &&
          r.registrarName &&
          r.registrarName.trim().length > 0
      ) ?? group.records.find((r) => r.registrarName && r.registrarName.trim().length > 0);
    if (regNameRec?.registrarName) {
      target.registrarName = regNameRec.registrarName.trim();
    }

    const regUrlRec = group.records.find(
      (r) => r.registrarUrl && r.registrarUrl.trim().length > 0
    );
    if (regUrlRec?.registrarUrl) {
      target.registrarUrl = regUrlRec.registrarUrl.trim();
    }

    // GMP merge (recency vs >30% median; never overwrite existing GMP when no source reported GMP)
    const resolvedGmp = resolveGmp(gmpObservations);
    if (resolvedGmp) {
      if (resolvedGmp.gmpUpdatedAtMs >= target.gmpUpdatedAtMs || target.gmpUpdatedAtMs === 0) {
        target.gmpRupees = resolvedGmp.gmpRupees;
        target.gmpUpdatedAtMs = resolvedGmp.gmpUpdatedAtMs;
      }
    }

    // Compute canonical status & merge source attribution
    const statusHint = group.records.find((r) => r.statusHint)?.statusHint;
    target.status = computeIpoStatus(target, nowMs, statusHint ?? target.status);
    target.sources = Array.from(
      new Set([...target.sources, ...group.records.map((r) => r.source)])
    ).sort();
    target.lastSeenAtMs = nowMs;

    candidateItems.push(target);
  }

  // Apply subscription records ONLY to LIVE or recently closed (<= 2 days) IPOs in the new selection
  const withSubscriptions = candidateItems.map((item) => {
    const matchingSubs = newSubRecords.filter((sub) =>
      areSameCompany(item, {
        companyName: sub.companyName,
        boardType: sub.boardType
      })
    );
    return mergeSubscriptionsIntoItem(item, matchingSubs, nowMs);
  });

  return selectRelevantIpos(withSubscriptions, nowMs, maxIpos, preDropped);
}

/**
 * Convenience wrapper returning only the selected IPO array.
 */
export function mergeIpoCatalog(
  existingItems: IpoBundleItem[],
  newIpoRecords: RawIpoRecord[],
  newSubRecords: RawSubscriptionRecord[],
  nowMs: number = Date.now(),
  maxIpos: number = parseMaxIpos(process.env.MAX_IPOS)
): IpoBundleItem[] {
  return mergeIpoCatalogWithStats(existingItems, newIpoRecords, newSubRecords, nowMs, maxIpos).ipos;
}
