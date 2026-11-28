import {
  isSubscriptionWindowIst,
  shouldRunCatalogInAllMode
} from "./dates.js";
import type { SyncStore } from "./firestore.js";
import {
  computeIposHash,
  MAX_BUNDLE_BYTES,
  mergeIpoCatalogWithStats,
  parseMaxIpos,
  trimBundleToByteLimit
} from "./merge.js";
import type { SyncPhases } from "./sources.js";
import type {
  IpoLiveBundleDoc,
  RawIpoRecord,
  RawSubscriptionRecord,
  RunMode,
  SelectionStats,
  SourceFetchResult
} from "./types.js";

/** Refresh unchanged bundle at least every 12h so per-item lastSeenAtMs stays well inside the 24h merge window. */
const LAST_SEEN_REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000;

export interface SyncRunSummary {
  runMode: RunMode;
  phases: SyncPhases;
  sourcesOk: string[];
  sourcesFailed: string[];
  wroteBundle: boolean;
  skippedUnchanged: boolean;
  allSourcesFailed: boolean;
  ipoCount: number;
  bundleBytes: number;
  geminiCallsUsed: number;
  selectionStats?: SelectionStats;
}

export function parseRunMode(raw: string | undefined): RunMode {
  const normalized = (raw ?? "all").trim().toLowerCase();
  if (
    normalized === "all" ||
    normalized === "catalog" ||
    normalized === "gmp" ||
    normalized === "subscription"
  ) {
    return normalized;
  }
  return "all";
}

export function resolvePhasesForRun(
  runMode: RunMode,
  lastCatalogSyncAtMs: number | undefined,
  nowMs: number
): SyncPhases {
  if (runMode === "catalog") {
    return { runCatalog: true, runGmp: false, runSubscription: false };
  }
  if (runMode === "gmp") {
    return { runCatalog: false, runGmp: true, runSubscription: false };
  }
  if (runMode === "subscription") {
    return { runCatalog: false, runGmp: false, runSubscription: true };
  }

  // "all" mode: decide by IST time
  return {
    runCatalog: shouldRunCatalogInAllMode(lastCatalogSyncAtMs, nowMs),
    runGmp: true,
    runSubscription: isSubscriptionWindowIst(nowMs)
  };
}

export interface RunSyncDependencies {
  store: SyncStore;
  fetchSources: (phases: SyncPhases) => Promise<SourceFetchResult[]>;
  getGeminiCallsUsed?: () => number;
  runMode?: RunMode;
  nowMs?: number;
  maxBundleBytes?: number;
  maxIpos?: number;
  log?: (line: string) => void;
}

/**
 * Orchestrates read-before-write replacement selection, 24h per-field fallback,
 * single-line JSON selection logging, unchanged-hash skip, 900 KB cap,
 * and meta-only write when every source fails.
 */
export async function runSyncPipeline(
  deps: RunSyncDependencies
): Promise<SyncRunSummary> {
  const nowMs = deps.nowMs ?? Date.now();
  const runMode = deps.runMode ?? "all";
  const maxBundleBytes = deps.maxBundleBytes ?? MAX_BUNDLE_BYTES;
  const maxIpos = parseMaxIpos(deps.maxIpos ?? process.env.MAX_IPOS);
  const log = deps.log ?? console.log;

  const [existingBundle, existingMeta] = await Promise.all([
    deps.store.readBundle(),
    deps.store.readMeta()
  ]);

  const phases = resolvePhasesForRun(
    runMode,
    existingMeta?.lastCatalogSyncAtMs,
    nowMs
  );

  const results = await deps.fetchSources(phases);
  const sourcesOk = results.filter((r) => r.ok).map((r) => r.sourceName).sort();
  const sourcesFailed = results
    .filter((r) => !r.ok)
    .map((r) => r.sourceName)
    .sort();

  const geminiCallsUsed = deps.getGeminiCallsUsed?.() ?? 0;

  // If every source fails, write NOTHING to ipo_live_bundle/current.
  // Only update sourcesFailed and lastError in ipo_live_meta/sync.
  if (sourcesOk.length === 0) {
    const errorSummary = results
      .filter((r) => !r.ok)
      .map((r) => `${r.sourceName}: ${r.error ?? "failed"}`)
      .join("; ")
      .slice(0, 1000);

    await deps.store.writeMeta({
      lastRunMode: runMode,
      sourcesOk: [],
      sourcesFailed,
      lastError: errorSummary || "All sources failed",
      lastCatalogSyncAtMs: existingMeta?.lastCatalogSyncAtMs,
      lastBundleHash: existingMeta?.lastBundleHash,
      geminiCallsUsed
    });

    return {
      runMode,
      phases,
      sourcesOk: [],
      sourcesFailed,
      wroteBundle: false,
      skippedUnchanged: false,
      allSourcesFailed: true,
      ipoCount: existingBundle?.ipos.length ?? 0,
      bundleBytes: 0,
      geminiCallsUsed
    };
  }

  const allIpoRecords: RawIpoRecord[] = [];
  const allSubRecords: RawSubscriptionRecord[] = [];
  for (const r of results) {
    if (!r.ok) continue;
    allIpoRecords.push(...r.ipoRecords);
    allSubRecords.push(...r.subscriptionRecords);
  }

  const existingItems = existingBundle?.ipos ?? [];
  const { ipos: selectedIpos, stats: selectionStats } = mergeIpoCatalogWithStats(
    existingItems,
    allIpoRecords,
    allSubRecords,
    nowMs,
    maxIpos
  );

  // Single-line structured JSON log after selection (no secrets, no raw HTML)
  log(
    JSON.stringify({
      event: "ipo_selection",
      maxIpos: selectionStats.maxIpos,
      beforeCap: selectionStats.beforeCap,
      afterCap: selectionStats.afterCap,
      byStatus: selectionStats.byStatus,
      byBoardType: selectionStats.byBoardType,
      droppedByReason: selectionStats.droppedByReason,
      fieldCoverage: selectionStats.fieldCoverage
    })
  );

  const candidateDoc: Omit<IpoLiveBundleDoc, "updatedAt"> = {
    version: 1,
    lastSuccessAt: new Date(nowMs).toISOString(),
    sourcesOk,
    sourcesFailed,
    ipos: selectedIpos
  };

  const trimmedDoc = trimBundleToByteLimit(candidateDoc, nowMs, maxBundleBytes);
  const bundleBytes = Buffer.byteLength(JSON.stringify(trimmedDoc), "utf8");

  const prevHash = existingBundle ? computeIposHash(existingBundle.ipos) : "";
  const nextHash = computeIposHash(trimmedDoc.ipos);

  const catalogSucceeded =
    phases.runCatalog && results.some((r) => r.kind === "catalog" && r.ok);
  const nextCatalogSyncAtMs = catalogSucceeded
    ? nowMs
    : existingMeta?.lastCatalogSyncAtMs;

  const needsLastSeenRefresh =
    existingBundle !== null &&
    existingBundle.ipos.some(
      (item) =>
        typeof item.lastSeenAtMs !== "number" ||
        !Number.isFinite(item.lastSeenAtMs) ||
        nowMs - item.lastSeenAtMs >= LAST_SEEN_REFRESH_INTERVAL_MS
    );

  if (existingBundle && prevHash === nextHash && !needsLastSeenRefresh) {
    await deps.store.writeMeta({
      lastRunMode: runMode,
      sourcesOk,
      sourcesFailed,
      lastError: "",
      lastCatalogSyncAtMs: nextCatalogSyncAtMs,
      lastBundleHash: nextHash,
      geminiCallsUsed
    });

    return {
      runMode,
      phases,
      sourcesOk,
      sourcesFailed,
      wroteBundle: false,
      skippedUnchanged: true,
      allSourcesFailed: false,
      ipoCount: trimmedDoc.ipos.length,
      bundleBytes,
      geminiCallsUsed,
      selectionStats
    };
  }

  // Always REPLACE the entire bundle document (including ipos array)
  await deps.store.writeBundle(trimmedDoc);
  await deps.store.writeMeta({
    lastRunMode: runMode,
    sourcesOk,
    sourcesFailed,
    lastError: "",
    lastCatalogSyncAtMs: nextCatalogSyncAtMs,
    lastBundleHash: nextHash,
    geminiCallsUsed
  });

  return {
    runMode,
    phases,
    sourcesOk,
    sourcesFailed,
    wroteBundle: true,
    skippedUnchanged: false,
    allSourcesFailed: false,
    ipoCount: trimmedDoc.ipos.length,
    bundleBytes,
    geminiCallsUsed,
    selectionStats
  };
}
