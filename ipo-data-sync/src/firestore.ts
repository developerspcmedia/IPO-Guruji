import { cert, getApps, initializeApp, type ServiceAccount } from "firebase-admin/app";
import { FieldValue, getFirestore, type Firestore } from "firebase-admin/firestore";
import type { IpoLiveBundleDoc, IpoLiveMetaDoc } from "./types.js";

/**
 * Named Firestore database ID for IPO Guruji.
 * NEVER write to "(default)".
 */
export const FIRESTORE_DATABASE_ID =
  "ai-studio-android-ipoguruj-a24800ae-d4aa-41d1-bac5-770bc39e5023";

export const BUNDLE_COLLECTION = "ipo_live_bundle";
export const BUNDLE_DOC_ID = "current";
export const META_COLLECTION = "ipo_live_meta";
export const META_DOC_ID = "sync";

export interface SyncStore {
  readBundle(): Promise<IpoLiveBundleDoc | null>;
  readMeta(): Promise<IpoLiveMetaDoc | null>;
  writeBundle(doc: Omit<IpoLiveBundleDoc, "updatedAt">): Promise<void>;
  writeMeta(doc: Omit<IpoLiveMetaDoc, "updatedAt">): Promise<void>;
}

function parseServiceAccountFromEnv(): ServiceAccount {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT?.trim();
  if (!raw) {
    throw new Error(
      "Missing required environment variable FIREBASE_SERVICE_ACCOUNT (JSON string)."
    );
  }
  try {
    const parsed = JSON.parse(raw) as ServiceAccount & {
      project_id?: string;
      private_key?: string;
      client_email?: string;
    };
    const projectId = parsed.projectId ?? parsed.project_id;
    const privateKey = parsed.privateKey ?? parsed.private_key;
    const clientEmail = parsed.clientEmail ?? parsed.client_email;
    if (!projectId || !privateKey || !clientEmail) {
      throw new Error("Service account JSON is missing project_id, client_email, or private_key.");
    }
    return parsed;
  } catch (err) {
    if (err instanceof Error && err.message.includes("missing project_id")) {
      throw err;
    }
    // Never include the raw env string in the error message
    throw new Error("FIREBASE_SERVICE_ACCOUNT is not valid JSON.");
  }
}

export class FirebaseAdminStore implements SyncStore {
  private readonly db: Firestore;

  constructor(databaseId: string = FIRESTORE_DATABASE_ID) {
    if (!databaseId || databaseId === "(default)") {
      throw new Error("Refusing to connect to the (default) Firestore database.");
    }
    const app =
      getApps().length > 0
        ? getApps()[0]
        : initializeApp({
            credential: cert(parseServiceAccountFromEnv())
          });
    this.db = getFirestore(app, databaseId);
  }

  async readBundle(): Promise<IpoLiveBundleDoc | null> {
    const snap = await this.db
      .collection(BUNDLE_COLLECTION)
      .doc(BUNDLE_DOC_ID)
      .get();
    if (!snap.exists) return null;
    const data = snap.data();
    if (!data || !Array.isArray(data.ipos)) return null;
    return data as IpoLiveBundleDoc;
  }

  async readMeta(): Promise<IpoLiveMetaDoc | null> {
    const snap = await this.db.collection(META_COLLECTION).doc(META_DOC_ID).get();
    if (!snap.exists) return null;
    return (snap.data() as IpoLiveMetaDoc) ?? null;
  }

  async writeBundle(doc: Omit<IpoLiveBundleDoc, "updatedAt">): Promise<void> {
    await this.db
      .collection(BUNDLE_COLLECTION)
      .doc(BUNDLE_DOC_ID)
      .set({
        ...doc,
        updatedAt: FieldValue.serverTimestamp()
      });
  }

  async writeMeta(doc: Omit<IpoLiveMetaDoc, "updatedAt">): Promise<void> {
    await this.db
      .collection(META_COLLECTION)
      .doc(META_DOC_ID)
      .set(
        {
          ...doc,
          updatedAt: FieldValue.serverTimestamp()
        },
        { merge: true }
      );
  }
}
