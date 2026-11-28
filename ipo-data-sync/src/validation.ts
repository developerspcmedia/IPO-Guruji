import { z } from "zod";
import { DAY_MS, parseFlexibleDateMs } from "./dates.js";
import { isPlaceholderCompanyName } from "./normalize.js";
import type { RawIpoRecord, RawSubscriptionRecord } from "./types.js";

export const MIN_GMP_RUPEES = -5000;
export const MAX_GMP_RUPEES = 15000;
export const MIN_SUB_X = 0;
export const MAX_SUB_X = 100000;
export const MAX_BIDDING_WINDOW_DAYS = 45;

export const BoardTypeSchema = z.enum(["MAINBOARD", "SME"]);
export const IpoStatusSchema = z.enum(["LIVE", "PRE_APPLY", "UPCOMING", "CLOSED"]);

export const RawIpoRecordSchema = z.object({
  companyName: z.string().min(2),
  boardType: BoardTypeSchema,
  source: z.string().min(1),
  isAuthoritative: z.boolean().optional(),
  isWithdrawn: z.boolean().optional(),
  statusHint: IpoStatusSchema.optional(),
  openDate: z.string().optional(),
  closeDate: z.string().optional(),
  allotmentDate: z.string().optional(),
  listingDate: z.string().optional(),
  priceLow: z.number().finite().optional(),
  priceHigh: z.number().finite().optional(),
  lotSize: z.number().finite().optional(),
  issueSizeCr: z.number().finite().optional(),
  gmpRupees: z.number().finite().optional(),
  gmpUpdatedAtMs: z.number().finite().optional(),
  registrarName: z.string().optional(),
  registrarUrl: z.string().optional()
});

export const RawSubscriptionRecordSchema = z.object({
  companyName: z.string().min(2),
  boardType: BoardTypeSchema,
  source: z.string().min(1),
  isAuthoritative: z.boolean().optional(),
  qibSubX: z.number().finite().optional(),
  bNiiSubX: z.number().finite().optional(),
  sNiiSubX: z.number().finite().optional(),
  retailSubX: z.number().finite().optional(),
  employeeSubX: z.number().finite().optional(),
  totalSubX: z.number().finite().optional(),
  subUpdatedAtMs: z.number().finite().nonnegative()
});

export interface ValidationResult<T> {
  valid: boolean;
  value?: T;
  errors: string[];
}

export function isValidGmp(gmp: number): boolean {
  return Number.isFinite(gmp) && gmp >= MIN_GMP_RUPEES && gmp <= MAX_GMP_RUPEES;
}

export function isValidSubscriptionValue(sub: number): boolean {
  return Number.isFinite(sub) && sub >= MIN_SUB_X && sub <= MAX_SUB_X;
}

export function validateRawIpoRecord(
  record: RawIpoRecord,
  referenceMs: number = Date.now()
): ValidationResult<RawIpoRecord> {
  const errors: string[] = [];

  const parsed = RawIpoRecordSchema.safeParse(record);
  if (!parsed.success) {
    return {
      valid: false,
      errors: parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`)
    };
  }

  const r = parsed.data;

  // Company name placeholder / header check
  if (isPlaceholderCompanyName(r.companyName)) {
    errors.push(`Invalid or placeholder company name: "${r.companyName}"`);
  }

  // Price band checks
  if (r.priceLow !== undefined && r.priceLow < 0) {
    errors.push(`Negative priceLow: ${r.priceLow}`);
  }
  if (r.priceHigh !== undefined && r.priceHigh < 0) {
    errors.push(`Negative priceHigh: ${r.priceHigh}`);
  }
  if (
    r.priceLow !== undefined &&
    r.priceHigh !== undefined &&
    r.priceHigh < r.priceLow
  ) {
    errors.push(`priceHigh (${r.priceHigh}) < priceLow (${r.priceLow})`);
  }

  // Lot size check: whenever lotSize is supplied, it must be > 0
  if (r.lotSize !== undefined && (!Number.isInteger(r.lotSize) || r.lotSize <= 0)) {
    errors.push(`Invalid lotSize (must be positive integer): ${r.lotSize}`);
  }

  // Issue size check
  if (r.issueSizeCr !== undefined && r.issueSizeCr < 0) {
    errors.push(`Negative issueSizeCr: ${r.issueSizeCr}`);
  }

  // Date ordering & bidding window checks
  const openMs = r.openDate ? parseFlexibleDateMs(r.openDate, referenceMs) : null;
  const closeMs = r.closeDate ? parseFlexibleDateMs(r.closeDate, referenceMs) : null;

  if (r.openDate && openMs === null) {
    errors.push(`Unparseable openDate: "${r.openDate}"`);
  }
  if (r.closeDate && closeMs === null) {
    errors.push(`Unparseable closeDate: "${r.closeDate}"`);
  }
  if (openMs !== null && closeMs !== null) {
    if (closeMs < openMs) {
      errors.push(`closeDate (${r.closeDate}) is before openDate (${r.openDate})`);
    } else {
      const windowDays = Math.round((closeMs - openMs) / DAY_MS);
      if (windowDays > MAX_BIDDING_WINDOW_DAYS) {
        errors.push(`Bidding window (${windowDays} days) exceeds ${MAX_BIDDING_WINDOW_DAYS} days`);
      }
    }
  }

  // GMP range check (-5000..15000)
  if (r.gmpRupees !== undefined && !isValidGmp(r.gmpRupees)) {
    errors.push(`gmpRupees (${r.gmpRupees}) outside [${MIN_GMP_RUPEES}, ${MAX_GMP_RUPEES}]`);
  }

  if (errors.length > 0) {
    return { valid: false, errors };
  }
  return { valid: true, value: r, errors: [] };
}

export function validateRawSubscriptionRecord(
  record: RawSubscriptionRecord
): ValidationResult<RawSubscriptionRecord> {
  const errors: string[] = [];

  const parsed = RawSubscriptionRecordSchema.safeParse(record);
  if (!parsed.success) {
    return {
      valid: false,
      errors: parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`)
    };
  }

  const r = parsed.data;

  if (isPlaceholderCompanyName(r.companyName)) {
    errors.push(`Invalid or placeholder company name: "${r.companyName}"`);
  }

  const subFields: Array<[string, number | undefined]> = [
    ["qibSubX", r.qibSubX],
    ["bNiiSubX", r.bNiiSubX],
    ["sNiiSubX", r.sNiiSubX],
    ["retailSubX", r.retailSubX],
    ["employeeSubX", r.employeeSubX],
    ["totalSubX", r.totalSubX]
  ];

  for (const [label, val] of subFields) {
    if (val !== undefined && !isValidSubscriptionValue(val)) {
      errors.push(`${label} (${val}) outside [${MIN_SUB_X}, ${MAX_SUB_X}]`);
    }
  }

  if (errors.length > 0) {
    return { valid: false, errors };
  }
  return { valid: true, value: r, errors: [] };
}

export function filterValidIpoRecords(
  records: RawIpoRecord[],
  referenceMs: number = Date.now()
): { valid: RawIpoRecord[]; rejectedCount: number } {
  const valid: RawIpoRecord[] = [];
  let rejectedCount = 0;
  for (const rec of records) {
    const res = validateRawIpoRecord(rec, referenceMs);
    if (res.valid && res.value) {
      valid.push(res.value);
    } else {
      rejectedCount += 1;
    }
  }
  return { valid, rejectedCount };
}

export function filterValidSubscriptionRecords(
  records: RawSubscriptionRecord[]
): { valid: RawSubscriptionRecord[]; rejectedCount: number } {
  const valid: RawSubscriptionRecord[] = [];
  let rejectedCount = 0;
  for (const rec of records) {
    const res = validateRawSubscriptionRecord(rec);
    if (res.valid && res.value) {
      valid.push(res.value);
    } else {
      rejectedCount += 1;
    }
  }
  return { valid, rejectedCount };
}
