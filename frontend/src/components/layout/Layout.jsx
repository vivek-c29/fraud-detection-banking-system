import { NavLink, useLocation } from 'react-router-dom'

const links = [
  { to: '/', label: 'Dashboard' },
  { to: '/accounts/new', label: 'Open account' },
  { to: '/transfer', label: 'Transfer money' },
  { to: '/payments', label: 'Add money' },
  { to: '/transactions', label: 'Transactions' },
]

export default function Layout({ children }) {
  const { pathname } = useLocation()

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-4 sm:px-6">
          <NavLink
            to="/"
            className="flex items-center gap-3 font-bold text-navy"
          >
            <span className="grid h-9 w-9 place-items-center rounded-xl bg-navy text-white">
              SB
            </span>

            <span>Secure Banking</span>
          </NavLink>

          <span className="hidden text-sm text-slate-500 sm:block">
            Gateway-connected banking workspace
          </span>
        </div>
      </header>

      <div className="mx-auto flex max-w-7xl flex-col md:flex-row">
        <nav className="border-b border-slate-200 bg-white p-3 md:min-h-[calc(100vh-65px)] md:w-56 md:border-b-0 md:border-r">
          <div className="flex gap-2 overflow-x-auto md:flex-col">
            {links.map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.to === '/'}
                className={({ isActive }) =>
                  `whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium ${isActive ||
                    (link.to === '/transactions' &&
                      pathname.startsWith('/transactions'))
                    ? 'bg-teal-50 text-teal-800'
                    : 'text-slate-600 hover:bg-slate-50'
                  }`
                }
              >
                {link.label}
              </NavLink>
            ))}
          </div>
        </nav>

        <main className="min-w-0 flex-1 p-4 sm:p-6">
          {children}
        </main>
      </div>
    </div>
  )
}
