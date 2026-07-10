// In dev, Vite proxies /api to localhost:8080.
// In production, set VITE_API_URL to the backend URL (e.g., https://instalego-backend.onrender.com)
const API_BASE = (import.meta.env.VITE_API_URL || '') + '/api';

async function handleResponse<T>(response: Response): Promise<T> {
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

export interface Bank {
  id: number;
  name: string;
  createdAt: string;
}

export interface FieldSchemaEntry {
  fieldName: string;
  description: string;
  type: 'text' | 'date' | 'number' | 'boolean';
  required: boolean;
}

export interface TemplateUploadResponse {
  templateId: number;
  bankId: number;
  templatePdfPath: string;
  version: number;
  derivedSchema: FieldSchemaEntry[];
}

export interface BankTemplate {
  id: number;
  bankId: number;
  templatePdfPath: string;
  fieldSchema: string;
  version: number;
  createdAt: string;
}

export interface LegalReference {
  id: number;
  bankId: number;
  fileName: string;
  fileType: string;
  createdAt: string;
}

export interface JobStatusResponse {
  id: number;
  bankId: number;
  bankName: string;
  status: string;
  extractedJson: string | null;
  verificationReport: string | null;
  errorMessage: string | null;
  outputAvailable: boolean;
  createdAt: string;
}

export interface CreateJobResponse {
  id: number;
  status: string;
  createdAt: string;
}

export const api = {
  // Banks
  async createBank(name: string): Promise<Bank> {
    const res = await fetch(`${API_BASE}/banks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    });
    return handleResponse<Bank>(res);
  },

  async getBanks(): Promise<Bank[]> {
    const res = await fetch(`${API_BASE}/banks`);
    return handleResponse<Bank[]>(res);
  },

  async getBanksWithTemplate(): Promise<Bank[]> {
    const res = await fetch(`${API_BASE}/banks/with-template`);
    return handleResponse<Bank[]>(res);
  },

  // Templates
  async uploadTemplate(bankId: number, file: File): Promise<TemplateUploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    const res = await fetch(`${API_BASE}/banks/${bankId}/template`, {
      method: 'POST',
      body: formData,
    });
    return handleResponse<TemplateUploadResponse>(res);
  },

  async saveSchema(bankId: number, request: { derivedSchema: FieldSchemaEntry[] }): Promise<any> {
    const res = await fetch(`${API_BASE}/banks/${bankId}/template`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    return handleResponse<any>(res);
  },

  async getTemplate(bankId: number): Promise<BankTemplate> {
    const res = await fetch(`${API_BASE}/banks/${bankId}/template`);
    return handleResponse<BankTemplate>(res);
  },

  async deleteTemplate(bankId: number): Promise<any> {
    const res = await fetch(`${API_BASE}/banks/${bankId}/template`, {
      method: 'DELETE',
    });
    return handleResponse<any>(res);
  },

  getTemplateDownloadUrl(bankId: number): string {
    return `${API_BASE}/banks/${bankId}/template/download`;
  },

  // Jobs
  async createJob(bankId: number, file: File): Promise<CreateJobResponse> {
    const formData = new FormData();
    formData.append('bankId', bankId.toString());
    formData.append('file', file);
    const res = await fetch(`${API_BASE}/jobs`, {
      method: 'POST',
      body: formData,
    });
    return handleResponse<CreateJobResponse>(res);
  },

  async getJobStatus(jobId: number): Promise<JobStatusResponse> {
    const res = await fetch(`${API_BASE}/jobs/${jobId}`);
    return handleResponse<JobStatusResponse>(res);
  },

  getJobOutputUrl(jobId: number): string {
    return `${API_BASE}/jobs/${jobId}/output`;
  },

  // Legal References
  async uploadReference(bankId: number, file: File): Promise<LegalReference> {
    const formData = new FormData();
    formData.append('file', file);
    const res = await fetch(`${API_BASE}/banks/${bankId}/references`, {
      method: 'POST',
      body: formData,
    });
    return handleResponse<LegalReference>(res);
  },

  async getReferences(bankId: number): Promise<LegalReference[]> {
    const res = await fetch(`${API_BASE}/banks/${bankId}/references`);
    return handleResponse<LegalReference[]>(res);
  },

  async deleteReference(bankId: number, referenceId: number): Promise<any> {
    const res = await fetch(`${API_BASE}/banks/${bankId}/references/${referenceId}`, {
      method: 'DELETE',
    });
    return handleResponse<any>(res);
  },
};
