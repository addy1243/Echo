ALTER TABLE social_lifecycle ADD COLUMN nonce TEXT;
UPDATE social_lifecycle SET nonce=lower(hex(randomblob(16))) WHERE nonce IS NULL;
INSERT OR IGNORE INTO social_lifecycle(uid,epoch,nonce)
SELECT user_a,0,lower(hex(randomblob(16))) FROM pair_versions;
INSERT OR IGNORE INTO social_lifecycle(uid,epoch,nonce)
SELECT user_b,0,lower(hex(randomblob(16))) FROM pair_versions;
DROP TABLE pair_versions;
