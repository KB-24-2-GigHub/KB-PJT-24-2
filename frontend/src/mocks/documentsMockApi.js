const documents = [
  {
    documentId: 1,
    docType: 'CONTRACT',
    workplaceId: 1,
    workCaseId: 201,
    fileName: '근로계약서_강남점_0722',
    fileExt: 'pdf',
    issuedDate: '2026-07-22',
    expiryDate: null,
    source: 'OWN',
    sharedByName: null,
    workCaseStatus: 'READY',
    createdAt: '2026-07-22T09:20:00'
  }
]
const shares = [{ workplaceId: 1, workplaceName: '강남점', sharedAt: '2026-07-20T10:00:00' }]
let nextDocumentId = 2
let nextShareId = 1

export async function listDocuments() {
  return { content: documents.map((document) => ({ ...document })) }
}

export async function uploadDocument() {
  return { documentId: nextDocumentId++ }
}

export async function updateDocumentIssuedDate(documentId, { issuedDate }) {
  return { documentId, issuedDate }
}

export async function deleteDocument() {}

export async function getDocumentShares() {
  return shares.map((share) => ({ ...share }))
}

export async function shareDocument() {
  return { shareId: nextShareId++ }
}

export async function revokeShare() {}
