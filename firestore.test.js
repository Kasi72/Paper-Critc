const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_researcher_1";
const BOB_UID = "bob_reviewer_2";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function buildValidReviewPayload(userId, nowTimestamp) {
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

test("Unauthenticated user cannot read or write reviews", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("reviews").get()
  );
});

test("Authenticated owner can create, read, list, update, and delete their own review", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 1000);
  const reviewRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("reviews")
    .doc("review_001");

  await assertSucceeds(reviewRef.set(buildValidReviewPayload(ALICE_UID, now)));
  await assertSucceeds(reviewRef.get());
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("reviews")
      .where("userId", "==", ALICE_UID)
      .get()
  );

  await assertSucceeds(
    reviewRef.update({
      bookmarked: true,
      personalNotes: "Updated notes after second pass.",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(reviewRef.delete());
});

test("Cross-user isolation: Bob cannot read, list, update, or delete Alice's review", async () => {
  const now = new Date(Date.now() - 1000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("reviews")
      .doc("alice_rev_1")
      .set(buildValidReviewPayload(ALICE_UID, now));
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

test("Shadow update & value poisoning: rejects ghost fields and invalid score/verdict bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 1000);
  const reviewRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("reviews")
    .doc("review_002");

  await assertSucceeds(reviewRef.set(buildValidReviewPayload(ALICE_UID, now)));

  // Ghost field injection
  await assertFails(
    reviewRef.update({
      isAdminApproved: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Invalid credibilityScore (> 100)
  await assertFails(
    reviewRef.update({
      credibilityScore: 150,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Invalid verdict enum
  await assertFails(
    reviewRef.update({
      verdict: "INSTANT_PUBLISH",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});
