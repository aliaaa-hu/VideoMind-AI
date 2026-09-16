const API_BASE = (import.meta.env?.VITE_API_BASE_URL || '').replace(/\/$/, '')
const TOKEN_KEY = 'authToken'

export function hasAuthToken() {
  return Boolean(localStorage.getItem(TOKEN_KEY))
}

export function setAuthToken(token) {
  if (!token) throw new Error('The sign-in response did not include a valid token.')
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * The backend uses a { code, message, data } response envelope. code === 0 indicates success;
 * business status lives in the body while HTTP status keeps transport semantics. Unwrap it here:
 *
 *   res.json()  ->  envelope data (arrays, objects, and strings remain unchanged)
 *   res.text()  ->  text data on success and the message on failure
 *   res.ok / res.status / res.headers  ->  original HTTP semantics (202 remains ok)
 *
 * Only unwrap application/json. SSE streams and audio downloads must pass through untouched;
 * reading their body here would consume and break the stream.
 */
// Identify envelopes with code and message rather than data. Error data is null and can be omitted
// by future non-null serialization, which would otherwise expose raw JSON errors to users.
function isEnvelope(payload) {
  return payload !== null
    && typeof payload === 'object'
    && !Array.isArray(payload)
    && typeof payload.code === 'number'
    && 'message' in payload
}

/** Convert data to text: use strings directly, map nullish values to empty text, serialize others. */
function dataAsText(data) {
  if (data === null || data === undefined) return ''
  return typeof data === 'string' ? data : JSON.stringify(data)
}

function unwrap(response, envelope) {
  const payload = envelope.data ?? null

  return {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    headers: response.headers,
    redirected: response.redirected,
    url: response.url,
    json: async () => payload,
    text: async () => (response.ok ? dataAsText(payload) : (envelope.message || '')),
    raw: response
  }
}

export async function apiRequest(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  } catch (error) {
    if (error?.name === 'AbortError') throw error
    throw new Error('Unable to reach the API. Confirm that the backend is running and its address is configured correctly.', { cause: error })
  }
  if (response.status === 401 && !path.startsWith('/user/')) {
    clearAuthToken()
    window.dispatchEvent(new Event('auth-expired'))
  }

  // Return non-JSON responses, including SSE, audio, and empty bodies, without consuming them.
  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) return response

  let envelope
  try {
    envelope = await response.clone().json()
  } catch {
    // If declared JSON cannot be parsed, such as an empty body, let the caller handle it.
    return response
  }
  if (!isEnvelope(envelope)) return response

  return unwrap(response, envelope)
}
