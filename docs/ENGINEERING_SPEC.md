# FinTrack — Engineering Specification

Status: approved foundation (Phase 1). This document records the approved product
definition, architecture, database conventions, business rules and development
standards. `CLAUDE.md` holds the short-form repository rules; this document is the
detailed reference. If they ever disagree, raise it instead of silently choosing one.

---

## 1. Product

- Native Android personal finance tracker and analytics app.
- Kotlin, Jetpack Compose, Material 3.
- User-facing text: Bahasa Indonesia. Currency: IDR (format `Rp1.500.000`).
- Default timezone: `Asia/Jakarta`.
- Philosophy: data accuracy over visual complexity; explicit business logic; local-first;
  analytics only from real transactions; no fake data; every financial calculation testable.

## 2. Technical stack

| Area | Choice | Introduced in |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 | Phase 1 |
| Local storage | Room (source of truth) | Phase 1 |
| Concurrency | Kotlin Coroutines, Flow | Phase 1 |
| DI | Hilt | Phase 1 |
| Architecture | MVVM, Repository Pattern | Phase 1 (repositories), Phase 2 (ViewModels) |
| Navigation | Navigation Compose | Phase 2 |
| Preferences | DataStore (no `app_settings` table) | When the first preference is needed |
| Charts | Vico or another maintained Compose chart library | Phase 3 |
| CSV import | Storage Access Framework + maintained CSV parser | Phase 5 |
| Cloud sync | Supabase (Auth, PostgreSQL, RLS), WorkManager | Phase 6 |

Dependencies are added only in the phase that uses them. All versions live in
`gradle/libs.versions.toml`. Do not upgrade dependencies without a clear reason.

### Verified toolchain (Phase 1)

| Component | Version |
|---|---|
| Gradle (wrapper) | 9.9.2 |
| Android Gradle Plugin | 9.5.2 (built-in Kotlin support; no `kotlin-android` plugin) |
| Kotlin (Gradle plugin, Compose compiler plugin) | 2.4.21 |
| KSP | 2.3.14 |
| Room | 2.8.5 |
| Hilt | 2.60.1 |
| Compose BOM | 2026.11.01 |
| compileSdk | 37, minor API level 2 (required by Compose 1.13 / core 1.19) |
| targetSdk | 36 |
| minSdk | 29 |
| Java/Kotlin bytecode target | 17 |
| Build JDK | 21 (verified) |

## 3. Architecture

Single `:app` module, layered by package:

```
com.fintrack.app
├── core/            common utilities, DI for cross-cutting services (Clock), UI theme
├── data/
│   ├── local/       entity/, dao/, database/
│   ├── remote/      (Phase 6)
│   ├── repository/  the only write path for application data
│   └── sync/        (Phase 6)
├── domain/
│   ├── model/       plain Kotlin models and enums, WriteResult
│   └── validation/  pure business rule validators
├── analytics/       (Phase 3–4) metrics, trends, anomaly, insights
└── presentation/    (Phase 2+) one package per feature
```

Rules:

- UI never accesses DAOs. ViewModels contain no SQL.
- Business and financial calculations never live in Composables.
- Repositories expose domain models (`Wallet`, `Category`, `Transaction`), not Room entities.
- Validators are pure Kotlin objects, tested without Android.
- Flow for observation; suspend functions for one-time operations.
- Expected failures (validation, not found) are returned as `WriteResult`, not thrown.
- Repositories are concrete classes. Interfaces are added only when a second
  implementation or a test seam is actually needed.
- Immutable UI state exposed through `StateFlow` (from Phase 2).

## 4. Data storage strategy

- Offline-first. Room is the source of truth; the UI reads only from Room.
- All writes go to Room first. Sync (Phase 6) is asynchronous and must never block or
  discard local writes.
- Every local write sets `syncStatus = PENDING`.
- `sync_outbox` exists in schema v1 but is not written before Phase 6. When Phase 6
  starts, the outbox is backfilled from rows with `syncStatus = PENDING`.
- Synchronized entities are soft-deleted (`deletedAt`), never physically deleted.
- Conflict resolution must be deterministic (designed in Phase 6).

## 5. Database conventions

- Identifiers: UUID strings (`TEXT` primary key).
- Money: `Long` integer rupiah. Never `Float`/`Double`.
- Timestamps: UTC epoch milliseconds (`Long`). Domain models use `java.time.Instant`.
- `createdAt`/`updatedAt`/`deletedAt` come from an injected UTC `java.time.Clock`.
- Enums are stored by name as `TEXT`. Renaming an enum constant requires a migration.
- Foreign keys use `RESTRICT`; Room enables `PRAGMA foreign_keys = ON` (tested).
- Reporting boundaries (day, week, month) are computed in Kotlin with
  `ZoneId.of("Asia/Jakarta")` and passed to queries as half-open UTC ranges
  `[start, end)`. Never use SQLite `localtime`, which depends on the device timezone.

### Schema v1 tables

| Table | Purpose |
|---|---|
| `wallets` | id, name, type, initialBalance, currency (`IDR`), isActive, createdAt, updatedAt, deletedAt, syncStatus |
| `categories` | id, name, type (INCOME/EXPENSE), parentId → categories, icon, color, isDefault, isArchived, createdAt, updatedAt, deletedAt, syncStatus |
| `transactions` | id, walletId → wallets, destinationWalletId → wallets, categoryId → categories, type, amount, transactionDate, description, merchant, source, importBatchId, externalId, createdAt, updatedAt, deletedAt, syncStatus |
| `sync_outbox` | id, entityType, entityId, operation, createdAt, attemptCount, lastAttemptAt, lastError |

Deferred tables (each introduced by an explicit migration in its phase):
`budgets` and `financial_goals` (Phase 5), `recurring_transactions` (not yet scheduled).
`app_settings` is replaced by DataStore.

### Migration policy

1. Never use destructive migration (`fallbackToDestructiveMigration*` is forbidden).
2. Every schema change: bump the version, add an explicit `Migration` to
   `FinTrackDatabase.ALL_MIGRATIONS`, commit the exported schema JSON in `app/schemas/`.
3. Every migration gets a test in `MigrationTest` that creates the previous version,
   inserts financial records, migrates, validates the schema, and asserts the records
   are preserved.
4. Never delete user data to fix a migration problem.

## 6. Transaction business rules

Types: `INCOME`, `EXPENSE`, `TRANSFER`. Amount is always positive; the direction comes
from the type.

| Rule | INCOME | EXPENSE | TRANSFER |
|---|---|---|---|
| Source wallet (`walletId`) | required | required | required |
| Destination wallet | forbidden | forbidden | required, different from source |
| Category | required, type INCOME | required, type EXPENSE | forbidden |
| Amount | > 0 | > 0 | > 0 |
| Counts as income / expense | income | expense | never |

Reference rules (enforced by `TransactionValidator` against current database state, inside
the same Room transaction as the write):

- Wallets and categories must exist and not be soft-deleted.
- Archived wallets (`isActive = false`) and archived categories (`isArchived = true`)
  cannot be chosen for new transactions or newly assigned during an edit.
- An edit may keep the archived wallet/category the transaction already references, so
  historical transactions stay valid and editable.

A transfer is stored as exactly one row. No mirrored rows are ever created.

### Wallet balance formula

```
balance = initialBalance
        + Σ amount of INCOME   where walletId = wallet
        − Σ amount of EXPENSE  where walletId = wallet
        − Σ amount of TRANSFER where walletId = wallet             (outgoing)
        + Σ amount of TRANSFER where destinationWalletId = wallet  (incoming)
```

Only transactions with `deletedAt IS NULL` are included. Balances are never stored; the
single implementation is `WalletDao.observeBalances()`. Editing or deleting a transaction
therefore updates balances without any extra bookkeeping. `initialBalance` may be
negative (for example, credit card debt).

### Category rules

- At most two levels: parent → child. A child has the same type as its parent.
- A category cannot be its own parent; a category with children cannot become a child.
- The category type is fixed after creation.
- Categories are archived, never physically deleted. The foreign key prevents
  physically deleting a category that is referenced by transactions or children.

## 7. Analytics rules (Phase 3+)

- Every metric has a documented formula and uses actual database records.
- Transfers are excluded from income and expense metrics.
- Net cash flow = income − expenses.
- Saving rate = (income − expenses) / income × 100; null/unavailable when income is 0.
- Expense ratio = expenses / income × 100; null/unavailable when income is 0.
- Period growth = (current − previous) / previous × 100; zero and negative baselines are
  handled explicitly.
- Respect the selected date range and `Asia/Jakarta` boundaries; never compare incomplete
  periods without showing the date range.
- Anomaly detection (z-score, median, MAD) requires a minimum sample size and is a
  spending insight, never a fraud/suspicious-activity label.

## 8. Security and privacy

- Data stays local by default.
- `android:allowBackup="false"`, and `data_extraction_rules.xml` excludes all domains from
  cloud backup and device transfer. An explicit encrypted backup/restore feature is planned.
- Never log transaction amounts or other sensitive financial details.
- No hardcoded secrets; `local.properties` and keystores are git-ignored.
- Supabase access (Phase 6) requires authentication and Row Level Security.

## 9. Testing standards

- Pure business rules: JVM unit tests (`TransactionValidatorTest`, `CategoryValidatorTest`).
- Room, DAO and repository behavior: Robolectric unit tests against a real in-memory Room
  database (`app/src/test/.../data`). The build environment has no emulator, so
  instrumented tests on a device are not part of the verified suite yet.
- Every balance-affecting operation has a test, including edits and deletes of each
  transaction type and protection against duplicate effects.
- A task is not complete until its tests pass.

## 10. Engineering rules

No fake production functionality, no TODO/FIXME in completed features, no hardcoded
metrics, no swallowed exceptions, no unexplained warning suppressions, no deprecated APIs
when maintained alternatives exist, no duplicated business logic, no giant files, no
unnecessary abstractions or dependencies, no unverified build claims, no skipped tests,
no unrelated rewrites, no silent schema changes, no visual-only controls, no placeholder
network data presented as real, no committed secrets, no speculative features, no
incomplete features marked complete.

## 11. Development workflow

Phases: 0 inspection → 1 foundation → 2 core transactions → 3 dashboard → 4 analytics →
5 import and planning → 6 cloud sync → 7 quality and release.

Per phase: state the objective, inspect relevant code, implement a small coherent change
set, run the Gradle build and tests, fix failures, review the diff, report files
created/modified, decisions, build and test results, known limitations, and the next
recommended task. Wait for approval before starting the next phase.
