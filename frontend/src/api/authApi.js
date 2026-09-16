import { request } from './client'

export async function loginUser(email, password) {
  const data = await request('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
  if (data.token) {
    localStorage.setItem('token', data.token)
    localStorage.setItem('user', JSON.stringify(data))
  }
  return data
}

export async function registerUser(fullName, email, password, role = 'USER') {
  const data = await request('/api/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ fullName, email, password, role }),
  })
  if (data.token) {
    localStorage.setItem('token', data.token)
    localStorage.setItem('user', JSON.stringify(data))
  }
  return data
}

export function logoutUser() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
}

export function getCurrentUser() {
  const user = localStorage.getItem('user')
  return user ? JSON.parse(user) : null
}
