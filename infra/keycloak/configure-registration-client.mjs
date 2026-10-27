import { randomBytes } from 'node:crypto'

const baseUrl = (process.env.KEYCLOAK_ADMIN_API_URL || 'http://localhost:8180').replace(/\/+$/, '')
const realm = process.env.KEYCLOAK_REALM || 'olist'
const adminUsername = process.env.KEYCLOAK_ADMIN
const adminPassword = process.env.KEYCLOAK_ADMIN_PASSWORD
const clientId = process.env.KEYCLOAK_REGISTRATION_CLIENT_ID || 'olist-registration-service'
const passwordGrantSetting = process.env.KEYCLOAK_ALLOW_PASSWORD_GRANT
const allowPasswordGrant = passwordGrantSetting === undefined || passwordGrantSetting === ''
  ? /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/?$/.test(baseUrl)
  : passwordGrantSetting === 'true'
const requiredRoles = ['manage-users', 'query-users', 'view-users']

if (!adminUsername || !adminPassword) {
  throw new Error('Set KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD before running this script.')
}

async function request(path, options = {}, accessToken) {
  const headers = new Headers(options.headers || {})
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  const response = await fetch(`${baseUrl}${path}`, { ...options, headers })
  const text = await response.text()
  if (!response.ok) {
    throw new Error(`Keycloak request failed (${response.status}) for ${path}.`)
  }
  return text ? JSON.parse(text) : null
}

const tokenBody = new URLSearchParams({
  grant_type: 'password',
  client_id: 'admin-cli',
  username: adminUsername,
  password: adminPassword,
})
const tokenResponse = await request('/realms/master/protocol/openid-connect/token', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: tokenBody,
})
const accessToken = tokenResponse.access_token
const adminPath = `/admin/realms/${encodeURIComponent(realm)}`

const realmRepresentation = await request(adminPath, {}, accessToken)
const needsDefaultLoginTheme = !realmRepresentation.loginTheme || realmRepresentation.loginTheme === 'olist'
if (realmRepresentation.registrationAllowed || realmRepresentation.passwordPolicy !== 'length(10)' || needsDefaultLoginTheme) {
  await request(adminPath, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...realmRepresentation,
      registrationAllowed: false,
      passwordPolicy: 'length(10)',
      ...(needsDefaultLoginTheme ? { loginTheme: 'keycloak.v2' } : {}),
    }),
  }, accessToken)
}

const webClientId = process.env.KEYCLOAK_WEB_CLIENT_ID || 'olist-web'
const webClients = await request(`${adminPath}/clients?clientId=${encodeURIComponent(webClientId)}`, {}, accessToken)
const webClient = webClients[0]
if (!webClient) {
  throw new Error(`Keycloak web client '${webClientId}' was not found.`)
}
const webClientAttributes = {
  ...(webClient.attributes || {}),
  'pkce.code.challenge.method': 'S256',
}
if (!webClient.publicClient
  || !webClient.standardFlowEnabled
  || webClient.implicitFlowEnabled
  || webClient.directAccessGrantsEnabled !== allowPasswordGrant
  || webClient.attributes?.['pkce.code.challenge.method'] !== 'S256') {
  await request(`${adminPath}/clients/${webClient.id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...webClient,
      publicClient: true,
      standardFlowEnabled: true,
      implicitFlowEnabled: false,
      directAccessGrantsEnabled: allowPasswordGrant,
      attributes: webClientAttributes,
    }),
  }, accessToken)
}

const clients = await request(`${adminPath}/clients?clientId=${encodeURIComponent(clientId)}`, {}, accessToken)
let registrationClient = clients[0]
if (!registrationClient) {
  await request(`${adminPath}/clients`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      clientId,
      name: 'SeekDateSet registration service',
      enabled: true,
      protocol: 'openid-connect',
      publicClient: false,
      standardFlowEnabled: false,
      implicitFlowEnabled: false,
      directAccessGrantsEnabled: false,
      serviceAccountsEnabled: true,
      clientAuthenticatorType: 'client-secret',
    }),
  }, accessToken)
  const createdClients = await request(`${adminPath}/clients?clientId=${encodeURIComponent(clientId)}`, {}, accessToken)
  registrationClient = createdClients[0]
} else {
  await request(`${adminPath}/clients/${registrationClient.id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...registrationClient,
      enabled: true,
      protocol: 'openid-connect',
      publicClient: false,
      standardFlowEnabled: false,
      implicitFlowEnabled: false,
      directAccessGrantsEnabled: false,
      serviceAccountsEnabled: true,
      clientAuthenticatorType: 'client-secret',
    }),
  }, accessToken)
}

const serviceAccount = await request(
  `${adminPath}/clients/${registrationClient.id}/service-account-user`,
  {},
  accessToken,
)
const managementClients = await request(`${adminPath}/clients?clientId=realm-management`, {}, accessToken)
const managementClientId = managementClients[0]?.id
if (!serviceAccount?.id || !managementClientId) {
  throw new Error('Could not resolve Keycloak registration service-account roles.')
}

const currentRoles = await request(
  `${adminPath}/users/${serviceAccount.id}/role-mappings/clients/${managementClientId}`,
  {},
  accessToken,
)
const currentRoleNames = new Set(currentRoles.map((role) => role.name))
const missingRoles = []
for (const roleName of requiredRoles) {
  if (currentRoleNames.has(roleName)) continue
  missingRoles.push(await request(
    `${adminPath}/clients/${managementClientId}/roles/${encodeURIComponent(roleName)}`,
    {},
    accessToken,
  ))
}
if (missingRoles.length > 0) {
  await request(
    `${adminPath}/users/${serviceAccount.id}/role-mappings/clients/${managementClientId}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(missingRoles),
    },
    accessToken,
  )
}

const clientSecret = await request(
  `${adminPath}/clients/${registrationClient.id}/client-secret`,
  {},
  accessToken,
)

console.log('Keycloak registration client is ready. Set this value in the root .env file:')
console.log(`KEYCLOAK_REGISTRATION_CLIENT_SECRET=${clientSecret.value}`)
console.log('The service account has only manage-users, query-users, and view-users.')
console.log(`Keycloak web client '${webClientId}' uses Authorization Code + PKCE; password grant ${allowPasswordGrant ? 'enabled for local development' : 'disabled'}.`)
console.log('Generate REGISTRATION_OTP_SECRET separately with at least 32 random bytes.')
