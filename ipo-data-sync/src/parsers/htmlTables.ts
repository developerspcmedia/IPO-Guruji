import * as cheerio from "cheerio";
import { normalizeDateString, parseDateRange } from "../dates.js";
import {
  cleanCompanyName,
  detectBoardType,
  isPlaceholderCompanyName,
  isWithdrawnOrCancelled
} from "../normalize.js";
import type { BoardType, RawIpoRecord, RawSubscriptionRecord } from "../types.js";
import {
  parseGmpRupees,
  parseInteger,
  parseIssueSizeCr,
  parsePriceBand,
  parseSubscriptionX,
  sanitizeHttpUrl
} from "./helpers.js";

function normalizeHeader(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

interface TableColumnMap {
  name: number;
  category: number;
  openDate: number;
  closeDate: number;
  dateRange: number;
  allotmentDate: number;
  listingDate: number;
  price: number;
  lotSize: number;
  issueSize: number;
  gmp: number;
  registrar: number;
  status: number;
}

function mapIpoColumns(headers: string[]): TableColumnMap {
  const map: TableColumnMap = {
    name: -1,
    category: -1,
    openDate: -1,
    closeDate: -1,
    dateRange: -1,
    allotmentDate: -1,
    listingDate: -1,
    price: -1,
    lotSize: -1,
    issueSize: -1,
    gmp: -1,
    registrar: -1,
    status: -1
  };

  headers.forEach((raw, idx) => {
    const h = normalizeHeader(raw);
    if (map.name === -1 && (h.includes("company") || h.includes("issuer") || h === "ipo" || h === "ipo name" || h === "name" || h.includes("stock"))) {
      map.name = idx;
    } else if (map.category === -1 && (h.includes("category") || h.includes("board") || h.includes("exchange") || h === "type" || h.includes("segment"))) {
      map.category = idx;
    } else if (map.openDate === -1 && (h.includes("open") || h.includes("start date"))) {
      map.openDate = idx;
    } else if (map.closeDate === -1 && (h.includes("close") || h.includes("end date"))) {
      map.closeDate = idx;
    } else if (map.allotmentDate === -1 && (h.includes("allot") || h.includes("boa"))) {
      map.allotmentDate = idx;
    } else if (map.listingDate === -1 && h.includes("listing") && !h.includes("gain") && !h.includes("price") && !h.includes("est")) {
      map.listingDate = idx;
    } else if (map.dateRange === -1 && (h === "date" || h === "dates" || h.includes("ipo date") || h.includes("issue date"))) {
      map.dateRange = idx;
    } else if (map.gmp === -1 && (h === "gmp" || h.startsWith("gmp ") || h.includes("grey market") || h.includes("ipo gmp"))) {
      map.gmp = idx;
    } else if (map.price === -1 && (h.includes("price") || h.includes("band")) && !h.includes("listing") && !h.includes("est") && !h.includes("kostak")) {
      map.price = idx;
    } else if (map.lotSize === -1 && (h.includes("lot") || h.includes("min qty") || h.includes("shares"))) {
      map.lotSize = idx;
    } else if (map.issueSize === -1 && (h.includes("issue size") || h.includes("ipo size") || h === "size")) {
      map.issueSize = idx;
    } else if (map.registrar === -1 && h.includes("registrar")) {
      map.registrar = idx;
    } else if (map.status === -1 && h.includes("status")) {
      map.status = idx;
    }
  });

  return map;
}

/**
 * Ported from Android LiveGmpRepository.parseHtmlGmpTables using cheerio.
 * Handles Chittorgarh, IPOWatch, and InvestorGain web HTML tables.
 */
export function parseHtmlGmpTables(
  html: string,
  sourceName: string,
  defaultBoardType: BoardType = "MAINBOARD",
  nowMs: number = Date.now()
): RawIpoRecord[] {
  if (!html.trim()) return [];
  const $ = cheerio.load(html);
  const results: RawIpoRecord[] = [];

  $("table").each((_, tableEl) => {
    const $table = $(tableEl);
    const rows = $table.find("tr").toArray();
    if (rows.length < 2) return;

    // Locate header row (prefer thead th, else first row with >=2 cells)
    let headerRowIndex = 0;
    let headers: string[] = [];

    const theadCells = $table.find("thead tr").first().find("th, td").toArray();
    if (theadCells.length >= 2) {
      headers = theadCells.map((el) => $(el).text().trim());
      headerRowIndex = $table.find("thead tr").length;
    } else {
      for (let i = 0; i < Math.min(rows.length, 3); i++) {
        const cells = $(rows[i]).find("th, td").toArray();
        if (cells.length >= 2) {
          headers = cells.map((el) => $(el).text().trim());
          headerRowIndex = i + 1;
          break;
        }
      }
    }

    if (headers.length < 2) return;
    const cols = mapIpoColumns(headers);
    if (cols.name === -1) return;

    // Require at least one useful IPO data column besides name
    const hasUsefulColumn =
      cols.openDate !== -1 ||
      cols.closeDate !== -1 ||
      cols.dateRange !== -1 ||
      cols.listingDate !== -1 ||
      cols.price !== -1 ||
      cols.gmp !== -1 ||
      cols.issueSize !== -1;
    if (!hasUsefulColumn) return;

    const bodyRows =
      $table.find("tbody tr").length > 0
        ? $table.find("tbody tr").toArray()
        : rows.slice(headerRowIndex);

    for (const rowEl of bodyRows) {
      const cells = $(rowEl).find("td").toArray();
      if (cells.length < 2 || cols.name >= cells.length) continue;

      const rawNameCell = $(cells[cols.name]).text().replace(/\s+/g, " ").trim();
      const companyName = cleanCompanyName(rawNameCell);
      if (isPlaceholderCompanyName(companyName)) continue;

      const categoryText =
        cols.category !== -1 && cols.category < cells.length
          ? $(cells[cols.category]).text().trim()
          : "";
      const boardType = detectBoardType(rawNameCell, categoryText, defaultBoardType);

      let openDate =
        cols.openDate !== -1 && cols.openDate < cells.length
          ? normalizeDateString($(cells[cols.openDate]).text().trim(), nowMs)
          : undefined;
      let closeDate =
        cols.closeDate !== -1 && cols.closeDate < cells.length
          ? normalizeDateString($(cells[cols.closeDate]).text().trim(), nowMs)
          : undefined;

      if ((!openDate || !closeDate) && cols.dateRange !== -1 && cols.dateRange < cells.length) {
        const parsedRange = parseDateRange($(cells[cols.dateRange]).text().trim(), nowMs);
        openDate = openDate ?? parsedRange.openDate;
        closeDate = closeDate ?? parsedRange.closeDate;
      }

      const allotmentDate =
        cols.allotmentDate !== -1 && cols.allotmentDate < cells.length
          ? normalizeDateString($(cells[cols.allotmentDate]).text().trim(), nowMs)
          : undefined;

      const listingDate =
        cols.listingDate !== -1 && cols.listingDate < cells.length
          ? normalizeDateString($(cells[cols.listingDate]).text().trim(), nowMs)
          : undefined;

      const priceBand =
        cols.price !== -1 && cols.price < cells.length
          ? parsePriceBand($(cells[cols.price]).text().trim())
          : {};

      const lotSize =
        cols.lotSize !== -1 && cols.lotSize < cells.length
          ? parseInteger($(cells[cols.lotSize]).text().trim())
          : undefined;

      const issueSizeCr =
        cols.issueSize !== -1 && cols.issueSize < cells.length
          ? parseIssueSizeCr($(cells[cols.issueSize]).text().trim())
          : undefined;

      const gmpRupees =
        cols.gmp !== -1 && cols.gmp < cells.length
          ? parseGmpRupees($(cells[cols.gmp]).text().trim())
          : undefined;

      let registrarName: string | undefined;
      let registrarUrl: string | undefined;
      if (cols.registrar !== -1 && cols.registrar < cells.length) {
        const $regCell = $(cells[cols.registrar]);
        const text = $regCell.text().replace(/\s+/g, " ").trim();
        if (text && !/^(-+|--+|na|n\/a|tba)$/i.test(text)) {
          registrarName = text;
        }
        const href = $regCell.find("a").first().attr("href");
        registrarUrl = sanitizeHttpUrl(href);
      }

      const statusText =
        cols.status !== -1 && cols.status < cells.length
          ? $(cells[cols.status]).text().replace(/\s+/g, " ").trim()
          : "";
      const isWithdrawn = isWithdrawnOrCancelled(rawNameCell, statusText) || undefined;

      results.push({
        companyName,
        boardType,
        source: sourceName,
        isAuthoritative: false,
        isWithdrawn,
        openDate,
        closeDate,
        allotmentDate,
        listingDate,
        priceLow: priceBand.priceLow,
        priceHigh: priceBand.priceHigh,
        lotSize,
        issueSizeCr,
        gmpRupees,
        gmpUpdatedAtMs: gmpRupees !== undefined ? nowMs : undefined,
        registrarName,
        registrarUrl
      });
    }
  });

  return results;
}

interface SubColumnMap {
  name: number;
  category: number;
  qib: number;
  bNii: number;
  sNii: number;
  niiFallback: number;
  retail: number;
  employee: number;
  total: number;
}

function mapSubColumns(headers: string[]): SubColumnMap {
  const map: SubColumnMap = {
    name: -1,
    category: -1,
    qib: -1,
    bNii: -1,
    sNii: -1,
    niiFallback: -1,
    retail: -1,
    employee: -1,
    total: -1
  };

  headers.forEach((raw, idx) => {
    const h = normalizeHeader(raw);
    if (map.name === -1 && (h.includes("company") || h.includes("ipo") || h === "name")) {
      map.name = idx;
    } else if (map.category === -1 && (h.includes("category") || h.includes("board") || h.includes("type"))) {
      map.category = idx;
    } else if (map.qib === -1 && (h.includes("qib"))) {
      map.qib = idx;
    } else if (map.bNii === -1 && (h.includes("bnii") || h.includes("b nii") || h.includes("b hni") || h.includes("bhni") || (h.includes("nii") && h.includes("10")))) {
      map.bNii = idx;
    } else if (map.sNii === -1 && (h.includes("snii") || h.includes("s nii") || h.includes("s hni") || h.includes("shni"))) {
      map.sNii = idx;
    } else if (map.niiFallback === -1 && (h === "nii" || h.includes("nii") || h.includes("hni"))) {
      map.niiFallback = idx;
    } else if (map.retail === -1 && (h.includes("retail") || h === "rii" || h.includes("riis"))) {
      map.retail = idx;
    } else if (map.employee === -1 && (h.includes("emp"))) {
      map.employee = idx;
    } else if (map.total === -1 && (h.includes("total"))) {
      map.total = idx;
    }
  });

  return map;
}

/**
 * Parses Chittorgarh and IPOWatch HTML subscription tables using cheerio.
 */
export function parseHtmlSubscriptionTables(
  html: string,
  sourceName: string,
  defaultBoardType: BoardType = "MAINBOARD",
  nowMs: number = Date.now()
): RawSubscriptionRecord[] {
  if (!html.trim()) return [];
  const $ = cheerio.load(html);
  const results: RawSubscriptionRecord[] = [];

  $("table").each((_, tableEl) => {
    const $table = $(tableEl);
    const rows = $table.find("tr").toArray();
    if (rows.length < 2) return;

    let headers: string[] = [];
    let headerRowIndex = 0;

    const theadCells = $table.find("thead tr").first().find("th, td").toArray();
    if (theadCells.length >= 2) {
      headers = theadCells.map((el) => $(el).text().trim());
      headerRowIndex = $table.find("thead tr").length;
    } else {
      for (let i = 0; i < Math.min(rows.length, 3); i++) {
        const cells = $(rows[i]).find("th, td").toArray();
        if (cells.length >= 2) {
          headers = cells.map((el) => $(el).text().trim());
          headerRowIndex = i + 1;
          break;
        }
      }
    }

    const cols = mapSubColumns(headers);
    if (cols.name === -1) return;
    if (cols.total === -1 && cols.qib === -1 && cols.retail === -1 && cols.niiFallback === -1) {
      return;
    }

    const bodyRows =
      $table.find("tbody tr").length > 0
        ? $table.find("tbody tr").toArray()
        : rows.slice(headerRowIndex);

    for (const rowEl of bodyRows) {
      const cells = $(rowEl).find("td").toArray();
      if (cells.length < 2 || cols.name >= cells.length) continue;

      const rawNameCell = $(cells[cols.name]).text().replace(/\s+/g, " ").trim();
      const companyName = cleanCompanyName(rawNameCell);
      if (isPlaceholderCompanyName(companyName)) continue;

      const categoryText =
        cols.category !== -1 && cols.category < cells.length
          ? $(cells[cols.category]).text().trim()
          : "";
      const boardType = detectBoardType(rawNameCell, categoryText, defaultBoardType);

      const readSub = (colIdx: number): number | undefined =>
        colIdx !== -1 && colIdx < cells.length
          ? parseSubscriptionX($(cells[colIdx]).text().trim())
          : undefined;

      const qibSubX = readSub(cols.qib);
      const bNiiSubX = readSub(cols.bNii) ?? readSub(cols.niiFallback);
      const sNiiSubX = readSub(cols.sNii);
      const retailSubX = readSub(cols.retail);
      const employeeSubX = readSub(cols.employee);
      const totalSubX = readSub(cols.total);

      if (
        qibSubX === undefined &&
        bNiiSubX === undefined &&
        sNiiSubX === undefined &&
        retailSubX === undefined &&
        employeeSubX === undefined &&
        totalSubX === undefined
      ) {
        continue;
      }

      results.push({
        companyName,
        boardType,
        source: sourceName,
        isAuthoritative: false,
        qibSubX,
        bNiiSubX,
        sNiiSubX,
        retailSubX,
        employeeSubX,
        totalSubX,
        subUpdatedAtMs: nowMs
      });
    }
  });

  return results;
}

/**
 * Extracts table text (or cleaned body text if no tables exist) trimmed to maxChars (default 15,000)
 * for optional Gemini HTML fallback extraction.
 */
export function extractTrimmedTablesText(html: string, maxChars: number = 15_000): string {
  if (!html.trim()) return "";
  const $ = cheerio.load(html);
  $("script, style, noscript, svg, iframe, nav, footer").remove();

  const lines: string[] = [];
  $("table").each((idx, tableEl) => {
    const tableLines: string[] = [];
    $(tableEl)
      .find("tr")
      .each((_, tr) => {
        const cells = $(tr)
          .find("th, td")
          .toArray()
          .map((c) => $(c).text().replace(/\s+/g, " ").trim())
          .filter((t) => t.length > 0);
        if (cells.length > 0) {
          tableLines.push(cells.join(" | "));
        }
      });
    if (tableLines.length >= 2) {
      lines.push(`--- TABLE ${idx + 1} ---`, ...tableLines);
    }
  });

  let text = lines.join("\n").trim();
  if (!text) {
    text = $("body").text().replace(/[ \t]+/g, " ").replace(/\n{2,}/g, "\n").trim();
  }

  if (text.length > maxChars) {
    return text.slice(0, maxChars);
  }
  return text;
}
