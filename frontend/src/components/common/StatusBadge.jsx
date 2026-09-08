import { statusClass } from '../../utils/format'
export default function StatusBadge({ status }) {
    return
    <span className={`inline-flex rounded-full px-2.5 py-1 text-xs font-bold tracking-wide 
        ${statusClass(status)}`}>{status || 'UNKNOWN'}
    </span>
}
