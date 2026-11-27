# FinTrack — Supabase setup

Cloud sync is optional. The app works offline without any of this.

## One-time setup (in the Supabase dashboard)

1. **SQL Editor → New query**: paste the whole of `migrations/0001_initial.sql` and run it once.
   It creates the tables, rules (CHECK constraints), the last-writer-wins trigger and Row Level
   Security. Running it a second time fails on purpose (tables already exist).
2. **Authentication → Users → Add user → Create new user**: your email and a strong password,
   with *Auto Confirm User* checked.
3. **Authentication → Sign In / Providers**: turn **off** *Allow new users to sign up*, so nobody
   else can create an account in your project.
4. Never put the **secret key** (`sb_secret_…`) or the database password in the app or the repo.
   The app only uses the project URL and the **publishable key**.

## App build configuration

Add to `local.properties` (git-ignored) before building:

```
supabase.url=https://<project-ref>.supabase.co
supabase.publishableKey=sb_publishable_...
```

Without these two lines the sync screen is hidden.

## Tests

`tests/run_local.sh` runs the migration and its behavior tests (RLS isolation between users,
no deletes, no anonymous access, business-rule CHECKs, last-writer-wins) on a local PostgreSQL,
using `tests/supabase_shim.sql` as a stand-in for Supabase's `auth` schema:

```
supabase/tests/run_local.sh -h <socket dir or host> -p <port> -U postgres
```
