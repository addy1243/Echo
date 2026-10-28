import assert from 'node:assert/strict';
import { test } from 'node:test';

const host = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
assert.ok(host && /^(127\.0\.0\.1|localhost):\d+$/.test(host), 'Start the local RTDB emulator');
const project = process.env.GCLOUD_PROJECT || 'demo-echo-rules';
assert.ok(project.startsWith('demo-'), 'Use only a demo project for rules tests');
const base = `http://${host}`;
const token = (uid, verified = true) => {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({
    aud: project, iss: `https://securetoken.google.com/${project}`, sub: uid, user_id: uid,
    iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 3600,
    email: `${uid}@example.test`, email_verified: verified,
    firebase: { sign_in_provider: 'google.com', identities: { 'google.com': [uid] } }
  })}.`;
};
async function request(path, method, uid, body, verified = true) {
  const url = new URL(`${base}/${path}.json`);
  url.searchParams.set('ns', `${project}-default-rtdb`);
  if (uid) url.searchParams.set('auth', token(uid, verified));
  return fetch(url, { method, headers: { 'Content-Type': 'application/json' },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
}
const timestamp = { '.sv': 'timestamp' };
const device = () => ({ label: 'Laptop', sessionId: 'session-1', updatedAt: timestamp, controlEnabled: true,
  player: { trackId: 'youtube:abcdefghijk', title: 'Test song', artist: 'Test artist', isPlaying: true,
    positionMs: 1000, durationMs: 120000, trackRevision: 'track-1', queueRevision: 'queue-1' } });

test('owner-only live playback, strict envelopes and cross-account denial', async () => {
  const uid = `owner-${Date.now()}`;
  const root = `users/${uid}/playback`;
  const path = `${root}/devices/laptop-1`;
  for (const who of [null, 'someone-else']) {
    assert.equal((await request(path, 'PUT', who, device())).status, 401);
    assert.equal((await request(root, 'GET', who)).status, 401);
  }
  assert.equal((await request(path, 'PUT', uid, device(), false)).status, 401);
  let result = await request(path, 'PUT', uid, device());
  assert.equal(result.status, 200, await result.text());
  assert.equal((await request(root, 'GET', uid)).status, 200);
  assert.equal((await request(`users/${uid}`, 'GET', uid)).status, 401);
  assert.equal((await request('users', 'GET', uid)).status, 401);
  assert.equal((await request(`${root}/arbitrary`, 'PUT', uid, true)).status, 401);
  assert.equal((await request(path, 'PUT', uid, { ...device(), audio: 'forbidden' })).status, 401);
  const bad = device(); bad.player.sourceUri = 'file:///private/song.mp3';
  assert.equal((await request(path, 'PUT', uid, bad)).status, 401);
  const oversized = device(); oversized.player.title = 'x'.repeat(501);
  assert.equal((await request(path, 'PUT', uid, oversized)).status, 401);
  const negative = device(); negative.player.positionMs = -1;
  assert.equal((await request(path, 'PUT', uid, negative)).status, 401);
  const now = Date.now();
  const command = { id: 'command-1', action: 'PAUSE', targetSessionId: 'session-1',
    expectedTrackRevision: 'track-1', expectedQueueRevision: 'queue-1', positionMs: 0,
    issuedAt: now, expiresAt: now + 20000, sourceDeviceId: 'phone-1', sourceSessionId: 'phone-session', createdAt: timestamp };
  const commandPath = `${root}/commands/laptop-1/command-1`;
  assert.equal((await request(commandPath, 'PUT', 'someone-else', command)).status, 401);
  result = await request(commandPath, 'PUT', uid, command);
  assert.equal(result.status, 200, await result.text());
  assert.equal((await request(commandPath, 'PUT', uid, { ...command, action: 'DELETE_LIBRARY' })).status, 401);
  assert.equal((await request(`${root}/commands/laptop-1/expired`, 'PUT', uid,
    { ...command, id: 'expired', issuedAt: now - 60000, expiresAt: now - 40000 })).status, 401);
  const ack = { commandId: 'command-1', status: 'applied', processedAt: timestamp };
  assert.equal((await request(`${commandPath}/ack`, 'PUT', uid, ack)).status, 200);
  assert.equal((await request(`${commandPath}/ack`, 'PUT', uid, { ...ack, status: 'invented' })).status, 401);
  assert.equal((await request(commandPath, 'DELETE', uid)).status, 200);
  assert.equal((await request(path, 'DELETE', uid)).status, 200);
});
