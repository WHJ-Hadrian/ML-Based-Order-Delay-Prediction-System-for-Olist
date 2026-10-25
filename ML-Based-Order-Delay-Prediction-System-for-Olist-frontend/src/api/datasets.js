import {
  AUTH_REQUIRED_EVENT,
  AuthenticationRequiredError,
  getAccessToken,
} from '@/auth/keycloak'

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api/v1/datasets'

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

async function request(url, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = await getAccessToken()
  if (!token) {
    throw authenticationRequired()
  }
  headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  let response
  try {
    response = await fetch(url, { ...options, headers })
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

export function listDatasets({
  status = '',
  keyword = '',
  format = '',
  hasData,
  page = 0,
  size = 10,
} = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (status) {
    params.set('status', status)
  }
  if (typeof keyword === 'string' && keyword.trim()) {
    params.set('keyword', keyword.trim())
  }
  if (typeof format === 'string' && format) {
    params.set('format', format)
  }
  if (hasData === true || hasData === false) {
    params.set('hasData', String(hasData))
  }
  return request(`${API_BASE}?${params.toString()}`)
}

export function getDataset(id) {
  return request(`${API_BASE}/${encodeURIComponent(id)}`)
}

export function createDataset({ name = '', description = '' } = {}) {
  return request(API_BASE, {
    method: 'POST',
    body: JSON.stringify({ name, description }),
  })
}

export function renameDataset(id, { name, description } = {}) {
  const payload = { name }
  if (description !== undefined) {
    payload.description = description
  }
  return request(`${API_BASE}/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  })
}

export function deleteDataset(id) {
  return request(`${API_BASE}/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
}

export function renameDatasetTable(datasetId, tableId, name) {
  return request(
    `${API_BASE}/${encodeURIComponent(datasetId)}/tables/${encodeURIComponent(tableId)}`,
    {
      method: 'PATCH',
      body: JSON.stringify({ name }),
    },
  )
}

export function deleteDatasetTable(datasetId, tableId) {
  return request(
    `${API_BASE}/${encodeURIComponent(datasetId)}/tables/${encodeURIComponent(tableId)}`,
    {
      method: 'DELETE',
    },
  )
}

async function uploadFile(url, file, onProgress) {
  const token = await getAccessToken()
  if (!token) {
    throw authenticationRequired()
  }

  return new Promise((resolve, reject) => {
    const formData = new FormData()
    formData.append('file', file)

    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    xhr.responseType = 'text'
    xhr.setRequestHeader('Authorization', `Bearer ${token}`)

    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    })

    xhr.addEventListener('load', () => {
      let body = null
      if (xhr.responseText) {
        try {
          body = JSON.parse(xhr.responseText)
        } catch {
          body = xhr.responseText
        }
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(body)
        return
      }
      if (xhr.status === 401) {
        reject(authenticationRequired())
        return
      }
      reject(new Error(extractError(body, `上传失败（HTTP ${xhr.status}）`)))
    })

    xhr.addEventListener('error', () => {
      reject(new Error('无法连接后端服务，请检查服务状态或网关配置'))
    })
    xhr.addEventListener('abort', () => reject(new Error('上传已取消')))

    xhr.send(formData)
  })
}

export function addDatasetFile(id, { file, onProgress } = {}) {
  return uploadFile(`${API_BASE}/${encodeURIComponent(id)}/files`, file, onProgress)
}

export function previewDatasetTable(datasetId, tableId, { limit = 10, mode = 'preview' } = {}) {
  const params = new URLSearchParams({ limit: String(limit), mode })
  return request(
    `${API_BASE}/${encodeURIComponent(datasetId)}/tables/${encodeURIComponent(tableId)}/preview?${params.toString()}`,
  )
}

export function listDatasetTableRows(datasetId, tableId, { offset = 0, limit = 200 } = {}) {
  const params = new URLSearchParams({ offset: String(offset), limit: String(limit) })
  return request(
    `${API_BASE}/${encodeURIComponent(datasetId)}/tables/${encodeURIComponent(tableId)}/rows?${params.toString()}`,
  )
}
