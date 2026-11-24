FinTrack — Repository Guidelines

Project Rules

This repository contains FinTrack, a native Android personal finance application.

All implementation must follow the product and engineering specifications established in this repository.

Mandatory Rules

- Use Kotlin and Jetpack Compose.
- Use Room as the local source of truth.
- Follow offline-first architecture.
- Use MVVM and Repository Pattern.
- Keep financial calculations outside UI components.
- Use Long for IDR monetary values.
- Use UUID for entity identifiers.
- Use UTC timestamps internally.
- Display dates using Asia/Jakarta.
- Use Bahasa Indonesia for user-facing text.
- Never use fabricated financial data in production.
- Never bypass transaction validation.
- Never count transfers as income or expenses.
- Never use destructive database migrations.

Code Quality

- Prefer simple, readable implementations.
- Avoid unnecessary abstractions.
- Avoid duplicated business logic.
- Avoid giant files.
- Avoid unnecessary dependencies.
- Handle exceptions explicitly.
- Use structured concurrency.
- Keep UI state immutable.
- Test financial calculations independently.

Database Safety

- Never delete financial records without explicit user intent.
- Use soft deletion for synchronized records.
- Preserve historical transaction data.
- Implement explicit Room migrations.
- Test foreign key constraints.
- Keep wallet balances consistent.

Analytics Safety

- Every metric must have a documented formula.
- All analytics must use actual database records.
- Exclude transfers from income and expense calculations.
- Handle empty datasets and zero denominators.
- Respect timezone boundaries.
- Do not silently mix incomplete reporting periods.

Development Process

Before modifying files:

1. Inspect the relevant existing implementation.
2. Understand dependencies.
3. Identify affected modules.
4. Propose the smallest appropriate change.

After modifying files:

1. Run the relevant Gradle build.
2. Run relevant tests.
3. Fix all introduced failures.
4. Review the final diff.
5. Report what was actually verified.

Do not claim a feature is complete if it has not been implemented and tested.

Scope Control

Only implement the currently approved development phase.

Do not independently expand the product scope.

Wait for user approval before moving to another phase.

Git

- Never overwrite uncommitted user changes.
- Never force push.
- Never delete branches without permission.
- Do not commit secrets.
- Keep commits focused and descriptive.
