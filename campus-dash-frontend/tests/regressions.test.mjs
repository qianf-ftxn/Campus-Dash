import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const compiled = new Map();
async function source(name) {
  if (!compiled.has(name)) compiled.set(name, (await build({ entryPoints: [`src/${name}.ts`], bundle: true, platform: 'node', format: 'esm', write: false })).outputFiles[0].text);
  return import(`data:text/javascript;base64,${Buffer.from(compiled.get(name)).toString('base64')}#${Math.random()}`);
}
function browser(t, values = { dash_token: 'old', dash_user: '1001', dash_role: 'PUBLISHER' }) {
  const original = Object.fromEntries(['localStorage','window','location','fetch','WebSocket'].map(k => [k, globalThis[k]]));
  const storage = new Map(Object.entries(values));
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (k,v) => storage.set(k,String(v)), removeItem: key => storage.delete(key) };
  globalThis.window = new EventTarget();
  globalThis.location = { protocol: 'http:', host: 'localhost:5273', hash: '' };
  t.after(() => { for (const [k,v] of Object.entries(original)) if (v === undefined) delete globalThis[k]; else globalThis[k] = v; });
  return storage;
}
const ok = data => new Response(JSON.stringify({code:'OK',message:'成功',data}));
const fail = (code, status = 200) => new Response(JSON.stringify({code,message:'失败',data:null}), {status});
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return {promise,resolve}; };

test('role routing canonicalizes query strings and blocks other workspaces', async () => {
  const {resolveRoute} = await source('workspace');
  assert.equal(resolveRoute('/user/home?from=notice','PUBLISHER'),'/user/home');
  assert.equal(resolveRoute('/user/orders?status=x','PUBLISHER'),'/user/orders');
  assert.equal(resolveRoute('/publish?type=BUY','PUBLISHER'),'/user/publish?type=BUY');
  assert.equal(resolveRoute('/user/publish?type=bad','PUBLISHER'),'/user/publish');
  assert.equal(resolveRoute('/detail/123?source=wallet','RUNNER'),'/detail/123');
  assert.equal(resolveRoute('/user/publish?type=BUY','RUNNER'),'/runner/hall');
  assert.equal(resolveRoute('/runner/hall','ARBITRATOR'),'/admin/disputes');
});
test('corrupted or incomplete stored sessions do not crash or enter the workspace', async t => {
  browser(t, {dash_token:'token',dash_user:'{broken'});
  const api = await source('api'); assert.equal(api.getUserId(),null); assert.equal(api.getToken(),null);
});
test('missing access token clears an orphaned user identity', async t => {
  browser(t, {dash_user:'1001',dash_role:'PUBLISHER'});
  assert.equal((await source('api')).getUserId(),null);
});
test('business UNAUTHORIZED clears only the matching session', async t => {
  browser(t); globalThis.fetch = async () => fail('UNAUTHORIZED');
  const {api,getToken} = await source('api'); let events = 0;
  window.addEventListener('dash:unauthorized', () => events++);
  await assert.rejects(api.wallet(), e => e.code === 'UNAUTHORIZED');
  assert.equal(getToken(),null); assert.equal(events,1);
});
test('an old request returning 401 cannot log out a newly authenticated account', async t => {
  browser(t); const old = deferred();
  globalThis.fetch = async path => path === '/api/auth/login' ? ok({token:'new',userId:'2001',role:'RUNNER'}) : old.promise;
  const app = await source('api'); const wallet = app.api.wallet(); const rejection = assert.rejects(wallet, e => e.code === 'UNAUTHORIZED');
  await app.login(2001); old.resolve(fail('UNAUTHORIZED',401)); await rejection;
  assert.equal(app.getToken(),'new'); assert.equal(app.getUserId(),2001); assert.equal(app.getRole(),'RUNNER');
});
test('malformed gateway responses are readable errors and retain the current session', async t => {
  browser(t); globalThis.fetch = async () => new Response('<html>Bad Gateway</html>',{status:502});
  const app = await source('api'); await assert.rejects(app.api.wallet(), e => e.code === 'INVALID_RESPONSE');
  assert.equal(app.getToken(),'old');
});
test('login does not accept an invalid role from a malformed response', async t => {
  browser(t,{}); globalThis.fetch = async () => ok({token:'x',userId:1001,role:'UNKNOWN'});
  const app = await source('api'); await assert.rejects(app.login(1001), e => e.code === 'INVALID_RESPONSE'); assert.equal(app.getToken(),null);
});
test('logout revokes the previous server token and clears local identity', async t => {
  browser(t); const calls = []; globalThis.fetch = async (...args) => { calls.push(args); return ok(null); };
  const app = await source('api'); app.logout(); assert.equal(app.getToken(),null);
  assert.equal(calls[0][0],'/api/auth/logout'); assert.equal(calls[0][1].headers.Authorization,'Bearer old');
});
test('pagination refresh preserves all loaded pages and suppresses overlapping requests', async () => {
  const {PagedCollection} = await source('paged'); let count = 0;
  const collection = new PagedCollection(async page => { count++; return Array.from({length:2},(_,i) => ({id:`${page*2+i}`})); }, x => x.id, () => {},2);
  await collection.refresh(); await Promise.all([collection.loadMore(),collection.loadMore()]);
  assert.equal(count,2); assert.equal(collection.snapshot.items.length,4);
  await collection.refresh(); assert.equal(count,4); assert.equal(collection.snapshot.items.length,4);
  collection.dispose();
});
test('failed append can be retried without skipping a page or erasing rows', async () => {
  const {PagedCollection} = await source('paged'); let failNext = true;
  const collection = new PagedCollection(async page => { if(page === 1 && failNext) { failNext=false; throw Error('offline'); } return [{id:String(page)}]; }, x=>x.id,()=>{},1);
  await collection.refresh(); await collection.loadMore(); assert.equal(collection.pages,1); assert.equal(collection.snapshot.items.length,1);
  await collection.loadMore(); assert.equal(collection.pages,2); assert.deepEqual(collection.snapshot.items.map(x=>x.id),['0','1']); collection.dispose();
});
test('disposed requests never commit stale rows into a different filter', async () => {
  const {PagedCollection} = await source('paged'); const pending=deferred(); let updates=0;
  const collection = new PagedCollection(()=>pending.promise,x=>x.id,()=>updates++);
  const flight=collection.refresh(); collection.dispose(); pending.resolve([{id:'old'}]); await flight;
  assert.equal(updates,1); assert.deepEqual(collection.snapshot.items,[]);
});
test('lost order visibility removes cached rows', async () => {
  const {PagedCollection} = await source('paged'); let denied=false;
  const collection=new PagedCollection(async()=>{ if(denied)throw Object.assign(Error('forbidden'),{code:'FORBIDDEN'}); return [{id:'1'}]; },x=>x.id,()=>{});
  await collection.refresh(); denied=true; await collection.refresh(); assert.equal(collection.snapshot.items.length,0); collection.dispose();
});
test('activity recovery retains the exact original payload and supports legacy request IDs', async () => {
  const {readPendingRush,canResubmit}=await source('rushPending'); const original={requestId:'rush:12:abcdefgh',campaignId:'12',title:'取快递',rewardCents:1234};
  const recovered=readPendingRush(JSON.stringify(original)); assert.deepEqual(recovered,original); assert.equal(canResubmit(recovered),true);
  assert.deepEqual(readPendingRush('rush:12:abcdefgh'),{requestId:'rush:12:abcdefgh'});
  assert.equal(readPendingRush('{bad json'),null);
  assert.equal(readPendingRush('{}'),null);
  assert.equal(readPendingRush('[]'),null);
  assert.equal(canResubmit(readPendingRush(JSON.stringify({requestId:'rush:12:abcdefgh',rewardCents:-1}))),false);
});
test('WebSocket uses the same origin and immediately switches to fallback on disconnect', async t => {
  browser(t); const instances=[]; let timer=0;
  Object.assign(window,{setTimeout:()=>++timer,setInterval:()=>++timer});
  class Socket { static OPEN=1; readyState=1; constructor(url){this.url=url;instances.push(this);} close(){this.onclose?.();} send(){} }
  globalThis.WebSocket=Socket;
  const ws=await source('ws'); t.after(()=>ws.disconnectWs());
  assert.equal(ws.isWsAvailable(),false); ws.connectWs(); ws.connectWs(); assert.equal(instances.length,1);
  assert.match(instances[0].url,/^ws:\/\/localhost:5273\/ws\?token=/);
  instances[0].onopen(); assert.equal(ws.isWsAvailable(),true);
  instances[0].onclose(); assert.equal(ws.isWsAvailable(),false); ws.disconnectWs();
});
