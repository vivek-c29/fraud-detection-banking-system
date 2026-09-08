import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { getTransaction, verifyOtp } from '../api/transactionApi'
import Notice from '../components/common/Notice'
import StatusBadge from '../components/common/StatusBadge'
import { money } from '../utils/format'

export default function OtpVerification() {
  const { transactionId } = useParams()
  const navigate = useNavigate()
  const [tx, setTx] = useState(null)
  const [otp, setOtp] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)

  const load = useCallback(async () => {
    try {
      const item = await getTransaction(transactionId)
      setTx(item)

      if (item.status !== 'PENDING_VERIFICATION')
        navigate(`/transactions/${transactionId}`, {
          replace: true,
        })
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [transactionId, navigate])

  useEffect(() => {
    load()
  }, [load])

  async function submit(e) {
    e.preventDefault()
    setError('')

    if (!/^\d{6}$/.test(otp))
      return setError(
        'Enter the 6-digit OTP sent by the backend notification service.'
      )

    setSubmitting(true)

    try {
      const updated = await verifyOtp(transactionId, otp)
      setTx(updated)

      if (updated.status !== 'PENDING_VERIFICATION') {
        navigate(`/transactions/${transactionId}`, {
          replace: true,
        })
      } else {
        setOtp('')
        setError(
          'The OTP was not accepted. You may try again while verification remains pending.'
        )
      }
    } catch (e) {
      setError(e.message)
      await load()
    } finally {
      setSubmitting(false)
    }
  }

  if (loading)
    return <p className="text-slate-600">Loading verification…</p>

  if (!tx)
    return <Notice>{error || 'Transaction unavailable.'}</Notice>

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <p className="text-sm font-semibold uppercase tracking-wider text-amber-700">
          Verification required
        </p>

        <h1 className="mt-1 text-3xl font-bold text-ink">
          Confirm this transfer
        </h1>

        <p className="mt-2 text-slate-600">
          Enter the one-time password provided by the notification service.
          The backend controls expiry and allowed attempts.
        </p>
      </div>

      <section className="panel space-y-5 p-5 sm:p-6">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm text-slate-500">Transfer amount</p>

            <p className="text-2xl font-bold text-ink">
              {money(tx.amount)}
            </p>
          </div>

          <StatusBadge status={tx.status} />
        </div>

        {error && <Notice>{error}</Notice>}

        <form onSubmit={submit} className="space-y-4">
          <label>
            <span className="field-label">6-digit OTP</span>

            <input
              className="field-input text-center text-xl tracking-[0.4em]"
              value={otp}
              onChange={(e) =>
                setOtp(
                  e.target.value.replace(/\D/g, '').slice(0, 6)
                )
              }
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength="6"
              autoFocus
            />
          </label>

          <button
            className="btn-primary w-full"
            disabled={submitting}
          >
            {submitting ? 'Verifying…' : 'Verify OTP'}
          </button>
        </form>
      </section>

      <Link
        className="btn-secondary"
        to={`/transactions/${transactionId}`}
      >
        View transaction
      </Link>
    </div>
  )
}
