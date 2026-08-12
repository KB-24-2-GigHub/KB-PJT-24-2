const configuredOperations = new Set(
  String(import.meta.env.VITE_MOCK_OPERATIONS ?? '')
    .split(',')
    .map((operation) => operation.trim())
    .filter(Boolean)
)

/** Development mock selection is explicit and scoped to one operation/domain. */
export function isMockOperationEnabled(operation) {
  if (!import.meta.env.DEV) return false
  const [domain] = operation.split('.')
  return configuredOperations.has(operation) || configuredOperations.has(`${domain}.*`)
}
