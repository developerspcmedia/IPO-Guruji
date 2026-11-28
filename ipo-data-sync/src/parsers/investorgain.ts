import { normalizeDateString, parseFlexibleDateMs } from "../dates.js";
import {
  cleanCompanyName,
  detectBoardType,
  isPlaceholderCompanyName,
  isWithdrawnOrCancelled
} from "../normalize.js";
import type { IpoStatus, RawIpoRecord, RawSubscriptionRecord } from "../types.js";
import {
  parseGmpRupees,
  parseInteger,
  parseIssueSizeCr,
  parsePriceBand,
  parseSubscriptionX,
  stripHtml
} from "./helpers.js";

type IgRow = Record<string, unknown>;

function extractReportRows(jsonText: string): IgRow[] {
  if (!jsonText.trim()) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(jsonText);
  } catch {
    return [];
  }
  if (Array.isArray(parsed)) {
    return parsed.filter((x): x is IgRow => typeof x === "object" && x !== null);
  }
  if (typeof parsed === "object" && parsed !== null) {
    const obj = parsed as Record<string, unknown>;
    const candidate = obj.reportTableData ?? obj.data ?? obj.rows;
    if (Array.isArray(candidate)) {
      return candidate.filter((x): x is IgRow => typeof x === "object" && x !== null);
    }
  }
  return [];
}

function getField(row: IgRow, keys: string[]): string {
  for (const key of keys) {
    const val = row[key];
    if (val !== undefined && val !== null) {
      const s = typeof val === "string" ? val : String(val);
      if (s.trim()) return s;
    }
  }
  // Case-insensitive fallback
  const lowerMap = new Map<string, string>();
  for (const [k, v] of Object.entries(row)) {
    if (v !== undefined && v !== null) {
      lowerMap.set(k.toLowerCase(), typeof v === "string" ? v : String(v));
    }
  }
  for (const key of keys) {
    const found = lowerMap.get(key.toLowerCase());
    if (found && found.trim()) return found;
  }
  return "";
}

function parseIgTimestampMs(raw: string, fallbackMs: number): number {
  const cleaned = stripHtml(raw);
  if (!cleaned) return fallbackMs;

  // e.g. "27-Nov-2026 14:35" or "27 Nov 2026 2:30 PM"
  const dateMs = parseFlexibleDateMs(cleaned, fallbackMs);
  if (dateMs === null) return fallbackMs;

  const timeMatch = cleaned.match(/(\d{1,2}):(\d{2})(?:\s*(AM|PM))?/i);
  if (!timeMatch) return dateMs;

  let hours = Number(timeMatch[1]);
  const minutes = Number(timeMatch[2]);
  const ampm = timeMatch[3]?.toUpperCase();
  if (ampm === "PM" && hours < 12) hours += 12;
  if (ampm === "AM" && hours === 12) hours = 0;

  return dateMs + (hours * 60 + minutes) * 60 * 1000;
}

function mapIgStatus(raw: string): IpoStatus | undefined {
  const s = stripHtml(raw).toLowerCase();
  if (!s) return undefined;
  if (s.includes("open") || s.includes("live")) return "LIVE";
  if (s.includes("pre")) return "PRE_APPLY";
  if (s.includes("upcoming") || s.includes("announce")) return "UPCOMING";
  if (s.includes("close") || s.includes("list") || s.includes("allot")) return "CLOSED";
  return undefined;
}

/**
 * Ported from Android LiveGmpRepository.parseInvestorGainJson.
 * Parses InvestorGain JSON endpoints (331 GMP, 377 Listed).
 */
export function parseInvestorGainJson(
  jsonText: string,
  sourceName: string = "investorgain",
  nowMs: number = Date.now()
): RawIpoRecord[] {
  const rows = extractReportRows(jsonText);
  const results: RawIpoRecord[] = [];

  for (const row of rows) {
    const rawNameHtml = getField(row, ["~ipo_name", "Name", "IPO", "Company", "~CompanyName"]);
    const rawNameText = stripHtml(rawNameHtml);
    const companyName = cleanCompanyName(rawNameText);
    if (isPlaceholderCompanyName(companyName)) continue;

    const categoryRaw = getField(row, ["~IPO_Category", "Category", "Board", "Type", "Exchange"]);
    const boardType = detectBoardType(rawNameText, stripHtml(categoryRaw), "MAINBOARD");

    const openDate = normalizeDateString(
      stripHtml(getField(row, ["~Srt_Open", "Open", "Open Date", "Issue Open"])),
      nowMs
    );
    const closeDate = normalizeDateString(
      stripHtml(getField(row, ["~Srt_Close", "Close", "Close Date", "Issue Close"])),
      nowMs
    );
    const allotmentDate = normalizeDateString(
      stripHtml(getField(row, ["~Srt_BoA_Dt", "BoA Dt", "Allotment", "Allotment Date"])),
      nowMs
    );
    const listingDate = normalizeDateString(
      stripHtml(getField(row, ["~str_listing_date", "Listing", "Listing Date"])),
      nowMs
    );

    const priceBand = parsePriceBand(
      getField(row, ["Price", "~Price", "Price Band", "Issue Price"])
    );
    const lotSize = parseInteger(getField(row, ["Lot", "Lot Size", "~LotSize", "Min Lot"]));
    const issueSizeCr = parseIssueSizeCr(
      getField(row, ["IPO Size", "Issue Size", "~IssueSize", "Size"])
    );

    const gmpRaw = getField(row, ["~gmp", "GMP", "GMP(₹)", "Last GMP"]);
    const gmpRupees = parseGmpRupees(gmpRaw);
    const gmpUpdatedRaw = getField(row, ["GMP Updated", "Updated", "Last Updated", "~Updated"]);
    const gmpUpdatedAtMs =
      gmpRupees !== undefined ? parseIgTimestampMs(gmpUpdatedRaw, nowMs) : undefined;

    const registrarName = stripHtml(getField(row, ["Registrar", "~Registrar"])) || undefined;
    const rawStatus = stripHtml(getField(row, ["Status", "~IPO_Status", "IPO Status"]));
    const statusHint = mapIgStatus(rawStatus);
    const isWithdrawn = isWithdrawnOrCancelled(rawNameText, rawStatus) || undefined;

    results.push({
      companyName,
      boardType,
      source: sourceName,
      isAuthoritative: false,
      isWithdrawn,
      statusHint,
      openDate,
      closeDate,
      allotmentDate,
      listingDate,
      priceLow: priceBand.priceLow,
      priceHigh: priceBand.priceHigh,
      lotSize,
      issueSizeCr,
      gmpRupees,
      gmpUpdatedAtMs,
      registrarName
    });
  }

  return results;
}

/**
 * Ported from Android LiveSubscriptionRepository.parseInvestorGainSubscriptionJson.
 * Parses InvestorGain Subscription JSON endpoint (333).
 */
export function parseInvestorGainSubscriptionJson(
  jsonText: string,
  sourceName: string = "investorgain_subscription",
  nowMs: number = Date.now()
): RawSubscriptionRecord[] {
  const rows = extractReportRows(jsonText);
  const results: RawSubscriptionRecord[] = [];

  for (const row of rows) {
    const rawNameText = stripHtml(
      getField(row, ["~ipo_name", "Name", "IPO", "Company", "~CompanyName"])
    );
    const companyName = cleanCompanyName(rawNameText);
    if (isPlaceholderCompanyName(companyName)) continue;

    const categoryRaw = getField(row, ["~IPO_Category", "Category", "Board", "Type"]);
    const boardType = detectBoardType(rawNameText, stripHtml(categoryRaw), "MAINBOARD");

    const qibSubX = parseSubscriptionX(getField(row, ["QIB", "QIBs", "qib"]));
    const bNiiSubX = parseSubscriptionX(
      getField(row, ["BNII", "bNII", "B-HNI", "BHNI", "bNII (>10L)", "HNI", "NII"])
    );
    const sNiiSubX = parseSubscriptionX(
      getField(row, ["SNII", "sNII", "S-HNI", "SHNI", "sNII (<10L)"])
    );
    const retailSubX = parseSubscriptionX(
      getField(row, ["RII", "Retail", "retail", "RIIs"])
    );
    const employeeSubX = parseSubscriptionX(
      getField(row, ["Emp", "Employee", "EMP", "Employees"])
    );
    const totalSubX = parseSubscriptionX(
      getField(row, ["Total", "Total Subscription", "total"])
    );

    if (
      qibSubX === undefined &&
      bNiiSubX === undefined &&
      sNiiSubX === undefined &&
      retailSubX === undefined &&
      employeeSubX === undefined &&
      totalSubX === undefined
    ) {
      continue;
    }

    const updatedRaw = getField(row, ["Updated", "Last Updated", "Updated On"]);
    const subUpdatedAtMs = parseIgTimestampMs(updatedRaw, nowMs);

    results.push({
      companyName,
      boardType,
      source: sourceName,
      isAuthoritative: false,
      qibSubX,
      bNiiSubX,
      sNiiSubX,
      retailSubX,
      employeeSubX,
      totalSubX,
      subUpdatedAtMs
    });
  }

  return results;
}
