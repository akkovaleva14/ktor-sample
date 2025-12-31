-- db/migration/V1_2__sessions_next_seq.sql

-- Add next_seq to avoid computing max(seq) on every insert
alter table public.sessions
    add column if not exists next_seq int not null default 1;

-- Backfill for existing sessions:
-- next_seq = max(seq)+1, or 1 if no messages
update public.sessions s
set next_seq = coalesce((
    select max(m.seq) + 1
    from public.messages m
    where m.session_id = s.id
), 1);

-- Safety: keep it >= 1
alter table public.sessions
    add constraint if not exists sessions_next_seq_positive_chk
    check (next_seq >= 1);
