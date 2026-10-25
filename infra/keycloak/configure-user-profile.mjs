const baseUrl = (process.env.KEYCLOAK_ADMIN_API_URL || 'http://localhost:8180').replace(/\/+$/, '')
const realm = process.env.KEYCLOAK_REALM || 'olist'
const adminUsername = process.env.KEYCLOAK_ADMIN
const adminPassword = process.env.KEYCLOAK_ADMIN_PASSWORD

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

const tokenResponse = await request('/realms/master/protocol/openid-connect/token', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({
    grant_type: 'password',
    client_id: 'admin-cli',
    username: adminUsername,
    password: adminPassword,
  }),
})
const accessToken = tokenResponse.access_token
const profilePath = `/admin/realms/${encodeURIComponent(realm)}/users/profile`
const profile = await request(profilePath, {}, accessToken)

profile.unmanagedAttributePolicy = 'ENABLED'

for (const attribute of profile.attributes || []) {
  if (attribute.name === 'lastName') {
    delete attribute.required
  }
}

await request(profilePath, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(profile),
}, accessToken)

console.log('Keycloak user profile now allows a blank surname and custom birthday, gender, and picture attributes.')
