import { afterEach, describe, expect, it } from "vitest";
import {
  DAY_MS,
  formatIstDate,
  isSubscriptionWindowIst,
  istDateToUtcMs,
  shouldRunCatalogInAllMode
} from "../src/dates.js";
import type { SyncStore } from "../src/firestore.js";
import {
  computeIposHash,
  DEFAULT_MAX_IPOS,
  LAST_SEEN_MERGE_WINDOW_MS,
  MAX_UNDATED_PRICE_BAND_IPOS,
  mergeIpoCatalog,
  mergeIpoCatalogWithStats,
  parseMaxIpos,
  resolveGmp,
  selectRelevantIpos,
  trimBundleToByteLimit
} from "../src/merge.js";
import { areSameCompany, tokenizeCompanyName } from "../src/normalize.js";
import { runSyncPipeline } from "../src/syncRunner.js";
import type {
  IpoBundleItem,
  IpoLiveBundleDoc,
  IpoLiveMetaDoc,
  RawIpoRecord,
  RawSubscriptionRecord
} from "../src/types.js";

// Friday, 27 Nov 2026 at 12:00 IST
const TODAY_START_MS = istDateToUtcMs(2026, 11, 27)!;
const NOW_MS = TODAY_START_MS + 12 * 60 * 60 * 1000;

function istDateOffset(daysFromToday: number): string {
  return formatIstDate(TODAY_START_MS + daysFromToday * DAY_MS);
}

function makeExistingItem(overrides: Partial<IpoBundleItem> = {}): IpoBundleItem {
  return {
    id: "acme-solar-mainboard",
    companyName: "Acme Solar Ltd",
    normalizedKey: "acme solar",
    boardType: "MAINBOARD",
    status: "LIVE",
    openDate: "25 Nov 2026",
    closeDate: "27 Nov 2026",
    allotmentDate: "28 Nov 2026",
    listingDate: "02 Dec 2026",
    priceLow: 275,
    priceHigh: 289,
    lotSize: 51,
    issueSizeCr: 2900,
    gmpRupees: 45,
    gmpUpdatedAtMs: NOW_MS - 3_600_000,
    qibSubX: 10.2,
    bNiiSubX: 15.1,
    sNiiSubX: 8.4,
    retailSubX: 3.9,
    employeeSubX: 1.1,
    totalSubX: 7.8,
    subUpdatedAtMs: NOW_MS - 3_600_000,
    registrarName: "KFin Technologies",
    registrarUrl: "https://ris.kfintech.com/",
    sources: ["investorgain_gmp", "chittorgarh_list"],
    lastSeenAtMs: NOW_MS - 3_600_000,
    ...overrides
  };
}

class InMemorySyncStore implements SyncStore {
  bundle: IpoLiveBundleDoc | null = null;
  meta: IpoLiveMetaDoc | null = null;
  bundleWrites = 0;
  metaWrites = 0;

  async readBundle(): Promise<IpoLiveBundleDoc | null> {
    return this.bundle;
  }

  async readMeta(): Promise<IpoLiveMetaDoc | null> {
    return this.meta;
  }

  async writeBundle(doc: Omit<IpoLiveBundleDoc, "updatedAt">): Promise<void> {
    this.bundleWrites += 1;
    this.bundle = { ...doc, updatedAt: "SERVER_TIMESTAMP" };
  }

  async writeMeta(doc: Omit<IpoLiveMetaDoc, "updatedAt">): Promise<void> {
    this.metaWrites += 1;
    this.meta = { ...this.meta, ...doc, updatedAt: "SERVER_TIMESTAMP" };
  }
}

const originalMaxIposEnv = process.env.MAX_IPOS;
afterEach(() => {
  if (originalMaxIposEnv === undefined) {
    delete process.env.MAX_IPOS;
  } else {
    process.env.MAX_IPOS = originalMaxIposEnv;
  }
});

describe("Company matching & deduplication", () => {
  it("uses ONLY legal suffixes as stop words and never merges Alpha Industries with Alpha Technologies", () => {
    expect(tokenizeCompanyName("Alpha Industries Ltd")).toEqual(["alpha", "industries"]);
    expect(tokenizeCompanyName("Alpha Technologies Limited")).toEqual(["alpha", "technologies"]);

    expect(
      areSameCompany(
        { companyName: "Alpha Industries Ltd", boardType: "MAINBOARD" },
        { companyName: "Alpha Technologies Ltd", boardType: "MAINBOARD" }
      )
    ).toBe(false);

    expect(
      areSameCompany(
        { companyName: "Alpha Industries Ltd", boardType: "MAINBOARD" },
        { companyName: "Alpha Industries Limited", boardType: "MAINBOARD" }
      )
    ).toBe(true);
  });

  it("requires identical boardType to merge and deduplicates same company across multiple sources", () => {
    expect(
      areSameCompany(
        { companyName: "Alpha Industries Ltd", boardType: "MAINBOARD" },
        { companyName: "Alpha Industries Ltd", boardType: "SME" }
      )
    ).toBe(false);

    const merged = mergeIpoCatalog(
      [],
      [
        {
          companyName: "Alpha Industries Ltd",
          boardType: "MAINBOARD",
          source: "chittorgarh_list",
          openDate: "25 Nov 2026",
          closeDate: "27 Nov 2026",
          priceLow: 100,
          priceHigh: 105
        },
        {
          companyName: "Alpha Industries Limited IPO",
          boardType: "MAINBOARD",
          source: "investorgain_gmp",
          openDate: "25 Nov 2026",
          closeDate: "27 Nov 2026",
          priceLow: 100,
          priceHigh: 105,
          gmpRupees: 25,
          gmpUpdatedAtMs: NOW_MS
        },
        {
          companyName: "Alpha Industries Ltd",
          boardType: "MAINBOARD",
          source: "ipowatch_gmp",
          gmpRupees: 27,
          gmpUpdatedAtMs: NOW_MS + 1000
        },
        {
          companyName: "Alpha Industries Ltd",
          boardType: "SME",
          source: "nse_current",
          isAuthoritative: true,
          openDate: "25 Nov 2026",
          closeDate: "27 Nov 2026"
        }
      ],
      [],
      NOW_MS
    );

    expect(merged).toHaveLength(2);
    expect(merged.map((m) => m.id).sort()).toEqual([
      "alpha-industries-mainboard",
      "alpha-industries-sme"
    ]);
    const mainboard = merged.find((m) => m.id === "alpha-industries-mainboard")!;
    expect(mainboard.sources).toEqual(["chittorgarh_list", "investorgain_gmp", "ipowatch_gmp"]);
    expect(mainboard.gmpRupees).toBe(27);
  });
});

describe("GMP & field agreement merge rules", () => {
  it("uses most recently updated GMP when sources agree within 30%", () => {
    const res = resolveGmp([
      { family: "investorgain", gmpRupees: 100, updatedAtMs: NOW_MS - 60_000 },
      { family: "ipowatch", gmpRupees: 120, updatedAtMs: NOW_MS }
    ]);
    expect(res).toEqual({ gmpRupees: 120, gmpUpdatedAtMs: NOW_MS });
  });

  it("falls back to median when GMP sources differ by more than 30%", () => {
    const res = resolveGmp([
      { family: "investorgain", gmpRupees: 50, updatedAtMs: NOW_MS - 120_000 },
      { family: "ipowatch", gmpRupees: 80, updatedAtMs: NOW_MS - 60_000 },
      { family: "chittorgarh", gmpRupees: 200, updatedAtMs: NOW_MS }
    ]);
    expect(res).toEqual({ gmpRupees: 80, gmpUpdatedAtMs: NOW_MS });
  });

  it("accepts dates, price band, and lot size only when >=2 independent sources agree or NSE provides them", () => {
    // 1 non-NSE source alone -> not accepted for dates/price/lotSize -> dropped as noDates
    const singleSource: RawIpoRecord[] = [
      {
        companyName: "Delta Robotics Ltd",
        boardType: "MAINBOARD",
        source: "ipowatch_upcoming",
        openDate: "01 Dec 2026",
        closeDate: "03 Dec 2026",
        priceLow: 100,
        priceHigh: 110,
        lotSize: 135
      }
    ];
    const res1 = mergeIpoCatalogWithStats([], singleSource, [], NOW_MS);
    expect(res1.ipos).toHaveLength(0);
    expect(res1.stats.droppedByReason.noDates).toBe(1);

    // 2 independent sources agreeing -> accepted
    const twoSources: RawIpoRecord[] = [
      ...singleSource,
      {
        companyName: "Delta Robotics Limited",
        boardType: "MAINBOARD",
        source: "chittorgarh_list",
        openDate: "01 Dec 2026",
        closeDate: "03 Dec 2026",
        priceLow: 100,
        priceHigh: 110,
        lotSize: 135
      }
    ];
    const res2 = mergeIpoCatalog([], twoSources, [], NOW_MS);
    expect(res2).toHaveLength(1);
    expect(res2[0].openDate).toBe("01 Dec 2026");
    expect(res2[0].closeDate).toBe("03 Dec 2026");
    expect(res2[0].priceLow).toBe(100);
    expect(res2[0].priceHigh).toBe(110);
    expect(res2[0].lotSize).toBe(135);

    // Single NSE authoritative source -> accepted immediately
    const nseOnly: RawIpoRecord[] = [
      {
        companyName: "Orbit Energy Ltd",
        boardType: "MAINBOARD",
        source: "nse_current",
        isAuthoritative: true,
        openDate: "25 Nov 2026",
        closeDate: "27 Nov 2026",
        priceLow: 400,
        priceHigh: 420,
        lotSize: 35
      }
    ];
    const resNse = mergeIpoCatalog([], nseOnly, [], NOW_MS);
    expect(resNse).toHaveLength(1);
    expect(resNse[0].openDate).toBe("25 Nov 2026");
    expect(resNse[0].priceHigh).toBe(420);
    expect(resNse[0].lotSize).toBe(35);
  });

  it("applies subscriptions ONLY to LIVE or closed-within-2-days IPOs", () => {
    const existing = [
      makeExistingItem({
        id: "live-energy-mainboard",
        companyName: "Live Energy Ltd",
        normalizedKey: "live energy",
        openDate: "25 Nov 2026",
        closeDate: "27 Nov 2026",
        qibSubX: 0,
        totalSubX: 0
      }),
      makeExistingItem({
        id: "recent-closed-mainboard",
        companyName: "Recent Closed Ltd",
        normalizedKey: "recent closed",
        openDate: "23 Nov 2026",
        closeDate: "25 Nov 2026", // 2 days before 27 Nov -> eligible
        listingDate: "30 Nov 2026",
        qibSubX: 0,
        totalSubX: 0
      }),
      makeExistingItem({
        id: "old-closed-mainboard",
        companyName: "Old Closed Ltd",
        normalizedKey: "old closed",
        openDate: "18 Nov 2026",
        closeDate: "20 Nov 2026", // 7 days ago -> NOT eligible for subscription updates
        listingDate: "25 Nov 2026", // listed 2 days ago -> still kept as recently listed
        qibSubX: 2.5,
        totalSubX: 3.1
      }),
      makeExistingItem({
        id: "upcoming-energy-mainboard",
        companyName: "Upcoming Energy Ltd",
        normalizedKey: "upcoming energy",
        openDate: "05 Dec 2026",
        closeDate: "08 Dec 2026",
        listingDate: "12 Dec 2026",
        qibSubX: 0,
        totalSubX: 0
      })
    ];

    const subs: RawSubscriptionRecord[] = [
      { companyName: "Live Energy Ltd", boardType: "MAINBOARD", source: "investorgain_subscription", qibSubX: 11, totalSubX: 9.5, subUpdatedAtMs: NOW_MS },
      { companyName: "Recent Closed Ltd", boardType: "MAINBOARD", source: "investorgain_subscription", qibSubX: 22, totalSubX: 18.4, subUpdatedAtMs: NOW_MS },
      { companyName: "Old Closed Ltd", boardType: "MAINBOARD", source: "investorgain_subscription", qibSubX: 99, totalSubX: 99, subUpdatedAtMs: NOW_MS },
      { companyName: "Upcoming Energy Ltd", boardType: "MAINBOARD", source: "investorgain_subscription", qibSubX: 5, totalSubX: 5, subUpdatedAtMs: NOW_MS }
    ];

    const merged = mergeIpoCatalog(existing, [], subs, NOW_MS);
    const byId = new Map(merged.map((i) => [i.id, i]));

    expect(byId.get("live-energy-mainboard")?.totalSubX).toBe(9.5);
    expect(byId.get("recent-closed-mainboard")?.totalSubX).toBe(18.4);
    expect(byId.get("old-closed-mainboard")?.totalSubX).toBe(3.1); // untouched
    expect(byId.get("upcoming-energy-mainboard")?.totalSubX).toBe(0); // untouched
  });
});

describe("IST Selection Windows & Eligibility", () => {
  it("keeps LIVE, upcoming <=21d, max 3 undated with price band, awaiting listing <=10d, and recently listed <=7d while dropping everything else", () => {
    const candidates: IpoBundleItem[] = [
      // 1. LIVE today
      makeExistingItem({
        id: "live-today-mainboard",
        companyName: "Live Today Ltd",
        normalizedKey: "live today",
        openDate: istDateOffset(-1),
        closeDate: istDateOffset(1),
        listingDate: istDateOffset(5)
      }),
      // 2. Upcoming exactly 21 days away -> KEEP
      makeExistingItem({
        id: "upcoming-21d-mainboard",
        companyName: "Upcoming 21D Ltd",
        normalizedKey: "upcoming 21d",
        openDate: istDateOffset(21),
        closeDate: istDateOffset(23),
        listingDate: istDateOffset(28)
      }),
      // 3. Upcoming 22 days away -> DROP (tooFarFuture)
      makeExistingItem({
        id: "upcoming-22d-mainboard",
        companyName: "Upcoming 22D Ltd",
        normalizedKey: "upcoming 22d",
        openDate: istDateOffset(22),
        closeDate: istDateOffset(24),
        listingDate: istDateOffset(29)
      }),
      // 4. Undated with price band (5 items -> keep max 3, drop 2 as overCap)
      ...Array.from({ length: 5 }, (_, idx) =>
        makeExistingItem({
          id: `undated-priced-${idx}-mainboard`,
          companyName: `Undated Priced ${idx} Ltd`,
          normalizedKey: `undated priced ${idx}`,
          status: "UPCOMING",
          openDate: "",
          closeDate: "",
          allotmentDate: "",
          listingDate: "",
          priceLow: 100 + idx * 10,
          priceHigh: 110 + idx * 10,
          gmpRupees: 0,
          gmpUpdatedAtMs: 0,
          qibSubX: 0,
          bNiiSubX: 0,
          sNiiSubX: 0,
          retailSubX: 0,
          totalSubX: 0
        })
      ),
      // 5. Undated WITHOUT price band (DRHP/RHP only) -> DROP (noDates)
      makeExistingItem({
        id: "drhp-only-mainboard",
        companyName: "DRHP Only Ventures Ltd",
        normalizedKey: "drhp only ventures",
        status: "UPCOMING",
        openDate: "",
        closeDate: "",
        allotmentDate: "",
        listingDate: "",
        priceLow: 0,
        priceHigh: 0,
        lotSize: 0
      }),
      // 6. Awaiting listing: closed 10 days ago, listing in 2 days -> KEEP
      makeExistingItem({
        id: "awaiting-10d-mainboard",
        companyName: "Awaiting 10D Ltd",
        normalizedKey: "awaiting 10d",
        status: "CLOSED",
        openDate: istDateOffset(-12),
        closeDate: istDateOffset(-10),
        listingDate: istDateOffset(2)
      }),
      // 7. Closed 11 days ago, listing still not passed -> DROP (tooOld)
      makeExistingItem({
        id: "awaiting-11d-mainboard",
        companyName: "Awaiting 11D Ltd",
        normalizedKey: "awaiting 11d",
        status: "CLOSED",
        openDate: istDateOffset(-13),
        closeDate: istDateOffset(-11),
        listingDate: istDateOffset(2)
      }),
      // 8. Recently listed 7 days ago -> KEEP
      makeExistingItem({
        id: "listed-7d-mainboard",
        companyName: "Listed 7D Ltd",
        normalizedKey: "listed 7d",
        status: "CLOSED",
        openDate: istDateOffset(-12),
        closeDate: istDateOffset(-10),
        listingDate: istDateOffset(-7)
      }),
      // 9. Listed 8 days ago -> DROP (tooOld)
      makeExistingItem({
        id: "listed-8d-mainboard",
        companyName: "Listed 8D Ltd",
        normalizedKey: "listed 8d",
        status: "CLOSED",
        openDate: istDateOffset(-14),
        closeDate: istDateOffset(-12),
        listingDate: istDateOffset(-8)
      }),
      // 10. Withdrawn IPO -> DROP (withdrawn)
      makeExistingItem({
        id: "withdrawn-corp-mainboard",
        companyName: "Withdrawn Corp Ltd (Withdrawn)",
        normalizedKey: "withdrawn corp",
        openDate: istDateOffset(2),
        closeDate: istDateOffset(4)
      }),
      // 11. Placeholder header row -> DROP (placeholder)
      makeExistingItem({
        id: "company-name-mainboard",
        companyName: "Company Name",
        normalizedKey: "company name",
        openDate: istDateOffset(1),
        closeDate: istDateOffset(3)
      })
    ];

    const { ipos, stats } = selectRelevantIpos(candidates, NOW_MS, DEFAULT_MAX_IPOS);
    const ids = ipos.map((i) => i.id);

    expect(ids).toContain("live-today-mainboard");
    expect(ids).toContain("upcoming-21d-mainboard");
    expect(ids).toContain("awaiting-10d-mainboard");
    expect(ids).toContain("listed-7d-mainboard");

    // Undated with price band capped at exactly 3
    const undatedKept = ids.filter((id) => id.startsWith("undated-priced-"));
    expect(undatedKept).toHaveLength(MAX_UNDATED_PRICE_BAND_IPOS);

    // Dropped entries
    expect(ids).not.toContain("upcoming-22d-mainboard");
    expect(ids).not.toContain("drhp-only-mainboard");
    expect(ids).not.toContain("awaiting-11d-mainboard");
    expect(ids).not.toContain("listed-8d-mainboard");
    expect(ids).not.toContain("withdrawn-corp-mainboard");
    expect(ids).not.toContain("company-name-mainboard");

    expect(stats.droppedByReason).toEqual({
      tooOld: 2,
      tooFarFuture: 1,
      noDates: 1,
      placeholder: 1,
      withdrawn: 1,
      overCap: 2
    });
    expect(stats.beforeCap).toBe(9); // 1 live + 1 upcoming + 5 undated priced + 1 awaiting + 1 listed
    expect(stats.afterCap).toBe(7); // 2 undated priced trimmed by 3-item undated cap
  });

  it("drops withdrawn IPOs when ANY source sets isWithdrawn=true in mergeIpoCatalogWithStats", () => {
    const records: RawIpoRecord[] = [
      {
        companyName: "Cancelled Tech Ltd",
        boardType: "MAINBOARD",
        source: "nse_current",
        isAuthoritative: true,
        openDate: istDateOffset(0),
        closeDate: istDateOffset(2),
        isWithdrawn: false
      },
      {
        companyName: "Cancelled Tech Limited",
        boardType: "MAINBOARD",
        source: "chittorgarh_list",
        isWithdrawn: true
      }
    ];

    const { ipos, stats } = mergeIpoCatalogWithStats([], records, [], NOW_MS);
    expect(ipos).toHaveLength(0);
    expect(stats.droppedByReason.withdrawn).toBe(1);
  });
});

describe("MAX_IPOS cap & priority ordering", () => {
  it("enforces default hard cap of 20 in exact priority order: LIVE (closing soonest) -> awaiting listing -> nearest upcoming -> recently listed (desc)", () => {
    const items: IpoBundleItem[] = [
      // 4 LIVE IPOs closing on different days (out of order)
      makeExistingItem({
        id: "live-closes-day3-mainboard",
        companyName: "Live Day3 Ltd",
        normalizedKey: "live day3",
        openDate: istDateOffset(0),
        closeDate: istDateOffset(3),
        listingDate: istDateOffset(7)
      }),
      makeExistingItem({
        id: "live-closes-today-mainboard",
        companyName: "Live Today Ltd",
        normalizedKey: "live today",
        openDate: istDateOffset(-2),
        closeDate: istDateOffset(0),
        listingDate: istDateOffset(4)
      }),
      makeExistingItem({
        id: "live-closes-day1-sme",
        companyName: "Live Day1 Ltd",
        normalizedKey: "live day1",
        boardType: "SME",
        openDate: istDateOffset(-1),
        closeDate: istDateOffset(1),
        listingDate: istDateOffset(5)
      }),
      makeExistingItem({
        id: "live-closes-day2-mainboard",
        companyName: "Live Day2 Ltd",
        normalizedKey: "live day2",
        openDate: istDateOffset(0),
        closeDate: istDateOffset(2),
        listingDate: istDateOffset(6)
      }),

      // 5 Awaiting listing IPOs
      ...Array.from({ length: 5 }, (_, idx) =>
        makeExistingItem({
          id: `awaiting-${idx}-mainboard`,
          companyName: `Awaiting ${idx} Ltd`,
          normalizedKey: `awaiting ${idx}`,
          status: "CLOSED",
          openDate: istDateOffset(-6),
          closeDate: istDateOffset(-4),
          listingDate: istDateOffset(5 - idx) // idx 4 lists soonest (+1d), idx 0 lists latest (+5d)
        })
      ),

      // 8 Upcoming IPOs within 21 days
      ...Array.from({ length: 8 }, (_, idx) =>
        makeExistingItem({
          id: `upcoming-${idx}-mainboard`,
          companyName: `Upcoming ${idx} Ltd`,
          normalizedKey: `upcoming ${idx}`,
          status: "UPCOMING",
          openDate: istDateOffset(10 - idx), // idx 7 opens soonest (+3d), idx 0 opens latest (+10d)
          closeDate: istDateOffset(12 - idx),
          listingDate: istDateOffset(16 - idx)
        })
      ),

      // 7 Recently listed IPOs within 7 days (days -1 .. -7)
      ...Array.from({ length: 7 }, (_, idx) =>
        makeExistingItem({
          id: `recent-listed-day${idx + 1}-mainboard`,
          companyName: `Recent Listed Day${idx + 1} Ltd`,
          normalizedKey: `recent listed day${idx + 1}`,
          status: "CLOSED",
          openDate: istDateOffset(-(idx + 7)),
          closeDate: istDateOffset(-(idx + 5)),
          listingDate: istDateOffset(-(idx + 1)) // day1 = yesterday (newest), day7 = 7d ago (oldest)
        })
      )
    ];

    // Total eligible = 4 + 5 + 8 + 7 = 24 -> cap at 20 (drops 4 oldest recently listed)
    const { ipos, stats } = selectRelevantIpos(items, NOW_MS);
    expect(ipos).toHaveLength(20);
    expect(stats.maxIpos).toBe(20);
    expect(stats.beforeCap).toBe(24);
    expect(stats.afterCap).toBe(20);
    expect(stats.droppedByReason.overCap).toBe(4);

    // 1. First 4 must be LIVE sorted by closeDate ascending
    expect(ipos.slice(0, 4).map((i) => i.id)).toEqual([
      "live-closes-today-mainboard",
      "live-closes-day1-sme",
      "live-closes-day2-mainboard",
      "live-closes-day3-mainboard"
    ]);

    // 2. Next 5 must be Awaiting listing sorted by soonest listingDate ascending
    expect(ipos.slice(4, 9).map((i) => i.id)).toEqual([
      "awaiting-4-mainboard",
      "awaiting-3-mainboard",
      "awaiting-2-mainboard",
      "awaiting-1-mainboard",
      "awaiting-0-mainboard"
    ]);

    // 3. Next 8 must be Upcoming sorted by nearest openDate ascending
    expect(ipos.slice(9, 17).map((i) => i.id)).toEqual([
      "upcoming-7-mainboard",
      "upcoming-6-mainboard",
      "upcoming-5-mainboard",
      "upcoming-4-mainboard",
      "upcoming-3-mainboard",
      "upcoming-2-mainboard",
      "upcoming-1-mainboard",
      "upcoming-0-mainboard"
    ]);

    // 4. Final 3 slots filled by most recently listed (listingDate descending)
    expect(ipos.slice(17, 20).map((i) => i.id)).toEqual([
      "recent-listed-day1-mainboard",
      "recent-listed-day2-mainboard",
      "recent-listed-day3-mainboard"
    ]);
  });

  it("supports overriding MAX_IPOS via environment variable", () => {
    process.env.MAX_IPOS = "15";
    expect(parseMaxIpos()).toBe(15);
    expect(parseMaxIpos("invalid")).toBe(20);
    expect(parseMaxIpos("0")).toBe(20);

    const items = Array.from({ length: 18 }, (_, idx) =>
      makeExistingItem({
        id: `live-${idx}-mainboard`,
        companyName: `Live ${idx} Ltd`,
        normalizedKey: `live ${idx}`,
        openDate: istDateOffset(0),
        closeDate: istDateOffset(1 + (idx % 3)),
        listingDate: istDateOffset(6)
      })
    );

    const { ipos, stats } = selectRelevantIpos(items, NOW_MS);
    expect(ipos).toHaveLength(15);
    expect(stats.maxIpos).toBe(15);
    expect(stats.beforeCap).toBe(18);
    expect(stats.afterCap).toBe(15);
    expect(stats.droppedByReason.overCap).toBe(3);
  });
});

describe("Replacement-only array policy & 24h per-field merge window", () => {
  it("REPLACES the old ipos array completely and NEVER merges/appends old IPOs back", () => {
    // Existing Firestore bundle has 400 old/stale IPOs
    const oldBundleItems: IpoBundleItem[] = Array.from({ length: 400 }, (_, idx) =>
      makeExistingItem({
        id: `legacy-ipo-${idx}-mainboard`,
        companyName: `Legacy IPO ${idx} Ltd`,
        normalizedKey: `legacy ipo ${idx}`,
        openDate: istDateOffset(-1),
        closeDate: istDateOffset(1),
        listingDate: istDateOffset(5),
        lastSeenAtMs: NOW_MS - 1_000 // even if seen 1s ago, NOT in new scrape -> MUST NOT be carried forward
      })
    );

    // New scrape returns only 3 current IPOs
    const newRecords: RawIpoRecord[] = Array.from({ length: 3 }, (_, idx) => ({
      companyName: `Fresh Solar ${idx} Ltd`,
      boardType: "MAINBOARD",
      source: "nse_current",
      isAuthoritative: true,
      openDate: istDateOffset(0),
      closeDate: istDateOffset(2),
      listingDate: istDateOffset(6),
      priceLow: 200,
      priceHigh: 210,
      lotSize: 70
    }));

    const merged = mergeIpoCatalog(oldBundleItems, newRecords, [], NOW_MS);
    expect(merged).toHaveLength(3);
    expect(merged.every((i) => i.id.startsWith("fresh-solar-"))).toBe(true);
    expect(merged.some((i) => i.id.startsWith("legacy-ipo-"))).toBe(false);
    expect(merged.every((i) => i.lastSeenAtMs === NOW_MS)).toBe(true);
  });

  it("allows per-field fallback ONLY for items in the new selection when old lastSeenAtMs is within 24 hours", () => {
    const freshExisting = makeExistingItem({
      id: "acme-solar-mainboard",
      companyName: "Acme Solar Ltd",
      normalizedKey: "acme solar",
      lastSeenAtMs: NOW_MS - (LAST_SEEN_MERGE_WINDOW_MS - 60_000) // 23h 59m ago -> eligible
    });

    // Sparse GMP-only scrape (simulating catalog source temporarily failing)
    const sparseScrape: RawIpoRecord[] = [
      {
        companyName: "Acme Solar Ltd",
        boardType: "MAINBOARD",
        source: "ipowatch_gmp",
        gmpRupees: 52,
        gmpUpdatedAtMs: NOW_MS
      }
    ];

    const merged = mergeIpoCatalog([freshExisting], sparseScrape, [], NOW_MS);
    expect(merged).toHaveLength(1);
    expect(merged[0]).toMatchObject({
      openDate: "25 Nov 2026",
      closeDate: "27 Nov 2026",
      priceLow: 275,
      priceHigh: 289,
      lotSize: 51,
      issueSizeCr: 2900,
      registrarName: "KFin Technologies",
      registrarUrl: "https://ris.kfintech.com/",
      gmpRupees: 52,
      qibSubX: 10.2,
      totalSubX: 7.8,
      lastSeenAtMs: NOW_MS
    });
  });

  it("does NOT merge fields from existing item when lastSeenAtMs is older than 24 hours or missing", () => {
    const staleExisting = makeExistingItem({
      id: "stale-solar-mainboard",
      companyName: "Stale Solar Ltd",
      normalizedKey: "stale solar",
      registrarName: "Old Registrar",
      issueSizeCr: 9999,
      gmpRupees: 75,
      gmpUpdatedAtMs: NOW_MS - 25 * 60 * 60 * 1000,
      lastSeenAtMs: NOW_MS - (LAST_SEEN_MERGE_WINDOW_MS + 60_000) // 24h 1m ago -> EXPIRED
    });

    const missingLastSeenExisting = makeExistingItem({
      id: "missing-ts-mainboard",
      companyName: "Missing Ts Ltd",
      normalizedKey: "missing ts",
      registrarName: "Legacy Registrar",
      issueSizeCr: 5000,
      lastSeenAtMs: undefined
    });

    const newRecords: RawIpoRecord[] = [
      {
        companyName: "Stale Solar Ltd",
        boardType: "MAINBOARD",
        source: "nse_current",
        isAuthoritative: true,
        openDate: istDateOffset(0),
        closeDate: istDateOffset(2),
        priceLow: 100,
        priceHigh: 110,
        lotSize: 100
      },
      {
        companyName: "Missing Ts Ltd",
        boardType: "MAINBOARD",
        source: "nse_current",
        isAuthoritative: true,
        openDate: istDateOffset(0),
        closeDate: istDateOffset(2),
        priceLow: 120,
        priceHigh: 130,
        lotSize: 90
      }
    ];

    const merged = mergeIpoCatalog([staleExisting, missingLastSeenExisting], newRecords, [], NOW_MS);
    expect(merged).toHaveLength(2);

    const staleOut = merged.find((i) => i.id === "stale-solar-mainboard")!;
    expect(staleOut.registrarName).toBe("");
    expect(staleOut.issueSizeCr).toBe(0);
    expect(staleOut.gmpRupees).toBe(0);
    expect(staleOut.lastSeenAtMs).toBe(NOW_MS);

    const missingOut = merged.find((i) => i.id === "missing-ts-mainboard")!;
    expect(missingOut.registrarName).toBe("");
    expect(missingOut.issueSizeCr).toBe(0);
    expect(missingOut.lastSeenAtMs).toBe(NOW_MS);
  });
});

describe("Pipeline: replacement write, selection JSON logging, byte cap, hash skip, and failure handling", () => {
  it("logs ONE JSON line after selection with counts, status/boardType breakdown, drop reasons, and field coverage", async () => {
    const store = new InMemorySyncStore();
    // Seed store with 50 old items to verify full replacement on write
    store.bundle = {
      version: 1,
      updatedAt: "OLD",
      lastSuccessAt: new Date(NOW_MS - 3_600_000).toISOString(),
      sourcesOk: ["chittorgarh_list"],
      sourcesFailed: [],
      ipos: Array.from({ length: 50 }, (_, idx) =>
        makeExistingItem({
          id: `old-item-${idx}-mainboard`,
          companyName: `Old Item ${idx} Ltd`,
          normalizedKey: `old item ${idx}`,
          openDate: istDateOffset(-40),
          closeDate: istDateOffset(-38),
          listingDate: istDateOffset(-33)
        })
      )
    };

    const loggedLines: string[] = [];
    const summary = await runSyncPipeline({
      store,
      runMode: "all",
      nowMs: NOW_MS,
      maxIpos: 20,
      log: (line) => loggedLines.push(line),
      fetchSources: async () => [
        {
          sourceName: "nse_current",
          kind: "catalog",
          ok: true,
          ipoRecords: [
            {
              companyName: "Alpha Solar Ltd",
              boardType: "MAINBOARD",
              source: "nse_current",
              isAuthoritative: true,
              openDate: istDateOffset(-1),
              closeDate: istDateOffset(1),
              allotmentDate: istDateOffset(2),
              listingDate: istDateOffset(5),
              priceLow: 200,
              priceHigh: 210,
              lotSize: 70,
              gmpRupees: 40,
              gmpUpdatedAtMs: NOW_MS
            },
            {
              companyName: "Beta SME Tech Ltd",
              boardType: "SME",
              source: "nse_current",
              isAuthoritative: true,
              openDate: istDateOffset(5),
              closeDate: istDateOffset(7),
              priceLow: 90,
              priceHigh: 95,
              lotSize: 1200
            },
            {
              companyName: "Too Old Listed Ltd",
              boardType: "MAINBOARD",
              source: "nse_current",
              isAuthoritative: true,
              openDate: istDateOffset(-30),
              closeDate: istDateOffset(-28),
              listingDate: istDateOffset(-20)
            }
          ],
          subscriptionRecords: [
            {
              companyName: "Alpha Solar Ltd",
              boardType: "MAINBOARD",
              source: "nse_subscription",
              isAuthoritative: true,
              qibSubX: 4.5,
              bNiiSubX: 8.2,
              sNiiSubX: 5.1,
              retailSubX: 3.3,
              totalSubX: 5.0,
              subUpdatedAtMs: NOW_MS
            }
          ]
        }
      ]
    });

    expect(summary.wroteBundle).toBe(true);
    expect(store.bundle?.ipos).toHaveLength(2);
    expect(loggedLines).toHaveLength(1);
    expect(loggedLines[0]).not.toContain("\n");

    const parsed = JSON.parse(loggedLines[0]);
    expect(parsed).toEqual({
      event: "ipo_selection",
      maxIpos: 20,
      beforeCap: 2,
      afterCap: 2,
      byStatus: { LIVE: 1, PRE_APPLY: 0, UPCOMING: 1, CLOSED: 0 },
      byBoardType: { MAINBOARD: 1, SME: 1 },
      droppedByReason: {
        tooOld: 1,
        tooFarFuture: 0,
        noDates: 0,
        placeholder: 0,
        withdrawn: 0,
        overCap: 0
      },
      fieldCoverage: {
        gmpRupees: 1,
        openDate: 2,
        closeDate: 2,
        allotmentDate: 1,
        listingDate: 1,
        qibSubX: 1,
        bNiiSubX: 1,
        sNiiSubX: 1,
        retailSubX: 1
      }
    });
  });

  it("drops oldest listed IPOs first when bundle exceeds byte cap", () => {
    const active = makeExistingItem({
      id: "keep-active-mainboard",
      companyName: "Keep Active Ltd",
      normalizedKey: "keep active",
      openDate: "25 Nov 2026",
      closeDate: "27 Nov 2026",
      listingDate: "02 Dec 2026"
    });
    const oldListed = makeExistingItem({
      id: "old-listed-mainboard",
      companyName: "Old Listed Ltd",
      normalizedKey: "old listed",
      status: "CLOSED",
      openDate: "01 Oct 2026",
      closeDate: "03 Oct 2026",
      listingDate: "08 Oct 2026",
      registrarName: "X".repeat(800)
    });
    const newerListed = makeExistingItem({
      id: "newer-listed-mainboard",
      companyName: "Newer Listed Ltd",
      normalizedKey: "newer listed",
      status: "CLOSED",
      openDate: "15 Nov 2026",
      closeDate: "17 Nov 2026",
      listingDate: "20 Nov 2026",
      registrarName: "Y".repeat(800)
    });

    const trimmed = trimBundleToByteLimit(
      {
        version: 1,
        lastSuccessAt: new Date(NOW_MS).toISOString(),
        sourcesOk: ["chittorgarh_list"],
        sourcesFailed: [],
        ipos: [active, oldListed, newerListed]
      },
      NOW_MS,
      2_500 // artificial small cap to test oldest-listed-first trimming
    );

    expect(trimmed.ipos.some((i) => i.id === "keep-active-mainboard")).toBe(true);
    expect(trimmed.ipos.some((i) => i.id === "newer-listed-mainboard")).toBe(true);
    expect(trimmed.ipos.some((i) => i.id === "old-listed-mainboard")).toBe(false);
  });

  it("skips writing ipo_live_bundle/current when SHA-256 hash of ipos is unchanged and lastSeenAtMs is fresh", async () => {
    const store = new InMemorySyncStore();
    const existingItem = makeExistingItem({
      sources: ["investorgain_gmp"],
      lastSeenAtMs: NOW_MS - 3_600_000 // 1h old (< 12h refresh threshold)
    });
    store.bundle = {
      version: 1,
      updatedAt: "PREV",
      lastSuccessAt: new Date(NOW_MS - 600_000).toISOString(),
      sourcesOk: ["investorgain_gmp"],
      sourcesFailed: [],
      ipos: [existingItem]
    };

    const summary = await runSyncPipeline({
      store,
      runMode: "gmp",
      nowMs: NOW_MS,
      log: () => {},
      fetchSources: async () => [
        {
          sourceName: "investorgain_gmp",
          kind: "gmp",
          ok: true,
          ipoRecords: [
            {
              companyName: "Acme Solar Ltd",
              boardType: "MAINBOARD",
              source: "investorgain_gmp",
              gmpRupees: 45,
              gmpUpdatedAtMs: existingItem.gmpUpdatedAtMs // same timestamp & value
            }
          ],
          subscriptionRecords: []
        }
      ]
    });

    expect(summary.skippedUnchanged).toBe(true);
    expect(summary.wroteBundle).toBe(false);
    expect(store.bundleWrites).toBe(0);
    expect(store.metaWrites).toBe(1);
    expect(computeIposHash(store.bundle.ipos)).toBe(store.meta?.lastBundleHash);
  });

  it("writes NOTHING to ipo_live_bundle/current when every source fails, updating only ipo_live_meta/sync", async () => {
    const store = new InMemorySyncStore();
    const originalItem = makeExistingItem();
    store.bundle = {
      version: 1,
      updatedAt: "ORIGINAL",
      lastSuccessAt: "2026-11-27T05:00:00.000Z",
      sourcesOk: ["investorgain_gmp"],
      sourcesFailed: [],
      ipos: [originalItem]
    };

    const summary = await runSyncPipeline({
      store,
      runMode: "gmp",
      nowMs: NOW_MS,
      log: () => {},
      fetchSources: async () => [
        {
          sourceName: "investorgain_gmp",
          kind: "gmp",
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: "HTTP 503"
        },
        {
          sourceName: "ipowatch_gmp",
          kind: "gmp",
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: "Timeout"
        }
      ]
    });

    expect(summary.allSourcesFailed).toBe(true);
    expect(summary.wroteBundle).toBe(false);
    expect(store.bundleWrites).toBe(0);
    expect(store.bundle.updatedAt).toBe("ORIGINAL");
    expect(store.metaWrites).toBe(1);
    expect(store.meta?.sourcesFailed).toEqual(["investorgain_gmp", "ipowatch_gmp"]);
    expect(store.meta?.lastError).toContain("HTTP 503");
  });

  it("evaluates IST schedule helpers for RUN_MODE=all accurately", () => {
    const friMidday = istDateToUtcMs(2026, 11, 27)! + 11.5 * 60 * 60 * 1000;
    const friMorning = istDateToUtcMs(2026, 11, 27)! + 9.5 * 60 * 60 * 1000;
    const satMidday = istDateToUtcMs(2026, 11, 28)! + 12 * 60 * 60 * 1000;

    expect(isSubscriptionWindowIst(friMidday)).toBe(true);
    expect(isSubscriptionWindowIst(friMorning)).toBe(false);
    expect(isSubscriptionWindowIst(satMidday)).toBe(false);

    expect(shouldRunCatalogInAllMode(undefined, friMidday)).toBe(true);
    expect(shouldRunCatalogInAllMode(friMidday - 30 * 60 * 1000, friMidday)).toBe(false);
    expect(shouldRunCatalogInAllMode(friMidday - 61 * 60 * 1000, friMidday)).toBe(true);
  });
});
