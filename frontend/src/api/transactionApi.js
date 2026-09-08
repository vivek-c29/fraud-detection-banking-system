import { request } from './client'

export const createTransfer = (data) =>
    request('/api/v1/transactions/transfer', {
        method: 'POST',
        body: JSON.stringify(data),
    })

export const getTransaction = (id) =>
    request(`/api/v1/transactions/${encodeURIComponent(id)}`)

export const getTransactionHistory = (accountNumber) =>
    request(
        `/api/v1/transactions/account/${encodeURIComponent(accountNumber)}`
    )

export const verifyOtp = (id, otp) =>
    request(
        `/api/v1/transactions/${encodeURIComponent(
            id
        )}/verify?otp=${encodeURIComponent(otp)}`,
        {
            method: 'POST',
        }
    )
