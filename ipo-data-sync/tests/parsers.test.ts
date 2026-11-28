import { describe, expect, it } from "vitest";
import { istDateToUtcMs } from "../src/dates.js";
import {
  extractTrimmedTablesText,
  parseHtmlGmpTables,
  parseHtmlSubscriptionTables
} from "../src/parsers/htmlTables.js";
import {
  parseInvestorGainJson,
  parseInvestorGainSubscriptionJson
} from "../src/parsers/investorgain.js";
import {
  parseNseIpoJson,
  parseNseSubscriptionJson
} from "../src/parsers/nse.js";

const REF_MS = istDateToUtcMs(2026, 11, 27)! + 12 * 60 * 60 * 1000;

describe("InvestorGain parsers", () => {
  it("parses InvestorGain GMP JSON with HTML badges, negative GMP, and dates", () => {
    const fixture = JSON.stringify({
      reportTableData: [
        {
          "~ipo_name": "<a href='/ipo/acme'>Acme Solar Holdings Ltd IPO</a>",
          "~IPO_Category": "IPO",
          Price: "₹275 to ₹289",
          Lot: "51",
          "IPO Size": "₹2,900.00 Cr",
          "~gmp": "<b>₹42</b> (14.5%)",
          "GMP Updated": "27-Nov-2026 11:30",
          Open: "25-Nov-2026",
          Close: "27-Nov-2026",
          "BoA Dt": "28-Nov-2026",
          Listing: "02-Dec-2026",
          Registrar: "KFin Technologies Ltd",
          Status: "Open"
        },
        {
          "~ipo_name": "Sree Cosmetics Ltd",
          "~IPO_Category": "Mainboard",
          Price: "100 - 110",
          Lot: "135",
          "~gmp": "₹-15 (-13.6%)",
          Open: "26 Nov 2026",
          Close: "28 Nov 2026"
        },
        {
          "~ipo_name": "Apex Precision Components (NSE SME)",
          "~IPO_Category": "SME",
          Price: "80",
          Lot: "1600",
          "~gmp": "₹25",
          Open: "25 Nov 2026",
          Close: "27 Nov 2026"
        }
      ]
    });

    const rows = parseInvestorGainJson(fixture, "investorgain_gmp", REF_MS);
    expect(rows).toHaveLength(3);

    expect(rows[0]).toMatchObject({
      companyName: "Acme Solar Holdings Ltd",
      boardType: "MAINBOARD",
      priceLow: 275,
      priceHigh: 289,
      lotSize: 51,
      issueSizeCr: 2900,
      gmpRupees: 42,
      openDate: "25 Nov 2026",
      closeDate: "27 Nov 2026",
      allotmentDate: "28 Nov 2026",
      listingDate: "02 Dec 2026",
      registrarName: "KFin Technologies Ltd"
    });

    // "Cosmetics" contains "sme" substring but MUST be MAINBOARD
    expect(rows[1].companyName).toBe("Sree Cosmetics Ltd");
    expect(rows[1].boardType).toBe("MAINBOARD");
    expect(rows[1].gmpRupees).toBe(-15);

    expect(rows[2].boardType).toBe("SME");
    expect(rows[2].lotSize).toBe(1600);
  });

  it("parses InvestorGain Subscription JSON including B-HNI and S-HNI", () => {
    const fixture = JSON.stringify({
      reportTableData: [
        {
          Name: "Acme Solar Holdings Ltd IPO",
          "~IPO_Category": "IPO",
          QIB: "12.45x",
          BNII: "18.20",
          SNII: "9.55",
          RII: "4.10x",
          Emp: "1.25",
          Total: "8.92x",
          Updated: "27-Nov-2026 15:10"
        }
      ]
    });

    const rows = parseInvestorGainSubscriptionJson(
      fixture,
      "investorgain_subscription",
      REF_MS
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      companyName: "Acme Solar Holdings Ltd",
      boardType: "MAINBOARD",
      qibSubX: 12.45,
      bNiiSubX: 18.2,
      sNiiSubX: 9.55,
      retailSubX: 4.1,
      employeeSubX: 1.25,
      totalSubX: 8.92
    });
  });
});

describe("NSE JSON parsers", () => {
  it("parses NSE current issue JSON with authoritative flag and subscription numbers", () => {
    const fixture = JSON.stringify([
      {
        companyName: "Acme Solar Holdings Limited",
        series: "EQ",
        issueStartDate: "25-Nov-2026",
        issueEndDate: "27-Nov-2026",
        priceRange: "Rs.275 to Rs.289",
        minBidQuantity: "51",
        issueSizeCr: "2900",
        status: "Active",
        noOfTime: "9.15",
        qib: "13.02",
        nii: "15.40",
        retail: "4.35"
      },
      {
        companyName: "Micro Fab Engineers Limited",
        series: "SME",
        issueStartDate: "26-Nov-2026",
        issueEndDate: "28-Nov-2026",
        issuePrice: "95",
        lotSize: "1200",
        status: "Active"
      }
    ]);

    const ipos = parseNseIpoJson(fixture, "nse_current", REF_MS);
    expect(ipos).toHaveLength(2);
    expect(ipos[0]).toMatchObject({
      companyName: "Acme Solar Holdings Limited",
      boardType: "MAINBOARD",
      isAuthoritative: true,
      openDate: "25 Nov 2026",
      closeDate: "27 Nov 2026",
      priceLow: 275,
      priceHigh: 289,
      lotSize: 51
    });
    expect(ipos[1].boardType).toBe("SME");

    const subs = parseNseSubscriptionJson(fixture, "nse_subscription", REF_MS);
    expect(subs).toHaveLength(1);
    expect(subs[0]).toMatchObject({
      companyName: "Acme Solar Holdings Limited",
      isAuthoritative: true,
      qibSubX: 13.02,
      bNiiSubX: 15.4,
      retailSubX: 4.35,
      totalSubX: 9.15
    });
  });
});

describe("HTML table parsers (Chittorgarh / IPOWatch)", () => {
  it("parses HTML GMP and catalog tables including date ranges and registrar links", () => {
    const html = `
      <html><body>
        <table>
          <thead>
            <tr>
              <th>Company Name</th>
              <th>IPO Dates</th>
              <th>Price Band</th>
              <th>Lot Size</th>
              <th>IPO GMP</th>
              <th>Issue Size</th>
              <th>Registrar</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td><a href="/ipo/acme">Acme Solar Holdings Ltd</a></td>
              <td>25-27 Nov 2026</td>
              <td>₹275 - ₹289</td>
              <td>51</td>
              <td>₹48 (16.6%)</td>
              <td>₹2,900 Cr</td>
              <td><a href="https://ris.kfintech.com/">KFin Technologies</a></td>
            </tr>
            <tr>
              <td>Total</td>
              <td>--</td>
              <td>--</td>
              <td>--</td>
              <td>--</td>
              <td>--</td>
              <td>--</td>
            </tr>
          </tbody>
        </table>
      </body></html>
    `;

    const rows = parseHtmlGmpTables(html, "ipowatch_gmp", "MAINBOARD", REF_MS);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      companyName: "Acme Solar Holdings Ltd",
      boardType: "MAINBOARD",
      openDate: "25 Nov 2026",
      closeDate: "27 Nov 2026",
      priceLow: 275,
      priceHigh: 289,
      lotSize: 51,
      gmpRupees: 48,
      issueSizeCr: 2900,
      registrarName: "KFin Technologies",
      registrarUrl: "https://ris.kfintech.com/"
    });
  });

  it("parses HTML subscription tables with QIB, B-HNI, S-HNI, Retail, Employee, Total", () => {
    const html = `
      <table>
        <thead>
          <tr>
            <th>Company Name</th>
            <th>QIB</th>
            <th>bNII (&gt;10L)</th>
            <th>sNII (&lt;10L)</th>
            <th>Retail</th>
            <th>Employee</th>
            <th>Total</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>Acme Solar Holdings Ltd IPO</td>
            <td>14.20x</td>
            <td>21.50x</td>
            <td>11.10x</td>
            <td>5.05x</td>
            <td>1.80x</td>
            <td>10.44x</td>
          </tr>
        </tbody>
      </table>
    `;

    const subs = parseHtmlSubscriptionTables(
      html,
      "chittorgarh_sub_mainboard",
      "MAINBOARD",
      REF_MS
    );
    expect(subs).toHaveLength(1);
    expect(subs[0]).toMatchObject({
      companyName: "Acme Solar Holdings Ltd",
      boardType: "MAINBOARD",
      qibSubX: 14.2,
      bNiiSubX: 21.5,
      sNiiSubX: 11.1,
      retailSubX: 5.05,
      employeeSubX: 1.8,
      totalSubX: 10.44
    });
  });

  it("trims extracted HTML table text to the configured char limit for Gemini fallback", () => {
    const bigRows = Array.from(
      { length: 400 },
      (_, i) => `<tr><td>Company ${i} Limited</td><td>₹100</td><td>25 Nov 2026</td></tr>`
    ).join("");
    const html = `<html><body><script>secret()</script><table><tr><th>Name</th><th>Price</th><th>Date</th></tr>${bigRows}</table></body></html>`;

    const text = extractTrimmedTablesText(html, 500);
    expect(text.length).toBeLessThanOrEqual(500);
    expect(text).not.toContain("secret()");
    expect(text).toContain("Company 0 Limited");
  });
});
