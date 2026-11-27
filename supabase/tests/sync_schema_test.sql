-- Behavior tests for migrations/0001_initial.sql on a local PostgreSQL with supabase_shim.sql.
-- Run with: tests/run_local.sh (psql stops at the first failed assertion).
\set ON_ERROR_STOP on

insert into auth.users values
    ('00000000-0000-0000-0000-00000000000a'),
    ('00000000-0000-0000-0000-00000000000b');

-- Signs in as user A or B (what PostgREST does with the JWT).
create function pg_temp.as_user(name text) returns void language plpgsql as $$
begin
    execute 'set role authenticated';
    perform set_config('request.jwt.claims', json_build_object('sub',
        case name when 'a' then '00000000-0000-0000-0000-00000000000a' else '00000000-0000-0000-0000-00000000000b' end)::text, false);
end $$;

-- Expects [stmt] to fail with SQLSTATE [code].
create function pg_temp.expect_error(stmt text, code text) returns void language plpgsql as $$
begin
    execute stmt;
    raise exception 'expected SQLSTATE % but statement succeeded: %', code, stmt;
exception when others then
    if sqlstate <> code then raise exception 'expected SQLSTATE % but got % (%): %', code, sqlstate, sqlerrm, stmt; end if;
end $$;

create function pg_temp.check(ok boolean, what text) returns void language plpgsql as $$
begin
    if not coalesce(ok, false) then raise exception 'assertion failed: %', what; end if;
end $$;

-- User A writes a wallet, a category and a transaction (user_id comes from auth.uid()).
select pg_temp.as_user('a');
insert into public.wallets (id, name, type, initial_balance, currency, is_active, created_at, updated_at)
values ('11111111-1111-1111-1111-111111111111', 'BCA', 'BANK', 1000000, 'IDR', true, 1, 1);
insert into public.categories (id, name, type, is_default, is_archived, created_at, updated_at)
values ('22222222-2222-2222-2222-222222222222', 'Makanan', 'EXPENSE', true, false, 1, 0);
insert into public.transactions (id, wallet_id, category_id, type, amount, transaction_date, source, created_at, updated_at)
values ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 'EXPENSE', 25000, 1, 'MANUAL', 1, 1);
select pg_temp.check((select count(*) = 1 from public.transactions), 'A sees own transaction');
select pg_temp.check((select user_id = '00000000-0000-0000-0000-00000000000a' from public.wallets), 'user_id defaults to auth.uid()');

-- Business-rule CHECKs.
select pg_temp.expect_error($s$insert into public.transactions (id, wallet_id, category_id, type, amount, transaction_date, source, created_at, updated_at)
    values (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'EXPENSE', 0, 1, 'MANUAL', 1, 1)$s$, '23514');
select pg_temp.expect_error($s$insert into public.transactions (id, wallet_id, destination_wallet_id, category_id, type, amount, transaction_date, source, created_at, updated_at)
    values (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', null, 'TRANSFER', 5, 1, 'MANUAL', 1, 1)$s$, '23514');
select pg_temp.expect_error($s$insert into public.transactions (id, wallet_id, category_id, type, amount, transaction_date, source, created_at, updated_at)
    values (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', null, 'INCOME', 5, 1, 'MANUAL', 1, 1)$s$, '23514');
select pg_temp.expect_error($s$insert into public.wallets (id, name, type, initial_balance, currency, is_active, created_at, updated_at)
    values (gen_random_uuid(), 'X', 'BANK', 0, 'USD', true, 1, 1)$s$, '23514');
select pg_temp.expect_error($s$insert into public.financial_goals (id, name, target_amount, current_amount, created_at, updated_at)
    values (gen_random_uuid(), ' ', 100, 0, 1, 1)$s$, '23514');
-- Missing wallet.
select pg_temp.expect_error($s$insert into public.transactions (id, wallet_id, category_id, type, amount, transaction_date, source, created_at, updated_at)
    values (gen_random_uuid(), gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'EXPENSE', 5, 1, 'MANUAL', 1, 1)$s$, '23503');

-- No hard deletes, not even of own rows.
select pg_temp.expect_error('delete from public.transactions', '42501');

-- Last-writer-wins: an older version is ignored, a newer one applied; server_updated_at moves.
create temp table stamp as select server_updated_at from public.categories;
insert into public.categories (id, name, type, is_default, is_archived, created_at, updated_at)
values ('22222222-2222-2222-2222-222222222222', 'Makan', 'EXPENSE', true, false, 1, 5)
on conflict (user_id, id) do update set name = excluded.name, updated_at = excluded.updated_at;
insert into public.categories (id, name, type, is_default, is_archived, created_at, updated_at)
values ('22222222-2222-2222-2222-222222222222', 'Seed lama', 'EXPENSE', true, false, 1, 0)
on conflict (user_id, id) do update set name = excluded.name, updated_at = excluded.updated_at;
select pg_temp.check((select name = 'Makan' and updated_at = 5 from public.categories), 'newer kept, older ignored');
select pg_temp.check((select c.server_updated_at > s.server_updated_at from public.categories c, stamp s), 'server_updated_at advanced');

-- User B: sees nothing of A, cannot write as A, cannot touch A's rows or reference them.
select pg_temp.as_user('b');
select pg_temp.check((select count(*) = 0 from public.wallets), 'B sees no rows of A');
select pg_temp.check((select count(*) = 0 from public.transactions), 'B sees no transactions of A');
select pg_temp.expect_error($s$insert into public.wallets (user_id, id, name, type, initial_balance, currency, is_active, created_at, updated_at)
    values ('00000000-0000-0000-0000-00000000000a', gen_random_uuid(), 'Palsu', 'CASH', 0, 'IDR', true, 1, 1)$s$, '42501');
update public.wallets set name = 'Diretas', updated_at = 99;
-- The same category id as A's default category is fine for B (composite key) ...
insert into public.categories (id, name, type, is_default, is_archived, created_at, updated_at)
values ('22222222-2222-2222-2222-222222222222', 'Makanan', 'EXPENSE', true, false, 1, 0);
-- ... but B cannot point a transaction at A's wallet.
select pg_temp.expect_error($s$insert into public.transactions (id, wallet_id, category_id, type, amount, transaction_date, source, created_at, updated_at)
    values (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'EXPENSE', 5, 1, 'MANUAL', 1, 1)$s$, '23503');

reset role;
select pg_temp.check((select name = 'BCA' from public.wallets where user_id = '00000000-0000-0000-0000-00000000000a'), 'B could not update A''s wallet');
select pg_temp.check((select count(*) = 2 from public.categories), 'A and B each have the default category');

-- Anonymous (publishable key without sign-in): no access at all.
set role anon;
select pg_temp.expect_error('select * from public.wallets', '42501');
select pg_temp.expect_error($s$insert into public.wallets (id, name, type, initial_balance, currency, is_active, created_at, updated_at)
    values (gen_random_uuid(), 'X', 'CASH', 0, 'IDR', true, 1, 1)$s$, '42501');
reset role;

\echo 'ALL SCHEMA TESTS PASSED'
