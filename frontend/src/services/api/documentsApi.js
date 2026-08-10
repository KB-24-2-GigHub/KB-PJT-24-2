import http from '@/services/http'

export async function listDocuments(params = {}) {
  const { data } = await http.get('/documents', { params })
  return data
}

export async function uploadDocument(formData) {
  const { data } = await http.post('/documents', formData)
  return data
}

export async function updateDocumentIssuedDate(documentId, { issuedDate }) {
  const { data } = await http.patch(`/documents/${documentId}`, { issuedDate })
  return data
}

export async function deleteDocument(documentId) {
  await http.delete(`/documents/${documentId}`)
}

export async function getDocumentShares(documentId) {
  const { data } = await http.get(`/documents/${documentId}/shares`)
  return data
}

export async function shareDocument(documentId, { workplaceId }) {
  const { data } = await http.post(`/documents/${documentId}/shares`, { workplaceId })
  return data
}

export async function revokeShare(documentId, workplaceId) {
  await http.delete(`/documents/${documentId}/shares/${workplaceId}`)
}

export function documentFileUrl(documentId, mode = 'view') {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  return `${base}/documents/${documentId}/file?mode=${mode}`
}
