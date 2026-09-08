import { request } from './client'

export const createPaymentOrder = (data) => request('/api/v1/payments/create-order', { method: 'POST', body: JSON.stringify(data) })
export const verifyPayment = (data) => request('/api/v1/payments/verify', { method: 'POST', body: JSON.stringify(data) })
