/**
 * 4xx responses other than request timeouts and rate limits are terminal; network and server
 * errors continue with exponential-backoff reconnects.
 */
export function isTerminalStatus(status) {
  return status >= 400 && status < 500 && status !== 408 && status !== 429
}
