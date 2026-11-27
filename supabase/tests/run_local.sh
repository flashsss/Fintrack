#!/usr/bin/env bash
# Runs the schema tests on a throwaway database of a local PostgreSQL server.
# Usage: supabase/tests/run_local.sh [psql connection options], e.g. -h /var/tmp/pg -p 54329 -U postgres
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
db="fintrack_schema_test_$$"
psql "$@" -q -d postgres -c "create database $db"
trap 'psql "$@" -q -d postgres -c "drop database if exists $db"; psql "$@" -q -d postgres -c "drop role if exists anon" -c "drop role if exists authenticated" 2>/dev/null || true' EXIT
psql "$@" -q -v ON_ERROR_STOP=1 -d "$db" -f "$here/supabase_shim.sql"
psql "$@" -q -v ON_ERROR_STOP=1 -d "$db" -f "$here/../migrations/0001_initial.sql"
psql "$@" -q -v ON_ERROR_STOP=1 -d "$db" -f "$here/sync_schema_test.sql"
