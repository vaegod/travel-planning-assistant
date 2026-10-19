import test, {beforeEach, afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {auth, clearAuth, ensureAuth, login} from '../src/utils/auth.ts';
import {apiFetch} from '../src/utils/http.ts';
const originalFetch = globalThis.fetch, originalWindow = globalThis.window;
const deferred = () => {let resolve; const promise = new Promise(yes => {resolve = yes;}); return {promise, resolve};};
const json = (data, status = 200) => new Response(JSON.stringify(status === 200 ? {success: true, data} : {success: false, ...data}), {status});
const csrf = () => json({token: 'fixture-token', headerName: 'X-CSRF-TOKEN'});
const newUser = {id: 42, username: 'new_session'};
beforeEach(() => {clearAuth(); auth.ready = false; globalThis.window = new EventTarget(); window.addEventListener('auth-expired', clearAuth);});
afterEach(() => {globalThis.fetch = originalFetch; if (originalWindow === undefined) delete globalThis.window; else globalThis.window = originalWindow; clearAuth();});

test('an old identity HTTP401 cannot sign out a newer successful login', async () => {
  const old = deferred();
  globalThis.fetch = async url => String(url).endsWith('/auth/me') ? old.promise : String(url).endsWith('/auth/csrf') ? csrf() : json(newUser);
  const lookup = ensureAuth();
  await login('new_session', 'fixture-password');
  assert.equal(auth.user.id, 42);
  old.resolve(json({errorCode: 'AUTH_REQUIRED'}, 401)); await lookup;
  assert.equal(auth.user?.id, 42);
});

test('an HTTP401 for the current session still signs out normally', async () => {
  globalThis.fetch = async url => String(url).endsWith('/auth/csrf') ? csrf() : String(url).endsWith('/auth/login') ? json(newUser) : json({errorCode: 'AUTH_REQUIRED'}, 401);
  await login('new_session', 'fixture-password');
  assert.equal((await apiFetch('/api/conversations')).status, 401);
  assert.equal(auth.user, null);
});

test('old session CSRF rejection cannot replay a write with the newer login', async () => {
  const old = deferred(), started = deferred(); let writes = 0;
  globalThis.fetch = async url => {
    if (String(url).endsWith('/auth/csrf')) return csrf();
    if (String(url).endsWith('/auth/login')) return json(newUser);
    writes++; started.resolve(); return writes === 1 ? old.promise : json({saved: true});
  };
  const write = apiFetch('/api/trips', {method: 'POST', body: '{}'}); await started.promise;
  await login('new_session', 'fixture-password');
  old.resolve(json({errorCode: 'CSRF_INVALID'}, 403));
  assert.equal((await write).status, 403); assert.equal(writes, 1);
});

test('a write waiting for old CSRF cannot be sent after logout', async () => {
  const oldToken = deferred(); let writes = 0;
  globalThis.fetch = async url => String(url).endsWith('/auth/csrf') ? oldToken.promise : (writes++, json({saved: true}));
  const write = apiFetch('/api/trips', {method: 'POST', body: '{}'});
  const result = write.then(() => null, error => error);
  clearAuth(); oldToken.resolve(csrf());
  assert.equal((await result)?.code, 'SESSION_CHANGED'); assert.equal(writes, 0);
});
