CREATE TABLE IF NOT EXISTS devices (
  id UUID PRIMARY KEY,
  owner_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('android', 'ios')),
  app_version TEXT,
  device_secret_hash TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'offline' CHECK (status IN ('online', 'offline', 'busy')),
  capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
  last_seen_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS devices_owner_idx ON devices(owner_id);
CREATE INDEX IF NOT EXISTS devices_status_idx ON devices(status);

CREATE TABLE IF NOT EXISTS remote_sessions (
  id UUID PRIMARY KEY,
  device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  requester_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'active', 'ended', 'expired')),
  reason TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  approved_at TIMESTAMPTZ,
  started_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS sessions_device_idx ON remote_sessions(device_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS sessions_one_live_per_device_idx ON remote_sessions(device_id) WHERE status IN ('pending', 'approved', 'active');
CREATE INDEX IF NOT EXISTS sessions_requester_idx ON remote_sessions(requester_id, created_at DESC);
CREATE INDEX IF NOT EXISTS sessions_expiry_idx ON remote_sessions(status, expires_at);

CREATE TABLE IF NOT EXISTS session_events (
  id BIGSERIAL PRIMARY KEY,
  session_id UUID NOT NULL REFERENCES remote_sessions(id) ON DELETE CASCADE,
  actor_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS session_events_session_idx ON session_events(session_id, created_at);

CREATE TABLE IF NOT EXISTS device_invites (
  id UUID PRIMARY KEY,
  device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  created_by TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS device_invites_device_idx ON device_invites(device_id, expires_at);

CREATE TABLE IF NOT EXISTS realtime_tickets (
  id UUID PRIMARY KEY,
  principal_kind TEXT NOT NULL CHECK (principal_kind IN ('user', 'device')),
  principal_id TEXT NOT NULL,
  owner_id TEXT,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS realtime_tickets_expiry_idx ON realtime_tickets(expires_at);
