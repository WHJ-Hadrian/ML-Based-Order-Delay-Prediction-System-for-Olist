const AUTH_API = import.meta.env.VITE_AUTH_API_BASE_URL || '/api/v1/auth'

async function post(path, payload) {
  let response
  try {
    response = await fetch(`${AUTH_API}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
  } catch {
    throw new Error('无法连接注册服务，请稍后重试。')
  }

  const text = await response.text()
  let body = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      body = null
    }
  }

  if (!response.ok) {
    throw new Error(body?.message || '操作未完成，请检查信息后重试。')
  }
  return body
}

export function requestRegistrationCode(email) {
  return post('/registration-codes', { email })
}

export function verifyRegistrationCode(email, code) {
  return post('/registration-codes/verify', { email, code })
}

export function createRegisteredAccount({
  verificationToken,
  displayName,
  password,
  birthday,
  gender,
}) {
  return post('/registrations', {
    verificationToken,
    displayName,
    password,
    birthday,
    gender,
  })
}
