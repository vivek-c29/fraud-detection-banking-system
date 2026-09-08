import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getTransactionHistory } from '../api/transactionApi'
import Notice from '../components/common/Notice'
import StatusBadge from '../components/common/StatusBadge'
import { dateTime, money } from '../utils/format'

export default function Transactions() {
  const [params, setParams] = useSearchParams()
  const [number, setNumber] = useState(params.get('account') || '')
  const [items, setItems] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function load(accountNumber) {
    if (!accountNumber?.trim()) return

    setLoading(true)
    setError('')

    try {
      setItems(await getTransactionHistory(accountNumber.trim()))
    } catch (e) {
      setItems(null)
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (params.get('account')) {
      load(params.get('account'))
    }
  }, [])

  function submit(e) {
    e.preventDefault()

    const value = number.trim()

    setParams(value ? { account: value } : {})
    load(value)
  }

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <p className="text-sm font-semibold uppercase tracking-wider text-teal-700">
          Transfer records
        </p>

        <h1 className="mt-1 text-3xl font-bold text-ink">
          Outgoing transfers
        </h1>

        <p className="mt-2 text-slate-600">
          The available API returns transfers sent from the entered account only.
        </p>
      </div>

      <section className="panel p-5">
        <form
          onSubmit={submit}
          className="flex flex-col gap-3 sm:flex-row sm:items-end"
        >
          <label className="flex-1">
            <span className="field-label">Sender account number</span>

            <input
              className="field-input"
              value={number}
              onChange={(e) => setNumber(e.target.value)}
            />
          </label>

          <button className="btn-primary" disabled={loading}>
            {loading ? 'Loading…' : 'View transfers'}
          </button>
        </form>
      </section>

      {error && <Notice>{error}</Notice>}

      {items &&
        (items.length === 0 ? (
          <section className="panel p-10 text-center text-slate-600">
            No transactions found.
          </section>
        ) : (
          <section className="panel overflow-hidden">
            <div className="hidden grid-cols-[1.4fr_1fr_1fr_1fr] gap-4 border-b border-slate-200 bg-slate-50 px-5 py-3 text-xs font-bold uppercase tracking-wide text-slate-500 md:grid">
              <span>Reference</span>
              <span>Recipient</span>
              <span>Amount</span>
              <span>Status</span>
            </div>

            <ul className="divide-y divide-slate-100">
              {items.map((tx) => (
                <li key={tx.id}>
                  <Link
                    to={`/transactions/${tx.id}`}
                    className="grid gap-2 px-5 py-4 hover:bg-slate-50 md:grid-cols-[1.4fr_1fr_1fr_1fr] md:items-center md:gap-4"
                  >
                    <div>
                      <p className="font-semibold text-ink">
                        {tx.referenceNumber || tx.id}
                      </p>

                      <p className="text-sm text-slate-500">
                        {dateTime(tx.createdAt)}
                      </p>
                    </div>

                    <p className="text-sm text-slate-700">
                      {tx.receiverAccountNumber}
                    </p>

                    <p className="font-semibold text-ink">
                      {money(tx.amount)}
                    </p>

                    <div>
                      <StatusBadge status={tx.status} />
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        ))}
    </div>
  )
}
