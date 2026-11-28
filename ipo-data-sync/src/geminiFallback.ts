import { z } from "zod";
import { normalizeDateString } from "./dates.js";
import { cleanCompanyName, detectBoardType } from "./normalize.js";
import { extractTrimmedTablesText } from "./parsers/htmlTables.js";
import type { BoardType, RawIpoRecord, RawSubscriptionRecord } from "./types.js";
import { filterValidIpoRecords, filterValidSubscriptionRecords } from "./validation.js";

export const DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";
export const MAX_GEMINI_CALLS_PER_RUN = 3;
export const MAX_GEMINI_INPUT_CHARS = 15_000;

const GeminiIpoRowSchema = z.object({
  companyName: z.string(),
  boardType: z.enum(["MAINBOARD", "SME"]).optional(),
  openDate: z.string().nullable().optional(),
  closeDate: z.string().nullable().optional(),
  allotmentDate: z.string().nullable().optional(),
  listingDate: z.string().nullable().optional(),
  priceLow: z.number().nullable().optional(),
  priceHigh: z.number().nullable().optional(),
  lotSize: z.number().nullable().optional(),
  issueSizeCr: z.number().nullable().optional(),
  gmpRupees: z.number().nullable().optional(),
  registrarName: z.string().nullable().optional()
});

const GeminiIpoResponseSchema = z.object({
  items: z.array(GeminiIpoRowSchema)
});

const GeminiSubRowSchema = z.object({
  companyName: z.string(),
  boardType: z.enum(["MAINBOARD", "SME"]).optional(),
  qibSubX: z.number().nullable().optional(),
  bNiiSubX: z.number().nullable().optional(),
  sNiiSubX: z.number().nullable().optional(),
  retailSubX: z.number().nullable().optional(),
  employeeSubX: z.number().nullable().optional(),
  totalSubX: z.number().nullable().optional()
});

const GeminiSubResponseSchema = z.object({
  items: z.array(GeminiSubRowSchema)
});

function sanitizeErrorMessage(raw: string, apiKey: string): string {
  if (!apiKey) return raw;
  return raw.split(apiKey).join("[REDACTED]");
}

/**
 * Optional free-tier Gemini REST API fallback triggered ONLY when a source returns HTML
 * and the cheerio parser extracts 0 rows.
 * - Uses temperature: 0 and structured JSON responseSchema
 * - Validates output with zod + standard IPO validation rules
 * - Enforces max 3 calls per run
 * - Silently skips when GEMINI_API_KEY is unset
 * - Never prints secrets in logs
 */
export class GeminiFallbackExtractor {
  private readonly apiKey: string;
  private readonly model: string;
  private readonly maxCalls: number;
  private callsUsed = 0;

  constructor(options?: { apiKey?: string; model?: string; maxCalls?: number }) {
    this.apiKey = (options?.apiKey ?? process.env.GEMINI_API_KEY ?? "").trim();
    this.model = (options?.model ?? process.env.GEMINI_MODEL ?? DEFAULT_GEMINI_MODEL).trim() || DEFAULT_GEMINI_MODEL;
    this.maxCalls = options?.maxCalls ?? MAX_GEMINI_CALLS_PER_RUN;
  }

  isEnabled(): boolean {
    return this.apiKey.length > 0 && this.callsUsed < this.maxCalls;
  }

  getCallsUsed(): number {
    return this.callsUsed;
  }

  private async callGeminiJson(
    prompt: string,
    responseSchema: Record<string, unknown>
  ): Promise<unknown | null> {
    if (!this.isEnabled()) return null;
    this.callsUsed += 1;

    const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(this.model)}:generateContent`;

    try {
      const res = await fetch(endpoint, {
        method: "POST",
        signal: AbortSignal.timeout(15_000),
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": this.apiKey
        },
        body: JSON.stringify({
          contents: [{ role: "user", parts: [{ text: prompt }] }],
          generationConfig: {
            temperature: 0,
            responseMimeType: "application/json",
            responseSchema
          }
        })
      });

      if (!res.ok) {
        console.warn(`[gemini-fallback] HTTP ${res.status} from Gemini API (model=${this.model})`);
        return null;
      }

      const payload = (await res.json()) as {
        candidates?: Array<{
          content?: { parts?: Array<{ text?: string }> };
        }>;
      };
      const text = payload.candidates?.[0]?.content?.parts?.[0]?.text;
      if (!text) return null;
      return JSON.parse(text);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      console.warn(`[gemini-fallback] Request failed: ${sanitizeErrorMessage(msg, this.apiKey)}`);
      return null;
    }
  }

  async extractIpoRecordsFromHtml(
    html: string,
    sourceName: string,
    defaultBoardType: BoardType = "MAINBOARD",
    nowMs: number = Date.now()
  ): Promise<RawIpoRecord[]> {
    if (!this.isEnabled()) return [];
    const trimmed = extractTrimmedTablesText(html, MAX_GEMINI_INPUT_CHARS);
    if (trimmed.length < 40) return [];

    const prompt = [
      "Extract Indian IPO rows from the table text below.",
      "Rules:",
      "- Return ONLY real company IPO rows. Omit headers, totals, TBA, NA, or summary rows.",
      "- Format dates as 'dd MMM yyyy' (e.g. '25 Nov 2026'). Omit unknown dates.",
      "- Use boardType 'SME' only if clearly SME/NSE Emerge/BSE SME; otherwise 'MAINBOARD'.",
      "- Omit unknown numeric fields (do not invent 0 for missing prices, lot sizes, or GMP).",
      "",
      trimmed
    ].join("\n");

    const schema = {
      type: "OBJECT",
      properties: {
        items: {
          type: "ARRAY",
          items: {
            type: "OBJECT",
            properties: {
              companyName: { type: "STRING" },
              boardType: { type: "STRING", enum: ["MAINBOARD", "SME"] },
              openDate: { type: "STRING", nullable: true },
              closeDate: { type: "STRING", nullable: true },
              allotmentDate: { type: "STRING", nullable: true },
              listingDate: { type: "STRING", nullable: true },
              priceLow: { type: "NUMBER", nullable: true },
              priceHigh: { type: "NUMBER", nullable: true },
              lotSize: { type: "INTEGER", nullable: true },
              issueSizeCr: { type: "NUMBER", nullable: true },
              gmpRupees: { type: "NUMBER", nullable: true },
              registrarName: { type: "STRING", nullable: true }
            },
            required: ["companyName"]
          }
        }
      },
      required: ["items"]
    };

    const rawJson = await this.callGeminiJson(prompt, schema);
    if (!rawJson) return [];

    const parsed = GeminiIpoResponseSchema.safeParse(rawJson);
    if (!parsed.success) {
      console.warn(`[gemini-fallback] Zod validation failed for ${sourceName}`);
      return [];
    }

    const candidates: RawIpoRecord[] = parsed.data.items.map((item) => ({
      companyName: cleanCompanyName(item.companyName),
      boardType: item.boardType ?? detectBoardType(item.companyName, "", defaultBoardType),
      source: `${sourceName}:gemini`,
      isAuthoritative: false,
      openDate: normalizeDateString(item.openDate ?? undefined, nowMs),
      closeDate: normalizeDateString(item.closeDate ?? undefined, nowMs),
      allotmentDate: normalizeDateString(item.allotmentDate ?? undefined, nowMs),
      listingDate: normalizeDateString(item.listingDate ?? undefined, nowMs),
      priceLow: item.priceLow ?? undefined,
      priceHigh: item.priceHigh ?? undefined,
      lotSize: item.lotSize ?? undefined,
      issueSizeCr: item.issueSizeCr ?? undefined,
      gmpRupees: item.gmpRupees ?? undefined,
      gmpUpdatedAtMs: item.gmpRupees !== null && item.gmpRupees !== undefined ? nowMs : undefined,
      registrarName: item.registrarName?.trim() || undefined
    }));

    return filterValidIpoRecords(candidates, nowMs).valid;
  }

  async extractSubscriptionsFromHtml(
    html: string,
    sourceName: string,
    defaultBoardType: BoardType = "MAINBOARD",
    nowMs: number = Date.now()
  ): Promise<RawSubscriptionRecord[]> {
    if (!this.isEnabled()) return [];
    const trimmed = extractTrimmedTablesText(html, MAX_GEMINI_INPUT_CHARS);
    if (trimmed.length < 40) return [];

    const prompt = [
      "Extract Indian IPO subscription multiplier rows (in times, e.g. 12.45) from the table text below.",
      "Rules:",
      "- Return ONLY real company IPO rows. Omit headers, totals, TBA, or NA rows.",
      "- Map HNI / B-HNI (>10L) to bNiiSubX and S-HNI (<10L) to sNiiSubX.",
      "- Omit unknown numeric fields.",
      "",
      trimmed
    ].join("\n");

    const schema = {
      type: "OBJECT",
      properties: {
        items: {
          type: "ARRAY",
          items: {
            type: "OBJECT",
            properties: {
              companyName: { type: "STRING" },
              boardType: { type: "STRING", enum: ["MAINBOARD", "SME"] },
              qibSubX: { type: "NUMBER", nullable: true },
              bNiiSubX: { type: "NUMBER", nullable: true },
              sNiiSubX: { type: "NUMBER", nullable: true },
              retailSubX: { type: "NUMBER", nullable: true },
              employeeSubX: { type: "NUMBER", nullable: true },
              totalSubX: { type: "NUMBER", nullable: true }
            },
            required: ["companyName"]
          }
        }
      },
      required: ["items"]
    };

    const rawJson = await this.callGeminiJson(prompt, schema);
    if (!rawJson) return [];

    const parsed = GeminiSubResponseSchema.safeParse(rawJson);
    if (!parsed.success) {
      console.warn(`[gemini-fallback] Zod validation failed for subscription ${sourceName}`);
      return [];
    }

    const candidates: RawSubscriptionRecord[] = parsed.data.items.map((item) => ({
      companyName: cleanCompanyName(item.companyName),
      boardType: item.boardType ?? detectBoardType(item.companyName, "", defaultBoardType),
      source: `${sourceName}:gemini`,
      isAuthoritative: false,
      qibSubX: item.qibSubX ?? undefined,
      bNiiSubX: item.bNiiSubX ?? undefined,
      sNiiSubX: item.sNiiSubX ?? undefined,
      retailSubX: item.retailSubX ?? undefined,
      employeeSubX: item.employeeSubX ?? undefined,
      totalSubX: item.totalSubX ?? undefined,
      subUpdatedAtMs: nowMs
    }));

    return filterValidSubscriptionRecords(candidates).valid;
  }
}
