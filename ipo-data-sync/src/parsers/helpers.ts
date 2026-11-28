import * as cheerio from "cheerio";

/** Strips HTML tags and decodes HTML entities cleanly using cheerio. */
export function stripHtml(raw: string | null | undefined): string {
  if (!raw) return "";
  if (!raw.includes("<") && !raw.includes("&")) {
    return raw.replace(/\u00a0/g, " ").replace(/\s+/g, " ").trim();
  }
  const $ = cheerio.load(`<div>${raw}</div>`);
  return $("div").text().replace(/\u00a0/g, " ").replace(/\s+/g, " ").trim();
}

/** Parses first finite number from text, stripping currency symbols, commas, and suffixes. */
export function parseNumericValue(raw: string | number | null | undefined): number | undefined {
  if (raw === null || raw === undefined) return undefined;
  if (typeof raw === "number") {
    return Number.isFinite(raw) ? raw : undefined;
  }
  const cleaned = stripHtml(raw)
    .replace(/[₹$€£,]/g, "")
    .replace(/\u2212/g, "-")
    .trim();

  if (!cleaned || /^(-+|--+|na|n\/a|tba|nil|none)$/i.test(cleaned)) {
    return undefined;
  }

  const match = cleaned.match(/[+-]?\d+(?:\.\d+)?/);
  if (!match) return undefined;
  const num = Number(match[0]);
  return Number.isFinite(num) ? num : undefined;
}

/**
 * Parses a price band string like "₹275 to ₹289", "275 - 289", "₹120 per share", "289".
 */
export function parsePriceBand(
  raw: string | number | null | undefined
): { priceLow?: number; priceHigh?: number } {
  if (raw === null || raw === undefined) return {};
  if (typeof raw === "number") {
    return Number.isFinite(raw) && raw > 0 ? { priceLow: raw, priceHigh: raw } : {};
  }

  const cleaned = stripHtml(raw)
    .replace(/[₹$€£,]/g, "")
    .replace(/rs\.?/gi, "")
    .replace(/[–—]/g, "-")
    .trim();

  if (!cleaned || /^(-+|--+|na|n\/a|tba|nil|none)$/i.test(cleaned)) {
    return {};
  }

  // Range: "275 to 289" or "275 - 289"
  const rangeMatch = cleaned.match(/(\d+(?:\.\d+)?)\s*(?:-|to)\s*(\d+(?:\.\d+)?)/i);
  if (rangeMatch) {
    const low = Number(rangeMatch[1]);
    const high = Number(rangeMatch[2]);
    if (Number.isFinite(low) && Number.isFinite(high)) {
      return { priceLow: low, priceHigh: high };
    }
  }

  // Single price
  const singleMatch = cleaned.match(/(\d+(?:\.\d+)?)/);
  if (singleMatch) {
    const val = Number(singleMatch[1]);
    if (Number.isFinite(val)) {
      return { priceLow: val, priceHigh: val };
    }
  }

  return {};
}

/**
 * Parses GMP values such as "₹45", "+45 (15.6%)", "-12", "₹-15 (-5%)", "0", "--".
 * Returns undefined when GMP is missing/placeholder ("--", "TBA", "NA").
 */
export function parseGmpRupees(raw: string | number | null | undefined): number | undefined {
  if (raw === null || raw === undefined) return undefined;
  if (typeof raw === "number") {
    return Number.isFinite(raw) ? raw : undefined;
  }

  const cleaned = stripHtml(raw)
    .replace(/\([^)]*\)/g, "") // remove percentage in parens e.g. "(15.6%)"
    .replace(/[₹,]/g, "")
    .replace(/rs\.?/gi, "")
    .replace(/\u2212/g, "-")
    .trim();

  if (!cleaned || /^(-+|--+|na|n\/a|tba|nil|none)$/i.test(cleaned)) {
    return undefined;
  }

  const match = cleaned.match(/([+-]?\s*\d+(?:\.\d+)?)/);
  if (!match) return undefined;
  const num = Number(match[1].replace(/\s+/g, ""));
  return Number.isFinite(num) ? Math.round(num) : undefined;
}

/**
 * Parses issue size in Crores from strings like "₹2,900.00 Cr", "45.60 Crore", "1200 Cr".
 */
export function parseIssueSizeCr(raw: string | number | null | undefined): number | undefined {
  if (raw === null || raw === undefined) return undefined;
  if (typeof raw === "number") {
    return Number.isFinite(raw) && raw >= 0 ? raw : undefined;
  }

  const cleaned = stripHtml(raw).replace(/[₹,]/g, "").trim();
  if (!cleaned || /^(-+|--+|na|n\/a|tba|nil|none)$/i.test(cleaned)) {
    return undefined;
  }

  const match = cleaned.match(/(\d+(?:\.\d+)?)/);
  if (!match) return undefined;
  const val = Number(match[1]);
  if (!Number.isFinite(val)) return undefined;

  if (/\blakh/i.test(cleaned)) {
    return Number((val / 100).toFixed(2));
  }
  return Number(val.toFixed(2));
}

/**
 * Parses subscription multiplier such as "12.45x", "12.45 times", "0.85", "--".
 */
export function parseSubscriptionX(raw: string | number | null | undefined): number | undefined {
  if (raw === null || raw === undefined) return undefined;
  if (typeof raw === "number") {
    return Number.isFinite(raw) ? raw : undefined;
  }

  const cleaned = stripHtml(raw)
    .replace(/[,]/g, "")
    .replace(/times|x$/gi, "")
    .trim();

  if (!cleaned || /^(-+|--+|na|n\/a|tba|nil|none)$/i.test(cleaned)) {
    return undefined;
  }

  const match = cleaned.match(/^([+-]?\d+(?:\.\d+)?)/);
  if (!match) return undefined;
  const val = Number(match[1]);
  return Number.isFinite(val) ? Number(val.toFixed(2)) : undefined;
}

export function parseInteger(raw: string | number | null | undefined): number | undefined {
  const num = parseNumericValue(raw);
  if (num === undefined) return undefined;
  return Math.trunc(num);
}

/**
 * Extracts a safe http/https URL if present; rejects javascript:/data: links.
 */
export function sanitizeHttpUrl(raw: string | null | undefined): string | undefined {
  if (!raw) return undefined;
  const trimmed = raw.trim();
  if (!trimmed) return undefined;
  try {
    const url = new URL(trimmed);
    if (url.protocol === "http:" || url.protocol === "https:") {
      return url.toString();
    }
  } catch {
    // ignore invalid URLs
  }
  return undefined;
}
