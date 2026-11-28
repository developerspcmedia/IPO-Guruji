import type { BoardType } from "./types.js";

/**
 * STRICT legal suffixes ONLY as stop words.
 * Distinguishing words like "industries", "technologies", "solutions", "services",
 * "enterprises", "infra", "projects" are intentionally NOT stop words so
 * "Alpha Industries Ltd" and "Alpha Technologies Ltd" never merge.
 */
export const LEGAL_STOP_WORDS = new Set<string>([
  "ltd",
  "limited",
  "pvt",
  "private",
  "llp",
  "inc",
  "plc",
  "corp",
  "corporation",
  "co",
  "company"
]);

const PLACEHOLDER_EXACT = new Set<string>([
  "tba",
  "tbd",
  "na",
  "n/a",
  "nil",
  "none",
  "null",
  "unknown",
  "company name",
  "ipo name",
  "issue name",
  "issuer name",
  "company",
  "name",
  "total",
  "grand total",
  "sub total",
  "subtotal",
  "summary",
  "particulars",
  "ipo",
  "sme ipo",
  "mainboard ipo",
  "upcoming ipo",
  "live ipo",
  "recent ipo",
  "sr no",
  "s no",
  "sl no"
]);

/** Cleans HTML/source suffixes like "(NSE SME)", "IPO", "[Mainboard]" without stripping distinguishing words. */
export function cleanCompanyName(raw: string): string {
  let s = raw
    .replace(/\u00a0/g, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&nbsp;/gi, " ")
    .replace(/\s*\((?:nse\s*sme|bse\s*sme|sme|mainboard|nse|bse|nse\s*emerge|bse\s*sme\s*ipo|ipo|upcoming|open|closed|listed)[^)]*\)/gi, "")
    .replace(/\s*\[(?:nse\s*sme|bse\s*sme|sme|mainboard|nse|bse|ipo)[^\]]*\]/gi, "")
    .replace(/\s+(?:nse\s+sme|bse\s+sme|mainboard|sme)?\s*ipo\s*(?:gmp|review|subscription|status|allotment|date|price)?$/i, "")
    .replace(/\s+(?:drhp|rhp)$/i, "")
    .replace(/\s+/g, " ")
    .trim();

  // Strip stray trailing punctuation
  s = s.replace(/[\-:|•,]+$/g, "").trim();
  return s;
}

export function isPlaceholderCompanyName(raw: string | null | undefined): boolean {
  if (!raw) return true;
  const cleaned = cleanCompanyName(raw);
  if (!cleaned || cleaned.length < 2) return true;

  const lower = cleaned.toLowerCase().replace(/[^a-z0-9/\s]+/g, " ").replace(/\s+/g, " ").trim();
  if (!lower) return true;
  if (PLACEHOLDER_EXACT.has(lower)) return true;

  // Reject pure numbers/punctuation (e.g., "123", "---", "₹45")
  const alphaCount = (cleaned.match(/[a-zA-Z]/g) ?? []).length;
  if (alphaCount < 2) return true;

  // Reject table header phrases
  if (/^(company|ipo|issue)\s+name\b/i.test(lower)) return true;
  if (/^(total|grand\s+total)\b/i.test(lower)) return true;

  return false;
}

/**
 * Detects MAINBOARD vs SME using word-boundary regex so names like "Sree Cosmetics Ltd"
 * are classified as MAINBOARD.
 */
export function detectBoardType(
  companyName: string,
  contextText: string = "",
  defaultBoardType: BoardType = "MAINBOARD"
): BoardType {
  const combined = `${companyName} ${contextText}`.toLowerCase();
  if (/\bsme\b/.test(combined) || /\bnsesme\b/.test(combined) || /\bbsesme\b/.test(combined) || /\bemerge\b/.test(combined)) {
    return "SME";
  }
  if (/\bmainboard\b/.test(combined) || /\bmain\s+board\b/.test(combined)) {
    return "MAINBOARD";
  }
  return defaultBoardType;
}

/**
 * Tokenizes a company name into lowercase alphanumeric tokens, dropping ONLY legal suffixes.
 */
export function tokenizeCompanyName(raw: string): string[] {
  const cleaned = cleanCompanyName(raw)
    .toLowerCase()
    .replace(/&/g, " ")
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();

  if (!cleaned) return [];
  return cleaned
    .split(" ")
    .filter((token) => token.length > 0 && !LEGAL_STOP_WORDS.has(token));
}

export function normalizeCompanyKey(raw: string): string {
  return tokenizeCompanyName(raw).join(" ");
}

export function makeIpoId(normalizedKey: string, boardType: BoardType): string {
  const slug = normalizedKey
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return `${slug}-${boardType.toLowerCase()}`;
}

/**
 * Company identity matcher:
 * - Always requires the SAME boardType.
 * - Uses ONLY legal suffixes as stop words.
 * - "Alpha Industries Ltd" and "Alpha Technologies Ltd" do NOT match.
 * - "Alpha Industries Ltd" and "Alpha Industries Limited" DO match.
 */
export function areSameCompany(
  a: { companyName: string; normalizedKey?: string; boardType: BoardType },
  b: { companyName: string; normalizedKey?: string; boardType: BoardType }
): boolean {
  if (a.boardType !== b.boardType) return false;

  const tokensA = a.normalizedKey ? a.normalizedKey.split(/\s+/).filter(Boolean) : tokenizeCompanyName(a.companyName);
  const tokensB = b.normalizedKey ? b.normalizedKey.split(/\s+/).filter(Boolean) : tokenizeCompanyName(b.companyName);

  if (tokensA.length === 0 || tokensB.length === 0) return false;

  // Exact normalized token sequence match
  if (tokensA.join(" ") === tokensB.join(" ")) return true;

  // Concatenated form match (handles e.g. "Pay tm" vs "Paytm" while keeping "alphaindustries" !== "alphatechnologies")
  if (tokensA.join("") === tokensB.join("")) return true;

  return false;
}
