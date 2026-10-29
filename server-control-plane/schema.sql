CREATE TABLE IF NOT EXISTS servers (
  id TEXT PRIMARY KEY NOT NULL,
  host TEXT NOT NULL,
  port INTEGER NOT NULL,
  region TEXT NOT NULL,
  engine TEXT NOT NULL,
  priority INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
  created_at INTEGER NOT NULL DEFAULT (unixepoch()),
  updated_at INTEGER NOT NULL DEFAULT (unixepoch())
);
CREATE INDEX IF NOT EXISTS idx_servers_enabled_priority ON servers(enabled, priority DESC, id ASC);
-- Empty by default: enable only servers operated by RØH or explicitly authorized.
