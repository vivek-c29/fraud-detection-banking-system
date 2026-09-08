import { request } from './client'

export const getAccount = (accountNumber) =>
    request(`/api/v1/accounts/${encodeURIComponent(accountNumber)}`)

export const createAccount = (data) =>
    request('/api/v1/accounts', { method: 'POST', body: JSON.stringify(data) })
