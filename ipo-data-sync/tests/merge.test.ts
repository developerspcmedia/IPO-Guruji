import { describe, expect, it } from "vitest";
import {
  formatIstDate,
  isSubscriptionWindowIst,
  istDateToUtcMs,
  shouldRunCatalogInAllMode
} from "../src/dates.js";
import type { SyncStore } from "../src/firestore.js";
import {
  applyRetentionPolicy,
  computeIposHash,
  mergeIpoCatalog,
  resolveGmp,
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
const NOW_MS = istDateToUtcMs(2026, 11, 27)! + 12 * 60 * 60 * 1000;

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

  it("requires identical boardType to merge", () => {
    expect(
      areSameCompany(
        { companyName: "Alpha Industries Ltd", boardType: "MAINBOARD" },
        { companyName: "Alpha Industries Ltd", boardType: "SME" }
      )
    ).toBe(false);

    const merged = mergeIpoCatalog(
      [],
      [
        { companyName: "Alpha Industries Ltd", boardType: "MAINBOARD", source: "nse_current", isAuthoritative: true, openDate: "25 Nov 2026", closeDate: "27 Nov 2026" },
        { companyName: "Alpha Industries Ltd", boardType: "SME", source: "nse_current", isAuthoritative: true, openDate: "25 Nov 2026", closeDate: "27 Nov 2026" }
      ],
      [],
      NOW_MS
    );

    expect(merged).toHaveLength(2);
    expect(merged.map((m) => m.id).sort()).toEqual([
      "alpha-industries-mainboard",
      "alpha-industries-sme"
    ]);
  });
});

describe("GMP & field agreement merge rules", () => {
  it("uses most recently updated GMP when sources agree within 30%", () => {
    const res = resolveGmp([
      { family: "investorgain", gmpRupees: 100, updatedAtMs: NOW_MS - 60_000 },
      { family: "ipowatch", gmpRupees: 120, updatedAtMs: NOW_MS }
    ]);
    // Spread = (120 - 100) / 120 = 16.7% <= 30% -> take newest (120)
    expect(res).toEqual({ gmpRupees: 120, gmpUpdatedAtMs: NOW_MS });
  });

  it("falls back to median when GMP sources differ by more than 30%", () => {
    const res = resolveGmp([
      { family: "investorgain", gmpRupees: 50, updatedAtMs: NOW_MS - 120_000 },
      { family: "ipowatch", gmpRupees: 80, updatedAtMs: NOW_MS - 60_000 },
      { family: "chittorgarh", gmpRupees: 200, updatedAtMs: NOW_MS }
    ]);
    // Spread = (200 - 50) / 200 = 75% > 30% -> median of [50, 80, 200] = 80
    expect(res).toEqual({ gmpRupees: 80, gmpUpdatedAtMs: NOW_MS });
  });

  it("accepts dates, price band, and lot size only when >=2 independent sources agree or NSE provides them", () => {
    // 1 non-NSE source alone -> not accepted for dates/price/lotSize
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
    const res1 = mergeIpoCatalog([], singleSource, [], NOW_MS);
    expect(res1[0].openDate).toBe("");
    expect(res1[0].priceLow).toBe(0);
    expect(res1[0].lotSize).toBe(0);

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
        closeDate: "20 Nov 2026", // 7 days ago -> NOT eligible
        listingDate: "25 Nov 2026",
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

  it("NEVER overwrites good existing fields with empty or zero values", () => {
    const existing = [makeExistingItem()];
    // New scrape returns only company name and updated GMP, omitting dates/price/lot/registrar
    const sparseScrape: RawIpoRecord[] = [
      {
        companyName: "Acme Solar Ltd",
        boardType: "MAINBOARD",
        source: "ipowatch_gmp",
        gmpRupees: 52,
        gmpUpdatedAtMs: NOW_MS
      }
    ];

    const merged = mergeIpoCatalog(existing, sparseScrape, [], NOW_MS);
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
      totalSubX: 7.8
    });
  });
});

describe("Retention, 900 KB cap, hash skip, and all-sources-failed handling", () => {
  it("keeps all upcoming/current IPOs and only the 25 most recent listed IPOs", () => {
    const active = [
      makeExistingItem({
        id: "active-1-mainboard",
        companyName: "Active 1 Ltd",
        normalizedKey: "active 1",
        openDate: "25 Nov 2026",
        closeDate: "27 Nov 2026",
        listingDate: "02 Dec 2026"
      })
    ];

    // Create 35 listed IPOs with distinct listing dates in past
    const listed: IpoBundleItem[] = Array.from({ length: 35 }, (_, idx) => {
      const listMs = istDateToUtcMs(2026, 11, 26)! - idx * 24 * 60 * 60 * 1000;
      const closeMs = listMs - 4 * 24 * 60 * 60 * 1000;
      const openMs = closeMs - 2 * 24 * 60 * 60 * 1000;
      return makeExistingItem({
        id: `listed-${String(idx).padStart(2, "0")}-mainboard`,
        companyName: `Listed ${idx} Ltd`,
        normalizedKey: `listed ${idx}`,
        status: "CLOSED",
        openDate: formatIstDate(openMs),
        closeDate: formatIstDate(closeMs),
        listingDate: formatIstDate(listMs)
      });
    });

    const retained = applyRetentionPolicy([...active, ...listed], NOW_MS);
    expect(retained).toHaveLength(26); // 1 active + 25 most recent listed
    expect(retained.some((i) => i.id === "active-1-mainboard")).toBe(true);
    expect(retained.some((i) => i.id === "listed-00-mainboard")).toBe(true);
    expect(retained.some((i) => i.id === "listed-24-mainboard")).toBe(true);
    expect(retained.some((i) => i.id === "listed-25-mainboard")).toBe(false);
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

  it("skips writing ipo_live_bundle/current when SHA-256 hash of ipos is unchanged", async () => {
    const store = new InMemorySyncStore();
    const existingItem = makeExistingItem({ sources: ["investorgain_gmp"] });
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
    // Friday 27 Nov 2026 11:30 IST -> weekday inside 10:00..18:00
    const friMidday = istDateToUtcMs(2026, 11, 27)! + 11.5 * 60 * 60 * 1000;
    // Friday 27 Nov 2026 09:30 IST -> before 10:00
    const friMorning = istDateToUtcMs(2026, 11, 27)! + 9.5 * 60 * 60 * 1000;
    // Saturday 28 Nov 2026 12:00 IST -> weekend
    const satMidday = istDateToUtcMs(2026, 11, 28)! + 12 * 60 * 60 * 1000;

    expect(isSubscriptionWindowIst(friMidday)).toBe(true);
    expect(isSubscriptionWindowIst(friMorning)).toBe(false);
    expect(isSubscriptionWindowIst(satMidday)).toBe(false);

    expect(shouldRunCatalogInAllMode(undefined, friMidday)).toBe(true);
    expect(shouldRunCatalogInAllMode(friMidday - 30 * 60 * 1000, friMidday)).toBe(false);
    expect(shouldRunCatalogInAllMode(friMidday - 61 * 60 * 1000, friMidday)).toBe(true);
  });
});
