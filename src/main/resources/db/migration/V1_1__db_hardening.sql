-- 1) assignments.join_key already has UNIQUE index, so this is redundant
drop index if exists public.idx_assignments_join_key;

-- 2) messages has UNIQUE(session_id, seq) which covers queries by session_id
drop index if exists public.idx_messages_session_id;

-- 3) idempotency: better index for session-scoped browsing/cleanup
drop index if exists public.idx_idempotency_created_at;

create index if not exists idx_idempotency_session_created_at
on public.idempotency (session_id, created_at);

-- 4) Safety constraints

-- seq must be positive
do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'messages_seq_positive_chk'
    ) then
        alter table public.messages
            add constraint messages_seq_positive_chk
            check (seq > 0);
    end if;
end $$;

-- role must be one of known values
do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'messages_role_chk'
    ) then
        alter table public.messages
            add constraint messages_role_chk
            check (role in ('student', 'tutor', 'system'));
    end if;
end $$;

-- content length guardrail (DB-side)
do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'messages_content_len_chk'
    ) then
        alter table public.messages
            add constraint messages_content_len_chk
            check (char_length(content) <= 16000);
    end if;
end $$;
