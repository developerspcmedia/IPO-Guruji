import { getInvestorGainDateParams } from "./dates.js";
import { GeminiFallbackExtractor } from "./geminiFallback.js";
import { PoliteHttpClient } from "./http.js";
import {
  parseHtmlGmpTables,
  parseHtmlSubscriptionTables
} from "./parsers/htmlTables.js";
import {
  parseInvestorGainJson,
  parseInvestorGainSubscriptionJson
} from "./parsers/investorgain.js";
import { parseNseIpoJson, parseNseSubscriptionJson } from "./parsers/nse.js";
import type { BoardType, SourceFetchResult } from "./types.js";
import {
  filterValidIpoRecords,
  filterValidSubscriptionRecords
} from "./validation.js";

export interface SyncPhases {
  runCatalog: boolean;
  runGmp: boolean;
  runSubscription: boolean;
}

interface SourceTask {
  sourceName: string;
  kind: "catalog" | "gmp" | "subscription";
  run: () => Promise<SourceFetchResult>;
}

function buildHtmlIpoTask(
  client: PoliteHttpClient,
  gemini: GeminiFallbackExtractor,
  sourceName: string,
  kind: "catalog" | "gmp",
  url: string,
  defaultBoardType: BoardType,
  nowMs: number
): SourceTask {
  return {
    sourceName,
    kind,
    run: async (): Promise<SourceFetchResult> => {
      const res = await client.fetchText(url);
      if (!res.ok || !res.body.trim()) {
        return {
          sourceName,
          kind,
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: res.error ?? `Empty response (HTTP ${res.status})`
        };
      }

      const raw = parseHtmlGmpTables(res.body, sourceName, defaultBoardType, nowMs);
      let valid = filterValidIpoRecords(raw, nowMs).valid;
      let usedGeminiFallback = false;

      // Trigger Gemini fallback ONLY when source returned HTML (HTTP 200) and cheerio extracted 0 rows
      if (valid.length === 0 && gemini.isEnabled()) {
        const geminiRows = await gemini.extractIpoRecordsFromHtml(
          res.body,
          sourceName,
          defaultBoardType,
          nowMs
        );
        if (geminiRows.length > 0) {
          valid = geminiRows;
          usedGeminiFallback = true;
        }
      }

      if (valid.length === 0) {
        return {
          sourceName,
          kind,
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: "Parsed 0 valid IPO rows",
          usedGeminiFallback
        };
      }

      return {
        sourceName,
        kind,
        ok: true,
        ipoRecords: valid,
        subscriptionRecords: [],
        usedGeminiFallback
      };
    }
  };
}

function buildHtmlSubTask(
  client: PoliteHttpClient,
  gemini: GeminiFallbackExtractor,
  sourceName: string,
  url: string,
  defaultBoardType: BoardType,
  nowMs: number
): SourceTask {
  return {
    sourceName,
    kind: "subscription",
    run: async (): Promise<SourceFetchResult> => {
      const res = await client.fetchText(url);
      if (!res.ok || !res.body.trim()) {
        return {
          sourceName,
          kind: "subscription",
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: res.error ?? `Empty response (HTTP ${res.status})`
        };
      }

      const raw = parseHtmlSubscriptionTables(res.body, sourceName, defaultBoardType, nowMs);
      let valid = filterValidSubscriptionRecords(raw).valid;
      let usedGeminiFallback = false;

      if (valid.length === 0 && gemini.isEnabled()) {
        const geminiRows = await gemini.extractSubscriptionsFromHtml(
          res.body,
          sourceName,
          defaultBoardType,
          nowMs
        );
        if (geminiRows.length > 0) {
          valid = geminiRows;
          usedGeminiFallback = true;
        }
      }

      if (valid.length === 0) {
        return {
          sourceName,
          kind: "subscription",
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: "Parsed 0 valid subscription rows",
          usedGeminiFallback
        };
      }

      return {
        sourceName,
        kind: "subscription",
        ok: true,
        ipoRecords: [],
        subscriptionRecords: valid,
        usedGeminiFallback
      };
    }
  };
}

function buildInvestorGainJsonTask(
  client: PoliteHttpClient,
  sourceName: string,
  kind: "catalog" | "gmp",
  url: string,
  nowMs: number
): SourceTask {
  return {
    sourceName,
    kind,
    run: async (): Promise<SourceFetchResult> => {
      const res = await client.fetchText(url, {
        headers: {
          Accept: "application/json,text/plain,*/*",
          Referer: "https://www.investorgain.com/"
        }
      });
      if (!res.ok || !res.body.trim()) {
        return {
          sourceName,
          kind,
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: res.error ?? `Empty response (HTTP ${res.status})`
        };
      }

      const raw = parseInvestorGainJson(res.body, sourceName, nowMs);
      const valid = filterValidIpoRecords(raw, nowMs).valid;
      if (valid.length === 0) {
        return {
          sourceName,
          kind,
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: "Parsed 0 valid rows from InvestorGain JSON"
        };
      }

      return {
        sourceName,
        kind,
        ok: true,
        ipoRecords: valid,
        subscriptionRecords: []
      };
    }
  };
}

function buildInvestorGainSubTask(
  client: PoliteHttpClient,
  sourceName: string,
  url: string,
  nowMs: number
): SourceTask {
  return {
    sourceName,
    kind: "subscription",
    run: async (): Promise<SourceFetchResult> => {
      const res = await client.fetchText(url, {
        headers: {
          Accept: "application/json,text/plain,*/*",
          Referer: "https://www.investorgain.com/"
        }
      });
      if (!res.ok || !res.body.trim()) {
        return {
          sourceName,
          kind: "subscription",
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: res.error ?? `Empty response (HTTP ${res.status})`
        };
      }

      const raw = parseInvestorGainSubscriptionJson(res.body, sourceName, nowMs);
      const valid = filterValidSubscriptionRecords(raw).valid;
      if (valid.length === 0) {
        return {
          sourceName,
          kind: "subscription",
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: "Parsed 0 valid subscription rows from InvestorGain JSON"
        };
      }

      return {
        sourceName,
        kind: "subscription",
        ok: true,
        ipoRecords: [],
        subscriptionRecords: valid
      };
    }
  };
}

function buildNseTask(
  client: PoliteHttpClient,
  sourceName: string,
  kind: "catalog" | "subscription",
  url: string,
  nowMs: number
): SourceTask {
  return {
    sourceName,
    kind,
    run: async (): Promise<SourceFetchResult> => {
      const res = await client.fetchNseJson(url);
      if (!res.ok || !res.body.trim()) {
        // NSE often blocks cloud IPs with 401/403; skip silently and record in sourcesFailed
        return {
          sourceName,
          kind,
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: res.error ?? `HTTP ${res.status} / empty`
        };
      }

      if (kind === "subscription") {
        const rawSub = parseNseSubscriptionJson(res.body, sourceName, nowMs);
        const validSub = filterValidSubscriptionRecords(rawSub).valid;
        if (validSub.length === 0) {
          return {
            sourceName,
            kind,
            ok: false,
            ipoRecords: [],
            subscriptionRecords: [],
            error: "Empty NSE subscription payload"
          };
        }
        return {
          sourceName,
          kind,
          ok: true,
          ipoRecords: [],
          subscriptionRecords: validSub
        };
      }

      const rawIpo = parseNseIpoJson(res.body, sourceName, nowMs);
      const validIpo = filterValidIpoRecords(rawIpo, nowMs).valid;
      const rawSub = parseNseSubscriptionJson(res.body, sourceName, nowMs);
      const validSub = filterValidSubscriptionRecords(rawSub).valid;

      if (validIpo.length === 0) {
        return {
          sourceName,
          kind,
          ok: false,
          ipoRecords: [],
          subscriptionRecords: [],
          error: "Empty NSE IPO payload"
        };
      }

      return {
        sourceName,
        kind,
        ok: true,
        ipoRecords: validIpo,
        subscriptionRecords: validSub
      };
    }
  };
}

export async function fetchAllEnabledSources(
  client: PoliteHttpClient,
  gemini: GeminiFallbackExtractor,
  phases: SyncPhases,
  nowMs: number = Date.now()
): Promise<SourceFetchResult[]> {
  const ig = getInvestorGainDateParams(nowMs);
  const ts = Math.floor(nowMs / 1000);
  const tasks: SourceTask[] = [];

  if (phases.runCatalog) {
    tasks.push(
      // Chittorgarh mainboard + SME lists & listed performance
      buildHtmlIpoTask(
        client,
        gemini,
        "chittorgarh_list",
        "catalog",
        "https://www.chittorgarh.com/report/ipo-in-india-list-main-board-sme/82/",
        "MAINBOARD",
        nowMs
      ),
      buildHtmlIpoTask(
        client,
        gemini,
        "chittorgarh_upcoming",
        "catalog",
        "https://www.chittorgarh.com/report/ipo-in-india-list-main-board-sme/82/?a=upcoming",
        "MAINBOARD",
        nowMs
      ),
      buildHtmlIpoTask(
        client,
        gemini,
        "chittorgarh_listed_mainboard",
        "catalog",
        "https://www.chittorgarh.com/report/ipo-listing-performance/84/",
        "MAINBOARD",
        nowMs
      ),
      buildHtmlIpoTask(
        client,
        gemini,
        "chittorgarh_listed_sme",
        "catalog",
        "https://www.chittorgarh.com/report/sme-ipo-listing-performance/85/",
        "SME",
        nowMs
      ),
      // InvestorGain listed & previous month archive
      buildInvestorGainJsonTask(
        client,
        "investorgain_listed",
        "catalog",
        `https://webnodejs.investorgain.com/cloud/report/data-read/377/1/${ig.mm}/${ig.yyyy}/${ig.fy}/0/all?search=&v=1&_ts=${ts}`,
        nowMs
      ),
      buildInvestorGainJsonTask(
        client,
        "investorgain_prev_month",
        "catalog",
        `https://webnodejs.investorgain.com/cloud/report/data-read/331/1/${ig.prevMm}/${ig.prevYyyy}/${ig.prevFy}/0/all?search=&v=1&_ts=${ts}`,
        nowMs
      ),
      // IPOWatch upcoming & listed
      buildHtmlIpoTask(
        client,
        gemini,
        "ipowatch_upcoming",
        "catalog",
        "https://ipowatch.in/upcoming-ipo-list/",
        "MAINBOARD",
        nowMs
      ),
      buildHtmlIpoTask(
        client,
        gemini,
        "ipowatch_listed",
        "catalog",
        "https://ipowatch.in/ipo-performance-tracker/",
        "MAINBOARD",
        nowMs
      ),
      // Optional NSE endpoints
      buildNseTask(
        client,
        "nse_current",
        "catalog",
        "https://www.nseindia.com/api/ipo-current-issue",
        nowMs
      ),
      buildNseTask(
        client,
        "nse_upcoming",
        "catalog",
        "https://www.nseindia.com/api/ipo-upcoming",
        nowMs
      )
    );
  }

  if (phases.runGmp) {
    tasks.push(
      buildInvestorGainJsonTask(
        client,
        "investorgain_gmp",
        "gmp",
        `https://webnodejs.investorgain.com/cloud/report/data-read/331/1/${ig.mm}/${ig.yyyy}/${ig.fy}/0/all?search=&v=1&_ts=${ts}`,
        nowMs
      ),
      buildHtmlIpoTask(
        client,
        gemini,
        "investorgain_gmp_html",
        "gmp",
        "https://www.investorgain.com/report/live-ipo-gmp/331/",
        "MAINBOARD",
        nowMs
      ),
      buildHtmlIpoTask(
        client,
        gemini,
        "ipowatch_gmp",
        "gmp",
        "https://ipowatch.in/ipo-grey-market-premium-latest-ipo-gmp/",
        "MAINBOARD",
        nowMs
      )
    );
  }

  if (phases.runSubscription) {
    tasks.push(
      buildInvestorGainSubTask(
        client,
        "investorgain_subscription",
        `https://webnodejs.investorgain.com/cloud/report/data-read/333/1/${ig.mm}/${ig.yyyy}/${ig.fy}/0/all?search=&v=1&_ts=${ts}`,
        nowMs
      ),
      buildHtmlSubTask(
        client,
        gemini,
        "chittorgarh_sub_mainboard",
        "https://www.chittorgarh.com/report/ipo-subscription-status-live-bidding-data-bse-nse/21/",
        "MAINBOARD",
        nowMs
      ),
      buildHtmlSubTask(
        client,
        gemini,
        "chittorgarh_sub_sme",
        "https://www.chittorgarh.com/report/sme-ipo-subscription-status-live-bidding-data-bse-nse/22/",
        "SME",
        nowMs
      ),
      buildHtmlSubTask(
        client,
        gemini,
        "ipowatch_subscription",
        "https://ipowatch.in/ipo-subscription-status-today/",
        "MAINBOARD",
        nowMs
      ),
      buildNseTask(
        client,
        "nse_subscription",
        "subscription",
        "https://www.nseindia.com/api/ipo-current-issue",
        nowMs
      )
    );
  }

  const settled = await Promise.allSettled(tasks.map((t) => t.run()));
  return settled.map((result, idx) => {
    const task = tasks[idx];
    if (result.status === "fulfilled") {
      return result.value;
    }
    const errMsg =
      result.reason instanceof Error ? result.reason.message : String(result.reason);
    return {
      sourceName: task.sourceName,
      kind: task.kind,
      ok: false,
      ipoRecords: [],
      subscriptionRecords: [],
      error: errMsg
    };
  });
}
