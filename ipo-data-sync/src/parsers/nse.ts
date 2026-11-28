import { normalizeDateString } from "../dates.js";
import { cleanCompanyName, detectBoardType, isPlaceholderCompanyName } from "../normalize.js";
import type { IpoStatus, RawIpoRecord, RawSubscriptionRecord } from "../types.js";
import {
  parseInteger,
  parseIssueSizeCr,
  parsePriceBand,
  parseSubscriptionX,
  stripHtml
} from "./helpers.js";

type NseRow = Record<string, unknown>;

function extractNseRows(jsonText: string): NseRow[] {
  if (!jsonText.trim()) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(jsonText);
  } catch {
    return [];
  }

  if (Array.isArray(parsed)) {
    return parsed.filter((x): x is NseRow => typeof x === "object" && x !== null);
  }
  if (typeof parsed === "object" && parsed !== null) {
    const obj = parsed as Record<string, unknown>;
    if (Array.isArray(obj.data)) {
      return obj.data.filter((x): x is NseRow => typeof x === "object" && x !== null);
    }
  }
  return [];
}

function asStr(val: unknown): string {
  if (typeof val === "string") return stripHtml(val);
  if (typeof val === "number" && Number.isFinite(val)) return String(val);
  return "";
}

function mapNseStatus(rawStatus: string): IpoStatus | undefined {
  const s = rawStatus.toLowerCase();
  if (s.includes("active") || s.includes("open") || s.includes("live")) return "LIVE";
  if (s.includes("forthcoming") || s.includes("upcoming") || s.includes("pre")) return "UPCOMING";
  if (s.includes("close") || s.includes("list")) return "CLOSED";
  return undefined;
}

/**
 * Ported from Android LiveGmpRepository.parseNseIpoJson.
 * Marks records with isAuthoritative = true (NSE exchange authority).
 */
export function parseNseIpoJson(
  jsonText: string,
  sourceName: string = "nse",
  referenceMs: number = Date.now()
): RawIpoRecord[] {
  const rows = extractNseRows(jsonText);
  const results: RawIpoRecord[] = [];

  for (const row of rows) {
    const rawName =
      asStr(row.companyName) ||
      asStr(row.company_name) ||
      asStr(row.issuerName) ||
      asStr(row.symbol);

    const companyName = cleanCompanyName(rawName);
    if (isPlaceholderCompanyName(companyName)) continue;

    const series = asStr(row.series) || asStr(row.subType) || asStr(row.category);
    const boardType = detectBoardType(rawName, series, "MAINBOARD");

    const openDate = normalizeDateString(
      asStr(row.issueStartDate) || asStr(row.openDate) || asStr(row.startDate),
      referenceMs
    );
    const closeDate = normalizeDateString(
      asStr(row.issueEndDate) || asStr(row.closeDate) || asStr(row.endDate),
      referenceMs
    );
    const listingDate = normalizeDateString(
      asStr(row.listingDate) || asStr(row.dateOfListing),
      referenceMs
    );

    const priceRaw =
      asStr(row.issuePrice) || asStr(row.priceRange) || asStr(row.priceBand);
    const { priceLow, priceHigh } = parsePriceBand(priceRaw);

    const lotSize = parseInteger(
      asStr(row.minBidQuantity) || asStr(row.lotSize) || asStr(row.marketLot)
    );

    // NSE issueSize can be shares or Cr; only accept if explicitly labeled Cr or reasonable <= 100000
    const rawIssueSize = asStr(row.issueSizeCr) || asStr(row.issueSize);
    let issueSizeCr: number | undefined;
    if (/cr/i.test(rawIssueSize) || asStr(row.issueSizeCr)) {
      issueSizeCr = parseIssueSizeCr(rawIssueSize);
    } else if (rawIssueSize && priceHigh) {
      const shares = parseInteger(rawIssueSize);
      if (shares && shares > 10_000) {
        issueSizeCr = Number(((shares * priceHigh) / 1e7).toFixed(2));
      }
    }

    const registrarName = asStr(row.registrar) || asStr(row.registrarName) || undefined;
    const statusHint = mapNseStatus(asStr(row.status));

    results.push({
      companyName,
      boardType,
      source: sourceName,
      isAuthoritative: true,
      statusHint,
      openDate,
      closeDate,
      listingDate,
      priceLow,
      priceHigh,
      lotSize,
      issueSizeCr,
      registrarName
    });
  }

  return results;
}

/**
 * Ported from Android LiveSubscriptionRepository.parseNseSubscriptionJson.
 */
export function parseNseSubscriptionJson(
  jsonText: string,
  sourceName: string = "nse_subscription",
  nowMs: number = Date.now()
): RawSubscriptionRecord[] {
  const rows = extractNseRows(jsonText);
  const results: RawSubscriptionRecord[] = [];

  for (const row of rows) {
    const rawName =
      asStr(row.companyName) ||
      asStr(row.company_name) ||
      asStr(row.issuerName) ||
      asStr(row.symbol);

    const companyName = cleanCompanyName(rawName);
    if (isPlaceholderCompanyName(companyName)) continue;

    const series = asStr(row.series) || asStr(row.subType) || asStr(row.category);
    const boardType = detectBoardType(rawName, series, "MAINBOARD");

    const qibSubX = parseSubscriptionX(asStr(row.qib) || asStr(row.qibSubX));
    const bNiiSubX = parseSubscriptionX(
      asStr(row.bNii) || asStr(row.bnii) || asStr(row.nii) || asStr(row.hni)
    );
    const sNiiSubX = parseSubscriptionX(asStr(row.sNii) || asStr(row.snii));
    const retailSubX = parseSubscriptionX(
      asStr(row.retail) || asStr(row.rii) || asStr(row.retailSubX)
    );
    const employeeSubX = parseSubscriptionX(asStr(row.employee) || asStr(row.emp));
    const totalSubX = parseSubscriptionX(
      asStr(row.noOfTime) || asStr(row.total) || asStr(row.totalSubX)
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

    results.push({
      companyName,
      boardType,
      source: sourceName,
      isAuthoritative: true,
      qibSubX,
      bNiiSubX,
      sNiiSubX,
      retailSubX,
      employeeSubX,
      totalSubX,
      subUpdatedAtMs: nowMs
    });
  }

  return results;
}
