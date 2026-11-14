CREATE TABLE social_lifecycle (
  uid TEXT PRIMARY KEY,
  epoch INTEGER NOT NULL CHECK (epoch >= 0)
);
INSERT INTO social_lifecycle(uid,epoch) SELECT uid,0 FROM profiles;
