import {
  AUTH_REQUIRED_EVENT,
  AuthenticationRequiredError,
  getAccessToken,
} from '@/auth/keycloak'

const API_ROOT = import.meta.env.VITE_API_ROOT || '/api/v1'

function authenticationRequired() {
  window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT))
  return new AuthenticationRequiredError()
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = await getAccessToken()
  if (!token) throw authenticationRequired()
  headers.set('Authorization', `Bearer ${token}`)
  if (options.body) headers.set('Content-Type', 'application/json; charset=utf-8')

  let response
  try {
    response = await fetch(`${API_ROOT}${path}`, { ...options, headers })
  } catch {
    throw new Error('无法连接模型服务，请检查服务状态或网关配置')
  }

  const text = await response.text()
  let body = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      body = text
    }
  }
  if (!response.ok) {
    if (response.status === 401) throw authenticationRequired()
    throw new Error(body?.detail || body?.message || `请求失败（HTTP ${response.status}）`)
  }
  return body
}

export function listModelVersions() {
  return request('/models/versions')
}

export function trainModel(payload) {
  return request('/models/training-runs', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function runModelTrial(modelId, payload) {
  return request(`/models/versions/${encodeURIComponent(modelId)}/trials`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function compareModelTrials(payload) {
  return request('/models/trials/compare', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function listModelTrials(modelId) {
  return request(`/models/versions/${encodeURIComponent(modelId)}/trials`)
}

export function getModelTrialComparison(comparisonId) {
  return request(`/models/comparisons/${encodeURIComponent(comparisonId)}`)
}

export function listModelWorkflows() {
  return request('/models/workflows')
}

export function getModelWorkflow(workflowId) {
  return request(`/models/workflows/${encodeURIComponent(workflowId)}`)
}

export function createModelWorkflow(payload) {
  return request('/models/workflows', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateModelWorkflow(workflowId, payload) {
  return request(`/models/workflows/${encodeURIComponent(workflowId)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function duplicateModelWorkflow(workflowId, name) {
  return request(`/models/workflows/${encodeURIComponent(workflowId)}/duplicate`, {
    method: 'POST',
    body: JSON.stringify({ name }),
  })
}
