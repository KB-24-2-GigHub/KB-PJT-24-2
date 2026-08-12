import http, { idempotentPost } from '@/services/http'

export async function getInvite(token) {
  const { data } = await http.get(`/invitations/${token}`)
  return data
}

export async function confirmInvite(token, { idempotencyKey }) {
  const { data } = await idempotentPost(`/invitations/${token}/accept`, undefined, {
    idempotencyKey,
    retries: 0
  })
  return data
}
