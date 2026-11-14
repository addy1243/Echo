import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import worker from "../src/worker.js";

const project="echo-social-test-261113", uid="synthetic-user-1";
const key=await webcrypto.subtle.generateKey({name:"RSASSA-PKCS1-v1_5",modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:"SHA-256"},true,["sign","verify"]);
const jwk=await webcrypto.subtle.exportKey("jwk",key.publicKey); jwk.kid="test-key";
let account={localId:uid,emailVerified:true,providerUserInfo:[{providerId:"google.com"}]};
let lookupFailure=false, quotaExhausted=false, ipAllowed=true, certFetches=0, lookupFetches=0, rateKeys=[];
globalThis.fetch=async url=>{
  if(String(url).includes("service_accounts/v1/jwk")) { certFetches++; return Response.json({keys:[jwk]},{headers:{"cache-control":"max-age=3600"}}); }
  if(String(url).includes("accounts:lookup")) { lookupFetches++; return lookupFailure?new Response("",{status:503}):Response.json({users:[account]}); }
  throw new Error("unexpected outbound request");
};
class EmptyDb {
  prepare() { return {bind(){return this;},async first(){return null;},async run(){return {meta:{changes:0}};},async all(){return {results:[]};}}; }
  async batch(statements){return statements.map((_,index)=>({meta:{changes:index===statements.length-1&&!quotaExhausted?1:0}}));}
}
const env={DB:new EmptyDb(),FIREBASE_PROJECT_ID:project,FIREBASE_API_KEY:"test-key",IP_RATE_LIMITER:{async limit({key}){rateKeys.push(key);return {success:ipAllowed};}}};
const b64=value=>Buffer.from(value).toString("base64url");
async function token(overrides={},signingKey=key.privateKey,kid="test-key"){
  const now=Math.floor(Date.now()/1000),header={alg:"RS256",kid,typ:"JWT"},claims={aud:project,iss:`https://securetoken.google.com/${project}`,sub:uid,auth_time:now,iat:now,exp:now+3600,email_verified:true,firebase:{sign_in_provider:"google.com"},...overrides};
  const signingInput=`${b64(JSON.stringify(header))}.${b64(JSON.stringify(claims))}`;
  const signature=await webcrypto.subtle.sign("RSASSA-PKCS1-v1_5",signingKey,new TextEncoder().encode(signingInput));
  return `${signingInput}.${b64(signature)}`;
}
const send=(path,auth,init={},target=worker)=>target.fetch(new Request(`https://social.test${path}`,{...init,headers:{"cf-connecting-ip":"198.51.100.10",...(init.headers||{}),...(auth?{authorization:`Bearer ${auth}`}:{})}}),env);

test("accepts a verified test-project identity and keeps setup private",async()=>{
  account={localId:uid,emailVerified:true,providerUserInfo:[{providerId:"google.com"}]};
  const response=await send("/v1/me",await token());
  assert.equal(response.status,200);
  assert.deepEqual(await response.json(),{setupComplete:false,setupEpoch:0});
});

test("requires an explicit valid Firebase project and does not refresh for unknown keys",async()=>{
  const auth=await token();
  assert.equal((await worker.fetch(new Request("https://social.test/v1/me",{headers:{"cf-connecting-ip":"198.51.100.10",authorization:`Bearer ${auth}`}}),{...env,FIREBASE_PROJECT_ID:undefined})).status,503);
  assert.equal((await worker.fetch(new Request("https://social.test/v1/me",{headers:{"cf-connecting-ip":"198.51.100.10",authorization:`Bearer ${auth}`}}),{...env,FIREBASE_PROJECT_ID:"bad/project"})).status,503);
  await send("/v1/me",auth);
  const before=certFetches, unknown=await token({},key.privateKey,"attacker-key");
  assert.equal((await send("/v1/me",unknown)).status,401);
  assert.equal((await send("/v1/me",unknown)).status,401);
  assert.equal((await send("/v1/me",await token({},key.privateKey,"another-attacker-key"))).status,401);
  assert.equal(certFetches,before,"unknown kid values never force a key refresh");
});

test("caps JWK cache freshness at five minutes",async()=>{
  const expiringWorker=(await import("../src/worker.js?expiry-cache-test")).default,realNow=Date.now,start=realNow();
  const first=await send("/v1/me",await token(),{},expiringWorker),before=certFetches;
  assert.equal(first.status,200);
  try {
    Date.now=()=>start+301_000;
    assert.equal((await send("/v1/me",await token(),{},expiringWorker)).status,200);
    assert.equal(certFetches,before+1,"expired JWK cache refreshes after the five-minute cap");
  } finally { Date.now=realNow; }
});

test("rejects missing, wrong-audience, unverified and invalid-signature tokens",async()=>{
  assert.equal((await send("/v1/me")).status,401);
  assert.equal((await send("/v1/me",await token({aud:"another-project"}))).status,401);
  assert.equal((await send("/v1/me",await token({email_verified:false}))).status,401);
  assert.equal((await send("/v1/me",await token({firebase:{sign_in_provider:"password"}}))).status,401);
  const other=await webcrypto.subtle.generateKey({name:"RSASSA-PKCS1-v1_5",modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:"SHA-256"},true,["sign","verify"]);
  assert.equal((await send("/v1/me",await token({},other.privateKey))).status,401);
});

test("requires both a Google sign-in token and a linked Google provider",async()=>{
  const before=lookupFetches;
  assert.equal((await send("/v1/me",await token({firebase:{sign_in_provider:"password"}}))).status,401);
  assert.equal(lookupFetches,before,"non-Google sign-in is rejected before account lookup");
  account={localId:uid,emailVerified:true,providerUserInfo:[{providerId:"password"}]};
  assert.equal((await send("/v1/me",await token())).status,401,"Google token requires a linked Google account");
  account={localId:uid,emailVerified:true,providerUserInfo:[{providerId:"google.com"}]};
});

test("fails closed on IP limits and applies the strict account quota before Firebase lookup",async()=>{
  const auth=await token(),before=lookupFetches,keysBefore=rateKeys.length;
  ipAllowed=false;
  assert.equal((await send("/v1/me",auth)).status,429);
  assert.equal(lookupFetches,before,"IP limit blocks Firebase lookup");
  ipAllowed=true;
  const noIp=new Request("https://social.test/v1/me",{headers:{authorization:`Bearer ${auth}`,"x-forwarded-for":"203.0.113.1"}});
  assert.equal((await worker.fetch(noIp,env)).status,503,"untrusted forwarded IP is not a fallback");
  assert.equal((await worker.fetch(new Request("https://social.test/v1/me",{headers:{"cf-connecting-ip":"198.51.100.10",authorization:`Bearer ${auth}`}}),{...env,IP_RATE_LIMITER:undefined})).status,503,"missing limiter fails closed");
  quotaExhausted=true;
  assert.equal((await send("/v1/me",auth)).status,429);
  assert.equal(lookupFetches,before,"account quota blocks Firebase lookup");
  quotaExhausted=false;
  const options=await worker.fetch(new Request("https://social.test/v1/me",{method:"OPTIONS"}),{DB:env.DB});
  assert.equal(options.status,204,"CORS preflight stays available without auth or rate bindings");
  assert.equal(rateKeys.length,keysBefore+2,"only requests with Cloudflare IP and a configured limiter reach it");
});

test("fails closed for disabled, unverified, revoked identities and lookup outages",async()=>{
  account={localId:uid,emailVerified:true,disabled:true,providerUserInfo:[{providerId:"google.com"}]};
  assert.equal((await send("/v1/me",await token())).status,401);
  account={localId:uid,emailVerified:false,providerUserInfo:[{providerId:"google.com"}]};
  assert.equal((await send("/v1/me",await token())).status,401);
  const revoked=await token(); account={localId:uid,emailVerified:true,providerUserInfo:[{providerId:"google.com"}],validSince:String(Math.floor(Date.now()/1000)+1)};
  assert.equal((await send("/v1/me",revoked)).status,401);
  account={localId:uid,emailVerified:true,providerUserInfo:[{providerId:"google.com"}]}; lookupFailure=true;
  assert.equal((await send("/v1/me",await token())).status,503);
  lookupFailure=false;
});

test("caps streamed JSON bodies and requires profile setup before friend reads",async()=>{
  const auth=await token();
  const oversized=await send("/v1/me",auth,{method:"PUT",headers:{"content-type":"application/json"},body:JSON.stringify({payload:"x".repeat(9000)})});
  assert.equal(oversized.status,413);
  assert.equal((await send("/v1/friends",auth)).status,409);
});
