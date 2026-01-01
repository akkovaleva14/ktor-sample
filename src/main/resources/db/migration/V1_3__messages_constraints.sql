-- db/migration/V1_3__messages_constraints.sql

do $$
begin
  if not exists (
    select 1
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    join pg_namespace n on n.oid = t.relnamespace
    where c.conname = 'messages_session_seq_uniq'
      and n.nspname = 'public'
      and t.relname = 'messages'
  ) then
    alter table public.messages
      add constraint messages_session_seq_uniq
      unique (session_id, seq);
  end if;
end $$;

do $$
begin
  if not exists (
    select 1
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    join pg_namespace n on n.oid = t.relnamespace
    where c.conname = 'messages_role_chk'
      and n.nspname = 'public'
      and t.relname = 'messages'
  ) then
    alter table public.messages
      add constraint messages_role_chk
      check (role in ('student', 'tutor'));
  end if;
end $$;
