import { reactive } from 'vue'
import Keycloak from 'keycloak-js'

export const AUTH_REQUIRED_EVENT = 'olist:auth-required'

export class AuthenticationRequiredError extends Error {
  constructor() {
    super('请先登录后继续')
    this.name = 'AuthenticationRequiredError'
  }
}

const client = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180',
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'olist',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'olist-web',
})
const keycloakUrl = import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180'
const keycloakRealm = import.meta.env.VITE_KEYCLOAK_REALM || 'olist'
const keycloakClientId = import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'olist-web'
const TOKEN_STORAGE_KEY = 'seekdateset.auth.tokens'

const state = reactive({
  status: 'checking',
  authenticated: false,
  user: null,
})

let initialization

function decodeToken(token) {
  const payload = token.split('.')[1]
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
  const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0))
  return JSON.parse(new TextDecoder().decode(bytes))
}

function readStoredTokens() {
  try {
    const tokens = JSON.parse(window.sessionStorage.getItem(TOKEN_STORAGE_KEY) || 'null')
    if (tokens?.accessToken && tokens?.refreshToken) return tokens
  } catch {
    // Ignore malformed or unavailable browser storage.
  }
  return null
}

function storeTokens(accessToken, refreshToken, idToken) {
  try {
    window.sessionStorage.setItem(TOKEN_STORAGE_KEY, JSON.stringify({
      accessToken,
      refreshToken,
      idToken,
    }))
  } catch {
    // The in-memory session still works when storage is unavailable.
  }
}

function clearStoredTokens() {
  try {
    window.sessionStorage.removeItem(TOKEN_STORAGE_KEY)
  } catch {
    // Nothing to clear.
  }
}

function applyTokens(accessToken, refreshToken, idToken) {
  client.token = accessToken
  client.tokenParsed = decodeToken(accessToken)
  client.refreshToken = refreshToken
  client.refreshTokenParsed = refreshToken ? decodeToken(refreshToken) : undefined
  client.idToken = idToken
  client.idTokenParsed = idToken ? decodeToken(idToken) : undefined
  client.authenticated = true
  client.subject = client.tokenParsed.sub
  client.sessionId = client.tokenParsed.sid
  client.realmAccess = client.tokenParsed.realm_access
  client.resourceAccess = client.tokenParsed.resource_access
  storeTokens(accessToken, refreshToken, idToken)
}

function updateState(authenticated) {
  const claims = client.tokenParsed || {}
  state.authenticated = Boolean(authenticated)
  state.status = state.authenticated ? 'authenticated' : 'anonymous'
  state.user = state.authenticated
    ? {
        name: claims.name || claims.preferred_username || claims.email || '用户',
        email: claims.email || '',
      }
    : null
}

export function initializeAuth() {
  if (!initialization) {
    const storedTokens = readStoredTokens()
    initialization = client
      .init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        scope: 'openid profile email',
        checkLoginIframe: false,
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
        silentCheckSsoFallback: false,
        ...(storedTokens ? {
          token: storedTokens.accessToken,
          refreshToken: storedTokens.refreshToken,
          idToken: storedTokens.idToken,
        } : {}),
      })
      .then((authenticated) => {
        updateState(authenticated)
        if (authenticated) {
          storeTokens(client.token, client.refreshToken, client.idToken)
        } else {
          clearStoredTokens()
        }
        return authenticated
      })
      .catch(() => {
        clearStoredTokens()
        updateState(false)
        return false
      })
  }
  return initialization
}

export async function login(loginHint = '') {
  await initializeAuth()
  return client.login({
    redirectUri: `${window.location.origin}/`,
    scope: 'openid profile email',
    ...(loginHint ? { loginHint } : {}),
  })
}

export async function loginWithPassword(identifier, password) {
  await initializeAuth()

  let response
  try {
    response = await fetch(`${keycloakUrl}/realms/${keycloakRealm}/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      credentials: 'include',
      body: new URLSearchParams({
        grant_type: 'password',
        client_id: keycloakClientId,
        username: identifier,
        password,
        scope: 'openid profile email',
      }),
    })
  } catch {
    throw new Error('无法连接登录服务，请稍后重试。')
  }

  const data = await response.json().catch(() => ({}))
  if (!response.ok || !data.access_token) {
    if (data.error === 'invalid_grant') {
      throw new Error('邮箱或密码不正确。')
    }
    throw new Error('登录服务暂时不可用，请稍后重试。')
  }

  applyTokens(data.access_token, data.refresh_token, data.id_token)
  updateState(true)
  return true
}

export async function logout() {
  clearStoredTokens()
  await client.logout({ redirectUri: window.location.origin })
}

export async function getAccessToken() {
  await initializeAuth()
  if (!client.authenticated) {
    return ''
  }

  try {
    await client.updateToken(30)
    storeTokens(client.token, client.refreshToken, client.idToken)
    updateState(true)
    return client.token || ''
  } catch {
    clearStoredTokens()
    updateState(false)
    window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT))
    return ''
  }
}

export function useAuth() {
  return { state, login, loginWithPassword, logout }
}
