import { describe, expect, it } from "vitest";
import { istDateToUtcMs } from "../src/dates.js";
import { detectBoardType, isPlaceholderCompanyName } from "../src/normalize.js";
import type { RawIpoRecord, RawSubscriptionRecord } from "../src/types.js";
import {
  validateRawIpoRecord,
  validateRawSubscriptionRecord
} from "../src/validation.js";

const REF_MS = istDateToUtcMs(2026, 11, 27)!;

function validIpo(overrides: Partial<RawIpoRecord> = {}): RawIpoRecord {
  return {
    companyName: "Acme Solar Holdings Ltd",
    boardType: "MAINBOARD",
    source: "investorgain_gmp",
    openDate: "25 Nov 2026",
    closeDate: "27 Nov 2026",
    priceLow: 275,
    priceHigh: 289,
    lotSize: 51,
    gmpRupees: 42,
    gmpUpdatedAtMs: REF_MS,
    ...overrides
  };
}

function validSub(overrides: Partial<RawSubscriptionRecord> = {}): RawSubscriptionRecord {
  return {
    companyName: "Acme Solar Holdings Ltd",
    boardType: "MAINBOARD",
    source: "chittorgarh_sub_mainboard",
    qibSubX: 10.5,
    bNiiSubX: 12.1,
    sNiiSubX: 8.3,
    retailSubX: 4.5,
    employeeSubX: 1.2,
    totalSubX: 8.9,
    subUpdatedAtMs: REF_MS,
    ...overrides
  };
}

describe("Validation rules", () => {
  it("accepts a valid IPO record", () => {
    const res = validateRawIpoRecord(validIpo(), REF_MS);
    expect(res.valid).toBe(true);
    expect(res.errors).toEqual([]);
  });

  it("rejects priceHigh < priceLow", () => {
    const res = validateRawIpoRecord(validIpo({ priceLow: 300, priceHigh: 250 }), REF_MS);
    expect(res.valid).toBe(false);
    expect(res.errors.some((e) => e.includes("priceHigh"))).toBe(true);
  });

  it("rejects lotSize <= 0", () => {
    expect(validateRawIpoRecord(validIpo({ lotSize: 0 }), REF_MS).valid).toBe(false);
    expect(validateRawIpoRecord(validIpo({ lotSize: -10 }), REF_MS).valid).toBe(false);
  });

  it("rejects closeDate before openDate", () => {
    const res = validateRawIpoRecord(
      validIpo({ openDate: "27 Nov 2026", closeDate: "25 Nov 2026" }),
      REF_MS
    );
    expect(res.valid).toBe(false);
    expect(res.errors.some((e) => e.includes("before openDate"))).toBe(true);
  });

  it("rejects bidding window longer than 45 days", () => {
    const res = validateRawIpoRecord(
      validIpo({ openDate: "01 Oct 2026", closeDate: "20 Nov 2026" }),
      REF_MS
    );
    expect(res.valid).toBe(false);
    expect(res.errors.some((e) => e.includes("45 days"))).toBe(true);
  });

  it("rejects GMP outside -5000..15000", () => {
    expect(validateRawIpoRecord(validIpo({ gmpRupees: -5001 }), REF_MS).valid).toBe(false);
    expect(validateRawIpoRecord(validIpo({ gmpRupees: 15001 }), REF_MS).valid).toBe(false);
    expect(validateRawIpoRecord(validIpo({ gmpRupees: -5000 }), REF_MS).valid).toBe(true);
    expect(validateRawIpoRecord(validIpo({ gmpRupees: 15000 }), REF_MS).valid).toBe(true);
  });

  it("rejects subscription values outside 0..100000", () => {
    expect(validateRawSubscriptionRecord(validSub({ qibSubX: -0.1 })).valid).toBe(false);
    expect(validateRawSubscriptionRecord(validSub({ totalSubX: 100001 })).valid).toBe(false);
    expect(validateRawSubscriptionRecord(validSub({ totalSubX: 0 })).valid).toBe(true);
    expect(validateRawSubscriptionRecord(validSub({ totalSubX: 100000 })).valid).toBe(true);
  });

  it("rejects header and placeholder company names", () => {
    for (const badName of ["TBA", "NA", "N/A", "Company Name", "Total", "IPO Name", "Summary", "---"]) {
      expect(isPlaceholderCompanyName(badName)).toBe(true);
      expect(validateRawIpoRecord(validIpo({ companyName: badName }), REF_MS).valid).toBe(false);
      expect(validateRawSubscriptionRecord(validSub({ companyName: badName })).valid).toBe(false);
    }
  });

  it("classifies 'Sree Cosmetics Ltd' as MAINBOARD using word-boundary SME detection", () => {
    expect(detectBoardType("Sree Cosmetics Ltd")).toBe("MAINBOARD");
    expect(detectBoardType("Apex Engineering SME IPO")).toBe("SME");
  });
});
