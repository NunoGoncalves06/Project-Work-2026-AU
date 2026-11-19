// Cognito hosted login: authorization code + PKCE, public client (no secret). See docs/admin-dashboard.md (Cognito section).
// Tokens live in sessionStorage only: gone when the tab closes, no cookies, so nothing to forge.

const TOKENS = 'au-admin-tokens';
const PENDING = 'au-admin-login';
const REFRESH_EARLY_MS = 60_000; // refresh a minute before the access token expires

let config = null;
let refreshing = null;

export class LoginRequired extends Error {}

/** {mode: cognito|off|unconfigured, region, userPoolId, clientId, domain} */
export async function loadConfig() {
  const res = await fetch('/api/auth/config', { cache: 'no-store' });
  if (!res.ok) throw new Error('The server did not answer (' + res.status + ').');
  config = await res.json();
  return config;
}

const redirectUri = () => location.origin + '/admin/';
// config.domain is the host only (AdminAuth refuses a scheme or path)
const hostedUi = () => 'https://' + config.domain;

function base64url(bytes) {
  let s = '';
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
const random = n => base64url(crypto.getRandomValues(new Uint8Array(n)));

const readTokens = () => {
  try { return JSON.parse(sessionStorage.getItem(TOKENS)); } catch { return null; }
};
const saveTokens = t => sessionStorage.setItem(TOKENS, JSON.stringify(t));
export const clearTokens = () => sessionStorage.removeItem(TOKENS);
export const hasTokens = () => readTokens() !== null;

export async function startLogin() {
  const verifier = random(32);
  const state = random(16);
  // keep where the user was, to come back to it after the hosted page
  sessionStorage.setItem(PENDING, JSON.stringify({ verifier, state, hash: location.hash }));
  const challenge = base64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  const q = new URLSearchParams({
    response_type: 'code',
    client_id: config.clientId,
    redirect_uri: redirectUri(),
    scope: 'openid',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });
  location.assign(hostedUi() + '/oauth2/authorize?' + q);
}

async function tokenRequest(fields) {
  const res = await fetch(hostedUi() + '/oauth2/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: config.clientId, ...fields }),
  });
  if (!res.ok) throw new Error('The login service refused the request (' + res.status + ').');
  return res.json();
}

function store(json, oldRefresh) {
  saveTokens({
    access: json.access_token,
    // Cognito does not send a new refresh token on refresh
    refresh: json.refresh_token || oldRefresh,
    expiresAt: Date.now() + json.expires_in * 1000,
  });
}

/** Finishes a login if the URL holds ?code= or ?error=. Throws with a readable message on failure. */
export async function handleRedirect() {
  const params = new URLSearchParams(location.search);
  if (!params.has('code') && !params.has('error')) return;
  let pending = null;
  try { pending = JSON.parse(sessionStorage.getItem(PENDING)); } catch { /* treated as missing */ }
  sessionStorage.removeItem(PENDING);
  // drop the code from the address bar and history straight away
  history.replaceState(null, '', '/admin/' + (pending?.hash || ''));
  if (params.has('error')) {
    throw new Error('Login failed: ' + (params.get('error_description') || params.get('error')));
  }
  if (!pending || params.get('state') !== pending.state) {
    throw new Error('Login could not be checked (it was started in another tab or has expired). Sign in again.');
  }
  store(await tokenRequest({
    grant_type: 'authorization_code',
    code: params.get('code'),
    redirect_uri: redirectUri(),
    code_verifier: pending.verifier,
  }));
}

/** The access token, refreshed first when it is about to expire; null when there is none (or login is off). */
export async function accessToken() {
  if (config?.mode !== 'cognito') return null;
  const t = readTokens();
  if (!t) return null;
  if (Date.now() < t.expiresAt - REFRESH_EARLY_MS) return t.access;
  if (!t.refresh) return null;
  // one refresh at a time, even when several requests notice the expiry together
  refreshing ??= tokenRequest({ grant_type: 'refresh_token', refresh_token: t.refresh })
    .then(json => { store(json, t.refresh); return json.access_token; })
    .catch(() => { clearTokens(); return null; })
    .finally(() => { refreshing = null; });
  return refreshing;
}

export function logout() {
  clearTokens();
  if (config?.mode !== 'cognito') { location.reload(); return; }
  const q = new URLSearchParams({ client_id: config.clientId, logout_uri: redirectUri() });
  location.assign(hostedUi() + '/logout?' + q);
}

/**
 * fetch for /api/admin/**: adds the Bearer token, JSON-encodes `json`.
 * A 401 clears the tokens and throws LoginRequired; other statuses are returned for the caller to explain.
 */
export async function api(path, { method = 'GET', json } = {}) {
  const headers = {};
  const token = await accessToken();
  if (config?.mode === 'cognito' && !token) throw new LoginRequired();
  if (token) headers.Authorization = 'Bearer ' + token;
  if (json !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, {
    method,
    headers,
    body: json === undefined ? undefined : JSON.stringify(json),
    cache: 'no-store',
  });
  if (res.status === 401) {
    clearTokens();
    throw new LoginRequired();
  }
  return res;
}
