import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { createTransfer } from '../api/transactionApi'
import Notice from '../components/common/Notice'

const initial = {
  senderAccountNumber: '',
  receiverAccountNumber: '',
  amount: '',
  description: '',
}

export default function Transfer() {
  const [form, setForm] = useState(initial)
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)
  const navigate = useNavigate()

  function change(e) {
    setForm({ ...form, [e.target.name]: e.target.value })
  }

  async function submit(e) {
    e.preventDefault()
    setError('')

    if (
      !form.senderAccountNumber.trim() ||
      !form.receiverAccountNumber.trim() ||
      !form.amount
    )
      return setError('Sender, receiver, and amount are required.')

    if (
      form.senderAccountNumber.trim() ===
      form.receiverAccountNumber.trim()
    )
      return setError('Sender and receiver accounts must be different.')

    if (Number(form.amount) <= 0)
      return setError('Amount must be positive.')

    setSending(true)

    try {
      const tx = await createTransfer({
        ...form,
        senderAccountNumber: form.senderAccountNumber.trim(),
        receiverAccountNumber: form.receiverAccountNumber.trim(),
        amount: Number(form.amount),
        description: form.description.trim() || null,
      })

      navigate(`/transactions/${tx.id}`)
    } catch (e) {
      setError(e.message)
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <p className="text-sm font-semibold uppercase tracking-wider text-teal-700">
          New transfer
        </p>

        <h1 className="mt-1 text-3xl font-bold text-ink">
          Send money
        </h1>

        <p className="mt-2 text-slate-600">
          Transfers are checked asynchronously before completion. You will be
          shown the live transaction state next.
        </p>
      </div>

      <form onSubmit={submit} className="panel space-y-5 p-5 sm:p-6">
        {error && <Notice>{error}</Notice>}

        <label>
          <span className="field-label">Sender account number</span>

          <input
            className="field-input"
            name="senderAccountNumber"
            value={form.senderAccountNumber}
            onChange={change}
            required
          />
        </label>

        <label>
          <span className="field-label">Receiver account number</span>

          <input
            className="field-input"
            name="receiverAccountNumber"
            value={form.receiverAccountNumber}
            onChange={change}
            required
          />
        </label>

        <label>
          <span className="field-label">Amount (INR)</span>

          <input
            className="field-input"
            name="amount"
            type="number"
            inputMode="decimal"
            min="0.01"
            step="0.01"
            value={form.amount}
            onChange={change}
            required
          />
        </label>

        <label>
          <span className="field-label">
            Description{' '}
            <span className="font-normal text-slate-400">
              (optional)
            </span>
          </span>

          <textarea
            className="field-input min-h-24 resize-y"
            name="description"
            value={form.description}
            onChange={change}
            maxLength="500"
          />
        </label>

        <div className="flex justify-end">
          <button
            className="btn-primary min-w-36"
            disabled={sending}
          >
            {sending ? 'Submitting…' : 'Submit transfer'}
          </button>
        </div>
      </form>
    </div>
  )
}
