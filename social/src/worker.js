const CERT_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const AVATARS = new Set(["avatar_1", "avatar_2", "avatar_3", "avatar_4", "avatar_5", "avatar_6", "avatar_7", "avatar_8"]);
const encoder = new TextEncoder();
let certCache, certRefresh;

class ApiError extends Error { constructor(status, code) { super(code); this.status = status; this.code = code; } }
const fail = (status, code) => { throw new ApiError(status, code); };
const json = (body, status = 200) => Response.json(body, { status, headers: { "cache-control": "no-store" } });
const uidPair = (a, b) => a < b ? [a, b] : [b, a];
const safe = value => typeof value === "string" && value.length > 0 && value.length <= 128 && !/[\u0000-\u001f\u007f]/.test(value);
const versionToken = /^[0-9a-f]{32}\.[0-9a-f]{32}$/;
const profileDto = row => ({ userId: row.uid, username: row.username, displayName: row.display_name, avatar: row.avatar });
const ownDto = row => ({ ...profileDto(row), onlineVisible: !!row.online_visible, currentSongVisible: !!row.song_visible, version: row.version, setupEpoch: row.setup_epoch });
const encodeB64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
const decodeB64 = value => Uint8Array.from(atob(value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=")), c => c.charCodeAt(0));

async function authenticate(request, env) {
  const project = env.FIREBASE_PROJECT_ID;
  if (typeof project !== "string" || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(project)) fail(503, "identity_unavailable");
  const token = request.headers.get("authorization")?.match(/^Bearer ([A-Za-z0-9._~-]+)$/)?.[1];
  if (!token) fail(401, "unauthorized");
  if (!env.FIREBASE_API_KEY) fail(503, "identity_unavailable");
  const parts = token.split(".");
  if (parts.length !== 3) fail(401, "unauthorized");
  let header, claims;
  try { header = JSON.parse(new TextDecoder().decode(decodeB64(parts[0]))); claims = JSON.parse(new TextDecoder().decode(decodeB64(parts[1]))); }
  catch { fail(401, "unauthorized"); }
  const now = Math.floor(Date.now() / 1000);
  if (header.alg !== "RS256" || typeof header.kid !== "string" || claims.aud !== project || claims.iss !== `https://securetoken.google.com/${project}` || !safe(claims.sub) || claims.email_verified !== true || claims.firebase?.sign_in_provider !== "google.com" || !Number.isInteger(claims.auth_time) || !Number.isInteger(claims.iat) || claims.auth_time > claims.iat || claims.auth_time > now + 5 || claims.iat > now + 60 || !Number.isInteger(claims.exp) || claims.exp <= now || claims.exp-claims.iat>3600) fail(401, "unauthorized");
  try {
    const fetchCerts = async () => {
      if (!certRefresh) certRefresh = (async () => {
        const response = await fetch(CERT_URL);
        if (!response.ok) fail(503, "identity_unavailable");
        const data = await response.json(), maxAge = Number(response.headers.get("cache-control")?.match(/max-age=(\d+)/)?.[1] || 300);
        certCache = { keys: data.keys, until: Date.now() + Math.min(maxAge, 300) * 1000 };
      })().finally(() => { certRefresh = undefined; });
      await certRefresh;
    };
    if (!certCache || certCache.until < Date.now()) await fetchCerts();
    let jwk = certCache.keys.find(key => key.kid === header.kid);
    if (!jwk) fail(401, "unauthorized");
    const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    if (!await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, decodeB64(parts[2]), encoder.encode(`${parts[0]}.${parts[1]}`))) fail(401, "unauthorized");
  } catch (error) {
    if (error instanceof ApiError) throw error;
    fail(503, "identity_unavailable");
  }
  await quota(env.DB, claims.sub, "auth", 300);
  try {
    const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${encodeURIComponent(env.FIREBASE_API_KEY)}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ idToken: token }) });
    if (!response.ok) fail(response.status === 400 || response.status === 401 ? 401 : 503, response.status === 400 || response.status === 401 ? "unauthorized" : "identity_unavailable");
    const result = await response.json();
    const account = result.users?.find(user => user.localId === claims.sub);
    if (!account || account.disabled === true || account.emailVerified !== true || !account.providerUserInfo?.some(provider => provider.providerId === "google.com") || (account.validSince != null && Number(account.validSince) >= claims.auth_time)) fail(401, "unauthorized");
  } catch (error) { if (error instanceof ApiError) throw error; fail(503, "identity_unavailable"); }
  return claims.sub;
}

async function body(request) {
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) fail(415, "json_required");
  if (Number(request.headers.get("content-length") || 0) > 8192) fail(413, "payload_too_large");
  if (!request.body) fail(400, "invalid_json");
  const reader=request.body.getReader(),chunks=[]; let size=0;
  while(true) { const {done,value}=await reader.read(); if(done) break; size+=value.byteLength; if(size>8192) { await reader.cancel(); fail(413,"payload_too_large"); } chunks.push(value); }
  const bytes=new Uint8Array(size); let offset=0; for(const chunk of chunks) { bytes.set(chunk,offset); offset+=chunk.byteLength; }
  try { const value = JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(bytes)); if (!value || typeof value !== "object" || Array.isArray(value)) fail(400, "invalid_json"); return value; }
  catch (error) { if (error instanceof ApiError) throw error; fail(400, "invalid_json"); }
}
async function quota(db, uid, action, limit) {
  const day = new Date().toISOString().slice(0, 10);
  const statements = action === "auth" ? [db.prepare("DELETE FROM quotas WHERE (uid,day,action) IN (SELECT uid,day,action FROM quotas WHERE day < date('now','-7 days') ORDER BY day LIMIT 1)")] : [];
  statements.push(db.prepare("INSERT INTO quotas(uid,day,action,count) VALUES(?,?,?,1) ON CONFLICT(uid,day,action) DO UPDATE SET count=count+1 WHERE count<?").bind(uid, day, action, limit));
  const result = await db.batch(statements);
  if (!result.at(-1).meta.changes) fail(429, "rate_limited");
}
async function limitIp(request, env) {
  const ip=request.headers.get("cf-connecting-ip"), limiter=env.IP_RATE_LIMITER;
  if (!safe(ip) || !limiter || typeof limiter.limit !== "function") fail(503, "rate_limit_unavailable");
  try { if (!(await limiter.limit({key:ip})).success) fail(429, "rate_limited"); }
  catch(error) { if(error instanceof ApiError) throw error; fail(503,"rate_limit_unavailable"); }
}
async function getProfile(db, uid) { return db.prepare("SELECT p.*,COALESCE(l.epoch,0) setup_epoch FROM profiles p LEFT JOIN social_lifecycle l ON l.uid=p.uid WHERE p.uid=?").bind(uid).first(); }
async function getEpoch(db, uid) { const row=await db.prepare("SELECT epoch FROM social_lifecycle WHERE uid=?").bind(uid).first(); return row?.epoch||0; }
async function requireProfile(db, uid) { const row = await getProfile(db, uid); if (!row) fail(409, "setup_required"); return row; }
const txGuard = ["INSERT INTO transaction_guard(ok) SELECT CASE WHEN changes()=1 THEN 1 ELSE 0 END", "DELETE FROM transaction_guard"];
async function relationshipState(db, actor, target) { return db.prepare("SELECT a.nonce actor_nonce,b.nonce target_nonce,EXISTS(SELECT 1 FROM profiles WHERE uid=a.uid) actor_profile,EXISTS(SELECT 1 FROM profiles WHERE uid=b.uid) target_profile FROM social_lifecycle a LEFT JOIN social_lifecycle b ON b.uid=? WHERE a.uid=?").bind(target,actor).first(); }
async function relationshipVersion(db, actor, target) {
  const state=await relationshipState(db,actor,target);
  if(!state?.actor_nonce||!state.target_nonce) fail(409,"stale_relationship");
  return `${state.actor_nonce}.${state.target_nonce}`;
}
function rotateRelationship(db, actor, target, actorNonce, targetNonce, allowMissingTarget) {
  const targetProfile=allowMissingTarget?"":" AND EXISTS(SELECT 1 FROM profiles WHERE uid=?)";
  const actorRotation=db.prepare(`UPDATE social_lifecycle SET nonce=lower(hex(randomblob(16))) WHERE uid=? AND nonce=? AND EXISTS(SELECT 1 FROM profiles WHERE uid=?) AND EXISTS(SELECT 1 FROM social_lifecycle WHERE uid=? AND nonce=?)${targetProfile}`).bind(...(allowMissingTarget?[actor,actorNonce,actor,target,targetNonce]:[actor,actorNonce,actor,target,targetNonce,target]));
  const targetRotation=db.prepare(`UPDATE social_lifecycle SET nonce=lower(hex(randomblob(16))) WHERE uid=? AND nonce=? AND EXISTS(SELECT 1 FROM profiles WHERE uid=?)${targetProfile}`).bind(...(allowMissingTarget?[target,targetNonce,actor]:[target,targetNonce,actor,target]));
  return [actorRotation,...txGuard.map(sql=>db.prepare(sql)),targetRotation,...txGuard.map(sql=>db.prepare(sql))];
}
async function blocked(db, a, b) { return !!(await db.prepare("SELECT 1 FROM blocks WHERE (blocker=? AND blocked=?) OR (blocker=? AND blocked=?) LIMIT 1").bind(a,b,b,a).first()); }
async function targetProfile(db, id) { if (!safe(id)) fail(400, "invalid_user"); const row = await getProfile(db, id); if (!row) fail(404, "not_found"); return row; }
async function relationshipAction(db, actor, target, expected, operation, allowMissingTarget = false) {
  if (typeof expected !== "string" || !versionToken.test(expected)) fail(400,"expected_version_required");
  if (!safe(target) || actor === target) fail(400,"invalid_user");
  const state=await relationshipState(db,actor,target);
  if(!state?.actor_profile) fail(409,"setup_required");
  if(!state.target_nonce||(!allowMissingTarget&&!state.target_profile)) fail(404,"not_found");
  const [actorNonce,targetNonce]=expected.split(".");
  if(state.actor_nonce!==actorNonce||state.target_nonce!==targetNonce) fail(409,"stale_relationship");
  try { await db.batch([...rotateRelationship(db,actor,target,actorNonce,targetNonce,allowMissingTarget), ...operation()]); }
  catch (error) { const message=String(error); if (message.includes("transaction_guard") || message.includes("CHECK constraint failed: ok") || message.includes("UNIQUE constraint failed: requests") || message.includes("UNIQUE constraint failed: blocks.blocker, blocks.blocked")) fail(409, "stale_relationship"); throw error; }
  return relationshipVersion(db,actor,target);
}
async function jsonBody(request, required = []) { const value = await body(request); for (const key of required) if (!(key in value)) fail(400, "invalid_request"); return value; }

async function route(request, env, uid) {
  const db = env.DB;
  const url = new URL(request.url), path = url.pathname, method = request.method;
  if (path === "/v1/me" && method === "GET") { const row = await getProfile(db, uid); return json(row ? ownDto(row) : { setupComplete: false,setupEpoch:await getEpoch(db,uid) }); }
  if (path === "/v1/me" && method === "PUT") {
    const input = await jsonBody(request, ["username", "displayName", "avatar", "onlineVisible", "currentSongVisible", "setupEpoch"]);
    const username = typeof input.username === "string" ? input.username.toLowerCase() : "";
    if (!/^[a-z0-9_]{3,24}$/.test(username) || typeof input.displayName !== "string" || !input.displayName.trim() || [...input.displayName].length > 40 || /[\u0000-\u001f\u007f]/.test(input.displayName) || !AVATARS.has(input.avatar) || typeof input.onlineVisible !== "boolean" || typeof input.currentSongVisible !== "boolean") fail(400, "invalid_profile");
    const old = await getProfile(db, uid);
    if (!Number.isSafeInteger(input.setupEpoch)||input.setupEpoch<0||input.setupEpoch!==(old?.setup_epoch??await getEpoch(db,uid))|| (old ? input.version !== old.version : input.version != null)) fail(409, "stale_profile");
    await quota(db, uid, "profile", 5);
    const now = Date.now();
    try { await db.batch([db.prepare("INSERT INTO social_lifecycle(uid,epoch,nonce) VALUES(?,0,lower(hex(randomblob(16)))) ON CONFLICT(uid) DO NOTHING").bind(uid),db.prepare("INSERT INTO profiles(uid,username,display_name,avatar,online_visible,song_visible,version,created_at,updated_at) SELECT ?,?,?,?,?,?,1,?,? WHERE COALESCE((SELECT epoch FROM social_lifecycle WHERE uid=?),0)=? ON CONFLICT(uid) DO UPDATE SET username=excluded.username,display_name=excluded.display_name,avatar=excluded.avatar,online_visible=excluded.online_visible,song_visible=excluded.song_visible,version=version+1,updated_at=excluded.updated_at WHERE version=? AND COALESCE((SELECT epoch FROM social_lifecycle WHERE uid=?),0)=?").bind(uid,username,input.displayName.trim(),input.avatar,Number(input.onlineVisible),Number(input.currentSongVisible),now,now,uid,input.setupEpoch,old?.version??-1,uid,input.setupEpoch),...txGuard.map(sql=>db.prepare(sql))]); }
    catch(error) { if(String(error).includes("transaction_guard")||String(error).includes("CHECK constraint failed: ok")) fail(409,"stale_profile"); throw error; }
    return json(ownDto(await getProfile(db, uid)));
  }
  if (path.startsWith("/v1/users/") && method === "GET") {
    await quota(db, uid, "lookup", 30); await requireProfile(db, uid);
    let name; try { name=decodeURIComponent(path.slice("/v1/users/".length)); } catch { fail(400,"invalid_username"); }
    if (!/^[a-z0-9_]{3,24}$/i.test(name)) fail(400, "invalid_username");
    const target = await db.prepare("SELECT p.uid,p.username,p.display_name,p.avatar,me.nonce actor_nonce,peer.nonce target_nonce FROM profiles p JOIN social_lifecycle me ON me.uid=? JOIN social_lifecycle peer ON peer.uid=p.uid WHERE p.username=? COLLATE NOCASE AND p.uid<>? AND EXISTS(SELECT 1 FROM profiles WHERE uid=?) AND NOT EXISTS(SELECT 1 FROM blocks WHERE (blocker=? AND blocked=p.uid) OR (blocker=p.uid AND blocked=?))").bind(uid,name,uid,uid,uid,uid).first();
    if (!target) fail(404, "not_found");
    return json({ ...profileDto(target), relationshipVersion:`${target.actor_nonce}.${target.target_nonce}` });
  }
  if (path === "/v1/friends" && method === "GET") {
    await quota(db,uid,"read",120); await requireProfile(db, uid);
    const rows = await db.prepare("SELECT p.uid,p.username,p.display_name,p.avatar,me.nonce actor_nonce,peer.nonce target_nonce FROM friendships f JOIN profiles p ON p.uid=CASE WHEN f.user_a=? THEN f.user_b ELSE f.user_a END JOIN social_lifecycle me ON me.uid=? JOIN social_lifecycle peer ON peer.uid=p.uid WHERE (f.user_a=? OR f.user_b=?) AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker=? AND b.blocked=p.uid) OR (b.blocker=p.uid AND b.blocked=?)) ORDER BY p.username LIMIT 50").bind(uid,uid,uid,uid,uid,uid).all();
    return json({ items: rows.results.map(row => ({ ...profileDto(row), relationshipVersion:`${row.actor_nonce}.${row.target_nonce}` })) });
  }
  if (path === "/v1/requests" && method === "GET") {
    await quota(db,uid,"read",120); await requireProfile(db, uid);
    const direction = url.searchParams.get("direction"); if (direction !== "incoming" && direction !== "outgoing") fail(400, "invalid_direction");
    const col = direction === "incoming" ? "receiver" : "sender", other = direction === "incoming" ? "sender" : "receiver";
    const rows = await db.prepare(`SELECT p.uid,p.username,p.display_name,p.avatar,me.nonce actor_nonce,peer.nonce target_nonce FROM requests r JOIN profiles p ON p.uid=r.${other} JOIN social_lifecycle me ON me.uid=? JOIN social_lifecycle peer ON peer.uid=p.uid WHERE r.${col}=? AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker=? AND b.blocked=p.uid) OR (b.blocker=p.uid AND b.blocked=?)) ORDER BY r.created_at LIMIT 50`).bind(uid,uid,uid,uid).all();
    return json({ items: rows.results.map(row => ({ ...profileDto(row), version:`${row.actor_nonce}.${row.target_nonce}` })) });
  }
  if (path === "/v1/blocks" && method === "GET") {
    await quota(db,uid,"read",120); await requireProfile(db, uid);
    const rows = await db.prepare("SELECT b.blocked user_id,p.username,p.display_name,p.avatar,me.nonce actor_nonce,peer.nonce target_nonce FROM blocks b LEFT JOIN profiles p ON p.uid=b.blocked JOIN social_lifecycle me ON me.uid=b.blocker JOIN social_lifecycle peer ON peer.uid=b.blocked WHERE b.blocker=? ORDER BY b.created_at DESC LIMIT 50").bind(uid).all();
    return json({ items: rows.results.map(row => ({ userId: row.user_id, ...(row.username ? { username:row.username,displayName:row.display_name,avatar:row.avatar } : {}), relationshipVersion:`${row.actor_nonce}.${row.target_nonce}` })) });
  }
  if (path === "/v1/requests" && method === "POST") {
    const input = await jsonBody(request,["userId","expectedVersion"]); await quota(db,uid,"request",20);
    const target=await targetProfile(db,input.userId); if(uid===target.uid||await blocked(db,uid,target.uid)) fail(403,"unavailable");
    const next=await relationshipAction(db,uid,target.uid,input.expectedVersion,()=>[db.prepare("INSERT INTO requests(sender,receiver,created_at) SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM blocks WHERE (blocker=? AND blocked=?) OR (blocker=? AND blocked=?)) AND NOT EXISTS(SELECT 1 FROM friendships WHERE user_a=MIN(?,?) AND user_b=MAX(?,?)) AND (SELECT COUNT(*) FROM requests WHERE sender=?)<20 AND (SELECT COUNT(*) FROM requests WHERE receiver=?)<20").bind(uid,target.uid,Date.now(),uid,target.uid,target.uid,uid,uid,target.uid,uid,target.uid,uid,target.uid),...txGuard.map(sql=>db.prepare(sql))]);
    return json({ userId:target.uid,version:next },201);
  }
  const reqAction = path.match(/^\/v1\/requests\/([^/]+)\/(accept|decline|cancel)$/);
  if (reqAction && method === "POST") {
    const [, rawId, action] = reqAction; let targetId; try { targetId=decodeURIComponent(rawId); } catch { fail(400,"invalid_user"); }
    const input = await jsonBody(request,["expectedVersion"]); await quota(db,uid,"request",20);
    const next=await relationshipAction(db,uid,targetId,input.expectedVersion,()=>{
      if(action==="accept") {
        const [a,b]=uidPair(uid,targetId);
        return [db.prepare("DELETE FROM requests WHERE sender=? AND receiver=? AND NOT EXISTS(SELECT 1 FROM blocks WHERE (blocker=? AND blocked=?) OR (blocker=? AND blocked=?))").bind(targetId,uid,uid,targetId,targetId,uid), ...txGuard.map(sql=>db.prepare(sql)), db.prepare("DELETE FROM requests WHERE sender=? AND receiver=?").bind(uid,targetId), db.prepare("INSERT INTO friendships(user_a,user_b,created_at) SELECT ?,?,? WHERE (SELECT COUNT(*) FROM friendships WHERE user_a=? OR user_b=?)<50 AND (SELECT COUNT(*) FROM friendships WHERE user_a=? OR user_b=?)<50 AND NOT EXISTS(SELECT 1 FROM blocks WHERE (blocker=? AND blocked=?) OR (blocker=? AND blocked=?))").bind(a,b,Date.now(),uid,uid,targetId,targetId,uid,targetId,targetId,uid),...txGuard.map(sql=>db.prepare(sql))];
      } else {
        const sender=action==="decline"?targetId:uid, receiver=action==="decline"?uid:targetId;
        return [db.prepare("DELETE FROM requests WHERE sender=? AND receiver=?").bind(sender,receiver), ...txGuard.map(sql=>db.prepare(sql))];
      }
    });
    return json({userId:targetId,version:next,state:action==="accept"?"friends":"none"});
  }
  const friendAction=path.match(/^\/v1\/friends\/([^/]+)$/);
  if(friendAction&&method==="DELETE") {
    let target; try { target=decodeURIComponent(friendAction[1]); } catch { fail(400,"invalid_user"); }
    const input=await jsonBody(request,["expectedVersion"]); await quota(db,uid,"relationship",30);
    const [a,b]=uidPair(uid,target);
    const next=await relationshipAction(db,uid,target,input.expectedVersion,()=>[db.prepare("DELETE FROM friendships WHERE user_a=? AND user_b=?").bind(a,b),...txGuard.map(sql=>db.prepare(sql)),db.prepare("DELETE FROM requests WHERE (sender=? AND receiver=?) OR (sender=? AND receiver=?)").bind(uid,target,target,uid)]);
    return json({userId:target,version:next,state:"none"});
  }
  const blockAction=path.match(/^\/v1\/blocks\/([^/]+)$/);
  if(blockAction&&(method==="PUT"||method==="DELETE")) {
    let target; try { target=decodeURIComponent(blockAction[1]); } catch { fail(400,"invalid_user"); }
    const input=await jsonBody(request,["expectedVersion"]); await quota(db,uid,"relationship",30);
    const [a,b]=uidPair(uid,target);
    const missingDeletedTarget=method==="DELETE"&&!await getProfile(db,target);
    const next=await relationshipAction(db,uid,target,input.expectedVersion,()=>[method==="PUT"?db.prepare("INSERT INTO blocks(blocker,blocked,created_at) SELECT ?,?,? WHERE (SELECT COUNT(*) FROM blocks WHERE blocker=?)<50 AND (SELECT COUNT(*) FROM blocks WHERE blocked=?)<50").bind(uid,target,Date.now(),uid,target):db.prepare("DELETE FROM blocks WHERE blocker=? AND blocked=?").bind(uid,target),...txGuard.map(sql=>db.prepare(sql)),db.prepare("DELETE FROM friendships WHERE user_a=? AND user_b=?").bind(a,b),db.prepare("DELETE FROM requests WHERE (sender=? AND receiver=?) OR (sender=? AND receiver=?)").bind(uid,target,target,uid),db.prepare("DELETE FROM invites WHERE (owner=? AND redeemed_by=?) OR (owner=? AND redeemed_by=?)").bind(uid,target,target,uid)],missingDeletedTarget);
    return json({userId:target,version:next,state:method==="PUT"?"blocked":"none"});
  }
  if(path==="/v1/invites"&&method==="POST") {
    let inviteBase;
    try { inviteBase=new URL(env.INVITE_BASE_URL); if(inviteBase.protocol!=="https:"||inviteBase.username||inviteBase.password||inviteBase.search||inviteBase.hash) fail(503,"invite_links_unconfigured"); }
    catch(error) { if(error instanceof ApiError) throw error; fail(503,"invite_links_unconfigured"); }
    await requireProfile(db,uid); await quota(db,uid,"invite",5);
    await db.prepare("DELETE FROM invites WHERE expires_at<=?").bind(Date.now()).run();
    const token=encodeB64(crypto.getRandomValues(new Uint8Array(32))), hash=encodeB64(await crypto.subtle.digest("SHA-256",encoder.encode(token))), now=Date.now(), expires=now+86400000;
    const saved=await db.prepare("INSERT INTO invites(token_hash,owner,expires_at,created_at) SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM profiles WHERE uid=?) AND (SELECT COUNT(*) FROM invites WHERE owner=? AND redeemed_by IS NULL AND expires_at>?)<5").bind(hash,uid,expires,now,uid,uid,now).run();
    if(!saved.meta.changes) fail(429,"invite_limit");
    inviteBase.hash=`token=${encodeURIComponent(token)}`;
    return json({url:inviteBase.href,expiresAt:expires},201);
  }
  if(path==="/v1/invites/redeem"&&method==="POST") {
    const input=await jsonBody(request,["token"]); await requireProfile(db,uid); await quota(db,uid,"request",20);
    if(typeof input.token!=="string"||input.token.length>128) fail(400,"invalid_invite");
    const hash=encodeB64(await crypto.subtle.digest("SHA-256",encoder.encode(input.token)));
    const invite=await db.prepare("SELECT owner FROM invites WHERE token_hash=? AND redeemed_by IS NULL AND expires_at>?").bind(hash,Date.now()).first();
    if(!invite||invite.owner===uid||await blocked(db,uid,invite.owner)) fail(404,"invalid_invite");
    const owner=await getProfile(db,invite.owner); if(!owner) fail(404,"invalid_invite");
    const expected=await relationshipVersion(db,uid,owner.uid), next=await relationshipAction(db,uid,owner.uid,expected,()=>[db.prepare("UPDATE invites SET redeemed_by=?,redeemed_at=? WHERE token_hash=? AND redeemed_by IS NULL AND expires_at>?").bind(uid,Date.now(),hash,Date.now()),...txGuard.map(sql=>db.prepare(sql)),db.prepare("INSERT INTO requests(sender,receiver,created_at) SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM blocks WHERE (blocker=? AND blocked=?) OR (blocker=? AND blocked=?)) AND NOT EXISTS(SELECT 1 FROM friendships WHERE user_a=MIN(?,?) AND user_b=MAX(?,?)) AND (SELECT COUNT(*) FROM requests WHERE sender=?)<20 AND (SELECT COUNT(*) FROM requests WHERE receiver=?)<20").bind(uid,owner.uid,Date.now(),uid,owner.uid,owner.uid,uid,uid,owner.uid,uid,owner.uid,uid,owner.uid),...txGuard.map(sql=>db.prepare(sql))]);
    return json({userId:owner.uid,version:next},201);
  }
  if(path==="/v1/me/social"&&method==="DELETE") {
    await quota(db,uid,"delete",3);
    const result=await db.batch([
      db.prepare("INSERT INTO social_lifecycle(uid,epoch,nonce) VALUES(?,1,lower(hex(randomblob(16)))) ON CONFLICT(uid) DO UPDATE SET epoch=epoch+1,nonce=lower(hex(randomblob(16)))").bind(uid),
      db.prepare("DELETE FROM profiles WHERE uid=?").bind(uid),
      db.prepare("DELETE FROM requests WHERE sender=? OR receiver=?").bind(uid,uid),
      db.prepare("DELETE FROM friendships WHERE user_a=? OR user_b=?").bind(uid,uid),
      db.prepare("DELETE FROM blocks WHERE blocker=?").bind(uid),
      db.prepare("DELETE FROM invites WHERE owner=? OR redeemed_by=?").bind(uid,uid),
    ]);
    return json({deleted:result[1].meta.changes>0,setupEpoch:await getEpoch(db,uid)});
  }
  return json({error:{code:"not_found",message:"Not found"}},404);
}

export default { async fetch(request, env) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: { "access-control-allow-origin": env.CORS_ORIGIN || "null", "access-control-allow-methods": "GET,POST,PUT,DELETE,OPTIONS", "access-control-allow-headers": "authorization,content-type", "access-control-max-age": "600", "vary": "Origin" } });
  try {
    await limitIp(request, env);
    const uid = await authenticate(request, env);
    const response = await route(request, env, uid);
    const headers = new Headers(response.headers); headers.set("access-control-allow-origin", env.CORS_ORIGIN || "null"); headers.set("vary", "Origin");
    return new Response(response.body,{status:response.status,headers});
  } catch (error) {
    const message=String(error),conflict=message.includes("UNIQUE constraint failed: profiles.username");
    const status=error instanceof ApiError?error.status:conflict?409:500,code=error instanceof ApiError?error.code:conflict?"username_taken":"internal_error";
    if(status===500) console.error("Social API request failed",error instanceof Error?error.name:"unknown");
    return json({error:{code,message:code.replaceAll("_"," ")}},status);
  }
} };
