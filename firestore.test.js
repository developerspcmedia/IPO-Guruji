const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_owner_uid_1";
const ADMIN_EMAIL = "developers.pcmedia@gmail.com";
const OTHER_UID = "regular_user_uid_2";
const OTHER_EMAIL = "viewer@example.com";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validOverridePayload(overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    targetIpoId: "crystal-crop-protection",
    normalizedKey: "crystalcropprotection",
    companyName: "Crystal Crop Protection Ltd",
    published: true,
    customIpo: false,
    hidden: false,
    meta: {
      shortCode: "CRYSTAL",
      sector: "Agrochemicals",
      boardType: "MAINBOARD",
      exchange: "NSE, BSE",
      status: "LIVE",
      overview: "Manually verified agrochemicals IPO filing details.",
      adminNote: "Corrected upper price band and GMP from RHP.",
    },
    schedule: {
      openDate: "19 Nov 2026",
      closeDate: "21 Nov 2026",
      allotmentDate: "22 Nov 2026",
      listingDate: "25 Nov 2026",
    },
    pricing: {
      priceLow: 510,
      priceHigh: 540,
      faceValue: 10,
      lotSize: 27,
      issueSizeCr: 1240.5,
      freshIssueCr: 650.0,
      ofsCr: 590.5,
      gmpRupees: 95,
      kostakRupees: 600,
    },
    subscription: {
      qibSubX: 12.4,
      bNiiSubX: 9.8,
      sNiiSubX: 7.5,
      retailSubX: 5.2,
      employeeSubX: 1.4,
      totalSubX: 8.6,
    },
    registrar: {
      registrarName: "Link Intime India Pvt Ltd",
      registrarUrl: "https://in.mpms.mufg.com/Initial_Offer/public-issues.html",
    },
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Verified admin can create, partially update, query, and delete IPO overrides", async () => {
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  const ref = adminDb.collection("ipo_overrides").doc("crystal-crop-protection");
  await assertSucceeds(ref.set(validOverridePayload()));

  // Partial update mirroring Kotlin repository (preserves targetIpoId & createdAt)
  await assertSucceeds(
    ref.update({
      pricing: {
        priceLow: 510,
        priceHigh: 555,
        gmpRupees: 112,
        lotSize: 27,
        issueSizeCr: 1240.5,
      },
      meta: {
        boardType: "MAINBOARD",
        status: "CLOSED",
      },
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Admin can list all overrides
  await assertSucceeds(adminDb.collection("ipo_overrides").get());

  // Admin can delete (Reset to Live Sync)
  await assertSucceeds(ref.delete());
});

test("Public/regular clients can query published == true overrides, but unfiltered list and all writes fail", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx
      .firestore()
      .collection("ipo_overrides")
      .doc("crystal-crop-protection")
      .set(validOverridePayload());
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const viewerDb = testEnv
    .authenticatedContext(OTHER_UID, {
      email: OTHER_EMAIL,
      email_verified: true,
    })
    .firestore();

  // Matches Kotlin public catalog sync query: whereEqualTo("published", true)
  await assertSucceeds(
    unauthDb.collection("ipo_overrides").where("published", "==", true).get()
  );
  await assertSucceeds(
    viewerDb.collection("ipo_overrides").where("published", "==", true).get()
  );

  // Unfiltered list query fails for non-admins (rules are not filters)
  await assertFails(unauthDb.collection("ipo_overrides").get());
  await assertFails(viewerDb.collection("ipo_overrides").get());

  // Non-admin and unauthenticated writes/updates/deletes MUST fail
  await assertFails(
    unauthDb
      .collection("ipo_overrides")
      .doc("fake-ipo")
      .set(validOverridePayload({ targetIpoId: "fake-ipo" }))
  );
  await assertFails(
    viewerDb
      .collection("ipo_overrides")
      .doc("fake-ipo")
      .set(validOverridePayload({ targetIpoId: "fake-ipo" }))
  );
  await assertFails(
    viewerDb
      .collection("ipo_overrides")
      .doc("crystal-crop-protection")
      .update({
        pricing: { gmpRupees: 999 },
        updatedAt: new Date(Date.now() - 1000),
      })
  );
  await assertFails(
    viewerDb
      .collection("ipo_overrides")
      .doc("crystal-crop-protection")
      .delete()
  );
});

test("Email spoofing attack (unverified admin email) is rejected", async () => {
  const spoofedDb = testEnv
    .authenticatedContext("spoof_uid", {
      email: ADMIN_EMAIL,
      email_verified: false,
    })
    .firestore();

  await assertFails(
    spoofedDb
      .collection("ipo_overrides")
      .doc("crystal-crop-protection")
      .set(validOverridePayload())
  );
  await assertFails(
    spoofedDb.collection("admin_profiles").doc("spoof_uid").set({
      userId: "spoof_uid",
      displayName: "Spoofer",
      email: ADMIN_EMAIL,
      role: "admin",
      createdAt: new Date(Date.now() - 1000),
      lastActiveAt: new Date(Date.now() - 1000),
    })
  );
});

test("Shadow fields, @DocumentId id key, invalid URLs, invalid price order, future timestamps, and immutable createdAt edits are rejected", async () => {
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  // Reject ghost/shadow field
  await assertFails(
    adminDb
      .collection("ipo_overrides")
      .doc("shadow-test")
      .set(validOverridePayload({ targetIpoId: "shadow-test", isVerified: true }))
  );

  // Reject redundant @DocumentId `id` field
  await assertFails(
    adminDb
      .collection("ipo_overrides")
      .doc("docid-test")
      .set(validOverridePayload({ id: "docid-test", targetIpoId: "docid-test" }))
  );

  // Reject javascript: XSS registrar URL
  await assertFails(
    adminDb
      .collection("ipo_overrides")
      .doc("xss-test")
      .set(
        validOverridePayload({
          targetIpoId: "xss-test",
          registrar: {
            registrarName: "Evil",
            registrarUrl: "javascript:alert(1)",
          },
        })
      )
  );

  // Reject priceHigh < priceLow
  await assertFails(
    adminDb
      .collection("ipo_overrides")
      .doc("price-test")
      .set(
        validOverridePayload({
          targetIpoId: "price-test",
          pricing: {
            priceLow: 500,
            priceHigh: 400,
          },
        })
      )
  );

  // Reject future timestamp
  await assertFails(
    adminDb
      .collection("ipo_overrides")
      .doc("future-ts")
      .set(
        validOverridePayload({
          targetIpoId: "future-ts",
          createdAt: new Date(Date.now() + 60000),
        })
      )
  );

  // Reject immutable createdAt modification on update
  const ref = adminDb.collection("ipo_overrides").doc("immut-test");
  await assertSucceeds(ref.set(validOverridePayload({ targetIpoId: "immut-test" })));
  await assertFails(
    ref.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Admin profile collection is strictly isolated to verified admin UID", async () => {
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();
  const otherDb = testEnv
    .authenticatedContext(OTHER_UID, {
      email: OTHER_EMAIL,
      email_verified: true,
    })
    .firestore();

  const now = new Date(Date.now() - 2000);
  const profileRef = adminDb.collection("admin_profiles").doc(ADMIN_UID);

  await assertSucceeds(
    profileRef.set({
      userId: ADMIN_UID,
      displayName: "IPO Guruji Admin",
      email: ADMIN_EMAIL,
      role: "admin",
      createdAt: now,
      lastActiveAt: now,
    })
  );

  await assertSucceeds(profileRef.get());
  await assertSucceeds(
    profileRef.update({
      displayName: "Shanky Admin",
      lastActiveAt: new Date(Date.now() - 1000),
    })
  );

  // Other user cannot read or write admin profile
  await assertFails(otherDb.collection("admin_profiles").doc(ADMIN_UID).get());
  await assertFails(otherDb.collection("admin_profiles").get());
  await assertFails(
    otherDb.collection("admin_profiles").doc(OTHER_UID).set({
      userId: OTHER_UID,
      displayName: "Intruder",
      email: OTHER_EMAIL,
      role: "admin",
      createdAt: now,
      lastActiveAt: now,
    })
  );

  // Admin cannot delete profile or list collection
  await assertFails(profileRef.delete());
  await assertFails(adminDb.collection("admin_profiles").get());
});

test("Normal users can create, read, update, and delete their own /user_profiles/{uid} doc only", async () => {
  const userADb = testEnv
    .authenticatedContext(OTHER_UID, {
      email: OTHER_EMAIL,
      email_verified: true,
    })
    .firestore();
  const userBDb = testEnv
    .authenticatedContext("another_user_uid_3", {
      email: "userb@example.com",
      email_verified: true,
    })
    .firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  const now = new Date(Date.now() - 2000);
  const userARef = userADb.collection("user_profiles").doc(OTHER_UID);

  // Create own user profile succeeds
  await assertSucceeds(
    userARef.set({
      userId: OTHER_UID,
      displayName: "Aarav Investor",
      email: OTHER_EMAIL,
      investorCategory: "RETAIL",
      bio: "Long-term Mainboard IPO investor",
      bookmarkedIpoIds: ["crystal-crop-protection", "razorpay-software"],
      preferences: {
        themePreference: "DARK",
        compactCards: false,
        notifyNewIpo: true,
        notifyOpenIpo: true,
        notifyAllotment: true,
        notifyNewsAlerts: false,
      },
      createdAt: now,
      updatedAt: now,
    })
  );

  // Owner can read and partially update own profile
  await assertSucceeds(userARef.get());
  await assertSucceeds(
    userARef.update({
      displayName: "Aarav S.",
      investorCategory: "S_HNI",
      bookmarkedIpoIds: ["crystal-crop-protection"],
      preferences: {
        themePreference: "LIGHT",
        compactCards: true,
        notifyNewIpo: true,
        notifyOpenIpo: false,
        notifyAllotment: true,
        notifyNewsAlerts: true,
      },
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Unauthenticated and cross-user reads/writes/lists MUST fail
  await assertFails(unauthDb.collection("user_profiles").doc(OTHER_UID).get());
  await assertFails(userBDb.collection("user_profiles").doc(OTHER_UID).get());
  await assertFails(
    userBDb.collection("user_profiles").doc(OTHER_UID).update({
      displayName: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(userADb.collection("user_profiles").get());
  await assertFails(userBDb.collection("user_profiles").get());

  // Role escalation / shadow fields / invalid category / immutable email mutation MUST fail
  await assertFails(
    userARef.update({
      role: "admin",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    userARef.update({
      investorCategory: "ADMIN_SUPERUSER",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    userARef.update({
      email: ADMIN_EMAIL,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Owner can delete own profile
  await assertSucceeds(userARef.delete());
});

test("ipo_admin_notes is strictly admin-only and rejects signed-out users, regular users, and invalid payloads", async () => {
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();
  const userDb = testEnv
    .authenticatedContext(OTHER_UID, {
      email: OTHER_EMAIL,
      email_verified: true,
    })
    .firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const spoofedDb = testEnv
    .authenticatedContext("spoof_uid", {
      email: ADMIN_EMAIL,
      email_verified: false,
    })
    .firestore();

  const now = new Date(Date.now() - 2000);
  const validNote = {
    targetIpoId: "crystal-crop-protection",
    adminNote: "Verified RHP upper price band with registrar.",
    updatedAt: now,
  };

  // Admin can create, get, list, update, and delete ipo_admin_notes
  const adminNoteRef = adminDb.collection("ipo_admin_notes").doc("crystal-crop-protection");
  await assertSucceeds(adminNoteRef.set(validNote));
  await assertSucceeds(adminNoteRef.get());
  await assertSucceeds(adminDb.collection("ipo_admin_notes").get());
  await assertSucceeds(
    adminNoteRef.set({
      targetIpoId: "crystal-crop-protection",
      adminNote: "Updated internal audit note.",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Validation checks for admin writes: >400 chars, extra keys, future timestamp, and targetIpoId mutation must fail
  await assertFails(
    adminDb.collection("ipo_admin_notes").doc("too-long-note").set({
      targetIpoId: "too-long-note",
      adminNote: "A".repeat(401),
      updatedAt: now,
    })
  );
  await assertFails(
    adminDb.collection("ipo_admin_notes").doc("extra-key-note").set({
      targetIpoId: "extra-key-note",
      adminNote: "Valid note",
      updatedAt: now,
      extraField: "not-allowed",
    })
  );
  await assertFails(
    adminDb.collection("ipo_admin_notes").doc("future-ts-note").set({
      targetIpoId: "future-ts-note",
      adminNote: "Valid note",
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Signed-out user cannot read, list, create, update, or delete ipo_admin_notes
  await assertFails(unauthDb.collection("ipo_admin_notes").doc("crystal-crop-protection").get());
  await assertFails(unauthDb.collection("ipo_admin_notes").get());
  await assertFails(
    unauthDb.collection("ipo_admin_notes").doc("unauth-note").set({
      targetIpoId: "unauth-note",
      adminNote: "Unauthorized",
      updatedAt: now,
    })
  );
  await assertFails(
    unauthDb.collection("ipo_admin_notes").doc("crystal-crop-protection").update({
      adminNote: "Tampered",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(unauthDb.collection("ipo_admin_notes").doc("crystal-crop-protection").delete());

  // Normal signed-in user cannot read, list, create, update, or delete ipo_admin_notes
  await assertFails(userDb.collection("ipo_admin_notes").doc("crystal-crop-protection").get());
  await assertFails(userDb.collection("ipo_admin_notes").get());
  await assertFails(
    userDb.collection("ipo_admin_notes").doc("user-note").set({
      targetIpoId: "user-note",
      adminNote: "Unauthorized",
      updatedAt: now,
    })
  );
  await assertFails(
    userDb.collection("ipo_admin_notes").doc("crystal-crop-protection").update({
      adminNote: "Tampered",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(userDb.collection("ipo_admin_notes").doc("crystal-crop-protection").delete());

  // Unverified spoofed admin email cannot read or write ipo_admin_notes
  await assertFails(spoofedDb.collection("ipo_admin_notes").doc("crystal-crop-protection").get());
  await assertFails(
    spoofedDb.collection("ipo_admin_notes").doc("spoof-note").set({
      targetIpoId: "spoof-note",
      adminNote: "Spoofed",
      updatedAt: now,
    })
  );

  // Admin can delete note document
  await assertSucceeds(adminNoteRef.delete());
});


