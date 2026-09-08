import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getAccount } from '../api/accountApi'
import Notice from '../components/common/Notice'
import StatusBadge from '../components/common/StatusBadge'
import { money, dateTime } from '../utils/format'

export default function Dashboard() {
  const [params] = useSearchParams()
  const [number, setNumber] = useState(params.get('account') || '')
  const [account, setAccount] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function lookup(e) {
    e.preventDefault()

    if (!number.trim()) return setError('Enter an account number.')

    setLoading(true)
    setError('')

    try {
      setAccount(await getAccount(number.trim()))
    } catch (e) {
      setAccount(null)
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <p className="text-sm font-semibold uppercase tracking-wider text-teal-700">
          Banking overview
        </p>

        <h1 className="mt-1 text-3xl font-bold text-ink">
          Account dashboard
        </h1>

        <p className="mt-2 text-slate-600">
          Look up an account to view live details. Authentication is not
          implemented by the backend, so account numbers are entered manually.
        </p>
      </div>

      <section className="panel p-5">
        <form
          onSubmit={lookup}
          className="flex flex-col gap-3 sm:flex-row sm:items-end"
        >
          <label className="flex-1">
            <span className="field-label">Account number</span>

            <input
              className="field-input"
              value={number}
              onChange={(e) => setNumber(e.target.value)}
              placeholder="Enter account number"
              autoComplete="off"
            />
          </label>

          <button className="btn-primary" disabled={loading}>
            {loading ? 'Loading…' : 'View account'}
          </button>
        </form>
      </section>

      {error && <Notice>{error}</Notice>}

      {account && (
        <section className="panel overflow-hidden">
          <div className="flex flex-col justify-between gap-3 border-b border-slate-200 p-5 sm:flex-row sm:items-center">
            <div>
              <p className="text-sm text-slate-500">Account holder</p>

              <h2 className="text-xl font-bold text-ink">
                {account.accountHolderName}
              </h2>
            </div>

            <StatusBadge status={account.status} />
          </div>

          <dl className="grid grid-cols-1 divide-y divide-slate-100 sm:grid-cols-2 sm:divide-x sm:divide-y-0">
            <div className="p-5">
              <dt className="text-sm text-slate-500">
                Available balance
              </dt>

              <dd className="mt-1 text-2xl font-bold text-ink">
                {money(account.balance)}
              </dd>
            </div>

            <div className="p-5">
              <dt className="text-sm text-slate-500">
                Account number
              </dt>

              <dd className="mt-1 font-semibold text-ink">
                {account.accountNumber}
              </dd>
            </div>

            <div className="p-5">
              <dt className="text-sm text-slate-500">Account type</dt>

              <dd className="mt-1 font-semibold text-ink">
                {account.accountType}
              </dd>
            </div>

            <div className="p-5">
              <dt className="text-sm text-slate-500">Opened</dt>

              <dd className="mt-1 font-semibold text-ink">
                {dateTime(account.createdAt)}
              </dd>
            </div>
          </dl>

          <div className="flex flex-wrap gap-3 border-t border-slate-200 p-5">
            <Link to="/transfer" className="btn-primary">
              Transfer money
            </Link>

            <Link
              to={`/transactions?account=${encodeURIComponent(
                account.accountNumber
              )}`}
              className="btn-secondary"
            >
              View outgoing transfers
            </Link>
          </div>
        </section>
      )}
    </div>
  )
}
