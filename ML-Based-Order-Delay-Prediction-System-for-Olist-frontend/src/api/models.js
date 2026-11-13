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

export function getTrainingRun(runId) {
  return request(`/models/training-runs/${encodeURIComponent(runId)}`)
}

export function listTrainingRuns(page = 0, size = 20) {
  return request(`/models/training-runs?page=${page}&size=${size}`)
}

export function cancelTrainingRun(runId) {
  return request(`/models/training-runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' })
}

export function getModelVersion(modelId) {
  return request(`/models/versions/${encodeURIComponent(modelId)}`)
}

export function approveModelVersion(modelId) {
  return request(`/models/versions/${encodeURIComponent(modelId)}/approve`, { method: 'POST' })
}

export function deprecateModelVersion(modelId) {
  return request(`/models/versions/${encodeURIComponent(modelId)}/deprecate`, { method: 'POST' })
}

export function getModelManifest(modelId) {
  return request(`/models/versions/${encodeURIComponent(modelId)}/manifest`)
}

export async function downloadModelArtifact(modelId) {
  const token = await getAccessToken()
  if (!token) throw authenticationRequired()
  const response = await fetch(
    `${API_ROOT}/models/versions/${encodeURIComponent(modelId)}/artifact`,
    { headers: { Authorization: `Bearer ${token}` } },
  )
  if (!response.ok) {
    if (response.status === 401) throw authenticationRequired()
    const text = await response.text()
    let message = `下载失败（HTTP ${response.status}）`
    try {
      const body = JSON.parse(text)
      message = body?.detail || body?.message || message
    } catch {
      // Keep the status-based message.
    }
    throw new Error(message)
  }
  return response.blob()
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

export function explainModelSample(modelId, payload) {
  return request(`/models/versions/${encodeURIComponent(modelId)}/explanations`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function submitModelPrediction(payload) {
  return request('/models/predictions', { method: 'POST', body: JSON.stringify(payload) })
}

export function listModelPredictions() {
  return request('/models/predictions')
}

export function getModelPrediction(id) {
  return request(`/models/predictions/${encodeURIComponent(id)}`)
}

export function deleteModelPrediction(id) {
  return request(`/models/predictions/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function clearModelPredictions() {
  return request('/models/predictions', { method: 'DELETE' })
}

export async function downloadModelReport(modelId, payload) {
  const token = await getAccessToken()
  if (!token) throw authenticationRequired()
  let response
  try {
    response = await fetch(
      `${API_ROOT}/models/versions/${encodeURIComponent(modelId)}/reports`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json; charset=utf-8',
        },
        body: JSON.stringify(payload),
      },
    )
  } catch {
    throw new Error('无法连接报告生成服务，请检查模型服务状态')
  }
  if (!response.ok) {
    if (response.status === 401) throw authenticationRequired()
    const text = await response.text()
    let message = `报告生成失败（HTTP ${response.status}）`
    try {
      const body = JSON.parse(text)
      message = body?.detail || body?.message || message
    } catch {
      // Keep the status-based message.
    }
    throw new Error(message)
  }
  const disposition = response.headers.get('Content-Disposition') || ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  const plain = disposition.match(/filename="?([^";]+)"?/i)
  let fileName = `model-report.${payload.format || 'pdf'}`
  if (encoded) {
    try {
      fileName = decodeURIComponent(encoded[1])
    } catch {
      // Keep the fallback name.
    }
  } else if (plain) {
    fileName = plain[1]
  }
  return { blob: await response.blob(), fileName }
}

export function listModelReports(modelId) {
  return request(`/models/versions/${encodeURIComponent(modelId)}/reports`)
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
