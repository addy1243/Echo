CREATE TABLE profiles (
  uid TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  display_name TEXT NOT NULL,
  avatar TEXT NOT NULL,
  online_visible INTEGER NOT NULL CHECK (online_visible IN (0,1)),
  song_visible INTEGER NOT NULL CHECK (song_visible IN (0,1)),
  version INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE requests (
  sender TEXT NOT NULL,
  receiver TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (sender, receiver),
  CHECK (sender <> receiver)
);
CREATE TABLE friendships (
  user_a TEXT NOT NULL,
  user_b TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_a, user_b),
  CHECK (user_a < user_b)
);
CREATE TABLE blocks (
  blocker TEXT NOT NULL,
  blocked TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (blocker, blocked),
  CHECK (blocker <> blocked)
);
CREATE TABLE pair_versions (
  user_a TEXT NOT NULL,
  user_b TEXT NOT NULL,
  version INTEGER NOT NULL,
  PRIMARY KEY (user_a, user_b),
  CHECK (user_a < user_b)
);
CREATE TABLE invites (
  token_hash TEXT PRIMARY KEY,
  owner TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  redeemed_by TEXT,
  redeemed_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX requests_receiver ON requests(receiver, created_at);
CREATE INDEX requests_sender ON requests(sender, created_at);
CREATE INDEX invites_owner ON invites(owner, expires_at);
CREATE TABLE quotas (
  uid TEXT NOT NULL,
  day TEXT NOT NULL,
  action TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (uid, day, action)
);
CREATE TABLE transaction_guard (ok INTEGER NOT NULL CHECK (ok = 1));
