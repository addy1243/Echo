import assert from 'node:assert/strict';
import { test } from 'node:test';

const host = process.env.FIRESTORE_EMULATOR_HOST;
assert.ok(host && /^(127\.0\.0\.1|localhost):\d+$/.test(host), 'Start the local Firestore emulator before running this check');
const project = process.env.GCLOUD_PROJECT || 'demo-echo-rules';
assert.ok(project.startsWith('demo-'), 'Rules checks must use a demo project');
const base = `http://${host}/v1/projects/${project}/databases/(default)/documents`;
const token = (uid, verified = true) => {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({
    aud: project, iss: `https://securetoken.google.com/${project}`, sub: uid, user_id: uid,
    iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 3600,
    email: `${uid}@example.test`, email_verified: verified,
    firebase: { sign_in_provider: 'google.com', identities: { 'google.com': [uid] } }
  })}.`;
};
const value = (uid, overrides = {}) => ({ fields: {
  uid: { stringValue: uid }, format: { stringValue: 'echo-library-state' },
  version: { integerValue: '1' }, encoding: { stringValue: 'gzip-base64' },
  payload: { stringValue: 'H4sIAAAAAAAA' }, ...overrides
} });
async function request(path, method, uid, body, verified = true) {
  return fetch(`${base}/${path}`, {
    method, headers: { 'Content-Type': 'application/json', ...(uid ? { Authorization: `Bearer ${token(uid, verified)}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
}

test('owner-only bounded library document; all other access denied', async () => {
  const owner = `owner-${Date.now()}`;
  const path = `users/${owner}/sync/library-v1`;
  assert.equal((await request(path, 'PATCH', null, value(owner))).status, 403);
  assert.equal((await request(path, 'PATCH', 'other-user', value(owner))).status, 403);
  assert.equal((await request(path, 'PATCH', owner, value(owner), false)).status, 403);
  assert.equal((await request(path, 'PATCH', owner, value(owner))).status, 200);
  assert.equal((await request(path, 'GET', owner)).status, 200);
  assert.equal((await request(path, 'GET', 'other-user')).status, 403);
  assert.equal((await request(path, 'GET', null)).status, 403);
  assert.equal((await request(path, 'PATCH', owner, value(owner, { uid: { stringValue: 'other-user' } }))).status, 403);
  assert.equal((await request(path, 'PATCH', owner, value(owner, { version: { integerValue: '2' } }))).status, 403);
  assert.equal((await request(path, 'PATCH', owner, value(owner, { payload: { stringValue: '' } }))).status, 403);
  assert.equal((await request(path, 'PATCH', owner, value(owner, { payload: { stringValue: 'x'.repeat(716801) } }))).status, 403);
  assert.equal((await request(path, 'PATCH', owner, value(owner, { extra: { stringValue: 'unexpected' } }))).status, 403);
  assert.equal((await request(path, 'DELETE', owner)).status, 403);
  assert.equal((await request(`users/${owner}/sync`, 'GET', owner)).status, 403);
  assert.equal((await request(`users/${owner}/sync/other`, 'PATCH', owner, value(owner))).status, 403);
});
