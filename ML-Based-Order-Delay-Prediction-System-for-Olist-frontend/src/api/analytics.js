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
    throw new Error('无法连接分析服务，请检查 analytics-query-service 是否启动')
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

export function getAnalyticsCapabilities(datasetVersionId, tableId) {
  return request(
    `/analytics/capabilities?datasetVersionId=${encodeURIComponent(datasetVersionId)}`
    + `&tableId=${encodeURIComponent(tableId)}`,
  )
}

export function queryAnalytics(payload) {
  return request('/analytics/query', { method: 'POST', body: JSON.stringify(payload) })
}

export function validateAnalytics(payload) {
  return request('/analytics/validate', { method: 'POST', body: JSON.stringify(payload) })
}

export function listAnalyticsValues(payload) {
  return request('/analytics/values', { method: 'POST', body: JSON.stringify(payload) })
}

export function listAnalysisPlans() {
  return request('/analytics/plans')
}

export function createAnalysisPlan(payload) {
  return request('/analytics/plans', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateAnalysisPlan(id, payload) {
  return request(`/analytics/plans/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function deleteAnalysisPlan(id) {
  return request(`/analytics/plans/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function runAnalysisPlan(id) {
  return request(`/analytics/plans/${encodeURIComponent(id)}/run`, { method: 'POST' })
}

export function listAnalysisRuns(planId) {
  const suffix = planId ? `?planId=${encodeURIComponent(planId)}` : ''
  return request(`/analytics/runs${suffix}`)
}

export function listAnalysisTemplates() {
  return request('/analytics/templates')
}

export function copyAnalysisTemplate(key, datasetVersionId, tableId) {
  const query = datasetVersionId && tableId
    ? `?datasetVersionId=${encodeURIComponent(datasetVersionId)}&tableId=${encodeURIComponent(tableId)}`
    : ''
  return request(`/analytics/templates/${encodeURIComponent(key)}/copy${query}`, { method: 'POST' })
}

export async function downloadAnalyticsReport(payload, format) {
  const headers = new Headers({ 'Content-Type': 'application/json; charset=utf-8' })
  const token = await getAccessToken()
  if (!token) throw authenticationRequired()
  headers.set('Authorization', `Bearer ${token}`)
  let response
  try {
    response = await fetch(`${API_ROOT}/analytics/report?format=${encodeURIComponent(format)}`, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
    })
  } catch {
    throw new Error('无法连接分析服务')
  }
  if (!response.ok) {
    if (response.status === 401) throw authenticationRequired()
    let detail = `报告生成失败（HTTP ${response.status}）`
    try {
      const body = await response.json()
      detail = body.detail || body.message || detail
    } catch {
      /* 忽略非 JSON 响应 */
    }
    throw new Error(detail)
  }
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') || ''
  const match = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  const filename = match ? decodeURIComponent(match[1]) : `分析报告.${format}`
  return { blob, filename }
}
