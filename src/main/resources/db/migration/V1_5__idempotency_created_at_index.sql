create index if not exists idx_idempotency_created_at
on public.idempotency(created_at);
