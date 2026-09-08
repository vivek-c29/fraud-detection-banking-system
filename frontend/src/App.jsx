import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/layout/Layout'
import Dashboard from './pages/Dashboard'
import CreateAccount from './pages/CreateAccount'
import Payments from './pages/Payments'
import Transfer from './pages/Transfer'
import Transactions from './pages/Transactions'
import TransactionDetails from './pages/TransactionDetails'
import OtpVerification from './pages/OtpVerification'

export default function App() {
    return (
    <Layout>
        <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/accounts/new" element={<CreateAccount />} />
            <Route path="/payments" element={<Payments />} />
            <Route path="/transfer" element={<Transfer />} />
            <Route path="/transactions" element={<Transactions />} />
            <Route
                path="/transactions/:transactionId/verify"
                element={<OtpVerification />}
            />
            <Route
                path="/transactions/:transactionId"
                element={<TransactionDetails />}
            />
            <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
    </Layout>
    )
}
