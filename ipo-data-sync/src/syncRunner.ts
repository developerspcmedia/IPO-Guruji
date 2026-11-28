import {
  isSubscriptionWindowIst,
  shouldRunCatalogInAllMode
} from "./dates.js";
import type { SyncStore } from "./firestore.js";
import {
  computeIposHash,
  MAX_BUNDLE_BYTES,
  mergeIpoCatalog,
  trimBundleToByteLimit
} from "./merge.js";
import type { SyncPhases } from "./sources.js";
import type {
  IpoLiveBundleDoc,
  RawIpoRecord,
  RawSubscriptionRecord,
  RunMode,
  SourceFetchResult
} from "./types.js";

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
}

/**
 * Orchestrates read-before-write merge, unchanged-hash skip, 900 KB cap,
 * and meta-only write when every source fails.
 */
export async function runSyncPipeline(
  deps: RunSyncDependencies
): Promise<SyncRunSummary> {
  const nowMs = deps.nowMs ?? Date.now();
  const runMode = deps.runMode ?? "all";
  const maxBundleBytes = deps.maxBundleBytes ?? MAX_BUNDLE_BYTES;

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
  const mergedIpos = mergeIpoCatalog(existingItems, allIpoRecords, allSubRecords, nowMs);

  const candidateDoc: Omit<IpoLiveBundleDoc, "updatedAt"> = {
    version: 1,
    lastSuccessAt: new Date(nowMs).toISOString(),
    sourcesOk,
    sourcesFailed,
    ipos: mergedIpos
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

  if (existingBundle && prevHash === nextHash) {
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
      geminiCallsUsed
    };
  }

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
    geminiCallsUsed
  };
}
