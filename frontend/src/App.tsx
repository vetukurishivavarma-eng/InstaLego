import { Routes, Route, NavLink, useNavigate } from 'react-router-dom';
import UserPage from './pages/UserPage';
import AdminPage from './pages/AdminPage';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import ProtectedRoute from './components/ProtectedRoute';
import { useAuth } from './context/AuthContext';

function Navbar() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <nav className="navbar">
      <div className="navbar-inner">
        <NavLink to="/" className="navbar-brand">
          <svg width="26" height="26" viewBox="0 0 100 100" fill="none">
            <rect width="100" height="100" rx="14" fill="#6b2735" />
            <text x="50" y="67" fontSize="46" fill="#f6f1e7" textAnchor="middle" fontFamily="Georgia, serif" fontWeight="bold">IL</text>
          </svg>
          InstaLego
        </NavLink>
        <div className="navbar-links">
          {user && (
            <>
              <NavLink to="/" end className={({ isActive }) => 'navbar-link' + (isActive ? ' active' : '')}>
                Legal Opinion
              </NavLink>
              {user.role === 'ADMIN' && (
                <NavLink to="/admin" className={({ isActive }) => 'navbar-link' + (isActive ? ' active' : '')}>
                  Admin
                </NavLink>
              )}
              <div className="navbar-user">
                <span className="navbar-user-email" title={user.email}>{user.email}</span>
                <button className="btn btn-ghost btn-sm" onClick={handleLogout}>Sign out</button>
              </div>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}

function App() {
  return (
    <div>
      <Navbar />

      <main className="container" style={{ paddingTop: '2rem', paddingBottom: '3rem' }}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/" element={<ProtectedRoute><UserPage /></ProtectedRoute>} />
          <Route path="/admin" element={<ProtectedRoute adminOnly><AdminPage /></ProtectedRoute>} />
        </Routes>
      </main>
    </div>
  );
}

export default App;
