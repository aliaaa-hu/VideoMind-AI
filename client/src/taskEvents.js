import { apiRequest } from './api.js'
import { isTerminalStatus } from './taskEventsPolicy.js'

/**
 * SSE connection pool for background tasks.
 * onActiveChange lets the UI, including library cards, show which videos still have active jobs
 * after a user closes the side panel.
 */
export function createTaskStreams({ onActiveChange = () => {} } = {}) {
  const streams = new Map()
  const keyOf = (id, type, scope = '') => `${type}:${id}:${scope}`
  const publish = () => onActiveChange([...streams.values()]
    .map(({ id, type, scope }) => ({ id, type, scope })))

  const stop = (id, type, scope = '') => {
    const key = keyOf(id, type, scope)
    const entry = streams.get(key)
    if (!entry) return
    entry.controller.abort()
    streams.delete(key)
    publish()
  }

  const stopAll = () => {
    if (!streams.size) return
    for (const { controller } of streams.values()) controller.abort()
    streams.clear()
    publish()
  }

  const stopMedia = id => {
    let changed = false
    for (const [key, entry] of streams.entries()) {
      if (String(entry.id) !== String(id)) continue
      entry.controller.abort()
      streams.delete(key)
      changed = true
    }
    if (changed) publish()
  }

  const start = (id, type, scope, path, onEvent, onError) => {
    stop(id, type, scope)
    const key = keyOf(id, type, scope)
    const controller = new AbortController()
    streams.set(key, { controller, id, type, scope })
    publish()
    let reconnectAttempt = 0

    const release = () => {
      if (streams.get(key)?.controller !== controller) return
      streams.delete(key)
      publish()
    }

    const run = async () => {
      while (!controller.signal.aborted && streams.get(key)?.controller === controller) {
        try {
          const response = await apiRequest(path, {
            headers: { Accept: 'text/event-stream' },
            signal: controller.signal
          })
          if (!response.ok) {
            const error = new Error(
              (await response.text()) || `Unable to connect to the event stream (HTTP ${response.status})`)
            error.status = response.status
            // Missing resources, unauthorized access, and invalid parameters cannot self-heal.
            // Release the connection and report a terminal state instead of reconnecting forever.
            if (isTerminalStatus(response.status)) {
              release()
              onError?.(error, reconnectAttempt + 1, true)
              return
            }
            throw error
          }
          if (!response.body) throw new Error('The server did not return an event stream.')
          const terminal = await consumeStream(response.body, async event => {
            reconnectAttempt = 0
            await onEvent(event)
          }, controller.signal)
          if (terminal) {
            release()
            return
          }
        } catch (error) {
          if (controller.signal.aborted) return
          onError?.(error, reconnectAttempt + 1)
        }
        const delay = Math.min(15_000, 1_000 * 2 ** reconnectAttempt++)
        await waitForRetry(delay, controller.signal)
      }
    }

    run().catch(error => {
      if (controller.signal.aborted) return
      // Reaching this path means the reconnect loop failed unexpectedly and cannot recover.
      release()
      onError?.(error, reconnectAttempt + 1, true)
    })
  }

  return {
    has: (id, type, scope = '') => streams.has(keyOf(id, type, scope)),
    hasMedia: id => [...streams.values()].some(entry => String(entry.id) === String(id)),
    start,
    stop,
    stopMedia,
    stopAll
  }
}

function waitForRetry(delay, signal) {
  if (signal.aborted) return Promise.resolve()
  return new Promise(resolve => {
    const timer = setTimeout(finish, delay)
    signal.addEventListener('abort', finish, { once: true })

    function finish() {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
  })
}

async function consumeStream(body, onEvent, signal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) {
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n')
        if (!data) continue
        const event = JSON.parse(data)
        await onEvent(event)
        if (event.state === 'COMPLETED' || event.state === 'FAILED') return true
      }
      if (done) return false
    }
    return false
  } finally {
    reader.releaseLock()
  }
}
