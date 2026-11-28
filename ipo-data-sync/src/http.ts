export interface HttpFetchOptions {
  headers?: Record<string, string>;
  timeoutMs?: number;
  maxRetries?: number;
  retryBackoffMs?: number;
  /** Skip retry on HTTP 401/403 (recommended for NSE). */
  skipRetryOnAuthError?: boolean;
}

export interface HttpResponseResult {
  ok: boolean;
  status: number;
  body: string;
  contentType: string;
  error?: string;
}

const DEFAULT_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const DEFAULT_TIMEOUT_MS = 10_000;
const DEFAULT_MIN_HOST_DELAY_MS = 1_000;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Polite per-host HTTP client:
 * - Enforces >= 1000 ms delay between requests to the same host
 * - Realistic browser User-Agent
 * - 10s request timeout via AbortSignal.timeout
 * - 1 retry with backoff (skipping 401/403 when configured)
 */
export class PoliteHttpClient {
  private readonly minHostDelayMs: number;
  private readonly hostQueue = new Map<string, Promise<void>>();
  private readonly hostLastRequestAt = new Map<string, number>();
  private nseCookieHeader: string | null = null;
  private nseWarmedUp = false;

  constructor(minHostDelayMs: number = DEFAULT_MIN_HOST_DELAY_MS) {
    this.minHostDelayMs = minHostDelayMs;
  }

  private async waitForHostSlot(url: string): Promise<void> {
    const host = new URL(url).hostname.toLowerCase();
    const prev = this.hostQueue.get(host) ?? Promise.resolve();

    let release!: () => void;
    const next = new Promise<void>((resolve) => {
      release = resolve;
    });
    this.hostQueue.set(host, prev.then(() => next));

    await prev;
    try {
      const last = this.hostLastRequestAt.get(host) ?? 0;
      const elapsed = Date.now() - last;
      if (last > 0 && elapsed < this.minHostDelayMs) {
        await sleep(this.minHostDelayMs - elapsed);
      }
      this.hostLastRequestAt.set(host, Date.now());
    } finally {
      release();
    }
  }

  async fetchText(url: string, options: HttpFetchOptions = {}): Promise<HttpResponseResult> {
    const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    const maxRetries = options.maxRetries ?? 1;
    const retryBackoffMs = options.retryBackoffMs ?? 1200;
    const skipRetryOnAuthError = options.skipRetryOnAuthError ?? true;

    let lastResult: HttpResponseResult = {
      ok: false,
      status: 0,
      body: "",
      contentType: "",
      error: "Request did not execute"
    };

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      if (attempt > 0) {
        await sleep(retryBackoffMs * attempt);
      }

      await this.waitForHostSlot(url);

      try {
        const res = await fetch(url, {
          method: "GET",
          redirect: "follow",
          signal: AbortSignal.timeout(timeoutMs),
          headers: {
            "User-Agent": DEFAULT_USER_AGENT,
            Accept:
              "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-IN,en-US;q=0.9,en;q=0.8",
            "Cache-Control": "no-cache",
            Pragma: "no-cache",
            ...(options.headers ?? {})
          }
        });

        const body = await res.text();
        const contentType = res.headers.get("content-type") ?? "";

        if (res.ok) {
          return {
            ok: true,
            status: res.status,
            body,
            contentType
          };
        }

        lastResult = {
          ok: false,
          status: res.status,
          body,
          contentType,
          error: `HTTP ${res.status}`
        };

        if (skipRetryOnAuthError && (res.status === 401 || res.status === 403)) {
          return lastResult;
        }
        if (res.status >= 400 && res.status < 500 && res.status !== 429) {
          return lastResult;
        }
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        lastResult = {
          ok: false,
          status: 0,
          body: "",
          contentType: "",
          error: message
        };
      }
    }

    return lastResult;
  }

  /**
   * Optional NSE session warmup to obtain cookies before querying NSE API endpoints.
   * Silently tolerates 401/403 blocks.
   */
  async warmUpNseSession(): Promise<string | null> {
    if (this.nseWarmedUp) return this.nseCookieHeader;
    this.nseWarmedUp = true;

    const cookies = new Map<string, string>();
    const urls = [
      "https://www.nseindia.com/",
      "https://www.nseindia.com/market-data/all-upcoming-issues-ipo"
    ];

    for (const url of urls) {
      await this.waitForHostSlot(url);
      try {
        const res = await fetch(url, {
          method: "GET",
          redirect: "follow",
          signal: AbortSignal.timeout(DEFAULT_TIMEOUT_MS),
          headers: {
            "User-Agent": DEFAULT_USER_AGENT,
            Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-IN,en-US;q=0.9,en;q=0.8",
            ...(cookies.size > 0
              ? {
                  Cookie: Array.from(cookies.entries())
                    .map(([k, v]) => `${k}=${v}`)
                    .join("; ")
                }
              : {})
          }
        });

        const setCookies =
          typeof res.headers.getSetCookie === "function" ? res.headers.getSetCookie() : [];
        for (const rawCookie of setCookies) {
          const pair = rawCookie.split(";")[0]?.trim();
          if (!pair) continue;
          const eq = pair.indexOf("=");
          if (eq > 0) {
            cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
          }
        }
      } catch {
        // Ignore NSE warmup errors; API fetch will cleanly record failure if blocked
      }
    }

    this.nseCookieHeader =
      cookies.size > 0
        ? Array.from(cookies.entries())
            .map(([k, v]) => `${k}=${v}`)
            .join("; ")
        : null;
    return this.nseCookieHeader;
  }

  async fetchNseJson(url: string): Promise<HttpResponseResult> {
    const cookie = await this.warmUpNseSession();
    return this.fetchText(url, {
      maxRetries: 0,
      skipRetryOnAuthError: true,
      headers: {
        Accept: "application/json,text/plain,*/*",
        Referer: "https://www.nseindia.com/market-data/all-upcoming-issues-ipo",
        "X-Requested-With": "XMLHttpRequest",
        ...(cookie ? { Cookie: cookie } : {})
      }
    });
  }
}
