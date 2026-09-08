import { useEffect, useRef, useState } from 'react'
import Notice from '../components/common/Notice'
import { createPaymentOrder, verifyPayment } from '../api/paymentApi'
import { money } from '../utils/format'

const loadCheckout = () => new Promise((resolve, reject) => {
  if (window.Razorpay) return resolve()
  const script = document.createElement('script')
  script.src = 'https://checkout.razorpay.com/v1/checkout.js'
  script.onload = resolve
  script.onerror = () => reject(new Error('Unable to load Razorpay Checkout. Check your internet connection and try again.'))
  document.head.appendChild(script)
})

export default function Payments() {
  const [form, setForm] = useState({ accountNumber: '', amount: '', description: '' }); const [error, setError] = useState(''); const [message, setMessage] = useState(''); const [loading, setLoading] = useState(false); const checkoutOpen = useRef(false)
  useEffect(() => { loadCheckout().catch(() => {}) }, [])
  const change = (event) => setForm({ ...form, [event.target.name]: event.target.value })
  async function submit(event) {
    event.preventDefault(); setError(''); setMessage('')
    if (!form.accountNumber.trim() || !form.amount) return setError('Account number and amount are required.')
    if (Number(form.amount) <= 0) return setError('Amount must be positive.')
    setLoading(true)
    try {
      await loadCheckout()
      const order = await createPaymentOrder({ accountNumber: form.accountNumber.trim(), amount: Number(form.amount), description: form.description.trim() || null })
      const checkout = new window.Razorpay({
        key: order.razorpayKeyId,
        amount: Math.round(Number(order.amount) * 100),
        currency: order.currency,
        name: 'Secure Banking',
        description: form.description.trim() || 'Account deposit',
        order_id: order.razorpayOrderId,
        theme: { color: '#0f766e' },
        modal: { ondismiss: () => { checkoutOpen.current = false; setLoading(false); setMessage('Payment window closed. No amount was added.') } },
        handler: async (response) => {
          try {
            const verified = await verifyPayment({ razorpayOrderId: response.razorpay_order_id, razorpayPaymentId: response.razorpay_payment_id, razorpaySignature: response.razorpay_signature })
            setMessage(`Payment verified. ${money(verified.amount)} is being credited to the selected account.`)
          } catch (e) { setError(`Payment was received but could not be verified: ${e.message}`) } finally { checkoutOpen.current = false; setLoading(false) }
        },
      })
      checkout.on('payment.failed', (response) => { checkoutOpen.current = false; setLoading(false); setError(response.error?.description || 'Payment was not completed.') })
      checkoutOpen.current = true; checkout.open()
    } catch (e) { setError(e.message); setLoading(false) }
  }
  return <div className="mx-auto max-w-2xl space-y-6"><div><p className="text-sm font-semibold uppercase tracking-wider text-teal-700">Account deposit</p><h1 className="mt-1 text-3xl font-bold text-ink">Add money securely</h1><p className="mt-2 text-slate-600">Test payments open Razorpay Checkout. The payment signature is verified by the Payment Service before your account is credited.</p></div><form className="panel space-y-5 p-5 sm:p-6" onSubmit={submit}>{error && <Notice>{error}</Notice>}{message && <Notice type="info">{message}</Notice>}<label><span className="field-label">Account number</span><input className="field-input" name="accountNumber" value={form.accountNumber} onChange={change} required /></label><label><span className="field-label">Amount (INR)</span><input className="field-input" name="amount" type="number" min="1" step="0.01" value={form.amount} onChange={change} required /></label><label><span className="field-label">Description <span className="font-normal text-slate-400">(optional)</span></span><textarea className="field-input min-h-24 resize-y" name="description" value={form.description} onChange={change} maxLength="500" /></label><div className="flex items-center justify-between gap-4"><p className="text-xs text-slate-500">Razorpay test mode only. Never enter real card details.</p><button className="btn-primary whitespace-nowrap" disabled={loading}>{loading ? (checkoutOpen.current ? 'Payment open…' : 'Preparing…') : 'Continue to pay'}</button></div></form></div>
}
