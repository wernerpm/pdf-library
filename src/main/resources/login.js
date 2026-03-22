'use strict';

const statusEl = document.getElementById('status');
function showStatus(msg, type = '') {
  statusEl.textContent = msg;
  statusEl.className = 'status' + (type ? ' ' + type : '');
}

// ---- Base64url helpers ----
function bufToB64url(buf) {
  const bytes = new Uint8Array(buf);
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

function b64urlToBuf(s) {
  s = s.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  const bin = atob(s);
  const buf = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
  return buf.buffer;
}

// Convert all known binary fields in creation options from base64url to ArrayBuffer
function prepareCreationOptions(opts) {
  opts.challenge = b64urlToBuf(opts.challenge);
  opts.user.id = b64urlToBuf(opts.user.id);
  if (opts.excludeCredentials) {
    opts.excludeCredentials = opts.excludeCredentials.map(c => ({ ...c, id: b64urlToBuf(c.id) }));
  }
  return opts;
}

// Convert all known binary fields in request options from base64url to ArrayBuffer
function prepareRequestOptions(opts) {
  opts.challenge = b64urlToBuf(opts.challenge);
  if (opts.allowCredentials) {
    opts.allowCredentials = opts.allowCredentials.map(c => ({ ...c, id: b64urlToBuf(c.id) }));
  }
  return opts;
}

function serializeAssertion(cred) {
  return {
    id: cred.id,
    rawId: bufToB64url(cred.rawId),
    type: cred.type,
    response: {
      authenticatorData: bufToB64url(cred.response.authenticatorData),
      clientDataJSON: bufToB64url(cred.response.clientDataJSON),
      signature: bufToB64url(cred.response.signature),
      userHandle: cred.response.userHandle ? bufToB64url(cred.response.userHandle) : null,
    },
  };
}

function serializeAttestation(cred) {
  return {
    id: cred.id,
    rawId: bufToB64url(cred.rawId),
    type: cred.type,
    response: {
      attestationObject: bufToB64url(cred.response.attestationObject),
      clientDataJSON: bufToB64url(cred.response.clientDataJSON),
    },
  };
}

// ---- Login ----
document.getElementById('btn-login').addEventListener('click', async () => {
  const btn = document.getElementById('btn-login');
  btn.disabled = true;
  showStatus('Waiting for passkey\u2026');
  try {
    const startRes = await fetch('/api/auth/login/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: '' }),
    });
    if (!startRes.ok) throw new Error('Login start failed');
    const startData = await startRes.json();
    const { sessionId, options } = startData.data;

    const pkOptions = prepareRequestOptions(options.publicKey);
    const credential = await navigator.credentials.get({ publicKey: pkOptions });
    if (!credential) throw new Error('No credential returned');

    const finishRes = await fetch('/api/auth/login/finish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, credential: serializeAssertion(credential) }),
    });
    if (!finishRes.ok) {
      const err = await finishRes.json().catch(() => ({}));
      throw new Error(err.error || 'Login failed');
    }
    const finishData = await finishRes.json();
    localStorage.setItem('jwt', finishData.data.token);
    showStatus('Signed in! Redirecting\u2026', 'success');
    window.location.href = '/';
  } catch (e) {
    showStatus(e.message || 'Login failed', 'error');
    btn.disabled = false;
  }
});

// ---- Registration ----
document.getElementById('btn-register').addEventListener('click', async () => {
  const btn = document.getElementById('btn-register');
  const username = document.getElementById('reg-username').value.trim();
  const displayName = document.getElementById('reg-displayname').value.trim();
  if (!username) { showStatus('Username is required', 'error'); return; }

  btn.disabled = true;
  showStatus('Starting registration\u2026');
  try {
    const token = localStorage.getItem('jwt');
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;

    const startRes = await fetch('/api/auth/register/start', {
      method: 'POST',
      headers,
      body: JSON.stringify({ username, displayName: displayName || username }),
    });
    if (!startRes.ok) {
      const err = await startRes.json().catch(() => ({}));
      throw new Error(err.error || 'Registration start failed');
    }
    const startData = await startRes.json();
    const { sessionId, options } = startData.data;

    const pkOptions = prepareCreationOptions(options.publicKey);
    const credential = await navigator.credentials.create({ publicKey: pkOptions });
    if (!credential) throw new Error('No credential created');

    const finishRes = await fetch('/api/auth/register/finish', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        sessionId,
        credential: serializeAttestation(credential),
        username,
        displayName: displayName || username,
      }),
    });
    if (!finishRes.ok) {
      const err = await finishRes.json().catch(() => ({}));
      throw new Error(err.error || 'Registration failed');
    }
    showStatus('Device registered! You can now sign in.', 'success');
  } catch (e) {
    showStatus(e.message || 'Registration failed', 'error');
  } finally {
    btn.disabled = false;
  }
});
