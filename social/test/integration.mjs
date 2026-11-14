import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { createHash, randomUUID, webcrypto } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import worker from "../src/worker.js";

const project="echo-social-test-261113", temp=process.env.ECHO_CLOUDFLARE_FEASIBILITY||"C:/Users/adars/AppData/Local/Temp/echo-cloudflare-feasibility";
const pairTokenPattern=/^[0-9a-f]{32}\.[0-9a-f]{32}$/;
process.env.XDG_CONFIG_HOME=path.join(temp,"xdg"); process.env.WRANGLER_SEND_METRICS="false";
const localRequire=createRequire(path.join(temp,"package.json")),{getPlatformProxy}=localRequire("wrangler");
const proxy=await getPlatformProxy({configPath:path.resolve("wrangler.toml"),persist:false});
const accounts=new Map(), users=[];
const env={...proxy.env,FIREBASE_PROJECT_ID:project,FIREBASE_API_KEY:"isolated-test-key",INVITE_BASE_URL:"https://invite.example.test/invite",IP_RATE_LIMITER:{async limit(){return {success:true};}}};
const key=await webcrypto.subtle.generateKey({name:"RSASSA-PKCS1-v1_5",modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:"SHA-256"},true,["sign","verify"]);
const jwk=await webcrypto.subtle.exportKey("jwk",key.publicKey); jwk.kid="local-google-test-key";
globalThis.fetch=async (input,init={})=>{
  const url=String(input);
  if(url.includes("service_accounts/v1/jwk")) return Response.json({keys:[jwk]},{headers:{"cache-control":"max-age=3600"}});
  if(url.includes("accounts:lookup")) {
    const idToken=JSON.parse(init.body||"{}").idToken,claims=JSON.parse(Buffer.from(idToken.split(".")[1],"base64url").toString("utf8")),account=accounts.get(claims.sub);
    return account?Response.json({users:[account]}):new Response("",{status:400});
  }
  throw new Error("unexpected outbound request");
};

async function mintToken(uid) {
  const now=Math.floor(Date.now()/1000),b64=value=>Buffer.from(value).toString("base64url");
  const signingInput=`${b64(JSON.stringify({alg:"RS256",kid:jwk.kid,typ:"JWT"}))}.${b64(JSON.stringify({aud:project,iss:`https://securetoken.google.com/${project}`,sub:uid,auth_time:now,iat:now,exp:now+3600,email_verified:true,firebase:{sign_in_provider:"google.com"}}))}`;
  return `${signingInput}.${b64(await webcrypto.subtle.sign("RSASSA-PKCS1-v1_5",key.privateKey,new TextEncoder().encode(signingInput)))}`;
}
async function api(token,pathName,method="GET",payload) {
  const response=await worker.fetch(new Request(`https://social.test${pathName}`,{method,headers:{"cf-connecting-ip":"198.51.100.10",...(token?{authorization:`Bearer ${token}`}:{ }),...(payload?{"content-type":"application/json"}:{})},body:payload?JSON.stringify(payload):undefined}),env);
  let data={}; try { data=await response.json(); } catch {}
  return {status:response.status,data};
}
async function createUser(label) {
  const uid=`synthetic-${label}-${randomUUID()}`;
  accounts.set(uid,{localId:uid,emailVerified:true,providerUserInfo:[{providerId:"google.com"}]});
  const user={uid,token:await mintToken(uid)}; users.push(user); return user;
}
async function profile(user,username) {
  const current=await api(user.token,"/v1/me");
  return api(user.token,"/v1/me","PUT",{username,displayName:username,avatar:"avatar_1",onlineVisible:true,currentSongVisible:true,setupEpoch:current.data.setupEpoch,...(current.data.version?{version:current.data.version}:{})});
}

try {
  for(const name of (await readdir(path.resolve("migrations"))).filter(name=>name.endsWith(".sql")).sort()) {
    const migration=await readFile(path.resolve("migrations",name),"utf8");
    for(const statement of migration.split(";").filter(statement=>statement.trim())) await env.DB.prepare(statement).run();
  }
  const alice=await createUser("alice"),bob=await createUser("bob"),carol=await createUser("carol"),dave=await createUser("dave");
  await env.DB.prepare("INSERT INTO quotas(uid,day,action,count) VALUES('inactive-deleted-user','1900-01-01','stale_test',1),('active-user','2000-01-01','stale_test',1),('another-user','2001-01-01','stale_test',1),('active-user',date('now'),'current_test',1)").run();
  assert.equal((await api(alice.token,"/v1/friends")).status,409,"social reads require completed profile setup");
  assert.equal(await env.DB.prepare("SELECT 1 FROM quotas WHERE uid='inactive-deleted-user' AND action='stale_test'").first(),null,"an inactive or deleted identity's expired row is eligible for global cleanup");
  assert.equal((await env.DB.prepare("SELECT COUNT(*) count FROM quotas WHERE action='stale_test'").first()).count,2,"one authenticated request deletes at most one expired quota row");
  assert.ok(await env.DB.prepare("SELECT 1 FROM quotas WHERE uid='active-user' AND action='current_test'").first(),"unexpired quota rows remain untouched");
  assert.equal((await profile(alice,"alice_echo")).status,200);
  assert.equal((await profile(bob,"bob_echo")).status,200);

  const erin=await createUser("erin"),frank=await createUser("frank");
  assert.equal((await profile(erin,"erin_echo")).status,200);
  assert.equal((await profile(frank,"frank_echo")).status,200);
  const erinLookup=await api(erin.token,"/v1/users/frank_echo");
  const erinRequest=await api(erin.token,"/v1/requests","POST",{userId:frank.uid,expectedVersion:erinLookup.data.relationshipVersion});
  const frankIncoming=await api(frank.token,"/v1/requests?direction=incoming");
  const frankRequest=await api(frank.token,"/v1/requests","POST",{userId:erin.uid,expectedVersion:frankIncoming.data.items[0].version});
  const accepted=await api(frank.token,`/v1/requests/${erin.uid}/accept`,"POST",{expectedVersion:frankRequest.data.version});
  assert.equal(accepted.status,200,"deterministic reciprocal acceptance succeeds");
  assert.equal((await api(erin.token,"/v1/requests?direction=outgoing")).data.items.length,0);
  assert.equal((await api(frank.token,"/v1/requests?direction=outgoing")).data.items.length,0);
  assert.equal((await api(erin.token,"/v1/friends")).data.items[0].userId,frank.uid);

  const [claimA,claimB]=await Promise.all([profile(carol,"same_echo"),profile(dave,"same_echo")]);
  assert.deepEqual([claimA.status,claimB.status].sort(),[200,409],"case-insensitive usernames must have one atomic winner");
  const lookup=await api(alice.token,"/v1/users/BOB_ECHO");
  assert.equal(lookup.status,200); assert.match(lookup.data.relationshipVersion,pairTokenPattern);
  assert.equal((await api(alice.token,`/v1/blocks/${bob.uid}`,"DELETE",{expectedVersion:lookup.data.relationshipVersion})).status,409,"deleting a nonexistent own block conflicts");
  assert.equal((await api(alice.token,"/v1/requests","POST",{userId:bob.uid,expectedVersion:lookup.data.relationshipVersion})).status,201);
  const incoming=await api(bob.token,"/v1/requests?direction=incoming");
  assert.match(incoming.data.items[0].version,pairTokenPattern);
  const reciprocal=await api(bob.token,"/v1/requests","POST",{userId:alice.uid,expectedVersion:incoming.data.items[0].version});
  assert.equal(reciprocal.status,201,"reciprocal request remains pending until accepted");
  const outgoing=(await api(alice.token,"/v1/requests?direction=outgoing")).data.items;
  assert.equal(outgoing.length,1);

  const race=await Promise.all([
    api(bob.token,`/v1/requests/${alice.uid}/accept`,"POST",{expectedVersion:reciprocal.data.version}),
    api(alice.token,`/v1/blocks/${bob.uid}`,"PUT",{expectedVersion:outgoing[0].version})
  ]);
  assert.deepEqual(race.map(result=>result.status).sort(),[200,409],"concurrent accept/block must serialize by both lifecycle nonces");
  const winner=race.find(result=>result.status===200),fresh=await api(alice.token,"/v1/users/bob_echo");
  if(winner.data.state==="blocked") {
    assert.equal(fresh.status,404,"block hides profile lookup");
    assert.equal((await api(alice.token,"/v1/requests?direction=outgoing")).data.items.length,0,"block clears incoming request");
    assert.equal((await api(bob.token,"/v1/requests?direction=outgoing")).data.items.length,0,"block clears reciprocal request");
  }
  else {
    const friend=(await api(alice.token,"/v1/friends")).data.items[0];
    assert.match(friend.relationshipVersion,pairTokenPattern);
    assert.equal((await api(alice.token,"/v1/requests?direction=outgoing")).data.items.length,0,"acceptance atomically removes reciprocal outgoing request");
    assert.equal((await api(bob.token,"/v1/requests?direction=outgoing")).data.items.length,0,"acceptance removes accepted request");
    assert.equal((await api(alice.token,`/v1/blocks/${bob.uid}`,"PUT",{expectedVersion:outgoing[0].version})).status,409,"pre-accept token is stale");
    const blocked=await api(alice.token,`/v1/blocks/${bob.uid}`,"PUT",{expectedVersion:friend.relationshipVersion});
    assert.equal(blocked.status,200);
    assert.equal((await api(alice.token,`/v1/blocks/${bob.uid}`,"PUT",{expectedVersion:blocked.data.version})).status,409,"duplicate block is a safe conflict");
    const blockedBob=(await api(alice.token,"/v1/blocks")).data.items.find(item=>item.userId===bob.uid);
    assert.deepEqual({username:blockedBob.username,displayName:blockedBob.displayName,avatar:blockedBob.avatar},{username:"bob_echo",displayName:"bob_echo",avatar:"avatar_1"});
    assert.equal("email" in blockedBob,false,"block list excludes email");
    assert.equal((await api(alice.token,"/v1/friends")).data.items.length,0);
    assert.equal((await api(bob.token,"/v1/users/alice_echo")).status,404);
    assert.equal((await api(alice.token,`/v1/blocks/${bob.uid}`,"DELETE",{expectedVersion:blocked.data.version})).status,200);
  }

  const invite=await api(alice.token,"/v1/invites","POST");
  assert.equal(invite.status,201); assert.match(invite.data.url,/^https:\/\/invite\.example\.test\/invite#token=/);
  const inviteToken=new URL(invite.data.url).hash.slice("#token=".length);
  const [redeemA,redeemB]=await Promise.all([
    api(carol.token,"/v1/invites/redeem","POST",{token:inviteToken}),
    api(dave.token,"/v1/invites/redeem","POST",{token:inviteToken})
  ]);
  assert.equal([redeemA,redeemB].filter(result=>result.status===201).length,1,"invite redemption must have one winner");
  const loser=[redeemA,redeemB].find(result=>result.status!==201);
  assert.ok([404,409].includes(loser.status),"single-use replay is rejected");

  const expiring=await api(alice.token,"/v1/invites","POST"),expiredToken=new URL(expiring.data.url).hash.slice("#token=".length);
  const hash=createHash("sha256").update(expiredToken).digest("base64url");
  await env.DB.prepare("UPDATE invites SET expires_at=0 WHERE token_hash=?").bind(hash).run();
  assert.equal((await api(bob.token,"/v1/invites/redeem","POST",{token:expiredToken})).status,404,"expired invite must not redeem");

  if((await api(alice.token,"/v1/users/bob_echo")).status===404) {
    const ownBlock=(await api(alice.token,"/v1/blocks")).data.items.find(item=>item.userId===bob.uid);
    assert.ok(ownBlock); assert.equal((await api(alice.token,`/v1/blocks/${bob.uid}`,"DELETE",{expectedVersion:ownBlock.relationshipVersion})).status,200);
  }
  const currentPair=await api(bob.token,"/v1/users/alice_echo");
  assert.equal((await api(bob.token,`/v1/blocks/${alice.uid}`,"PUT",{expectedVersion:currentPair.data.relationshipVersion})).status,200);
  assert.equal((await api(alice.token,"/v1/me/social","DELETE")).status,200);
  const deletedProfile=await api(alice.token,"/v1/me");
  assert.deepEqual(deletedProfile.data,{setupComplete:false,setupEpoch:1},"social deletion removes profile but preserves lifecycle epoch");
  assert.equal((await api(alice.token,"/v1/me","PUT",{username:"alice_echo",displayName:"Alice",avatar:"avatar_1",onlineVisible:true,currentSongVisible:true,setupEpoch:0})).status,409,"stale setup must not recreate a deleted profile");
  const deletedBlock=(await api(bob.token,"/v1/blocks")).data.items.find(item=>item.userId===alice.uid);
  assert.ok(deletedBlock,"deletion preserves a block created by the other account");
  assert.equal("username" in deletedBlock,false,"deleted target identity is omitted from block list");
  assert.equal((await api(bob.token,`/v1/blocks/${alice.uid}`,"DELETE",{expectedVersion:deletedBlock.relationshipVersion})).status,200,"a retained block can be removed after its target deletes their profile");
  assert.equal((await api(bob.token,"/v1/blocks")).data.items.some(item=>item.userId===alice.uid),false);
  assert.equal((await profile(alice,"alice_echo")).status,200);
  const pairAfterUnblock=await api(bob.token,"/v1/users/alice_echo");
  assert.equal((await api(bob.token,`/v1/blocks/${alice.uid}`,"PUT",{expectedVersion:pairAfterUnblock.data.relationshipVersion})).status,200);
  assert.equal((await api(alice.token,"/v1/users/bob_echo")).status,404,"counterpart block remains effective after re-setup");

  await profile(carol,"carol_echo");
  const carolLookup=await api(alice.token,"/v1/users/carol_echo");
  const [lateRequest,deletion]=await Promise.all([
    api(alice.token,"/v1/requests","POST",{userId:carol.uid,expectedVersion:carolLookup.data.relationshipVersion}),
    api(alice.token,"/v1/me/social","DELETE")
  ]);
  assert.ok([201,409].includes(lateRequest.status)); assert.equal(deletion.status,200);
  assert.equal((await api(carol.token,"/v1/requests?direction=incoming")).data.items.some(item=>item.userId===alice.uid),false,"delete racing a request cannot leave an orphan edge");
  assert.equal((await api(alice.token,"/v1/me")).data.setupEpoch,2);
  assert.equal((await api(alice.token,"/v1/me","PUT",{username:"alice_echo",displayName:"Alice",avatar:"avatar_1",onlineVisible:true,currentSongVisible:true,setupEpoch:1})).status,409);
  assert.equal((await profile(alice,"alice_echo")).status,200);
  assert.equal((await api(alice.token,"/v1/requests","POST",{userId:carol.uid,expectedVersion:carolLookup.data.relationshipVersion})).status,409,"lifecycle nonce rejects a stale pre-delete relationship token");
  assert.equal((await api(alice.token,"/v1/me")).status,200,"social deletion leaves Firebase identity valid");

  await env.DB.prepare("WITH RECURSIVE seq(i) AS (VALUES(0) UNION ALL SELECT i+1 FROM seq WHERE i<500) INSERT INTO social_lifecycle(uid,epoch,nonce) SELECT 'lookup-user-'||i,0,lower(hex(randomblob(16))) FROM seq").run();
  await env.DB.prepare("WITH RECURSIVE seq(i) AS (VALUES(0) UNION ALL SELECT i+1 FROM seq WHERE i<500) INSERT INTO profiles(uid,username,display_name,avatar,online_visible,song_visible,version,created_at,updated_at) SELECT 'lookup-user-'||i,'search_user_'||i||'_echo','Search User '||i,'avatar_1',1,1,1,0,0 FROM seq").run();
  const lookupsBefore=(await env.DB.prepare("SELECT COUNT(*) count FROM social_lifecycle").first()).count;
  for(let i=0;i<=500;i++) {
    const uid=`lookup-user-${i}`;
    accounts.set(uid,{localId:uid,emailVerified:true,providerUserInfo:[{providerId:"google.com"}]});
    const result=await api(await mintToken(uid),"/v1/users/alice_echo");
    assert.equal(result.status,200,`lookup ${i} succeeds beyond the former pair cap`);
    assert.match(result.data.relationshipVersion,pairTokenPattern);
  }
  assert.equal((await env.DB.prepare("SELECT COUNT(*) count FROM social_lifecycle").first()).count,lookupsBefore,"lookups allocate no relationship rows or per-pair tombstones");
  assert.equal(await env.DB.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='pair_versions'").first(),null,"pair_versions storage is removed");
  console.log("Local Worker + isolated D1 integration passed with mocked verified Google identities: 501 read-only lookups, lifecycle nonce CAS, reciprocal accept cleanup, accept/block race, deleted-target block privacy/unblock, invite races, bounded global quota cleanup, missing-block conflict, and deletion/re-setup stale-token rejection.");
} finally {
  await proxy.dispose();
}
