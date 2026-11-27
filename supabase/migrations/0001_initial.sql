-- FinTrack cloud schema v1 (Phase 6). Run once in the Supabase SQL Editor.
-- Design: docs/PHASE_6_TECHNICAL_DESIGN.md §4.
--
-- * Columns mirror the Room schema (snake_case). Money is bigint rupiah; timestamps are epoch
--   milliseconds UTC (bigint), exactly as on the device. syncStatus is device-only.
-- * Primary keys are (user_id, id): default categories have the same UUID for every user.
-- * Foreign keys include user_id, so a row can never reference another user's row.
-- * Row Level Security: a signed-in user can select, insert and update only their own rows.
--   There is no DELETE policy or grant: records are soft-deleted with deleted_at.
-- * A trigger stamps server_updated_at (the pull cursor) and ignores updates whose updated_at
--   is older than the stored row (last-writer-wins on the server).

begin;

-- Shared trigger -------------------------------------------------------------------------------

create function public.fintrack_sync_guard() returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if tg_op = 'UPDATE' and new.updated_at < old.updated_at then
        return null; -- an older version must not overwrite a newer one
    end if;
    new.server_updated_at := clock_timestamp();
    return new;
end;
$$;

-- Tables ---------------------------------------------------------------------------------------

create table public.wallets (
    user_id           uuid        not null default auth.uid() references auth.users (id),
    id                uuid        not null,
    name              text        not null check (length(btrim(name)) > 0),
    type              text        not null check (type in ('CASH', 'BANK', 'E_WALLET', 'CREDIT_CARD', 'INVESTMENT', 'OTHER')),
    initial_balance   bigint      not null,
    currency          text        not null check (currency = 'IDR'),
    is_active         boolean     not null,
    created_at        bigint      not null,
    updated_at        bigint      not null,
    deleted_at        bigint,
    server_updated_at timestamptz not null default clock_timestamp(),
    primary key (user_id, id)
);

create table public.categories (
    user_id           uuid        not null default auth.uid() references auth.users (id),
    id                uuid        not null,
    name              text        not null check (length(btrim(name)) > 0),
    type              text        not null check (type in ('INCOME', 'EXPENSE')),
    parent_id         uuid,
    icon              text,
    color             text,
    is_default        boolean     not null,
    is_archived       boolean     not null,
    created_at        bigint      not null,
    updated_at        bigint      not null,
    deleted_at        bigint,
    server_updated_at timestamptz not null default clock_timestamp(),
    primary key (user_id, id),
    foreign key (user_id, parent_id) references public.categories (user_id, id),
    check (parent_id is distinct from id)
);

create table public.transactions (
    user_id               uuid        not null default auth.uid() references auth.users (id),
    id                    uuid        not null,
    wallet_id             uuid        not null,
    destination_wallet_id uuid,
    category_id           uuid,
    type                  text        not null check (type in ('INCOME', 'EXPENSE', 'TRANSFER')),
    amount                bigint      not null check (amount > 0),
    transaction_date      bigint      not null,
    description           text,
    merchant              text,
    source                text        not null check (source in ('MANUAL', 'CSV_IMPORT')),
    import_batch_id       uuid,
    external_id           text,
    created_at            bigint      not null,
    updated_at            bigint      not null,
    deleted_at            bigint,
    server_updated_at     timestamptz not null default clock_timestamp(),
    primary key (user_id, id),
    foreign key (user_id, wallet_id) references public.wallets (user_id, id),
    foreign key (user_id, destination_wallet_id) references public.wallets (user_id, id),
    foreign key (user_id, category_id) references public.categories (user_id, id),
    -- Transfers move money between two different wallets and are never income or expense.
    check (
        (type = 'TRANSFER' and destination_wallet_id is not null and destination_wallet_id <> wallet_id and category_id is null)
        or (type in ('INCOME', 'EXPENSE') and destination_wallet_id is null and category_id is not null)
    )
);

create table public.budgets (
    user_id           uuid        not null default auth.uid() references auth.users (id),
    id                uuid        not null,
    category_id       uuid, -- null = overall budget (all expenses)
    amount            bigint      not null check (amount > 0),
    created_at        bigint      not null,
    updated_at        bigint      not null,
    deleted_at        bigint,
    server_updated_at timestamptz not null default clock_timestamp(),
    primary key (user_id, id),
    foreign key (user_id, category_id) references public.categories (user_id, id)
);

create table public.financial_goals (
    user_id           uuid        not null default auth.uid() references auth.users (id),
    id                uuid        not null,
    name              text        not null check (length(btrim(name)) > 0),
    target_amount     bigint      not null check (target_amount > 0),
    current_amount    bigint      not null check (current_amount >= 0),
    target_date       bigint,
    created_at        bigint      not null,
    updated_at        bigint      not null,
    deleted_at        bigint,
    server_updated_at timestamptz not null default clock_timestamp(),
    primary key (user_id, id)
);

-- Triggers, pull indexes, access ---------------------------------------------------------------

do $$
declare
    t text;
begin
    foreach t in array array['wallets', 'categories', 'transactions', 'budgets', 'financial_goals'] loop
        execute format('create trigger sync_guard before insert or update on public.%I
                        for each row execute function public.fintrack_sync_guard()', t);
        execute format('create index %I on public.%I (user_id, server_updated_at)', t || '_pull_idx', t);

        execute format('alter table public.%I enable row level security', t);
        execute format('alter table public.%I force row level security', t);
        execute format('create policy own_select on public.%I for select to authenticated
                        using (user_id = (select auth.uid()))', t);
        execute format('create policy own_insert on public.%I for insert to authenticated
                        with check (user_id = (select auth.uid()))', t);
        execute format('create policy own_update on public.%I for update to authenticated
                        using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))', t);

        execute format('revoke all on public.%I from public, anon, authenticated', t);
        execute format('grant select, insert, update on public.%I to authenticated', t);
    end loop;
end;
$$;

revoke all on function public.fintrack_sync_guard() from public, anon, authenticated;

commit;
