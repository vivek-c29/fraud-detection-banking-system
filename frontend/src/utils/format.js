export const money = (value) =>
    value == null
        ? '—'
        : new Intl.NumberFormat('en-IN', {
            style: 'currency',
            currency: 'INR',
            maximumFractionDigits: 2,
        }).format(value)

export const dateTime = (value) =>
    value
        ? new Intl.DateTimeFormat('en-IN', {
            dateStyle: 'medium',
            timeStyle: 'short',
        }).format(new Date(value))
        : '—'

export const intermediateStatuses = new Set([
    'INITIATED',
    'PENDING',
    'PROCESSING',
])

export const isProcessing = (status) => intermediateStatuses.has(status)

export const statusClass = (status) =>
({
    COMPLETED: 'bg-emerald-100 text-emerald-800',
    FAILED: 'bg-rose-100 text-rose-800',
    FLAGGED: 'bg-amber-100 text-amber-800',
    PENDING_VERIFICATION: 'bg-amber-100 text-amber-800',
    PROCESSING: 'bg-sky-100 text-sky-800',
    PENDING: 'bg-sky-100 text-sky-800',
    INITIATED: 'bg-sky-100 text-sky-800',
}[status] || 'bg-slate-100 text-slate-700')
