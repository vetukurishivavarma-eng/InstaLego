// In dev, Vite proxies /api to localhost:8080.
// In production, set VITE_API_URL to the backend URL (e.g., https://instalego-backend.onrender.com)
const API_BASE = (import.meta.env.VITE_API_URL || '') + '/api';

const TOKEN_KEY = 'instalego_token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

/** Set by AuthContext so a 401 from any request can immediately clear the session. */
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler;
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (response.status === 401) {
    clearToken();
    if (onUnauthorized) onUnauthorized();
  }
  if (!response.ok) {
    const errorBody = await response.text();
    let errorMessage: string;
    try {
      const errorJson = JSON.parse(errorBody);
      errorMessage = errorJson.error || errorJson.message || `HTTP ${response.status}`;
    } catch {
      errorMessage = errorBody || `HTTP ${response.status}`;
    }
    throw new Error(errorMessage);
  }
  return response.json();
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: { ...authHeaders(), ...(options.headers || {}) },
  });
  return handleResponse<T>(res);
}

async function requestForm<T>(path: string, method: string, formData: FormData): Promise<T> {
  return request<T>(path, { method, body: formData });
}

async function requestJson<T>(path: string, method: string, body: unknown): Promise<T> {
  return request<T>(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export interface Bank {
  id: number;
  name: string;
  createdAt: string;
}

export interface LegalReference {
  id: number;
  bankId: number;
  fileName: string;
  fileType: string;
  createdAt: string;
}

export interface AuthResponse {
  token: string;
  email: string;
  role: 'USER' | 'ADMIN';
}

export interface MySession {
  id: number;
  bankId: number;
  bankName: string;
  status: string;
  verdict: string | null;
  createdAt: string;
}

export const api = {
  // Auth
  async register(email: string, password: string): Promise<AuthResponse> {
    return requestJson<AuthResponse>('/auth/register', 'POST', { email, password });
  },

  async login(email: string, password: string): Promise<AuthResponse> {
    return requestJson<AuthResponse>('/auth/login', 'POST', { email, password });
  },

  async me(): Promise<{ email: string; role: string }> {
    return request('/auth/me');
  },

  // Banks
  async createBank(name: string): Promise<Bank> {
    return requestJson<Bank>('/banks', 'POST', { name });
  },

  async getBanks(): Promise<Bank[]> {
    return request<Bank[]>('/banks');
  },

  async getBanksWithTemplate(): Promise<Bank[]> {
    return request<Bank[]>('/banks/with-template');
  },

  // Legal References
  async uploadReference(bankId: number, file: File): Promise<LegalReference> {
    const formData = new FormData();
    formData.append('file', file);
    return requestForm<LegalReference>(`/banks/${bankId}/references`, 'POST', formData);
  },

  async getReferences(bankId: number): Promise<LegalReference[]> {
    return request<LegalReference[]>(`/banks/${bankId}/references`);
  },

  async deleteReference(bankId: number, referenceId: number): Promise<any> {
    return request(`/banks/${bankId}/references/${referenceId}`, { method: 'DELETE' });
  },

  // Multi-Document Verification ("Submit for Legal Opinion")
  async startVerification(bankId: number): Promise<any> {
    const formData = new FormData();
    formData.append('bankId', bankId.toString());
    return requestForm('/verify/start', 'POST', formData);
  },

  async addVerificationDocument(sessionId: number, file: File, label: string): Promise<any> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('label', label);
    return requestForm(`/verify/${sessionId}/add-document`, 'POST', formData);
  },

  async getVerificationStatus(sessionId: number): Promise<any> {
    return request(`/verify/${sessionId}`);
  },

  async runVerification(sessionId: number): Promise<any> {
    return request(`/verify/${sessionId}/run`, { method: 'POST' });
  },

  async getMySessions(): Promise<MySession[]> {
    return request<MySession[]>('/verify/mine');
  },

  async downloadOpinionPdf(sessionId: number): Promise<Blob> {
    const res = await fetch(`${API_BASE}/verify/${sessionId}/opinion.pdf`, {
      headers: authHeaders(),
    });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(body || `HTTP ${res.status}`);
    }
    return res.blob();
  },

  // Report Format (Admin)
  async uploadReportFormat(bankId: number, file: File): Promise<any> {
    const formData = new FormData();
    formData.append('file', file);
    return requestForm(`/banks/${bankId}/report-format`, 'POST', formData);
  },

  async getReportFormat(bankId: number): Promise<any> {
    return request(`/banks/${bankId}/report-format`);
  },

  async deleteReportFormat(bankId: number): Promise<any> {
    return request(`/banks/${bankId}/report-format`, { method: 'DELETE' });
  },
};
