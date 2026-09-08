const BASE_URL = (
  import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'
).replace(/\/$/, '')

export class ApiError extends Error {
  constructor(message, status, body) {
    super(message)
    this.status = status
    this.body = body
  }
}

export async function request(path, options = {}) {
  let response

  try {
    response = await fetch(`${BASE_URL}${path}`, {
      headers: {
        Accept: 'application/json',
        ...(options.body
          ? { 'Content-Type': 'application/json' }
          : {}),
        ...options.headers,
      },
      ...options,
    })
  } catch {
    throw new ApiError(
      'Unable to reach the API Gateway. Confirm that it is running and VITE_API_BASE_URL is correct.',
      0
    )
  }

  const contentType = response.headers.get('content-type') || ''

  const body = contentType.includes('application/json')
    ? await response.json()
    : await response.text()

  if (!response.ok)
    throw new ApiError(
      body?.message ||
      body?.error ||
      (typeof body === 'string' && body) ||
      'Request failed.',
      response.status,
      body
    )

  return body
}
