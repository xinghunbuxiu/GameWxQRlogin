BEGIN;

INSERT INTO devices (
  id, owner_id, display_name, platform, device_secret_hash, status
) VALUES (
  '00000000-0000-4000-8000-000000000101',
  'gamepilot-db-test-owner',
  'invariant-test-device',
  'android',
  'test-secret-hash',
  'online'
);

-- An expired pending row must be moved out of the partial unique index
-- before a new request for the same device can be inserted.
INSERT INTO remote_sessions (
  id, device_id, requester_id, status, reason, expires_at
) VALUES (
  '00000000-0000-4000-8000-000000000201',
  '00000000-0000-4000-8000-000000000101',
  'expired-requester',
  'pending',
  'expired fixture',
  now() - interval '1 minute'
);

UPDATE remote_sessions
SET status = 'expired'
WHERE device_id = '00000000-0000-4000-8000-000000000101'
  AND status IN ('pending', 'approved', 'active')
  AND expires_at <= now();

INSERT INTO remote_sessions (
  id, device_id, requester_id, status, reason, expires_at
) VALUES (
  '00000000-0000-4000-8000-000000000202',
  '00000000-0000-4000-8000-000000000101',
  'first-requester',
  'pending',
  'first live request',
  now() + interval '90 seconds'
);

DO $$
DECLARE
  conflict_seen BOOLEAN := FALSE;
  affected INTEGER;
BEGIN
  BEGIN
    INSERT INTO remote_sessions (
      id, device_id, requester_id, status, reason, expires_at
    ) VALUES (
      '00000000-0000-4000-8000-000000000203',
      '00000000-0000-4000-8000-000000000101',
      'second-requester',
      'pending',
      'competing live request',
      now() + interval '90 seconds'
    );
  EXCEPTION WHEN unique_violation THEN
    conflict_seen := TRUE;
  END;

  IF NOT conflict_seen THEN
    RAISE EXCEPTION 'live-session unique index did not reject a competing request';
  END IF;

  UPDATE remote_sessions
  SET status = 'approved', approved_at = now(), expires_at = now() + interval '5 minutes'
  WHERE id = '00000000-0000-4000-8000-000000000202'
    AND status = 'pending'
    AND expires_at > now();
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 1 THEN
    RAISE EXCEPTION 'pending -> approved transition failed';
  END IF;

  UPDATE remote_sessions
  SET status = 'active', started_at = now(), expires_at = now() + interval '4 hours'
  WHERE id = '00000000-0000-4000-8000-000000000202'
    AND status = 'approved'
    AND expires_at > now();
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 1 THEN
    RAISE EXCEPTION 'approved -> active transition failed';
  END IF;

  UPDATE remote_sessions
  SET status = 'ended', ended_at = now()
  WHERE id = '00000000-0000-4000-8000-000000000202'
    AND status IN ('approved', 'active', 'pending');
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 1 THEN
    RAISE EXCEPTION 'active -> ended transition failed';
  END IF;

  -- A late session.started must not revive a terminal session.
  UPDATE remote_sessions
  SET status = 'active', started_at = now()
  WHERE id = '00000000-0000-4000-8000-000000000202'
    AND status = 'approved'
    AND expires_at > now();
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 0 THEN
    RAISE EXCEPTION 'terminal session was incorrectly revived';
  END IF;
END $$;

ROLLBACK;
