const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8180'
const KEYCLOAK_ADMIN = process.env.KEYCLOAK_ADMIN || 'admin'
const KEYCLOAK_ADMIN_PASSWORD = process.env.KEYCLOAK_ADMIN_PASSWORD || 'local-admin-change-me'
const SENDER_EMAIL = process.env.SENDER_EMAIL?.trim()
const SENDER_PASS = process.env.SENDER_PASS?.trim()
const REQUEST_TIMEOUT_MS = 10_000
const KEYCLOAK_STARTUP_TIMEOUT_MS = 90_000
const RETRY_INTERVAL_MS = 2_000
const SMTP_SETTINGS = {
  auth: 'true',
  from: SENDER_EMAIL,
  fromDisplayName: 'SeekDateSet',
  host: 'smtp.qq.com',
  password: SENDER_PASS,
  port: '465',
  ssl: 'true',
  starttls: 'false',
  user: SENDER_EMAIL,
}

if (!SENDER_EMAIL || !SENDER_PASS) {
  throw new Error('Set SENDER_EMAIL and SENDER_PASS in the root .env file before running this script.')
}

const baseUrl = KEYCLOAK_URL.replace(/\/$/, '')
const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

async function waitForKeycloak() {
  const deadline = Date.now() + KEYCLOAK_STARTUP_TIMEOUT_MS
  const discoveryUrl = `${baseUrl}/realms/master/.well-known/openid-configuration`

  while (Date.now() < deadline) {
    try {
      const response = await fetch(discoveryUrl, { signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) })
      if (response.ok) return
    } catch {
      // Keycloak may still be starting.
    }
    await delay(RETRY_INTERVAL_MS)
  }

  throw new Error('Keycloak did not become ready in time.')
}

async function request(path, { accessToken, method = 'GET', body } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...(accessToken ? { authorization: `Bearer ${accessToken}` } : {}),
      ...(body ? { 'content-type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  })

  if (!response.ok) {
    throw new Error(`Keycloak request failed (${method} ${path}: ${response.status}).`)
  }
  return response
}

async function getAdminAccessToken() {
  const response = await fetch(`${baseUrl}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'admin-cli',
      username: KEYCLOAK_ADMIN,
      password: KEYCLOAK_ADMIN_PASSWORD,
    }),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  })

  if (!response.ok) {
    throw new Error(`Keycloak admin authentication failed (${response.status}); check local admin credentials.`)
  }
  return (await response.json()).access_token
}

async function findAdminUser(accessToken) {
  const query = new URLSearchParams({ username: KEYCLOAK_ADMIN, exact: 'true' })
  const users = await (await request(`/admin/realms/master/users?${query}`, { accessToken })).json()
  const user = users.find(({ username }) => username === KEYCLOAK_ADMIN)
  if (!user?.id) throw new Error('Could not find the Keycloak admin user for the SMTP test.')
  return user
}

async function ensureAdminTestEmailIsAvailable(accessToken, adminUser) {
  const query = new URLSearchParams({ email: SENDER_EMAIL, exact: 'true' })
  const users = await (await request(`/admin/realms/master/users?${query}`, { accessToken })).json()
  if (users.some(({ id }) => id !== adminUser.id)) {
    throw new Error('The sender address belongs to another Keycloak admin user; refusing to alter accounts for the SMTP test.')
  }
}

async function updateAdminUser(accessToken, user, email, emailVerified) {
  await request(`/admin/realms/master/users/${encodeURIComponent(user.id)}`, {
    accessToken,
    method: 'PUT',
    body: { ...user, email, emailVerified },
  })
}

await waitForKeycloak()
const accessToken = await getAdminAccessToken()
const adminUser = await findAdminUser(accessToken)
const originalEmail = adminUser.email ?? null
const originalEmailVerified = adminUser.emailVerified ?? false
const temporaryEmailChange = originalEmail !== SENDER_EMAIL

try {
  if (temporaryEmailChange) {
    await ensureAdminTestEmailIsAvailable(accessToken, adminUser)
    await updateAdminUser(accessToken, adminUser, SENDER_EMAIL, true)
  }

  await request('/admin/realms/olist/testSMTPConnection', {
    accessToken,
    method: 'POST',
    body: SMTP_SETTINGS,
  })

  const realm = await (await request('/admin/realms/olist', { accessToken })).json()
  realm.smtpServer = SMTP_SETTINGS
  await request('/admin/realms/olist', { accessToken, method: 'PUT', body: realm })

  console.info('SMTP test email was accepted; the Olist realm now uses QQ SMTP for verification and password-reset mail.')
} finally {
  if (temporaryEmailChange) {
    await updateAdminUser(accessToken, adminUser, originalEmail, originalEmailVerified)
  }
}
