export default function Notice({ children, type = 'error' }) {
  const styles =
    type === 'error'
      ? 'border-rose-200 bg-rose-50 text-rose-800'
      : 'border-sky-200 bg-sky-50 text-sky-800'

  return (
    <div
      role={type === 'error' ? 'alert' : 'status'}
      className={`rounded-xl border px-4 py-3 text-sm ${styles}`}
    >
      {children}
    </div>
  )
}