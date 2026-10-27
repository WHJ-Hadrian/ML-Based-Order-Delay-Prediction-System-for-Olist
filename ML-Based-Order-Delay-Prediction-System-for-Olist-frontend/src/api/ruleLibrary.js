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

function extractError(body, fallback) {
  if (body && typeof body === 'object') {
    return body.detail || body.title || body.message || fallback
  }
  return fallback
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = await getAccessToken()
  if (!token) {
    throw authenticationRequired()
  }
  headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json; charset=utf-8')
  }

  let response
  try {
    response = await fetch(`${API_ROOT}${path}`, { ...options, headers })
  } catch {
    throw new Error('无法连接后端服务，请检查服务状态或网关配置')
  }

  let body = null
  const text = await response.text()
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      body = text
    }
  }

  if (!response.ok) {
    if (response.status === 401) {
      throw authenticationRequired()
    }
    throw new Error(extractError(body, `请求失败（HTTP ${response.status}）`))
  }
  return body
}

function pageParams({ page = 0, size = 20, keyword = '', status = '' } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (keyword.trim()) {
    params.set('keyword', keyword.trim())
  }
  if (status) {
    params.set('status', status)
  }
  return params
}

export function listFields({ page = 0, size = 100, keyword = '', status = '', semanticRole = '' } = {}) {
  const params = pageParams({ page, size, keyword, status })
  if (semanticRole) {
    params.set('semanticRole', semanticRole)
  }
  return request(`/fields?${params.toString()}`)
}

export function getField(id) {
  return request(`/fields/${encodeURIComponent(id)}`)
}

export function updateField(id, payload) {
  return request(`/fields/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function importFields(datasetVersionId, { tableIds, columnIds } = {}) {
  const payload = { datasetVersionId }
  if (Array.isArray(tableIds) && tableIds.length) {
    payload.tableIds = tableIds
  }
  if (Array.isArray(columnIds) && columnIds.length) {
    payload.columnIds = columnIds
  }
  return request('/fields/import', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function getFieldCatalog(datasetVersionId) {
  const params = new URLSearchParams({ datasetVersionId })
  return request(`/fields/catalog?${params.toString()}`)
}

export function listRuleWorkbooks({ page = 0, size = 20, keyword = '', status = '' } = {}) {
  const params = pageParams({ page, size, keyword, status })
  return request(`/rule-workbooks?${params.toString()}`)
}

export function createRuleWorkbook(payload) {
  return request('/rule-workbooks', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function getRuleWorkbook(id) {
  return request(`/rule-workbooks/${encodeURIComponent(id)}`)
}

export function deleteRuleWorkbook(id) {
  return request(`/rule-workbooks/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function saveRuleWorkbookDraft(id, document, expectedRevision) {
  return request(`/rule-workbooks/${encodeURIComponent(id)}/draft`, {
    method: 'PUT',
    body: JSON.stringify({ document, expectedRevision }),
  })
}

export function validateRuleWorkbook(id) {
  return request(`/rule-workbooks/${encodeURIComponent(id)}/validate`, {
    method: 'POST',
    body: '{}',
  })
}

export function publishRuleWorkbook(id) {
  return request(`/rule-workbooks/${encodeURIComponent(id)}/publish`, {
    method: 'POST',
    body: '{}',
  })
}

export function listRuleWorkbookVersions(id) {
  return request(`/rule-workbooks/${encodeURIComponent(id)}/versions`)
}

export function createRuleRun(id, payload = {}) {
  return request(`/rule-workbooks/${encodeURIComponent(id)}/runs`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function listRuleRuns(id, limit = 20) {
  const params = new URLSearchParams({ limit: String(limit) })
  return request(`/rule-workbooks/${encodeURIComponent(id)}/runs?${params.toString()}`)
}

export function getRuleRun(runId) {
  return request(`/rule-runs/${encodeURIComponent(runId)}`)
}

export function getRuleRunExecution(runId) {
  return request(`/rule-runs/${encodeURIComponent(runId)}/execution`)
}

export function listRuleRunNodes(runId) {
  return request(`/rule-runs/${encodeURIComponent(runId)}/nodes`)
}

export function listFieldLibraries({ keyword = '' } = {}) {
  const params = new URLSearchParams()
  if (keyword.trim()) {
    params.set('keyword', keyword.trim())
  }
  const query = params.toString()
  return request(`/field-libraries${query ? `?${query}` : ''}`)
}

export function createFieldLibrary({ name, description = '' }) {
  return request('/field-libraries', {
    method: 'POST',
    body: JSON.stringify({ name, description }),
  })
}

export function updateFieldLibrary(id, { name, description = '' }) {
  return request(`/field-libraries/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify({ name, description }),
  })
}

export function deleteFieldLibrary(id) {
  return request(`/field-libraries/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function listFieldLibraryFields(id) {
  return request(`/field-libraries/${encodeURIComponent(id)}/fields`)
}

export function createFieldLibraryField(id, payload) {
  return request(`/field-libraries/${encodeURIComponent(id)}/fields`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateFieldLibraryField(id, fieldId, payload) {
  return request(
    `/field-libraries/${encodeURIComponent(id)}/fields/${encodeURIComponent(fieldId)}`,
    {
      method: 'PUT',
      body: JSON.stringify(payload),
    },
  )
}

export function deleteFieldLibraryField(id, fieldId) {
  return request(
    `/field-libraries/${encodeURIComponent(id)}/fields/${encodeURIComponent(fieldId)}`,
    { method: 'DELETE' },
  )
}

export function bulkCreateFieldLibraryFields(id, rows) {
  return request(`/field-libraries/${encodeURIComponent(id)}/fields/bulk`, {
    method: 'POST',
    body: JSON.stringify({ rows }),
  })
}

export function importFieldsToLibrary(id, payload) {
  return request(`/field-libraries/${encodeURIComponent(id)}/import`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
