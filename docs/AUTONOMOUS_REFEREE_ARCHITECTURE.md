# Autonomous Scientific Referee Architecture

## Overview

Paper Critic operates as a bounded, evidence-grounded scientific referee system optimized for **reproducibility**, **traceability**, **calibrated uncertainty**, and **safe abstention**. It never issues strong verified verdicts unless every structural, statistical, domain-methodological, entailment, coverage, and provenance gate passes.

## 9-Stage Tracked Pipeline

Every appraisal records explicit SHA-256 input/output digests, ruleset/prompt versions, assumptions, and failure notes across 9 ordered stages (`PipelineStageId` / `AutonomousRefereeOrchestrator`):

1. **S1_INGEST — Document Ingestion & Extraction**: Extracts searchable text, page markers, low-text diagnostics, and PDF vision page digests.
2. **S2_STRUCT — Document Structure Detection (`DocumentReadinessEvaluator`)**: Classifies document readiness (`READY`, `READY_WITH_LIMITATIONS`, `INCOMPLETE`, `EXTRACTION_FAILURE`, `UNSUPPORTED_FORMAT`) and supplement availability (`PRESENT`, `ABSENT`, `UNKNOWN`). `DocumentReadinessEvaluator.assess` is the single canonical readiness gate across pre-inference checks and orchestrator adjudication; scanned image PDFs require bound page vision digests (`visionPagesBound`) and proceed strictly as `READY_WITH_LIMITATIONS` (never full `VERIFIED` verdicts). `READY_WITH_LIMITATIONS` with material limitations blocks `allowsVerifiedVerdict`.
3. **S3_CLAIMS — Claim Inventory**: Extracts canonical typed `ScientificProposition` records (PICO, estimand, effect measure, CI/bounds, p-value, time point, analysis population, adjustment status, direction, polarity, confidence, unresolved fields).
4. **S4_STATS — Statistical Reconstruction (Deterministic & Immutable)**: Executes deterministic CI geometry checks, `statcheck` p-value recomputation, GRIM/GRIMMER, 2x2 table checks, and meta-analysis checks with an explicit 10-field assumption contract (`CalculatorAssumptionContract`). Deterministic findings cannot be downgraded or overwritten by LLM passes. Quantitative findings require per-finding source quote verification, context-window qualifier checks, and evidence-backed 8-channel `DiscrepancyCrossCheckChecklist` completion before promotion to `CONFIRMED_ERROR`.
5. **S5_METHODS — Methodological Appraisal (`DomainAppraisalAdapters`)**: Applies one of 7 design-specific bias/reporting rubrics (`RANDOMIZED_TRIAL` / RoB 2, `OBSERVATIONAL_COHORT` / ROBINS-I & NOS, `CASE_CONTROL` / NOS, `DIAGNOSTIC_STUDY` / QUADAS-2, `SYSTEMATIC_REVIEW_META_ANALYSIS` / AMSTAR-2 & GRADE, `PREDICTION_MODEL` / TRIPOD+AI & PROBAST, `QUALITATIVE_STUDY` / CASP-SRQR-COREQ), or falls back to `DESIGN_UNCERTAIN` (blocking verified verdicts). Domain scanners resolve exact manuscript sections (excluding `References`), classify negation/future-tense (`PROPOSED_ONLY`) and prior-work citations (`CITED_AS_PRIOR_WORK`), and propagate exact provenance anchors.
6. **S6_VERIFY — Source Verification (`TypedPropositionEntailmentEngine`)**: Evaluates canonical clause-level entailment (`ENTAILED`, `CONTRADICTED`, `PARTIALLY_SUPPORTED`, `UNRESOLVED`, `NOT_ENTAILED`) across typed `ScientificProposition` fields without cross-clause merging. Rejects mismatches in population, intervention/exposure, comparator, outcome, estimand, effect measure, magnitude, bounds, confidence level, p-value, time point, analysis population, adjustment status, Bayesian CrI vs. Frequentist CI interpretation, direction, and polarity.
7. **S7_CONTRA — Contradiction Analysis**: Explicitly emits `InterPassDisagreement` records when independent passes or deterministic engines conflict rather than silently overwriting results.
8. **S8_ADJUD — Severity Adjudication (`RefereeDecision` + `AbstentionGate`)**: Enforces all `NEVER VERIFIED` conditions and all 10 `AbstentionTrigger` rules, producing `{ whatIsMissing, whyItMatters, blockedConclusion, nextStepsForUser }`. `AbstentionGate` is authoritative: whenever `shouldAbstainFromVerifiedVerdict` is true, verified decisions are downgraded to `PROVISIONAL_APPRAISAL`, `INSUFFICIENT_EVIDENCE`, or `OUT_OF_SCOPE`.
9. **S9_REPORT — Final Report Generation**: Computes ONE canonical verdict (`PaperReview.verdict` derived strictly from `RefereeDecision` via `mapRefereeDecisionToModeVerdict`) and renders Compose UI (`DossierDetailView`) and PDF reports (`PdfReportExporter`) strictly from the adjudicated evidence ledger, standardized coverage labels, `AnalysisManifest`, and SHA-256 `TamperEvidentAuditLog` + `auditCommitment`.

## Evidence Ledger & Firestore Schema Contract

- Single source of truth: verified findings must attach source coordinates (`documentId`, `pageNumber`, `section`, `startOffset`, `endOffset`, `exactQuote`, `extractionMethod`, `sourceChecksum`).
- Unanchored findings or discrepancies lacking evidence-backed 8-channel cross-checks (`DiscrepancyCrossCheckChecklist`) cannot be promoted to `CONFIRMED_ERROR` or `VERIFIED`.
- `firestore.rules` enforces strict field size limits across all narrative sections, rubric justifications, checksums, bracket-checked JSON metadata, enum-restricted `refereeDecision`, referee-verdict consistency, and post-create immutability of `auditCommitment` and `auditLogJson`.

## Privacy, Consent & Provenance

- **Content-Bound Consent**: Remote AI consent binds SHA-256 digests of manuscript text, replication code, PDF page bitmap bytes, provider fingerprint, owner scope, and session generation. Any edit invalidates prior consent.
- **Fail-Closed Provider Routing**: Custom BYOK routing never silently reverts to built-in Firebase AI Logic if unavailable or unconfigured.
- **Tamper-Evident Audit Chain & Commitment**: `TamperEvidentAuditLog` links each stage via SHA-256 chain hashes (`GENESIS` -> stage entries) and binds the terminal hash, `analysisId`, `manuscriptSha256`, and stage count into `auditCommitment`. Absent audit logs honestly report `AuditLogVerificationStatus.NOT_AVAILABLE` and uncommitted chains report `LOCAL_CHAIN_ONLY`.
- **Local Reference Cleanup Hook**: `LocalReferenceCleanupHook` (`supportsRemoteDeletion = false`) tracks and clears local in-memory payload references in `finally` blocks without claiming remote deletion of external LLM API payloads.

## Benchmark Limitations & Supported Scope

- **Honest External Validation Status (`ReleaseValidationState`)**: `ReleaseReadinessEvaluator` explicitly separates internal invariant checks (`INTERNAL_INVARIANTS_PASS` / `EXTERNAL_VALIDATION_PENDING` when internal gates pass without external validation) from `RELEASE_BLOCKED`. Internal benchmark evaluation (`RefereeBenchmarkHarness`) runs strictly against curated deterministic fixtures (`externallyValidated = false`) and does **not** substitute for empirical validation on unseen expert-labeled manuscripts.
- **Supported Uses**: Bounded preliminary critical appraisal, deterministic statistical cross-checking, source-anchored evidence verification, and structured abstention reporting.
- **Unsupported Uses**: Automated clinical diagnosis/treatment decisions, unverified autonomous peer-review acceptance/rejection without human domain oversight, or assessment of unscanned unreadable PDFs without OCR text.
