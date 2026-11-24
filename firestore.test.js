const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_researcher_1";
const BOB_UID = "bob_reviewer_2";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function buildLegacyReviewPayload(userId, nowTimestamp) {
  return {
    userId,
    title: "Yell at It: Prompt Engineering for Automated Peer Review",
    authors: "Garg et al.",
    publicationYear: "2026",
    venueOrCitation: "Working Paper, isitcredible.com (2026)",
    isEmpirical: true,
    credibilityScore: 84,
    verdict: "MINOR_REVISION",
    abstractSummary: "Evaluates a 30+ stage adversarial LLM peer-review pipeline with verification cascades.",
    researchQuestion: "Does adversarial red-team prompting paired with a blue-team verification cascade outperform single-pass LLM peer review?",
    keyMethodology: "Pairwise blinded evaluations across 20 benchmark economics and ML manuscripts.",
    coreContributions: "1. Multi-stage Breaker/Butcher/Shredder red team. 2. Verification cascade reducing false positives.",
    redTeamCritique: "Sample size of 20 benchmark pairings may underpower subgroup heterogeneity analysis.",
    blueTeamVerification: "Verified numerical consistency across Table 1 (15 wins, 4 ties, 1 loss).",
    mathAuditReport: "All inline notation and win-rate confidence intervals verified.",
    codeAuditReport: "Pipeline stages are deterministic at temperature 0.0 for metadata extraction.",
    editorNote: "Strong methodological contribution to automated scholarly auditing; minor clarifications requested.",
    copyeditSuggestions: "Tighten Section 3.2 transition between Stage 02c Blue Team and Stage 03a Checker.",
    reviewerQuestions: "Q1: How sensitive is the cascade to temperature > 0.2 in Stage 01a?",
    formattedDossier: "IS IT CREDIBLE? - Automated Peer Review Dossier",
    bookmarked: false,
    personalNotes: "Excellent reference architecture for AI-assisted peer review.",
    createdAt: nowTimestamp,
    updatedAt: nowTimestamp,
  };
}

function buildFullV4ReviewPayload(userId, nowTimestamp, overrides = {}) {
  // Weighted composite: (6*25 + 8*20 + 7*15 + 7*15 + 5*15 + 4*10) / 10.0 = 63.5 -> 64
  return {
    ...buildLegacyReviewPayload(userId, nowTimestamp),
    title: "Need Estimates of Psychiatric Beds: A Systematic Review and Meta-Analysis",
    authors: "Mundt et al.",
    publicationYear: "2022",
    venueOrCitation: "World Psychiatry",
    credibilityScore: 64,
    verdict: "REQUIRES_CORRECTION",
    methodologyScore: 6,
    significanceScore: 8,
    clarityScore: 7,
    originalityScore: 7,
    mathRigorScore: 5,
    reproducibilityScore: 4,
    methodologyJustification: "Random-effects meta-analyses and meta-regression were executed across 65 studies; quality checklist was unvalidated.",
    significanceJustification: "High policy relevance across HIC and LMIC health systems.",
    clarityJustification: "Clear PRISMA flow and stratified synthesis tables.",
    originalityJustification: "Comprehensive global benchmark of normative psychiatric bed estimates.",
    mathRigorJustification: "Narrative Page 8 long-stay CI (9, 95% CI 1.26 to 2.35) conflicts with Figure 2 Page 9 (95% CI 3-15).",
    reproducibilityJustification: "Protocol registration and raw extraction sheet links should be archived.",
    refereeConfidence: 4,
    extractionConfidence: 5,
    reviewMode: "POST_PUBLICATION_APPRAISAL",
    coverageDisclosure: "12/12 page(s) extracted • Engine: PDFBox-Android (Full ToUnicode) • Visual Pages Pass 1: P1,P5,P8,P9 • Pass 2: P8,P9",
    validationStatus: "VERIFIED",
    structuredFindingsJson: JSON.stringify([
      {
        id: "Q-01",
        title: "Deterministic Discrepancy Requiring Source Reconciliation: 9, 95% CI 1.26 to 2.35",
        allegation: "Narrative text reports point estimate 9 outside [1.26, 2.35]; Figure 2 on Page 9 shows 9 (95% CI 3-15).",
        severity: "CRITICAL",
        status: "VERIFIED",
      },
    ]),
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

test("Unauthenticated user cannot read, list, or write reviews", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("reviews").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("reviews")
      .doc("r_unauth")
      .set(buildFullV4ReviewPayload(ALICE_UID, new Date(Date.now() - 1000)))
  );
});

test("Round-trip preservation: Firestore read-back preserves every v4 provenance, rubric, confidence, and findings field", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const reviewsCol = aliceDb.collection("users").doc(ALICE_UID).collection("reviews");

  // Empty collection list must succeed (aligns with Kotlin observeReviews / getUserReviews)
  await assertSucceeds(reviewsCol.where("userId", "==", ALICE_UID).get());

  const now = new Date(Date.now() - 1000);
  const written = buildFullV4ReviewPayload(ALICE_UID, now);
  const reviewRef = reviewsCol.doc("review_v4_roundtrip");

  await assertSucceeds(reviewRef.set(written));
  const snap = await assertSucceeds(reviewRef.get());
  assert.equal(snap.exists, true);
  const loaded = snap.data();

  // Exact round-trip field-by-field assertion across all 18 v4 fields + composite & verdict
  const fieldsToVerify = [
    "credibilityScore",
    "verdict",
    "methodologyScore",
    "significanceScore",
    "clarityScore",
    "originalityScore",
    "mathRigorScore",
    "reproducibilityScore",
    "methodologyJustification",
    "significanceJustification",
    "clarityJustification",
    "originalityJustification",
    "mathRigorJustification",
    "reproducibilityJustification",
    "refereeConfidence",
    "extractionConfidence",
    "reviewMode",
    "coverageDisclosure",
    "validationStatus",
    "structuredFindingsJson",
  ];
  for (const field of fieldsToVerify) {
    assert.equal(loaded[field], written[field], `Round-trip mismatch on field '${field}'`);
  }

  // Mode-aligned updates across CLINICAL and JOURNAL modes succeed when verdict matches reviewMode
  await assertSucceeds(
    reviewRef.update({
      reviewMode: "CLINICAL_EVIDENCE_APPRAISAL",
      verdict: "MODERATE_CERTAINTY",
      validationStatus: "ADJUDICATED_WITH_CAVEATS",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(
    reviewRef.update({
      reviewMode: "JOURNAL_REFEREE",
      verdict: "MINOR_REVISION_WITH_CAVEATS",
      validationStatus: "PROVISIONAL",
      bookmarked: true,
      personalNotes: "Verified after erratum.",
      updatedAt: new Date(Date.now() - 250),
    })
  );
  await assertSucceeds(
    reviewRef.update({
      reviewMode: "POST_PUBLICATION_APPRAISAL",
      verdict: "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN",
      validationStatus: "LEGACY_UNVALIDATED",
      updatedAt: new Date(Date.now() - 100),
    })
  );

  await assertSucceeds(reviewRef.delete());
});

test("Backward-compatible cloud migration: legacy doc can be upgraded in-place to full consistent v4 record", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const reviewRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("reviews")
    .doc("legacy_doc_001");

  // Seed legacy pre-v4 document (without rubric scores or reviewMode)
  await assertSucceeds(reviewRef.set(buildLegacyReviewPayload(ALICE_UID, created)));

  // Upgrade in-place with full consistent v4 payload while preserving original createdAt
  const upgraded = buildFullV4ReviewPayload(ALICE_UID, created, {
    updatedAt: new Date(Date.now() - 500),
  });
  await assertSucceeds(reviewRef.set(upgraded));
});

test("Cross-user isolation: Bob cannot read, list, update, or delete Alice's v4 review", async () => {
  const now = new Date(Date.now() - 1000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("reviews")
      .doc("alice_rev_1")
      .set(buildFullV4ReviewPayload(ALICE_UID, now));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceDocFromBob = bobDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("reviews")
    .doc("alice_rev_1");

  await assertFails(aliceDocFromBob.get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("reviews").get()
  );
  await assertFails(aliceDocFromBob.update({ bookmarked: true }));
  await assertFails(aliceDocFromBob.delete());
});

test("Strict schema integrity: rejects zero rubric scores, cross-mode verdicts, composite mismatch, partial rubrics, ghost keys, and createdAt tampering", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const reviewsCol = aliceDb.collection("users").doc(ALICE_UID).collection("reviews");

  // 1. Reject zero rubric score (v4 requires 1..10)
  await assertFails(
    reviewsCol.doc("bad_zero_rubric").set(
      buildFullV4ReviewPayload(ALICE_UID, created, {
        methodologyScore: 0,
        credibilityScore: 49,
      })
    )
  );

  // 2. Reject cross-mode verdicts (clinical review with ACCEPT; journal review with HIGH_CERTAINTY; post-pub with ACCEPT)
  await assertFails(
    reviewsCol.doc("bad_clinical_accept").set(
      buildFullV4ReviewPayload(ALICE_UID, created, {
        reviewMode: "CLINICAL_EVIDENCE_APPRAISAL",
        verdict: "ACCEPT",
      })
    )
  );
  await assertFails(
    reviewsCol.doc("bad_referee_certainty").set(
      buildFullV4ReviewPayload(ALICE_UID, created, {
        reviewMode: "JOURNAL_REFEREE",
        verdict: "HIGH_CERTAINTY",
      })
    )
  );
  await assertFails(
    reviewsCol.doc("bad_postpub_accept").set(
      buildFullV4ReviewPayload(ALICE_UID, created, {
        reviewMode: "POST_PUBLICATION_APPRAISAL",
        verdict: "ACCEPT",
      })
    )
  );

  // 3. Reject composite credibilityScore contradicting the 6 persisted dimensions (true weighted = 64, not 85)
  await assertFails(
    reviewsCol.doc("bad_composite_mismatch").set(
      buildFullV4ReviewPayload(ALICE_UID, created, {
        credibilityScore: 85,
      })
    )
  );

  // 4. Reject partial v4 rubric (missing reproducibilityScore while reviewMode/other scores are present)
  const partialRubric = buildFullV4ReviewPayload(ALICE_UID, created);
  delete partialRubric.reproducibilityScore;
  await assertFails(reviewsCol.doc("bad_partial_rubric").set(partialRubric));

  // 5. Reject payload containing unexpected "id" key (@DocumentId must stay out of map)
  await assertFails(
    reviewsCol.doc("review_bad_id").set({
      ...buildFullV4ReviewPayload(ALICE_UID, created),
      id: "review_bad_id",
    })
  );

  const reviewRef = reviewsCol.doc("review_v4_002");
  await assertSucceeds(reviewRef.set(buildFullV4ReviewPayload(ALICE_UID, created)));

  // 6. Reject ghost field injection, out-of-range confidence, invalid status, and createdAt tampering
  await assertFails(
    reviewRef.update({
      isAdminApproved: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    reviewRef.update({
      methodologyScore: 0,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    reviewRef.update({
      extractionConfidence: 0,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    reviewRef.update({
      reviewMode: "CLINICAL_EVIDENCE_APPRAISAL",
      verdict: "ACCEPT",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    reviewRef.update({
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 500),
    })
  );
});
