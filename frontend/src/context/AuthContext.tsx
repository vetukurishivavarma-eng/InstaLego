import { createContext, useContext, useState, useCallback, useEffect, ReactNode } from 'react';
import { api, getToken, setToken, clearToken, setUnauthorizedHandler } from '../api/client';

interface AuthUser {
  email: string;
  role: 'USER' | 'ADMIN';
}

interface AuthContextValue {
  user: AuthUser | null;
  initializing: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const USER_KEY = 'instalego_user';

function loadStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(loadStoredUser);
  const [initializing, setInitializing] = useState(true);

  const logout = useCallback(() => {
    clearToken();
    localStorage.removeItem(USER_KEY);
    setUser(null);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(logout);
  }, [logout]);

  // Validate any stored token once on load; drop stale sessions quietly.
  useEffect(() => {
    if (!getToken()) {
      setInitializing(false);
      return;
    }
    api.me()
      .then(me => {
        const authUser: AuthUser = { email: me.email, role: me.role as 'USER' | 'ADMIN' };
        setUser(authUser);
        localStorage.setItem(USER_KEY, JSON.stringify(authUser));
      })
      .catch(() => logout())
      .finally(() => setInitializing(false));
  }, [logout]);

  const applySession = (email: string, role: 'USER' | 'ADMIN', token: string) => {
    setToken(token);
    const authUser: AuthUser = { email, role };
    localStorage.setItem(USER_KEY, JSON.stringify(authUser));
    setUser(authUser);
  };

  const login = async (email: string, password: string) => {
    const res = await api.login(email, password);
    applySession(res.email, res.role, res.token);
  };

  const register = async (email: string, password: string) => {
    const res = await api.register(email, password);
    applySession(res.email, res.role, res.token);
  };

  return (
    <AuthContext.Provider value={{ user, initializing, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider');
  return ctx;
}
