export type BoardType = "MAINBOARD" | "SME";

export type IpoStatus = "LIVE" | "PRE_APPLY" | "UPCOMING" | "CLOSED";

export type RunMode = "all" | "catalog" | "gmp" | "subscription";

export interface IpoBundleItem {
  id: string;
  companyName: string;
  normalizedKey: string;
  boardType: BoardType;
  status: IpoStatus;
  openDate: string;
  closeDate: string;
  allotmentDate: string;
  listingDate: string;
  priceLow: number;
  priceHigh: number;
  lotSize: number;
  issueSizeCr: number;
  gmpRupees: number;
  gmpUpdatedAtMs: number;
  qibSubX: number;
  bNiiSubX: number;
  sNiiSubX: number;
  retailSubX: number;
  employeeSubX: number;
  totalSubX: number;
  subUpdatedAtMs: number;
  registrarName: string;
  registrarUrl: string;
  sources: string[];
}

export interface IpoLiveBundleDoc {
  version: 1;
  updatedAt: unknown;
  lastSuccessAt: string;
  sourcesOk: string[];
  sourcesFailed: string[];
  ipos: IpoBundleItem[];
}

export interface IpoLiveMetaDoc {
  updatedAt: unknown;
  lastRunMode: RunMode;
  sourcesOk: string[];
  sourcesFailed: string[];
  lastError?: string;
  lastCatalogSyncAtMs?: number;
  lastBundleHash?: string;
  geminiCallsUsed?: number;
}

export interface RawIpoRecord {
  companyName: string;
  boardType: BoardType;
  source: string;
  /** True when the source is an exchange authority (NSE/BSE). */
  isAuthoritative?: boolean;
  /** Optional provider-reported status hint. */
  statusHint?: IpoStatus;
  openDate?: string;
  closeDate?: string;
  allotmentDate?: string;
  listingDate?: string;
  priceLow?: number;
  priceHigh?: number;
  lotSize?: number;
  issueSizeCr?: number;
  gmpRupees?: number;
  gmpUpdatedAtMs?: number;
  registrarName?: string;
  registrarUrl?: string;
}

export interface RawSubscriptionRecord {
  companyName: string;
  boardType: BoardType;
  source: string;
  isAuthoritative?: boolean;
  qibSubX?: number;
  bNiiSubX?: number;
  sNiiSubX?: number;
  retailSubX?: number;
  employeeSubX?: number;
  totalSubX?: number;
  subUpdatedAtMs: number;
}

export interface SourceFetchResult {
  sourceName: string;
  kind: "catalog" | "gmp" | "subscription";
  ok: boolean;
  ipoRecords: RawIpoRecord[];
  subscriptionRecords: RawSubscriptionRecord[];
  error?: string;
  usedGeminiFallback?: boolean;
}
