-- assignments: задания, которые создаёт teacher
create table if not exists assignments (
    id uuid primary key,
    join_key text not null unique,
    topic text not null,
    vocab jsonb not null,
    level text null,
    created_at timestamptz not null default now()
);

create index if not exists idx_assignments_join_key on assignments(join_key);

-- sessions: конкретная сессия ученика, открытая по join_key
create table if not exists sessions (
    id uuid primary key,
    assignment_id uuid not null references assignments(id) on delete cascade,
    join_key text not null,
    topic text not null,
    vocab jsonb not null,
    level text null,
    created_at timestamptz not null default now()
);

create index if not exists idx_sessions_assignment_id on sessions(assignment_id);

-- messages: история диалога по сессии
create table if not exists messages (
    id bigserial primary key,
    session_id uuid not null references sessions(id) on delete cascade,
    seq int not null,
    role text not null,
    content text not null,
    created_at timestamptz not null default now(),
    unique(session_id, seq)
);

create index if not exists idx_messages_session_id on messages(session_id);

-- idempotency: кэш ответов для POST /messages (чтобы не плодить дублей на ретраях)
create table if not exists idempotency (
    session_id uuid not null references sessions(id) on delete cascade,
    idem_key text not null,
    response jsonb not null,
    created_at timestamptz not null default now(),
    primary key (session_id, idem_key)
);

create index if not exists idx_idempotency_created_at on idempotency(created_at);
