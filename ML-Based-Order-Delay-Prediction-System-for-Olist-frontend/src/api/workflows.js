import {
  AUTH_REQUIRED_EVENT,
  AuthenticationRequiredError,
  getAccessToken,
} from '@/auth/keycloak'

const API_ROOT = import.meta.env.VITE_API_ROOT || '/api/v1'

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = await getAccessToken()
  if (!token) {
    window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT))
    throw new AuthenticationRequiredError()
  }
  headers.set('Authorization', `Bearer ${token}`)
  if (options.body) headers.set('Content-Type', 'application/json; charset=utf-8')

  let response
  try {
    response = await fetch(`${API_ROOT}${path}`, { ...options, headers })
  } catch {
    throw new Error('无法连接工作流服务，请检查服务状态或网关配置')
  }

  const text = await response.text()
  const body = text ? (() => {
    try { return JSON.parse(text) } catch { return text }
  })() : null
  if (!response.ok) {
    if (response.status === 401) {
      window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT))
      throw new AuthenticationRequiredError()
    }
    throw new Error(body?.detail || body?.message || `请求失败（HTTP ${response.status}）`)
  }
  return body
}

export function listWorkflows() { return request('/workflows') }
export function getWorkflow(id) { return request(`/workflows/${encodeURIComponent(id)}`) }
export function createWorkflow(payload) {
  return request('/workflows', { method: 'POST', body: JSON.stringify(payload) })
}
export function updateWorkflow(id, payload) {
  return request(`/workflows/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) })
}
export function duplicateWorkflow(id, name) {
  return request(`/workflows/${encodeURIComponent(id)}/duplicate`, {
    method: 'POST',
    body: JSON.stringify({ name }),
  })
}
export function deleteWorkflow(id) {
  return request(`/workflows/${encodeURIComponent(id)}`, { method: 'DELETE' })
}
export function listWorkflowRuns(id) { return request(`/workflows/${encodeURIComponent(id)}/runs`) }
export function listRecentWorkflowRuns(limit = 50) {
  const boundedLimit = Math.max(1, Math.min(50, Number(limit) || 50))
  return request(`/workflow-runs?limit=${boundedLimit}`)
}
export function startWorkflowRun(id, expectedRevision) {
  return request(`/workflows/${encodeURIComponent(id)}/runs`, {
    method: 'POST',
    body: JSON.stringify({ expectedRevision }),
  })
}
export function getWorkflowRun(id) { return request(`/workflow-runs/${encodeURIComponent(id)}`) }
export function cancelWorkflowRun(id) {
  return request(`/workflow-runs/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
}
export function retryWorkflowRun(id) {
  return request(`/workflow-runs/${encodeURIComponent(id)}/retry`, { method: 'POST' })
}
