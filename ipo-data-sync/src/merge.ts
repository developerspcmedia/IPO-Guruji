import { createHash } from "node:crypto";
import {
  computeIpoStatus,
  DAY_MS,
  isEligibleForSubscription,
  isIpoListed,
  parseFlexibleDateMs
} from "./dates.js";
import {
  areSameCompany,
  cleanCompanyName,
  makeIpoId,
  normalizeCompanyKey
} from "./normalize.js";
import type {
  IpoBundleItem,
  IpoLiveBundleDoc,
  IpoStatus,
  RawIpoRecord,
  RawSubscriptionRecord
} from "./types.js";
import {
  isValidGmp,
  isValidSubscriptionValue,
  MAX_BIDDING_WINDOW_DAYS
} from "./validation.js";

export const MAX_LISTED_IPOS = 25;
export const MAX_BUNDLE_BYTES = 900 * 1024; // 900 KB strict cap
export const GMP_DISAGREEMENT_THRESHOLD = 0.30; // 30%

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
  boardType: IpoBundleItem["boardType"]
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
    sources: []
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

const STATUS_SORT_ORDER: Record<IpoStatus, number> = {
  LIVE: 0,
  PRE_APPLY: 1,
  UPCOMING: 2,
  CLOSED: 3
};

function getRecencyMs(item: IpoBundleItem, nowMs: number): number {
  return (
    parseFlexibleDateMs(item.listingDate, nowMs) ??
    parseFlexibleDateMs(item.closeDate, nowMs) ??
    parseFlexibleDateMs(item.openDate, nowMs) ??
    0
  );
}

export function sortIpoBundleItems(
  items: IpoBundleItem[],
  nowMs: number
): IpoBundleItem[] {
  return [...items].sort((a, b) => {
    const statusDiff = STATUS_SORT_ORDER[a.status] - STATUS_SORT_ORDER[b.status];
    if (statusDiff !== 0) return statusDiff;
    const recencyDiff = getRecencyMs(b, nowMs) - getRecencyMs(a, nowMs);
    if (recencyDiff !== 0) return recencyDiff;
    return a.id.localeCompare(b.id);
  });
}

/**
 * Keeps all upcoming + current IPOs plus at most the 25 most recent listed IPOs.
 * Drops everything else.
 */
export function applyRetentionPolicy(
  items: IpoBundleItem[],
  nowMs: number = Date.now(),
  maxListed: number = MAX_LISTED_IPOS
): IpoBundleItem[] {
  const activeOrUpcoming: IpoBundleItem[] = [];
  const listed: IpoBundleItem[] = [];

  for (const item of items) {
    if (isIpoListed(item, nowMs)) {
      listed.push(item);
    } else {
      activeOrUpcoming.push(item);
    }
  }

  // Sort listed newest first
  listed.sort((a, b) => {
    const diff = getRecencyMs(b, nowMs) - getRecencyMs(a, nowMs);
    return diff !== 0 ? diff : a.id.localeCompare(b.id);
  });

  const retainedListed = listed.slice(0, maxListed);
  return sortIpoBundleItems([...activeOrUpcoming, ...retainedListed], nowMs);
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
 * Full merge pipeline:
 * 1. Groups newly scraped IPO records by strict company identity (legal suffixes only, same boardType).
 * 2. Applies GMP recency / >30% median rule and 2-source agreement / NSE authority rule for dates, price band, lot size.
 * 3. Merges into existing Firestore items WITHOUT ever overwriting good values with empty/zero.
 * 4. Applies subscription updates ONLY to IPOs that are LIVE or closed within the last 2 days.
 * 5. Recomputes IST statuses and enforces retention (upcoming + current + 25 most recent listed).
 */
export function mergeIpoCatalog(
  existingItems: IpoBundleItem[],
  newIpoRecords: RawIpoRecord[],
  newSubRecords: RawSubscriptionRecord[],
  nowMs: number = Date.now()
): IpoBundleItem[] {
  // Group new IPO records into distinct companies using strict areSameCompany matching
  interface CompanyGroup {
    normalizedKey: string;
    boardType: IpoBundleItem["boardType"];
    records: RawIpoRecord[];
  }

  const groups: CompanyGroup[] = [];
  for (const rec of newIpoRecords) {
    const cleanedName = cleanCompanyName(rec.companyName);
    const normalizedKey = normalizeCompanyKey(cleanedName);
    if (!normalizedKey) continue;

    const existingGroup = groups.find((g) =>
      areSameCompany(
        { companyName: cleanedName, normalizedKey, boardType: rec.boardType },
        { companyName: g.normalizedKey, normalizedKey: g.normalizedKey, boardType: g.boardType }
      )
    );
    if (existingGroup) {
      existingGroup.records.push({ ...rec, companyName: cleanedName });
    } else {
      groups.push({
        normalizedKey,
        boardType: rec.boardType,
        records: [{ ...rec, companyName: cleanedName }]
      });
    }
  }

  // Start with a cloned list of existing items so we never mutate input
  const mergedList: IpoBundleItem[] = existingItems.map((item) => ({
    ...item,
    sources: [...item.sources]
  }));

  for (const group of groups) {
    const existingIdx = mergedList.findIndex((item) =>
      areSameCompany(item, {
        companyName: group.normalizedKey,
        normalizedKey: group.normalizedKey,
        boardType: group.boardType
      })
    );

    const existing = existingIdx >= 0 ? mergedList[existingIdx] : undefined;
    const baseName = chooseBestCompanyName(
      group.records.map((r) => ({
        name: r.companyName,
        authoritative: isAuthoritativeSource(r.source, r.isAuthoritative)
      }))
    );

    const target: IpoBundleItem = existing
      ? { ...existing, sources: [...existing.sources] }
      : createEmptyItem(baseName, group.normalizedKey, group.boardType);

    // Upgrade display name if an authoritative source provides a better one
    if (existing) {
      const authName = group.records.find((r) =>
        isAuthoritativeSource(r.source, r.isAuthoritative)
      )?.companyName;
      if (authName) {
        target.companyName = authName;
      }
    }

    // 1) Agreed Dates (2 independent sources agree OR NSE/BSE)
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

    if (agreedAllot) target.allotmentDate = agreedAllot;
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

    // Merge source list
    const statusHint = group.records.find((r) => r.statusHint)?.statusHint;
    target.status = computeIpoStatus(target, nowMs, statusHint ?? target.status);
    target.sources = Array.from(
      new Set([...target.sources, ...group.records.map((r) => r.source)])
    ).sort();

    if (existingIdx >= 0) {
      mergedList[existingIdx] = target;
    } else {
      mergedList.push(target);
    }
  }

  // Recompute status for untouched existing items as calendar days advance
  const refreshed = mergedList.map((item) => ({
    ...item,
    status: computeIpoStatus(item, nowMs, item.status)
  }));

  // Apply subscription records ONLY to LIVE or recently closed (<= 2 days) IPOs
  const withSubscriptions = refreshed.map((item) => {
    const matchingSubs = newSubRecords.filter((sub) =>
      areSameCompany(item, {
        companyName: sub.companyName,
        boardType: sub.boardType
      })
    );
    return mergeSubscriptionsIntoItem(item, matchingSubs, nowMs);
  });

  return applyRetentionPolicy(withSubscriptions, nowMs);
}
