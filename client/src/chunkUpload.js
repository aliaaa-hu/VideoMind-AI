import { apiRequest } from './api'

const CHUNK_SIZE = 5 * 1024 * 1024
const UPLOAD_CONCURRENCY = 3
const MAX_TOTAL_CHUNKS = 410
const CHUNK_MAX_ATTEMPTS = 4
const CHUNK_RETRY_BASE_MS = 800
const CHUNK_RETRY_CEILING_MS = 8_000
const SPEED_WINDOW_MS = 8_000
const SPEED_MIN_SAMPLE_MS = 1_500

export const MAX_UPLOAD_BYTES = MAX_TOTAL_CHUNKS * CHUNK_SIZE

/** Thrown when a user cancels an upload so callers can distinguish cancellation from failure. */
export class UploadAbortedError extends Error {
  constructor(message = 'Upload cancelled') {
    super(message)
    this.name = 'UploadAbortedError'
    this.aborted = true
  }
}

export function formatBytes(bytes) {
  const value = Number(bytes) || 0
  if (value < 1024) return `${value} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let scaled = value / 1024
  let unitIndex = 0
  while (scaled >= 1024 && unitIndex < units.length - 1) {
    scaled /= 1024
    unitIndex += 1
  }
  return `${scaled >= 10 ? Math.round(scaled) : scaled.toFixed(1)} ${units[unitIndex]}`
}

export function formatDurationText(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return ''
  const total = Math.round(seconds)
  if (total < 60) return `${total}s`
  const minutes = Math.floor(total / 60)
  if (minutes < 60) return `${minutes}m ${String(total % 60).padStart(2, '0')}s`
  return `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`
}

/** Validate a selected file before entering the upload state. */
export function validateVideoFile(file) {
  if (!file) return 'Select a video file first.'
  if (!file.size) return 'This file is empty. It may be damaged or still syncing; please choose it again.'
  if (file.size > MAX_UPLOAD_BYTES) {
    return `This file is ${formatBytes(file.size)}, above the ${formatBytes(MAX_UPLOAD_BYTES)} limit. Compress or split it first.`
  }
  return ''
}

function storageKey(file) {
  return `upload:${file.name}:${file.size}:${file.lastModified}`
}

function readStoredUploadId(file) {
  try {
    return localStorage.getItem(storageKey(file))
  } catch {
    // localStorage can be unavailable in private browsing, so fall back to a normal upload.
    return null
  }
}

function writeStoredUploadId(file, uploadId) {
  try {
    localStorage.setItem(storageKey(file), uploadId)
  } catch {
    // Failure to persist resume credentials does not affect this upload, only a later resume.
  }
}

export function forgetUploadProgress(file) {
  if (!file) return
  try {
    localStorage.removeItem(storageKey(file))
  } catch {
    // Ignore unavailable storage for the same reason.
  }
}

export function hasUploadProgress(file) {
  return Boolean(file && readStoredUploadId(file))
}

/**
 * Chunked, resumable upload.
 * - Failed chunks retry with exponential backoff instead of failing the entire upload.
 * - AbortSignal cancellation retains resume credentials for a later retry.
 * - onProgress reports byte-level progress, throughput, and estimated remaining time.
 */
export async function uploadVideoInChunks(file, onProgress = () => {}, signal) {
  const invalid = validateVideoFile(file)
  if (invalid) throw new Error(invalid)
  throwIfAborted(signal)

  const totalBytes = file.size
  const totalChunks = Math.ceil(totalBytes / CHUNK_SIZE)
  const { uploadId, uploadedChunks } = await resolveUploadSession(file, totalChunks, signal)

  const pendingChunks = []
  let uploadedBytes = 0
  for (let index = 0; index < totalChunks; index += 1) {
    if (uploadedChunks.has(index)) uploadedBytes += chunkSize(file, index)
    else pendingChunks.push(index)
  }

  const resumedBytes = uploadedBytes
  const resumedChunks = totalChunks - pendingChunks.length
  const meter = createSpeedMeter()
  const retrying = new Map()
  let completedChunks = resumedChunks

  const emit = phase => {
    const transferred = uploadedBytes - resumedBytes
    const speed = meter.speed(transferred)
    const remainingBytes = Math.max(0, totalBytes - uploadedBytes)
    const attempts = [...retrying.values()]
    onProgress({
      phase,
      completedChunks,
      totalChunks,
      uploadedBytes,
      totalBytes,
      resumedBytes,
      resumedChunks,
      percent: totalBytes
        ? Math.min(100, Math.round((uploadedBytes / totalBytes) * 100))
        : 0,
      bytesPerSecond: speed,
      etaSeconds: speed && remainingBytes ? remainingBytes / speed : null,
      retryingCount: attempts.length,
      retryAttempt: attempts.length ? Math.max(...attempts) : 0,
      retryMaxAttempts: CHUNK_MAX_ATTEMPTS
    })
  }

  emit(resumedChunks ? 'resuming' : 'uploading')

  let cursor = 0
  let fatalError = null
  const worker = async () => {
    while (!fatalError && cursor < pendingChunks.length) {
      if (signal?.aborted) {
        fatalError = new UploadAbortedError()
        return
      }
      const index = pendingChunks[cursor++]
      try {
        await uploadChunkWithRetry(file, uploadId, index, totalChunks, signal, attempt => {
          retrying.set(index, attempt)
          emit('uploading')
        })
        retrying.delete(index)
        uploadedBytes += chunkSize(file, index)
        completedChunks += 1
        meter.record(uploadedBytes - resumedBytes)
        emit('uploading')
      } catch (error) {
        retrying.delete(index)
        fatalError = error
      }
    }
  }

  const workerCount = pendingChunks.length
    ? Math.min(UPLOAD_CONCURRENCY, pendingChunks.length)
    : 0
  await Promise.all(Array.from({ length: workerCount }, worker))
  if (fatalError) throw fatalError
  throwIfAborted(signal)

  emit('merging')
  const params = new URLSearchParams({ uploadId })
  const response = await apiRequest(`/media/complete-upload?${params}`, {
    method: 'POST',
    signal
  })
  if (!response.ok) {
    throwIfAborted(signal)
    throw new Error(await readErrorText(response) || 'Unable to merge upload chunks. Select the same file to resume.')
  }
  const media = await response.json()
  forgetUploadProgress(file)
  return media
}

/**
 * Discard local resume progress only when the server explicitly reports that credentials are
 * missing, expired, or malformed. The backend returns 400 for these cases.
 *   "uploadId does not exist or has expired" / "invalid uploadId"
 */
function isDeadUploadSession(status, detail) {
  if (status !== 400) return false
  const text = (detail || '').toLowerCase()
  return text.includes('does not exist')
    || text.includes('has expired')
    || text.includes('invalid uploadid')
}

async function resolveUploadSession(file, totalChunks, signal) {
  const storedUploadId = readStoredUploadId(file)
  if (storedUploadId) {
    let response
    try {
      const params = new URLSearchParams({ uploadId: storedUploadId })
      response = await apiRequest(`/media/upload-status?${params}`, { signal })
    } catch (error) {
      if (isAbortError(error, signal)) throw new UploadAbortedError()
      // Network loss or timeouts make progress unknown, not invalid. Keep credentials and stop so
      // users can resume instead of retransmitting every completed chunk.
      throw new Error('Network issue: upload progress could not be confirmed. Your resume data has been kept; try again shortly.')
    }

    if (response.ok) {
      const indexes = await response.json()
      const uploadedChunks = new Set((Array.isArray(indexes) ? indexes : [])
        .map(Number)
        .filter(index => Number.isInteger(index) && index >= 0 && index < totalChunks))
      return { uploadId: storedUploadId, uploadedChunks }
    }

    const detail = await readErrorText(response)
    if (!isDeadUploadSession(response.status, detail)) {
      // 401 / 403 / 429 / 5xx responses can leave credentials valid; retain them for a later retry.
      const error = new Error(detail
        || `Upload progress could not be confirmed (HTTP ${response.status}). Your resume data has been kept; try again shortly.`)
      error.status = response.status
      throw error
    }
    forgetUploadProgress(file)
  }

  const uploadId = await initializeUpload(file.name, totalChunks, signal)
  writeStoredUploadId(file, uploadId)
  return { uploadId, uploadedChunks: new Set() }
}

async function initializeUpload(filename, totalChunks, signal) {
  const params = new URLSearchParams({ filename, totalChunks: String(totalChunks) })
  const response = await apiRequest(`/media/init-upload?${params}`, {
    method: 'POST',
    signal
  })
  const body = (await response.text()).trim()
  if (!response.ok) throw new Error(body || 'Unable to initialize the upload. Please try again shortly.')
  return body
}

async function uploadChunkWithRetry(file, uploadId, chunkIndex, totalChunks, signal, onRetry) {
  let lastError = null
  for (let attempt = 1; attempt <= CHUNK_MAX_ATTEMPTS; attempt += 1) {
    throwIfAborted(signal)
    try {
      await uploadChunk(file, uploadId, chunkIndex, totalChunks, signal)
      return
    } catch (error) {
      if (isAbortError(error, signal)) throw new UploadAbortedError()
      lastError = error
      if (attempt === CHUNK_MAX_ATTEMPTS || !isRetriable(error)) break
      onRetry(attempt)
      await sleep(retryDelay(attempt), signal)
    }
  }
  throw new Error(
    `Chunk ${chunkIndex + 1}/${totalChunks} failed: ${lastError?.message || 'network issue'}`
  )
}

async function uploadChunk(file, uploadId, chunkIndex, totalChunks, signal) {
  const [start, end] = chunkBounds(file, chunkIndex)
  const formData = new FormData()
  formData.append('uploadId', uploadId)
  formData.append('chunkIndex', String(chunkIndex))
  formData.append('totalChunks', String(totalChunks))
  formData.append('file', file.slice(start, end))

  const response = await apiRequest('/media/upload-chunk', {
    method: 'POST',
    body: formData,
    signal
  })
  if (response.ok) return
  const error = new Error(await readErrorText(response) || 'The server did not accept this chunk.')
  error.status = response.status
  throw error
}

/** Retry only transient network and server failures; parameter and authorization retries cannot help. */
function isRetriable(error) {
  const status = error?.status
  if (status === undefined) return true
  if (status === 408 || status === 429) return true
  return status >= 500
}

function retryDelay(attempt) {
  const base = Math.min(CHUNK_RETRY_CEILING_MS, CHUNK_RETRY_BASE_MS * 2 ** (attempt - 1))
  return Math.round(base * (0.75 + Math.random() * 0.5))
}

function chunkBounds(file, chunkIndex) {
  return [
    chunkIndex * CHUNK_SIZE,
    Math.min(file.size, (chunkIndex + 1) * CHUNK_SIZE)
  ]
}

function chunkSize(file, chunkIndex) {
  const [start, end] = chunkBounds(file, chunkIndex)
  return end - start
}

/** Use a moving window so a full-upload average does not overestimate weak-network throughput. */
function createSpeedMeter() {
  const samples = [{ at: performance.now(), bytes: 0 }]
  return {
    record(bytes) {
      const at = performance.now()
      samples.push({ at, bytes })
      while (samples.length > 2 && at - samples[0].at > SPEED_WINDOW_MS) samples.shift()
    },
    speed(currentBytes) {
      const first = samples[0]
      const elapsed = performance.now() - first.at
      if (elapsed < SPEED_MIN_SAMPLE_MS) return null
      const bytes = currentBytes - first.bytes
      if (bytes <= 0) return null
      return bytes / (elapsed / 1000)
    }
  }
}

async function readErrorText(response) {
  try {
    return (await response.text()).trim()
  } catch {
    return ''
  }
}

function throwIfAborted(signal) {
  if (signal?.aborted) throw new UploadAbortedError()
}

function isAbortError(error, signal) {
  return Boolean(signal?.aborted) || error?.name === 'AbortError' || error?.aborted === true
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new UploadAbortedError())
      return
    }
    const cleanup = () => signal?.removeEventListener('abort', onAbort)
    const onAbort = () => {
      clearTimeout(timer)
      cleanup()
      reject(new UploadAbortedError())
    }
    const timer = setTimeout(() => {
      cleanup()
      resolve()
    }, ms)
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}
