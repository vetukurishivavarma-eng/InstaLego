import { useState, useEffect, useRef, useCallback } from 'react';
import { api, Bank, JobStatusResponse } from '../api/client';

const ACCEPTED_TYPES = '.pdf,.jpg,.jpeg,.png,.docx';
const MAX_FILE_SIZE = 15 * 1024 * 1024; // 15MB
const POLL_INTERVAL_MS = 2000;

export default function UserPage() {
  const [banks, setBanks] = useState<Bank[]>([]);
  const [selectedBankId, setSelectedBankId] = useState<number | ''>('');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Job state
  const [jobId, setJobId] = useState<number | null>(null);
  const [jobStatus, setJobStatus] = useState<JobStatusResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const pollRef = useRef<ReturnType<typeof setInterval>>();

  const loadBanks = useCallback(async () => {
    try {
      setError('');
      const data = await api.getBanksWithTemplate();
      setBanks(data);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadBanks();
  }, [loadBanks]);

  // Poll job status
  useEffect(() => {
    if (jobId) {
      pollRef.current = setInterval(async () => {
        try {
          const status = await api.getJobStatus(jobId);
          setJobStatus(status);

          if (status.status === 'DONE' || status.status === 'FAILED') {
            if (pollRef.current) clearInterval(pollRef.current);
          }
        } catch (e: any) {
          setError('Failed to check job status: ' + e.message);
          if (pollRef.current) clearInterval(pollRef.current);
        }
      }, POLL_INTERVAL_MS);

      return () => {
        if (pollRef.current) clearInterval(pollRef.current);
      };
    }
  }, [jobId]);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFile = e.target.files?.[0];
    if (!selectedFile) return;

    if (selectedFile.size > MAX_FILE_SIZE) {
      setError('File size exceeds 15MB limit. Please choose a smaller file.');
      setFile(null);
      return;
    }

    setError('');
    setFile(selectedFile);
  };

  const handleSubmit = async () => {
    if (!selectedBankId || !file) {
      setError('Please select a bank and upload a file.');
      return;
    }

    setSubmitting(true);
    setError('');
    setSuccess('');
    setJobId(null);
    setJobStatus(null);

    try {
      const response = await api.createJob(selectedBankId, file);
      setJobId(response.id);
      setSuccess(`Job created! ID: ${response.id}. Processing your document...`);
      setFile(null);
      // Reset file input
      const fileInput = document.getElementById('file-input') as HTMLInputElement;
      if (fileInput) fileInput.value = '';
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDownload = () => {
    if (jobStatus && jobStatus.outputAvailable) {
      window.open(api.getJobOutputUrl(jobStatus.id), '_blank');
    }
  };

  const handleReset = () => {
    setJobId(null);
    setJobStatus(null);
    setSuccess('');
    setError('');
    setFile(null);
  };

  const getStatusBadge = (status: string) => {
    const cls = status === 'DONE' ? 'badge-done'
      : status === 'FAILED' ? 'badge-failed'
      : status === 'PROCESSING' ? 'badge-processing'
      : 'badge-pending';
    return <span className={`badge ${cls}`}>{status}</span>;
  };

  return (
    <div>
      <div className="page-header">
        <h1>Convert Legal Document</h1>
        <p>Upload a legal document and select a bank to generate a formatted output</p>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {success && <div className="alert alert-success">{success}</div>}

      {!jobId ? (
        <div className="card">
          {/* Bank Selection */}
          <div className="form-group">
            <label htmlFor="bank-select">Select Bank</label>
            {loading ? (
              <div style={{ padding: '0.5rem 0' }}><span className="spinner" /> Loading banks...</div>
            ) : banks.length === 0 ? (
              <p style={{ color: 'var(--gray-500)', fontSize: '0.875rem' }}>
                No banks with active templates. Ask an admin to set up bank templates first.
              </p>
            ) : (
              <select
                id="bank-select"
                className="form-select"
                value={selectedBankId}
                onChange={e => setSelectedBankId(e.target.value ? Number(e.target.value) : '')}
              >
                <option value="">— Select a bank —</option>
                {banks.map(bank => (
                  <option key={bank.id} value={bank.id}>{bank.name}</option>
                ))}
              </select>
            )}
          </div>

          {/* File Upload */}
          <div className="form-group">
            <label htmlFor="file-input">Upload Document</label>
            <div
              className={`file-upload-area ${file ? 'has-file' : ''}`}
              onClick={() => document.getElementById('file-input')?.click()}
            >
              <input
                id="file-input"
                type="file"
                accept={ACCEPTED_TYPES}
                onChange={handleFileChange}
              />
              {file ? (
                <div>
                  <p style={{ fontWeight: 600, color: 'var(--success)' }}>✓ {file.name}</p>
                  <p style={{ fontSize: '0.8125rem', color: 'var(--gray-500)', marginTop: '0.25rem' }}>
                    {(file.size / 1024).toFixed(1)} KB — click to change
                  </p>
                </div>
              ) : (
                <div>
                  <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="var(--gray-400)"
                       strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"
                       style={{ marginBottom: '0.5rem' }}>
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                    <polyline points="14 2 14 8 20 8"/>
                    <line x1="12" y1="18" x2="12" y2="12"/>
                    <line x1="9" y1="15" x2="15" y2="15"/>
                  </svg>
                  <p style={{ fontWeight: 500, color: 'var(--gray-600)' }}>
                    Drop your document here or click to browse
                  </p>
                  <p style={{ fontSize: '0.8125rem', color: 'var(--gray-400)', marginTop: '0.25rem' }}>
                    PDF, JPG, PNG, or DOCX — Max 15MB
                  </p>
                </div>
              )}
            </div>
          </div>

          <button
            className="btn btn-primary"
            onClick={handleSubmit}
            disabled={!selectedBankId || !file || submitting || loading}
            style={{ width: '100%', justifyContent: 'center' }}
          >
            {submitting ? <><span className="spinner" /> Submitting...</> : 'Convert Document'}
          </button>
        </div>
      ) : (
        /* Job Status View */
        <div className="card">
          <h2 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '1rem' }}>
            Job Status
          </h2>

          <div className="job-status-bar" style={{ marginBottom: '1rem' }}>
            <span style={{ fontWeight: 500 }}>Job #{jobStatus?.id || jobId}</span>
            {jobStatus ? getStatusBadge(jobStatus.status) : <span className="spinner" />}
            {jobStatus?.bankName && (
              <span style={{ color: 'var(--gray-500)', fontSize: '0.875rem' }}>
                — {jobStatus.bankName}
              </span>
            )}
          </div>

          {/* Done state */}
          {jobStatus?.status === 'DONE' && (
            <div>
              <div className="alert alert-success">
                Document processed successfully!
              </div>

              {jobStatus.extractedJson && (
                <div className="form-group">
                  <label>Extracted Data (preview)</label>
                  <pre style={{
                    background: 'var(--gray-50)',
                    padding: '0.75rem',
                    borderRadius: 'var(--radius)',
                    fontSize: '0.8125rem',
                    overflow: 'auto',
                    maxHeight: '300px',
                    border: '1px solid var(--gray-200)',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                  }}>
                    {(() => {
                      try {
                        return JSON.stringify(JSON.parse(jobStatus.extractedJson), null, 2);
                      } catch {
                        return jobStatus.extractedJson;
                      }
                    })()}
                  </pre>
                </div>
              )}

              <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
                <button className="btn btn-primary" onClick={handleDownload}>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                       strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                    <polyline points="7 10 12 15 17 10"/>
                    <line x1="12" y1="15" x2="12" y2="3"/>
                  </svg>
                  Download Output PDF
                </button>
                <button className="btn btn-secondary" onClick={handleReset}>
                  Convert Another
                </button>
              </div>
            </div>
          )}

          {/* Failed state */}
          {jobStatus?.status === 'FAILED' && (
            <div>
              <div className="alert alert-error">
                Conversion failed. See error details below.
              </div>
              {jobStatus.errorMessage && (
                <div className="form-group">
                  <label>Error Details</label>
                  <pre style={{
                    background: '#fef2f2',
                    padding: '0.75rem',
                    borderRadius: 'var(--radius)',
                    fontSize: '0.8125rem',
                    overflow: 'auto',
                    maxHeight: '200px',
                    border: '1px solid #fecaca',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                  }}>
                    {jobStatus.errorMessage}
                  </pre>
                </div>
              )}
              <button className="btn btn-secondary" onClick={handleReset}>
                Try Again
              </button>
            </div>
          )}

          {/* Processing state */}
          {(!jobStatus || (jobStatus.status !== 'DONE' && jobStatus.status !== 'FAILED')) && (
            <div style={{ textAlign: 'center', padding: '1.5rem' }}>
              <span className="spinner" style={{ width: '2rem', height: '2rem', borderWidth: '3px' }} />
              <p style={{ marginTop: '0.75rem', color: 'var(--gray-600)' }}>
                Processing your document with AI...
              </p>
              <p style={{ fontSize: '0.8125rem', color: 'var(--gray-400)', marginTop: '0.25rem' }}>
                This may take up to a minute. The page refreshes automatically.
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
