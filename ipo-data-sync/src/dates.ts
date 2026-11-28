import type { IpoBundleItem, IpoStatus } from "./types.js";

export const IST_OFFSET_MS = 5.5 * 60 * 60 * 1000;
export const DAY_MS = 24 * 60 * 60 * 1000;

const SHORT_MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec"
] as const;

const MONTH_MAP: Record<string, number> = {
  jan: 1,
  january: 1,
  feb: 2,
  february: 2,
  mar: 3,
  march: 3,
  apr: 4,
  april: 4,
  may: 5,
  jun: 6,
  june: 6,
  jul: 7,
  july: 7,
  aug: 8,
  august: 8,
  sep: 9,
  sept: 9,
  september: 9,
  oct: 10,
  october: 10,
  nov: 11,
  november: 11,
  dec: 12,
  december: 12
};

export interface IstDateParts {
  year: number;
  month: number; // 1..12
  day: number; // 1..31
  hour: number; // 0..23
  minute: number; // 0..59
  second: number; // 0..59
  dayOfWeek: number; // 0=Sun .. 6=Sat
}

export function toIstParts(utcMs: number): IstDateParts {
  const shifted = new Date(utcMs + IST_OFFSET_MS);
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
    second: shifted.getUTCSeconds(),
    dayOfWeek: shifted.getUTCDay()
  };
}

/** Returns UTC epoch ms corresponding to 00:00:00.000 IST of (year, month, day). */
export function istDateToUtcMs(year: number, month: number, day: number): number | null {
  if (year < 2000 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
    return null;
  }
  const probe = new Date(Date.UTC(year, month - 1, day));
  if (
    probe.getUTCFullYear() !== year ||
    probe.getUTCMonth() !== month - 1 ||
    probe.getUTCDate() !== day
  ) {
    return null;
  }
  return probe.getTime() - IST_OFFSET_MS;
}

export function startOfIstDayMs(utcMs: number): number {
  const p = toIstParts(utcMs);
  return Date.UTC(p.year, p.month - 1, p.day) - IST_OFFSET_MS;
}

/** Formats UTC ms into "dd MMM yyyy" in IST (e.g., "05 Nov 2026"). */
export function formatIstDate(utcMs: number): string {
  const p = toIstParts(utcMs);
  const dd = String(p.day).padStart(2, "0");
  const mmm = SHORT_MONTHS[p.month - 1];
  return `${dd} ${mmm} ${p.year}`;
}

function cleanDateToken(raw: string): string {
  return raw
    .replace(/\u00a0/g, " ")
    .replace(/(\d{1,2})(st|nd|rd|th)\b/gi, "$1")
    .replace(/\s+/g, " ")
    .trim();
}

function resolveYearlessDate(month: number, day: number, referenceMs: number): number | null {
  const refParts = toIstParts(referenceMs);
  const candidates = [refParts.year - 1, refParts.year, refParts.year + 1]
    .map((y) => istDateToUtcMs(y, month, day))
    .filter((ms): ms is number => ms !== null);

  if (candidates.length === 0) return null;
  const refStart = startOfIstDayMs(referenceMs);
  let best = candidates[0];
  let bestDiff = Math.abs(best - refStart);
  for (const c of candidates) {
    const diff = Math.abs(c - refStart);
    if (diff < bestDiff) {
      best = c;
      bestDiff = diff;
    }
  }
  return best;
}

/**
 * Flexible parser for Indian IPO date formats, normalizing all dates to 00:00:00 IST.
 * Supports: "25 Nov 2026", "25-Nov-2026", "Nov 25, 2026", "2026-11-25",
 * "25/11/2026", "25-11-2026", "25 Nov 26", yearless "25 Nov", ISO timestamps.
 */
export function parseFlexibleDateMs(raw: string | null | undefined, referenceMs: number = Date.now()): number | null {
  if (!raw) return null;
  const cleaned = cleanDateToken(raw);
  if (
    !cleaned ||
    /^(tba|na|n\/a|nil|none|-+|--+|upcoming|to be announced|expected|not announced)$/i.test(cleaned)
  ) {
    return null;
  }

  // Strip trailing time part like "10:30 AM" or "T00:00:00Z" after an ISO date
  const isoMatch = cleaned.match(/^(\d{4})-(\d{1,2})-(\d{1,2})(?:[T\s].*)?$/);
  if (isoMatch) {
    return istDateToUtcMs(Number(isoMatch[1]), Number(isoMatch[2]), Number(isoMatch[3]));
  }

  // dd-MM-yyyy or dd/MM/yyyy
  const dmyNum = cleaned.match(/^(\d{1,2})[\/\-](\d{1,2})[\/\-](\d{2,4})$/);
  if (dmyNum) {
    const day = Number(dmyNum[1]);
    const month = Number(dmyNum[2]);
    const rawYear = Number(dmyNum[3]);
    const year = rawYear < 100 ? 2000 + rawYear : rawYear;
    return istDateToUtcMs(year, month, day);
  }

  // dd MMM yyyy or dd-MMM-yyyy or dd MMM yy (optional trailing time)
  const dmyText = cleaned.match(/^(\d{1,2})[\s\-\/,.]+([A-Za-z]{3,9})[\s\-\/,.]+(\d{2,4})(?:\s+.*)?$/);
  if (dmyText) {
    const day = Number(dmyText[1]);
    const month = MONTH_MAP[dmyText[2].toLowerCase()];
    const rawYear = Number(dmyText[3]);
    const year = rawYear < 100 ? 2000 + rawYear : rawYear;
    if (month) return istDateToUtcMs(year, month, day);
  }

  // MMM dd, yyyy or MMM dd yyyy
  const mdyText = cleaned.match(/^([A-Za-z]{3,9})[\s\-\/]+(\d{1,2})(?:[\s,]+(\d{2,4}))(?:\s+.*)?$/);
  if (mdyText) {
    const month = MONTH_MAP[mdyText[1].toLowerCase()];
    const day = Number(mdyText[2]);
    const rawYear = Number(mdyText[3]);
    const year = rawYear < 100 ? 2000 + rawYear : rawYear;
    if (month) return istDateToUtcMs(year, month, day);
  }

  // Yearless: "25 Nov" or "25-Nov"
  const dmText = cleaned.match(/^(\d{1,2})[\s\-]+([A-Za-z]{3,9})$/);
  if (dmText) {
    const day = Number(dmText[1]);
    const month = MONTH_MAP[dmText[2].toLowerCase()];
    if (month) return resolveYearlessDate(month, day, referenceMs);
  }

  // Yearless: "Nov 25"
  const mdText = cleaned.match(/^([A-Za-z]{3,9})[\s\-]+(\d{1,2})$/);
  if (mdText) {
    const month = MONTH_MAP[mdText[1].toLowerCase()];
    const day = Number(mdText[2]);
    if (month) return resolveYearlessDate(month, day, referenceMs);
  }

  return null;
}

export function normalizeDateString(raw: string | null | undefined, referenceMs: number = Date.now()): string | undefined {
  const ms = parseFlexibleDateMs(raw, referenceMs);
  return ms === null ? undefined : formatIstDate(ms);
}

/**
 * Parses common Indian IPO date ranges such as:
 * - "25 Nov 2026 - 27 Nov 2026"
 * - "25-27 Nov 2026"
 * - "25 Nov - 27 Nov 2026"
 * - "Nov 25 - 27, 2026"
 * - "25 Nov to 27 Nov"
 */
export function parseDateRange(
  raw: string | null | undefined,
  referenceMs: number = Date.now()
): { openDate?: string; closeDate?: string } {
  if (!raw) return {};
  const cleaned = cleanDateToken(raw).replace(/[–—]/g, "-");
  if (!cleaned) return {};

  // Compact same-month range: "25-27 Nov 2026" or "25 - 27 Nov"
  const sameMonthDmy = cleaned.match(/^(\d{1,2})\s*-\s*(\d{1,2})\s+([A-Za-z]{3,9})(?:\s*,?\s*(\d{2,4}))?$/);
  if (sameMonthDmy) {
    const suffix = sameMonthDmy[4] ? `${sameMonthDmy[3]} ${sameMonthDmy[4]}` : sameMonthDmy[3];
    const openMs = parseFlexibleDateMs(`${sameMonthDmy[1]} ${suffix}`, referenceMs);
    const closeMs = parseFlexibleDateMs(`${sameMonthDmy[2]} ${suffix}`, referenceMs);
    return {
      openDate: openMs !== null ? formatIstDate(openMs) : undefined,
      closeDate: closeMs !== null ? formatIstDate(closeMs) : undefined
    };
  }

  // Compact same-month MDY: "Nov 25-27, 2026" or "Nov 25 - 27"
  const sameMonthMdy = cleaned.match(/^([A-Za-z]{3,9})\s+(\d{1,2})\s*-\s*(\d{1,2})(?:\s*,?\s*(\d{2,4}))?$/);
  if (sameMonthMdy) {
    const yearPart = sameMonthMdy[4] ? `, ${sameMonthMdy[4]}` : "";
    const openMs = parseFlexibleDateMs(`${sameMonthMdy[1]} ${sameMonthMdy[2]}${yearPart}`, referenceMs);
    const closeMs = parseFlexibleDateMs(`${sameMonthMdy[1]} ${sameMonthMdy[3]}${yearPart}`, referenceMs);
    return {
      openDate: openMs !== null ? formatIstDate(openMs) : undefined,
      closeDate: closeMs !== null ? formatIstDate(closeMs) : undefined
    };
  }

  // Two-part range separated by " to " or " - "
  const parts = cleaned.split(/\s+(?:to|-)\s+/i);
  if (parts.length === 2) {
    const rightMs = parseFlexibleDateMs(parts[1], referenceMs);
    let leftStr = parts[0];
    // If right has a year and left does not, inherit year from right
    const rightYear = parts[1].match(/\b(20\d{2})\b/);
    if (rightYear && !/\b20\d{2}\b/.test(leftStr)) {
      leftStr = `${leftStr} ${rightYear[1]}`;
    }
    const leftMs = parseFlexibleDateMs(leftStr, rightMs ?? referenceMs);
    return {
      openDate: leftMs !== null ? formatIstDate(leftMs) : undefined,
      closeDate: rightMs !== null ? formatIstDate(rightMs) : undefined
    };
  }

  // Single date fallback
  const singleMs = parseFlexibleDateMs(cleaned, referenceMs);
  if (singleMs !== null) {
    return { openDate: formatIstDate(singleMs) };
  }
  return {};
}

/** Adds N working days (Mon–Fri) in IST to an IST start-of-day UTC ms timestamp. */
export function addWorkingDaysIst(istStartMs: number, workingDays: number): number {
  let current = startOfIstDayMs(istStartMs);
  let remaining = workingDays;
  while (remaining > 0) {
    current += DAY_MS;
    const dow = toIstParts(current).dayOfWeek;
    if (dow !== 0 && dow !== 6) {
      remaining -= 1;
    }
  }
  return current;
}

/**
 * Determines whether an IPO is already listed.
 * If listingDate is present, listed when today (IST) >= listingDate.
 * If listingDate is missing, uses closeDate + 3 working days (IST) and requires today > expectedListing.
 */
export function isIpoListed(
  item: Pick<IpoBundleItem, "closeDate" | "listingDate">,
  nowMs: number = Date.now()
): boolean {
  const today = startOfIstDayMs(nowMs);
  const listingMs = parseFlexibleDateMs(item.listingDate, nowMs);
  if (listingMs !== null) {
    return today >= listingMs;
  }
  const closeMs = parseFlexibleDateMs(item.closeDate, nowMs);
  if (closeMs !== null) {
    const expectedListing = addWorkingDaysIst(closeMs, 3);
    return today > expectedListing;
  }
  return false;
}

/**
 * Computes canonical status (LIVE | PRE_APPLY | UPCOMING | CLOSED) from IST dates.
 */
export function computeIpoStatus(
  item: Pick<IpoBundleItem, "openDate" | "closeDate" | "listingDate">,
  nowMs: number = Date.now(),
  statusHint?: IpoStatus
): IpoStatus {
  const today = startOfIstDayMs(nowMs);
  const openMs = parseFlexibleDateMs(item.openDate, nowMs);
  const closeMs = parseFlexibleDateMs(item.closeDate, nowMs);

  if (openMs !== null && closeMs !== null) {
    if (today >= openMs && today <= closeMs) return "LIVE";
    if (today > closeMs) return "CLOSED";
    const daysUntilOpen = Math.round((openMs - today) / DAY_MS);
    if (daysUntilOpen <= 2) return "PRE_APPLY";
    return "UPCOMING";
  }

  if (openMs !== null) {
    if (today === openMs) return "LIVE";
    if (today < openMs) {
      const daysUntilOpen = Math.round((openMs - today) / DAY_MS);
      return daysUntilOpen <= 2 ? "PRE_APPLY" : "UPCOMING";
    }
    return "CLOSED";
  }

  if (closeMs !== null) {
    return today <= closeMs ? "LIVE" : "CLOSED";
  }

  if (isIpoListed(item, nowMs)) return "CLOSED";
  return statusHint ?? "UPCOMING";
}

/**
 * Returns true if subscription data should be fetched/applied for this IPO:
 * ONLY IPOs that are LIVE or closed within the last 2 days (IST).
 */
export function isEligibleForSubscription(
  item: Pick<IpoBundleItem, "status" | "openDate" | "closeDate" | "listingDate">,
  nowMs: number = Date.now()
): boolean {
  const status = computeIpoStatus(item, nowMs, item.status);
  if (status === "LIVE") return true;
  if (status !== "CLOSED") return false;

  const closeMs = parseFlexibleDateMs(item.closeDate, nowMs);
  if (closeMs === null) return false;
  const today = startOfIstDayMs(nowMs);
  const daysSinceClose = Math.round((today - closeMs) / DAY_MS);
  return daysSinceClose >= 0 && daysSinceClose <= 2;
}

/**
 * In "all" mode, subscription scraping runs only between 10:00 and 18:00 IST on weekdays (Mon-Fri).
 */
export function isSubscriptionWindowIst(nowMs: number = Date.now()): boolean {
  const p = toIstParts(nowMs);
  const isWeekday = p.dayOfWeek >= 1 && p.dayOfWeek <= 5;
  if (!isWeekday) return false;
  const minutesOfDay = p.hour * 60 + p.minute;
  return minutesOfDay >= 10 * 60 && minutesOfDay <= 18 * 60;
}

/**
 * In "all" mode, catalog scraping runs at most once per hour.
 */
export function shouldRunCatalogInAllMode(
  lastCatalogSyncAtMs: number | undefined,
  nowMs: number = Date.now()
): boolean {
  if (!lastCatalogSyncAtMs || lastCatalogSyncAtMs <= 0) return true;
  return nowMs - lastCatalogSyncAtMs >= 60 * 60 * 1000;
}

export interface InvestorGainDateParams {
  mm: string;
  yyyy: string;
  fy: string;
  prevMm: string;
  prevYyyy: string;
  prevFy: string;
}

function toIndianFinancialYear(year: number, month: number): string {
  const startYear = month >= 4 ? year : year - 1;
  const endShort = String((startYear + 1) % 100).padStart(2, "0");
  return `${startYear}-${endShort}`;
}

export function getInvestorGainDateParams(nowMs: number = Date.now()): InvestorGainDateParams {
  const p = toIstParts(nowMs);
  const prevMonth = p.month === 1 ? 12 : p.month - 1;
  const prevYear = p.month === 1 ? p.year - 1 : p.year;

  return {
    mm: String(p.month).padStart(2, "0"),
    yyyy: String(p.year),
    fy: toIndianFinancialYear(p.year, p.month),
    prevMm: String(prevMonth).padStart(2, "0"),
    prevYyyy: String(prevYear),
    prevFy: toIndianFinancialYear(prevYear, prevMonth)
  };
}
