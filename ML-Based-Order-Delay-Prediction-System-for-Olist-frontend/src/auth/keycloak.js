import { reactive } from 'vue'
import Keycloak from 'keycloak-js'

export const AUTH_REQUIRED_EVENT = 'olist:auth-required'

export class AuthenticationRequiredError extends Error {
  constructor() {
    super('请先登录后继续')
    this.name = 'AuthenticationRequiredError'
  }
}

const AUTH_BYPASS = import.meta.env.VITE_AUTH_BYPASS === 'true'
const BYPASS_USER = { name: '本地调试用户', email: 'local@example.com' }

const client = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180',
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'olist',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'olist-web',
})

const state = reactive({
  status: 'checking',
  authenticated: false,
  user: null,
})

let initialization

function enableBypass() {
  state.authenticated = true
  state.status = 'authenticated'
  state.user = BYPASS_USER
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
  if (AUTH_BYPASS) {
    enableBypass()
    return Promise.resolve(true)
  }
  if (!initialization) {
    initialization = client
      .init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        scope: 'openid profile email',
        checkLoginIframe: false,
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
        silentCheckSsoFallback: false,
      })
      .then((authenticated) => {
        updateState(authenticated)
        return authenticated
      })
      .catch(() => {
        updateState(false)
        return false
      })
  }
  return initialization
}

export async function login(loginHint = '') {
  if (AUTH_BYPASS) {
    enableBypass()
    return true
  }
  await initializeAuth()
  return client.login({
    redirectUri: `${window.location.origin}/`,
    scope: 'openid profile email',
    ...(loginHint ? { loginHint } : {}),
  })
}

export async function logout() {
  if (AUTH_BYPASS) {
    state.authenticated = false
    state.status = 'anonymous'
    state.user = null
    return
  }
  await client.logout({ redirectUri: window.location.origin })
}

export async function getAccessToken() {
  if (AUTH_BYPASS) {
    enableBypass()
    return 'local-dev-token'
  }
  await initializeAuth()
  if (!client.authenticated) {
    return ''
  }

  try {
    await client.updateToken(30)
    updateState(true)
    return client.token || ''
  } catch {
    updateState(false)
    window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT))
    return ''
  }
}

export function useAuth() {
  return { state, login, logout }
}
