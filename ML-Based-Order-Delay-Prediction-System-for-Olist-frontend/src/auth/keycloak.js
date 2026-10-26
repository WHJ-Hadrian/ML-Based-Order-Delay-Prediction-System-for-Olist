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

const state = reactive({
  status: 'checking',
  authenticated: false,
  user: null,
})

let initialization

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
  await initializeAuth()
  return client.login({
    redirectUri: `${window.location.origin}/`,
    scope: 'openid profile email',
    ...(loginHint ? { loginHint } : {}),
  })
}

export async function logout() {
  await client.logout({ redirectUri: window.location.origin })
}

export async function getAccessToken() {
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
