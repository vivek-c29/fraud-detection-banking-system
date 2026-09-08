import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { getTransaction } from '../api/transactionApi'
import Notice from '../components/common/Notice'
import StatusBadge from '../components/common/StatusBadge'
import { dateTime, isProcessing, money } from '../utils/format'

export default function TransactionDetails() {
  const { transactionId } = useParams()
  const navigate = useNavigate()
  const [tx, setTx] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const inflight = useRef(false)

  const load = useCallback(async () => {
    if (inflight.current) return

    inflight.current = true

    try {
      setError('')
      setTx(await getTransaction(transactionId))
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
      inflight.current = false
    }
  }, [transactionId])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    if (!isProcessing(tx?.status)) return

    const timer = setInterval(load, 3000)

    return () => clearInterval(timer)
  }, [tx?.status, load])

  useEffect(() => {
    if (tx?.status === 'PENDING_VERIFICATION')
      navigate(`/transactions/${transactionId}/verify`, {
        replace: true,
      })
  }, [tx?.status, transactionId, navigate])

  if (loading)
    return <p className="text-slate-600">Loading transaction…</p>

  if (error)
    return (
      <div className="space-y-4">
        <Notice>{error}</Notice>

        <Link to="/transactions" className="btn-secondary">
          Back to transfers
        </Link>
      </div>
    )

  if (!tx) return null

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-start">
        <div>
          <p className="text-sm font-semibold uppercase tracking-wider text-teal-700">
            Transfer status
          </p>

          <h1 className="mt-1 break-all text-2xl font-bold text-ink">
            {tx.referenceNumber || tx.id}
          </h1>

          <p className="mt-2 text-slate-600">
            {isProcessing(tx.status)
              ? 'Fraud checks are in progress. This page refreshes automatically.'
              : 'Current transaction information from the backend.'}
          </p>
        </div>

        <StatusBadge status={tx.status} />
      </div>

      {isProcessing(tx.status) && (
        <Notice type="info">
          Transaction processing… Checking status every 3 seconds.
        </Notice>
      )}

      <section className="panel divide-y divide-slate-100">
        <Row label="Amount" value={money(tx.amount)} strong />
        <Row label="From" value={tx.senderAccountNumber} />
        <Row label="To" value={tx.receiverAccountNumber} />
        <Row label="Description" value={tx.description || '—'} />
        <Row label="Created" value={dateTime(tx.createdAt)} />
        <Row label="Completed" value={dateTime(tx.completedAt)} />

        {tx.failureReason && (
          <Row
            label="Failure reason"
            value={tx.failureReason}
            danger
          />
        )}
      </section>

      <Link to="/transactions" className="btn-secondary">
        Back to transfers
      </Link>
    </div>
  )
}

function Row({ label, value, strong, danger }) {
  return (
    <div className="flex flex-col gap-1 px-5 py-4 sm:flex-row sm:justify-between sm:gap-8">
      <dt className="text-sm text-slate-500">{label}</dt>

      <dd
        className={`${strong ? 'text-xl font-bold' : 'font-medium'} ${danger ? 'text-rose-700' : 'text-ink'
          } break-all`}
      >
        {value}
      </dd>
    </div>
  )
}
