# List Query Validation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan.

**Goal:** Complete the remaining request-query validation in #32.
**Architecture:** Validated MVC DTOs, scalar query guard, existing ProblemDetail advice.
**Tech Stack:** Kotlin, Spring MVC, Jakarta Validation, MockMvc/JUnit 5.
**Spec:** docs/superpowers/specs/2026-10-25-list-validation-design.md

## Constraints and review focus

Preserve sorting and response schemas. Validate before facades. Cover invalid numeric
and typed input, omitted versus empty parameters, repeated values and form aliases,
inclusive and one-sided date ranges, and integer offset overflow with existing rows.

## Task 1: Query validation contract

- [x] Add ListQueryValidationControllerTest with 400/code/detail assertions for
  invalid pagination and filters across all three resources; test defaults,
  size boundaries, valid filters and far-out empty pages with seeded data.
- [x] Run the new tests and confirm failures against current implementation.
- [x] Add validation starter, PaginationRequest, ValidDateRange validator and
  scalar query guard. Bind pagination and filters with @Valid @ModelAttribute
  in list controllers. Extend ApiExceptionHandler for query validation details.
- [x] Fix offset arithmetic in the three facades and run focused tests.
- [x] Document the contract in README and OpenAPI; test generated schemas.
- [x] Run the full suite and git diff --check; review the resulting diff.

## Execution record

- Working in the existing clean feature checkout; changes remain available for review.
- Baseline sandbox run could not create Gradle's local lock socket; retry outside
  sandbox with the existing JDK 21 and cached Gradle dependencies.

- New contract tests: 35 failures reproduced before implementation (36 cases).
- Focused verification: 50 tests passed (validation and existing sorting).
- Full verification: 344 tests, 0 failures, 0 errors, 0 skipped.
- Independent static review: no findings.
- Related date-filter defect fixed: MatchServiceImpl previously ignored both boundaries.
- Raw guard moved into scoped @InitBinder hooks after indexed query tests exposed
  Spring property-binding errors before controller invocation.
