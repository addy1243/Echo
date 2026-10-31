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
const v2Fence = sessionId => ({ sessionId, queueRevision: 1, occurrenceRevision: 2, transportRevision: 3 });
const v2Recording = () => ({ id: 'youtube:abcdefghijk', title: 'Test song', artist: 'Test artist', durationMs: 120000 });
const v2Queue = () => ({ entries: [{ id: '00000000-0000-4000-8000-000000000001', recording: v2Recording() }],
  repeatMode: 'OFF', shuffleEnabled: false });
const v2Device = () => ({ sessionId: 'session-1', label: 'Laptop', updatedAt: timestamp, enabled: true,
  capabilities: ['move', 'group'], snapshot: { fence: v2Fence('session-1'), currentOccurrenceId: '00000000-0000-4000-8000-000000000001',
    positionMs: 1000, playing: true, ready: true, error: '', ended: false, recordingId: 'youtube:abcdefghijk',
    title: 'Test song', artist: 'Test artist', durationMs: 120000 } });

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

test('playback v2 MOVE and bounded group rows enforce owner, safe payloads, fences and immutable plans', async () => {
  const uid = `v2-owner-${Date.now()}`;
  const base = `users/${uid}/playbackV2`;
  const op = '00000000-0000-4000-8000-000000000011';
  const group = '00000000-0000-4000-8000-000000000099';
  const occurrence = '00000000-0000-4000-8000-000000000001';
  const now = Date.now();
  const devicePath = `${base}/devices/laptop-1`;
  assert.equal((await request(devicePath, 'PUT', uid, v2Device(), false)).status, 401);
  assert.equal((await request(devicePath, 'PUT', 'other-user', v2Device())).status, 401);
  let result = await request(devicePath, 'PUT', uid, v2Device());
  assert.equal(result.status, 200, await result.text());
  const localTarget = v2Device(); localTarget.snapshot.recordingId = `local:${'a'.repeat(64)}`;
  result = await request(`${base}/devices/local-target`, 'PUT', uid, localTarget);
  assert.equal(result.status, 200, await result.text(), 'hashed local metadata may be published for target discovery');
  assert.equal((await request(`${base}/devices/laptop-1`, 'PUT', uid, { ...v2Device(), token: 'secret' })).status, 401);
  const unsafeDevice = v2Device(); unsafeDevice.snapshot.title = 'https://example.test/song';
  assert.equal((await request(devicePath, 'PUT', uid, unsafeDevice)).status, 401);
  assert.equal((await request(`${base}/unrecognized`, 'PUT', uid, true)).status, 401);

  const requestBody = { schema: 2, id: op, type: 'MOVE', sourceDeviceId: 'laptop-1', sourceSessionId: 'session-1',
    targetDeviceId: 'desktop-1', targetSessionId: 'session-2', sourceFence: v2Fence('session-1'),
    targetFence: v2Fence('session-2'), queue: v2Queue(), positionMs: 1000, playing: true,
    createdAt: timestamp, expiresAt: now + 60000 };
  const requestPath = `${base}/ops/${op}/request`;
  assert.equal((await request(requestPath, 'PUT', uid, requestBody, false)).status, 401);
  result = await request(requestPath, 'PUT', uid, requestBody);
  assert.equal(result.status, 200, await result.text());
  assert.equal((await request(requestPath, 'PUT', uid, { ...requestBody, playing: false })).status, 401,
    'immutable MOVE requests cannot be rewritten');
  const injected = { ...requestBody, id: '00000000-0000-4000-8000-000000000012',
    queue: { ...v2Queue(), entries: [{ ...v2Queue().entries[0], recording: { ...v2Recording(), id: 'file:///private/song' } }] } };
  assert.equal((await request(`${base}/ops/${injected.id}/request`, 'PUT', uid, injected)).status, 401);
  const stale = { ...requestBody, id: '00000000-0000-4000-8000-000000000013', createdAt: now - 60000,
    expiresAt: now - 30000 };
  assert.equal((await request(`${base}/ops/${stale.id}/request`, 'PUT', uid, stale)).status, 401);

  const timeline = { epoch: 1, sequence: 0, queue: v2Queue(), cursor: 0, positionMs: 0,
    playing: false, ended: false, issuedAtMs: now, digest: 'a'.repeat(64) };
  const timelinePath = `${base}/groups/${group}/timelines/0`;
  result = await request(timelinePath, 'PUT', uid, timeline);
  assert.equal(result.status, 200, await result.text());
  assert.equal((await request(timelinePath, 'PUT', uid, { ...timeline, positionMs: 20 })).status, 401,
    'shared timelines are immutable');
  const head = { schema: 2, id: group, epoch: 1, coordinatorDeviceId: 'laptop-1', coordinatorSessionId: 'session-1',
    leaseUntil: now + 15000, timelineSeq: 0, timelineDigest: 'a'.repeat(64), status: 'PREPARING', updatedAt: timestamp };
  result = await request(`${base}/groups/${group}/head`, 'PUT', uid, head);
  assert.equal(result.status, 200, await result.text());
  assert.equal((await request(`${base}/groups/${group}/head`, 'PUT', uid, { ...head, leaseUntil: now + 60000 })).status, 401);
  const member = { sessionId: 'session-1', epoch: 1, lastAppliedSeq: -1, status: 'INVITED',
    observedOccurrenceId: '', observedPositionMs: 0, updatedAt: timestamp };
  result = await request(`${base}/groups/${group}/members/laptop-1`, 'PUT', uid, member);
  assert.equal(result.status, 200, await result.text());
  result = await request(`${base}/groups/${group}/members/desktop-1`, 'PUT', uid, { ...member, sessionId: 'session-2' });
  assert.equal(result.status, 200, await result.text());
  assert.equal((await request(`${base}/groups/${group}/members/third-player`, 'PUT', uid, { ...member, localPath: 'C:\\Music' })).status, 401,
    'member rows reject local paths and unknown fields');
  const groupRequest = { id: op, sourceDeviceId: 'laptop-1', sourceSessionId: 'session-1', expectedEpoch: 1,
    expectedSeq: 0, action: 'PAUSE', positionMs: 0, createdAt: timestamp, expiresAt: now + 20000 };
  result = await request(`${base}/groups/${group}/requests/${op}`, 'PUT', uid, groupRequest);
  assert.equal(result.status, 200, await result.text());
  assert.equal((await request(`${base}/groups/${group}/requests/${op}`, 'PUT', uid,
    { ...groupRequest, action: 'DELETE_LIBRARY' })).status, 401);
  assert.equal((await request(`${base}/groups/${group}/head`, 'GET', 'someone-else')).status, 401);
  assert.equal((await request(`${base}/ops/${op}/request`, 'DELETE', uid)).status, 200);
});
