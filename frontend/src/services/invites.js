import * as api from '@/services/api/invitesApi'

export function getInvite(token) {
  return api.getInvite(token)
}

export function confirmInvite(token, options) {
  return api.confirmInvite(token, options)
}
