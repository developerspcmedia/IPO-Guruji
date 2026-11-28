import { FirebaseAdminStore, FIRESTORE_DATABASE_ID } from "./firestore.js";
import { GeminiFallbackExtractor } from "./geminiFallback.js";
import { PoliteHttpClient } from "./http.js";
import { fetchAllEnabledSources } from "./sources.js";
import { parseRunMode, runSyncPipeline } from "./syncRunner.js";

async function main(): Promise<void> {
  const runMode = parseRunMode(process.env.RUN_MODE);
  const nowMs = Date.now();

  console.log(
    JSON.stringify({
      event: "sync_start",
      runMode,
      databaseId: FIRESTORE_DATABASE_ID,
      timestamp: new Date(nowMs).toISOString()
    })
  );

  const store = new FirebaseAdminStore(FIRESTORE_DATABASE_ID);
  const httpClient = new PoliteHttpClient(1_000);
  const gemini = new GeminiFallbackExtractor();

  const summary = await runSyncPipeline({
    store,
    runMode,
    nowMs,
    fetchSources: (phases) => fetchAllEnabledSources(httpClient, gemini, phases, nowMs),
    getGeminiCallsUsed: () => gemini.getCallsUsed()
  });

  console.log(
    JSON.stringify({
      event: "sync_complete",
      ...summary
    })
  );

  if (summary.allSourcesFailed) {
    // Exit 0 so scheduled GitHub Actions does not spam failure emails on transient upstream outages
    // while recording the exact failure in ipo_live_meta/sync.
    console.warn("All sources failed; ipo_live_bundle/current was preserved untouched.");
  }
}

main().catch((err) => {
  const message = err instanceof Error ? err.message : String(err);
  // Never dump full objects/env vars to avoid leaking secrets
  console.error(`Fatal sync error: ${message}`);
  process.exitCode = 1;
});
