const status = document.querySelector('#status');
const login = document.querySelector('#login');
const logout = document.querySelector('#logout');
const reload = document.querySelector('#reload');
const profile = document.querySelector('#profile');
let csrfToken = '';

function signedOut() {
  csrfToken = '';
  status.textContent = 'Your session has ended. Please sign in.';
  login.hidden = false;
  logout.hidden = true;
  reload.hidden = true;
  profile.hidden = true;
  profile.replaceChildren();
}

async function loadProfile() {
  const response = await fetch('/api/profile');
  if (response.status === 401) return signedOut();
  if (!response.ok) throw new Error('The identity provider could not return your profile. Please try again.');
  profile.replaceChildren();
  for (const [key, value] of Object.entries(await response.json())) {
    const label = document.createElement('dt');
    const field = document.createElement('dd');
    label.textContent = key;
    field.textContent = String(value);
    profile.append(label, field);
  }
  profile.hidden = false;
  status.textContent = 'You are signed in. Your profile is up to date.';
}

async function loadSession() {
  const response = await fetch('/api/session');
  if (response.status === 401) {
    signedOut();
    status.textContent = 'You are not signed in.';
    return;
  }
  if (!response.ok) throw new Error('Could not load your session. Please reload to try again.');
  const session = await response.json();
  csrfToken = session.csrfToken;
  logout.hidden = false;
  reload.hidden = false;
  await loadProfile();
}

reload.addEventListener('click', async () => {
  reload.disabled = true;
  try {
    await loadProfile();
  } catch (error) {
    status.textContent = error.message;
  } finally {
    reload.disabled = false;
  }
});

logout.addEventListener('click', async () => {
  logout.disabled = true;
  try {
    const response = await fetch('/auth/logout', {
      method: 'POST',
      headers: { 'X-CSRF-Token': csrfToken }
    });
    if (!response.ok && response.status !== 401) throw new Error('Could not sign out. Please try again.');
    window.location.assign('/');
  } catch (error) {
    status.textContent = error.message;
    logout.disabled = false;
  }
});

loadSession().catch(error => { status.textContent = error.message; });
