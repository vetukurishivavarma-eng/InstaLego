import { Routes, Route, NavLink } from 'react-router-dom';
import UserPage from './pages/UserPage';
import AdminPage from './pages/AdminPage';

function App() {
  return (
    <div>
      <nav className="navbar">
        <div className="navbar-inner">
          <NavLink to="/" className="navbar-brand">
            <svg width="28" height="28" viewBox="0 0 100 100" fill="none">
              <rect width="100" height="100" rx="20" fill="#4f46e5"/>
              <text x="50" y="68" fontSize="48" fill="white" textAnchor="middle"
                    fontFamily="serif" fontWeight="bold">IL</text>
            </svg>
            InstaLego
          </NavLink>
          <div className="navbar-links">
            <NavLink to="/" end className={({ isActive }) => 'navbar-link' + (isActive ? ' active' : '')}>
              Convert
            </NavLink>
            <NavLink to="/admin" className={({ isActive }) => 'navbar-link' + (isActive ? ' active' : '')}>
              Admin
            </NavLink>
          </div>
        </div>
      </nav>

      <main className="container" style={{ paddingTop: '2rem', paddingBottom: '3rem' }}>
        <Routes>
          <Route path="/" element={<UserPage />} />
          <Route path="/admin" element={<AdminPage />} />
        </Routes>
      </main>
    </div>
  );
}

export default App;
