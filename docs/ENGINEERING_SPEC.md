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
| Charts | Vico 3.3.1 (`compose-m3`) | Phase 3 |
| CSV import | Storage Access Framework + Apache Commons CSV 1.14.1 | Phase 5 |
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

Schema v2 (Phase 5, `MIGRATION_1_2`): `budgets` (id, categoryId → categories RESTRICT or NULL for
the overall budget, amount, timestamps, deletedAt, syncStatus) and `financial_goals` (id, name,
targetAmount, currentAmount, targetDate as UTC millis of a Jakarta midnight or NULL, timestamps,
deletedAt, syncStatus).

Deferred tables: `recurring_transactions` (not yet scheduled).
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

### Default categories (Phase 2)

- Seeded on every app start by `CategoryRepository.seedDefaultCategories()` (idempotent).
- Each default has a stable key; its id is `UUID.nameUUIDFromBytes("fintrack.default-category.<key>")`,
  inserted with `OnConflictStrategy.IGNORE`. Defaults are never duplicated, and a default the user
  renamed or archived is never recreated or overwritten.
- Income: Gaji, Bisnis, Investasi, Bonus, Hadiah, Pemasukan lainnya. Expense: Makanan dan minuman,
  Transportasi, Belanja, Tempat tinggal, Tagihan, Kesehatan, Hiburan, Pendidikan, Perjalanan,
  Kebutuhan pribadi, Pengeluaran lainnya.

### Transaction history search and filter (Phase 2)

- `TransactionDao.observeFiltered` filters by an optional UTC range `[start, end)` and an optional
  LIKE pattern over description, merchant and category name. User input is escaped (`\`, `%`, `_`)
  so wildcards are matched literally. Periods ("Bulan ini", "Bulan lalu", custom range) are
  computed in `JakartaTime` with `Asia/Jakarta` boundaries.

## 7. Analytics rules

- Every metric has a documented formula and uses actual database records.
- Transfers are excluded from income and expense metrics (they are only counted in the
  transaction count, labelled "termasuk N transfer").
- Soft-deleted transactions are excluded everywhere.
- Respect the selected date range and `Asia/Jakarta` boundaries; never compare incomplete
  periods without showing the date range.
- Anomaly detection (z-score, median, MAD) requires a minimum sample size and is a
  spending insight, never a fraud/suspicious-activity label (Phase 4).

### Dashboard metrics (Phase 3)

| Metric | Formula | Notes |
|---|---|---|
| Total saldo dompet aktif | Σ balance of wallets with `isActive = 1` | Balances from `WalletDao.observeBalances()` (the single balance formula). Negative balances included, archived wallets excluded. Independent of the selected period. Not net worth. |
| Pemasukan / Pengeluaran | Σ amount of INCOME / EXPENSE in the reported range | `ReportDao.observePeriodTotals` |
| Arus kas bersih | income − expense | May be negative. |
| Rasio tabungan | (income − expense) / income × 100 | Unavailable when income = 0; may be negative. |
| Jumlah transaksi | COUNT of all non-deleted transactions in range | Transfers counted and shown separately. |
| Harian | Σ INCOME / EXPENSE per Jakarta day | Missing days are zero; days after today are not shown. |
| Pengeluaran per kategori | Σ EXPENSE grouped by `categoryId` | Top 5 by amount (ties by id), rest merged as "Lainnya (n kategori)". Share = slice / Σ expense × 100; no slices when Σ = 0. Archived categories keep their name; a null id is "Tanpa kategori". |
| Perubahan | absolute = current − previous; % = (current − previous) / previous × 100 | % only when previous > 0. Zero baseline → "tidak tersedia"; negative baseline (net cash flow) → not shown. |

### Reporting periods

- Periods: Bulan ini, Bulan lalu, or a custom Jakarta date range. All period-dependent
  sections share one selected period.
- Reported range = first day .. min(last day, today). Days after today are never reported,
  so future-dated transactions are excluded from period reports (but are part of wallet
  balances, which include every recorded transaction). A range entirely in the future
  reports nothing.
- The daily chart is shown for ranges up to 62 days.
- Monthly comparison:
  - Bulan ini (in progress): days 1..D of this month vs days 1..D of the previous month
    (D clamped to that month's length). The full previous month is shown separately as a
    reference, without a percentage.
  - Bulan lalu: full month vs the full month before.
  - Custom range: no monthly comparison (stated in the UI).
- "Today" comes from `TodayProvider` (Phase 4.0): always recomputed from the injected `Clock` in
  `Asia/Jakarta`, on collection start (also on returning to the foreground through
  `WhileSubscribed`), on system time/date/time-zone broadcasts, and on a best-effort midnight
  tick. Dashboard and transaction-history periods move to the new day without reselection.

### Report queries

`ReportDao` holds all dashboard SQL: period totals, daily totals, expense per category,
recent transactions and "has any transaction". Daily totals group by
`(transactionDate − rangeStart) / 86 400 000`; this is exact because Asia/Jakarta has no DST,
and `ReportRepository` verifies that every day in the range is 24 hours before querying.
Range queries use `+deletedAt IS NULL` so SQLite uses the `transactionDate` /
`(type, transactionDate)` indexes instead of the low-selectivity `deletedAt` index
(`ReportQueryPlanTest` guards this). No schema change was needed.

SQL for these queries lives in `ReportSql` constants so tests use the DAO's exact statements.
`SqliteCompatibilityTest` runs them on SQLite 3.21 (test-only `sqlite-jdbc`), because minSdk 29
devices ship SQLite 3.22 while Robolectric uses a modern SQLite: window functions, `FILTER`,
`NULLS FIRST/LAST` and upserts are not allowed. Every new DAO query must be added to that test.

### Analisis tab (Phase 4)

Full design, formulas and review decisions: `docs/PHASE_4_TECHNICAL_DESIGN.md` (addendum B is binding).

- Periods: Bulan ini, Bulan lalu, 3/6/12 bulan (including the running month), custom up to 366
  days; default 3 bulan. Reported span ends today (WIB); comparisons use `PeriodShift`
  (same elapsed days of the previous month, or the equally long span just before).
- Tabs: Wawasan (rules I1, I2, I3, I5 with fired / no finding / not-enough-data reasons;
  thresholds in `InsightRules`), Tren (daily/weekly/monthly buckets, partial buckets marked and
  excluded from averages), Kategori (flat and parent roll-up; uncategorized and unknown ids
  outside the hierarchy; labels from the live categories flow), Merchant (normalized by
  trim + whitespace + case only, coverage shown, new = absent in the previous 365 days; no
  median), Pola (weekday averages normalized by calendar occurrences; exact distribution).
- Only the selected tab queries the database. Day and weekday grouping is relative to the range
  start and requires 24-hour days (`JakartaTime.hasUniformDays`); ranges across the 1964
  Asia/Jakarta offset change are rejected with an explanation.
- Exact distribution (`ExpenseDistributionReader`): one statement returns amounts and an
  uncorrelated COUNT, inside a DEFERRED read transaction; percentiles type 7, population
  standard deviation (Welford), minimum sample size per statistic.
- Not implemented on purpose: hour-of-day analysis (stored time is entry time), merchant median,
  daily-anomaly insight, fuzzy merchant matching, approximate percentiles, covering index.

### Planning (Phase 5)

- Budgets are monthly limits, either overall (all expense) or per expense category; one active
  budget per scope. Spending formula: sum of EXPENSE amounts in the current Jakarta calendar month
  (transfers and income excluded); a parent-category budget includes its subcategories, a
  subcategory budget counts only itself. Utilization = spent / limit; remaining = limit − spent
  (may be negative). Levels: below 80%, 80% (warning), 100% (exceeded).
- Budget notifications (channel `budget_alerts`, needs POST_NOTIFICATIONS on Android 13+) fire
  when a new expense in the current month crosses 80% or 100% of a budget; editing an existing
  transaction does not alert.
- Goals: target amount, manually updated current amount, optional target date. Percent =
  current / target; months left include the current month; required per month = remaining /
  months left rounded up. Goals do not move money between wallets.

### CSV import (Phase 5)

1. Pick: Storage Access Framework (`OpenDocument`); files over 5 MB or 10 000 data rows are
   rejected, never truncated. Text is UTF-8, or Windows-1252 when not valid UTF-8; BOM removed.
   Delimiter `;` or `,` detected from the header line; RFC 4180 quoting.
2. Map: date column + format (`dd/MM/yyyy`, `dd-MM-yyyy`, `yyyy-MM-dd`, strict), amount column,
   optional description, merchant and category columns, target wallet, fallback income and
   expense categories. Amounts are Indonesian-formatted whole rupiah; negative = EXPENSE,
   positive = INCOME. Transfers cannot be imported. Category names match active categories of
   the row's type (case-insensitive); otherwise the fallback is used. Time is 12:00 WIB.
3. Review (nothing written): rows are ready, failed (with reasons: missing/invalid/future date,
   missing/invalid/fractional/zero/too large amount) or duplicates.
   Fingerprint = SHA-256 of wallet, date, type, amount, normalized description and merchant,
   with `#n` for the n-th identical row in the file; stored in `externalId`.
   Duplicate kinds: already imported (fingerprint stored for the wallet, including soft-deleted
   rows; never importable), repeated identical row in the file, possible existing (same wallet,
   Jakarta day, type and amount). The last two are skipped unless the user includes them.
4. Confirm dialog, then one Room transaction: every row is re-validated with
   `TransactionValidator` and its fingerprint re-checked; any failure writes nothing.
   Rows get `source = CSV_IMPORT`, a shared `importBatchId` and the fingerprint.

## 8. Presentation layer (Phase 2)

- One package per feature under `presentation/` (`transactions`, `wallets`, `categories`,
  `settings`), plus `common` (shared components, labels, error messages) and `navigation`.
- Each screen has a `@HiltViewModel` exposing immutable state through `StateFlow`; form input lives
  in a `MutableStateFlow` so text fields update synchronously.
- Forms never re-implement validation: they submit through the repository and map
  `WriteResult.Invalid` errors to field messages. Only amount parsing (text → `Long`) happens in
  the form (`AmountInput`).
- Option lists mirror the archive rule: archived wallets/categories are offered only when the
  edited transaction already uses them.
- Money is formatted by `formatRupiah` (`Rp1.500.000`); dates by `JakartaTime` (Indonesian locale).
- Strings live in `res/values/strings.xml` (Bahasa Indonesia). Icons are Material Symbols vector
  drawables (the Compose material-icons artifacts are no longer updated).
- Beranda is the dashboard and the start destination; Analisis is the Phase 4 analytics tab.
- Charts use Vico 3.3.1 (column chart for daily values, donut for categories), drawn
  statically without animation; chart colors come from `LocalChartColors` (light and dark).
- Database read failures surface as error states; write failures (`SQLException`) as a form
  message. Other exceptions are not caught.

## 9. Security and privacy

- Data stays local by default.
- `android:allowBackup="false"`, and `data_extraction_rules.xml` excludes all domains from
  cloud backup and device transfer. An explicit encrypted backup/restore feature is planned.
- Never log transaction amounts or other sensitive financial details.
- No hardcoded secrets; `local.properties` and keystores are git-ignored.
- Supabase access (Phase 6) requires authentication and Row Level Security.
- Phase 7 review: no `INTERNET` permission (nothing can leave the device); no logging calls in
  app code; only the launcher activity is exported; CSV files are read through the system picker
  with a one-time read grant, size-capped before decoding, and never copied.
- Budget notifications are `VISIBILITY_PRIVATE` with a generic public version, so category
  names and amounts are not shown on a secure lock screen.

### Release build (Phase 7)

- `./gradlew :app:assembleRelease` runs R8 (minify + resource shrinking) and produces
  `app-release-unsigned.apk`. No signing configuration or keystore is committed; sign with
  `apksigner` (or a Gradle signing config read from outside the repository) before distribution.
- No project keep rules are needed: Room, Hilt, Compose, Vico and Commons CSV ship consumer
  rules. The release APK has been built and inspected (Room `_Impl` and entry points kept) but
  not yet run on a device.
- `targetSdk` stays 36 until the app is tested on an API 37 device (lint `OldTargetApi`).
- Launcher icon: adaptive vector icon with a monochrome layer (themed icons).

## 10. Testing standards

- Pure business rules: JVM unit tests (`TransactionValidatorTest`, `CategoryValidatorTest`).
- Room, DAO and repository behavior: Robolectric unit tests against a real in-memory Room
  database (`app/src/test/.../data`). The build environment has no emulator, so
  instrumented tests on a device are not part of the verified suite yet.
- `MigrationTestHelper` reads `app/schemas` as assets. Robolectric only sees the tested
  variant's merged assets, so the schemas are added to the `debug` source set's assets
  (debug APK only; release builds do not contain them).
- ViewModels are tested against the real in-memory database with a test Main dispatcher.
- Compose UI flow tests (`presentation/ui`) render real screens under Robolectric at a phone size
  (`w411dp-h891dp`). Waits on non-UI conditions idle the paused main looper explicitly.
- `AccessibilityChecksTest` walks the semantics tree of key screens and requires a spoken label
  on every clickable node (with a negative control). Google's Accessibility Test Framework was
  tried and does not see Compose nodes under Robolectric, so it is not used.
- Every balance-affecting operation has a test, including edits and deletes of each
  transaction type and protection against duplicate effects.
- A task is not complete until its tests pass.

## 11. Engineering rules

No fake production functionality, no TODO/FIXME in completed features, no hardcoded
metrics, no swallowed exceptions, no unexplained warning suppressions, no deprecated APIs
when maintained alternatives exist, no duplicated business logic, no giant files, no
unnecessary abstractions or dependencies, no unverified build claims, no skipped tests,
no unrelated rewrites, no silent schema changes, no visual-only controls, no placeholder
network data presented as real, no committed secrets, no speculative features, no
incomplete features marked complete.

## 12. Development workflow

Phases: 0 inspection → 1 foundation → 2 core transactions → 3 dashboard → 4 analytics →
5 import and planning → 6 cloud sync → 7 quality and release.

Per phase: state the objective, inspect relevant code, implement a small coherent change
set, run the Gradle build and tests, fix failures, review the diff, report files
created/modified, decisions, build and test results, known limitations, and the next
recommended task. Wait for approval before starting the next phase.
