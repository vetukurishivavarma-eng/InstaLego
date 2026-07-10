import { useState, useEffect, useCallback } from 'react';
import { api, Bank, LegalReference } from '../api/client';

export default function AdminPage() {
  const [banks, setBanks] = useState<Bank[]>([]);
  const [newBankName, setNewBankName] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Report Format state
  const [hasReportFormat, setHasReportFormat] = useState(false);
  const [reportFormatFile, setReportFormatFile] = useState<File | null>(null);
  const [uploadingFormat, setUploadingFormat] = useState(false);
  const [structureDerived, setStructureDerived] = useState(false);

  // View state
  const [viewingBank, setViewingBank] = useState<Bank | null>(null);

  // Legal References state
  const [references, setReferences] = useState<LegalReference[]>([]);
  const [loadingRefs, setLoadingRefs] = useState(false);
  const [refUploadFile, setRefUploadFile] = useState<File | null>(null);
  const [uploadingRef, setUploadingRef] = useState(false);

  const loadBanks = useCallback(async () => {
    try {
      setError('');
      const data = await api.getBanks();
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

  // Load references and report format when viewing a bank
  useEffect(() => {
    if (viewingBank) {
      loadReferences(viewingBank.id);
      loadReportFormat(viewingBank.id);
    }
  }, [viewingBank]);

  const loadReferences = async (bankId: number) => {
    setLoadingRefs(true);
    try {
      const refs = await api.getReferences(bankId);
      setReferences(refs);
    } catch {
      setReferences([]);
    } finally {
      setLoadingRefs(false);
    }
  };

  const loadReportFormat = async (bankId: number) => {
    try {
      const info = await api.getReportFormat(bankId);
      setHasReportFormat(info.hasReportFormat);
      setStructureDerived(!!info.reportStructure);
    } catch {
      setHasReportFormat(false);
      setStructureDerived(false);
    }
  };

  const handleAddBank = async () => {
    if (!newBankName.trim()) return;
    try {
      setError('');
      setSuccess('');
      await api.createBank(newBankName.trim());
      setNewBankName('');
      await loadBanks();
      setSuccess(`Bank "${newBankName.trim()}" created successfully.`);
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleUploadReportFormat = async () => {
    if (!viewingBank || !reportFormatFile) return;
    setUploadingFormat(true);
    setError('');
    try {
      const result = await api.uploadReportFormat(viewingBank.id, reportFormatFile);
      setHasReportFormat(true);
      setStructureDerived(!!result.structureDerived);
      setReportFormatFile(null);
      setSuccess(`Report format uploaded${result.structureDerived ? ' and structure derived' : ''}.`);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setUploadingFormat(false);
    }
  };

  const handleDeleteReportFormat = async () => {
    if (!viewingBank) return;
    if (!window.confirm(`Delete the report format for "${viewingBank.name}"? This cannot be undone.`)) return;
    try {
      setError('');
      setSuccess('');
      await api.deleteReportFormat(viewingBank.id);
      setHasReportFormat(false);
      setStructureDerived(false);
      setSuccess('Report format deleted.');
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleUploadReference = async () => {
    if (!viewingBank || !refUploadFile) return;
    setUploadingRef(true);
    setError('');
    try {
      await api.uploadReference(viewingBank.id, refUploadFile);
      setRefUploadFile(null);
      await loadReferences(viewingBank.id);
      setSuccess(`Reference document "${refUploadFile.name}" uploaded.`);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setUploadingRef(false);
    }
  };

  const handleDeleteReference = async (ref: LegalReference) => {
    if (!window.confirm(`Delete "${ref.fileName}"? This cannot be undone.`)) return;
    try {
      setError('');
      setSuccess('');
      await api.deleteReference(ref.bankId, ref.id);
      await loadReferences(ref.bankId);
      setSuccess(`Reference "${ref.fileName}" deleted.`);
    } catch (e: any) {
      setError(e.message);
    }
  };

  return (
    <div>
      <div className="page-header">
        <h1>Admin Panel</h1>
        <p>Manage banks, report formats, and legal reference documents</p>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {success && <div className="alert alert-success">{success}</div>}

      {/* Add Bank Section */}
      <div className="card" style={{ marginBottom: '1.5rem' }}>
        <h2 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '0.75rem' }}>Add a Bank</h2>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <input
            className="form-input"
            placeholder="Bank name (e.g., 'HDFC Bank')"
            value={newBankName}
            onChange={e => setNewBankName(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleAddBank()}
          />
          <button className="btn btn-primary" onClick={handleAddBank} disabled={!newBankName.trim()}>
            Add Bank
          </button>
        </div>
      </div>

      {/* Banks List */}
      <div className="card" style={{ marginBottom: '1.5rem' }}>
        <h2 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '0.75rem' }}>
          Banks ({banks.length})
        </h2>
        {loading ? (
          <div style={{ padding: '1rem 0' }}><span className="spinner" /> Loading...</div>
        ) : banks.length === 0 ? (
          <p style={{ color: 'var(--gray-500)', fontSize: '0.875rem' }}>
            No banks added yet. Create a bank above.
          </p>
        ) : (
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Name</th>
                  <th>Created</th>
                  <th>Report Format</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {banks.map(bank => (
                  <tr key={bank.id}>
                    <td>{bank.id}</td>
                    <td style={{ fontWeight: 500 }}>{bank.name}</td>
                    <td style={{ color: 'var(--gray-500)', fontSize: '0.8125rem' }}>
                      {bank.createdAt ? new Date(bank.createdAt).toLocaleDateString() : '-'}
                    </td>
                    <td>
                      {viewingBank?.id === bank.id && hasReportFormat ? (
                        <span className="badge badge-done">Active</span>
                      ) : viewingBank?.id === bank.id ? (
                        <span className="badge badge-pending">Not set</span>
                      ) : null}
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: '0.375rem', flexWrap: 'wrap' }}>
                        <button
                          className="btn btn-secondary btn-sm"
                          onClick={() => {
                            setViewingBank(bank);
                            setReportFormatFile(null);
                          }}
                        >
                          View
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* View Bank Details */}
      {viewingBank && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          <h2 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '0.75rem' }}>
            {viewingBank.name}
          </h2>

          {/* Report Format Section */}
          <div style={{ marginBottom: '1.5rem' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '0.75rem' }}>
              <h3 style={{ fontSize: '0.9375rem', fontWeight: 600 }}>Verification Report Format</h3>
              {hasReportFormat && (
                <span className="badge badge-done" style={{ fontSize: '0.6875rem' }}>
                  {structureDerived ? '✅ Structure derived' : '📄 Uploaded'}
                </span>
              )}
            </div>

            <p style={{ fontSize: '0.8125rem', color: 'var(--gray-500)', marginBottom: '0.75rem' }}>
              Upload a sample PDF showing how the verification report should look. The AI will analyze it and
              format its output to match. If none is uploaded, the AI uses its own default format.
            </p>

            {hasReportFormat ? (
              <div style={{
                padding: '0.75rem', borderRadius: 'var(--radius)',
                background: '#f0fdf4', border: '1px solid #86efac',
                marginBottom: '0.75rem'
              }}>
                <p style={{ fontSize: '0.875rem', color: '#065f46', marginBottom: '0.5rem' }}>
                  ✓ Report format is configured for this bank.
                </p>
                <button className="btn btn-danger btn-sm" onClick={handleDeleteReportFormat}>
                  Delete Report Format
                </button>
              </div>
            ) : (
              <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-end', marginBottom: '0.75rem' }}>
                <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                  <div
                    className="file-upload-area"
                    style={{ padding: '1rem', cursor: 'pointer' }}
                    onClick={() => document.getElementById('report-format-input')?.click()}
                  >
                    <input
                      id="report-format-input"
                      type="file"
                      accept=".pdf"
                      onChange={e => setReportFormatFile(e.target.files?.[0] || null)}
                    />
                    {reportFormatFile ? (
                      <p style={{ fontWeight: 600, color: 'var(--success)', fontSize: '0.875rem' }}>
                        ✓ {reportFormatFile.name}
                      </p>
                    ) : (
                      <p style={{ color: 'var(--gray-500)', fontSize: '0.8125rem' }}>
                        Click to select a sample report PDF
                      </p>
                    )}
                  </div>
                </div>
                <button
                  className="btn btn-primary btn-sm"
                  onClick={handleUploadReportFormat}
                  disabled={!reportFormatFile || uploadingFormat}
                >
                  {uploadingFormat ? <><span className="spinner" /> Analyzing...</> : 'Upload & Analyze'}
                </button>
              </div>
            )}
          </div>

          {/* Legal References Section */}
          <div style={{ borderTop: '1px solid var(--gray-200)', paddingTop: '1.5rem' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '0.75rem' }}>
              Legal References
            </h2>
            <p style={{ fontSize: '0.8125rem', color: 'var(--gray-500)', marginBottom: '0.75rem' }}>
              Upload bank policies, regulatory guidelines, or legal requirements for cross-referencing during verification.
            </p>

            {/* Upload reference doc */}
            <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem', alignItems: 'flex-end' }}>
              <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                <div
                  className="file-upload-area"
                  style={{ padding: '1rem', cursor: 'pointer' }}
                  onClick={() => document.getElementById('ref-file-input')?.click()}
                >
                  <input
                    id="ref-file-input"
                    type="file"
                    accept=".pdf,.txt,.docx"
                    onChange={e => setRefUploadFile(e.target.files?.[0] || null)}
                  />
                  {refUploadFile ? (
                    <p style={{ fontWeight: 600, color: 'var(--success)', fontSize: '0.875rem' }}>
                      ✓ {refUploadFile.name}
                    </p>
                  ) : (
                    <p style={{ color: 'var(--gray-500)', fontSize: '0.8125rem' }}>
                      Click to select PDF, DOCX, or TXT
                    </p>
                  )}
                </div>
              </div>
              <button
                className="btn btn-primary btn-sm"
                onClick={handleUploadReference}
                disabled={!refUploadFile || uploadingRef}
              >
                {uploadingRef ? <><span className="spinner" /> Uploading...</> : 'Upload Reference'}
              </button>
            </div>

            {/* List of uploaded references */}
            {loadingRefs ? (
              <div style={{ padding: '0.75rem 0' }}><span className="spinner" /> Loading references...</div>
            ) : references.length === 0 ? (
              <p style={{ color: 'var(--gray-500)', fontSize: '0.875rem', fontStyle: 'italic' }}>
                No legal reference documents uploaded yet.
              </p>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                {references.map(ref => (
                  <div
                    key={ref.id}
                    style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                      padding: '0.625rem 0.75rem', background: 'var(--gray-50)',
                      borderRadius: 'var(--radius)', border: '1px solid var(--gray-200)',
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--gray-500)"
                           strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                        <polyline points="14 2 14 8 20 8"/>
                      </svg>
                      <div>
                        <p style={{ fontWeight: 500, fontSize: '0.875rem' }}>{ref.fileName}</p>
                        <p style={{ fontSize: '0.75rem', color: 'var(--gray-400)' }}>
                          {ref.fileType} — {ref.createdAt ? new Date(ref.createdAt).toLocaleDateString() : ''}
                        </p>
                      </div>
                    </div>
                    <button
                      className="btn btn-danger btn-sm"
                      style={{ padding: '0.25rem 0.5rem', fontSize: '0.75rem' }}
                      onClick={() => handleDeleteReference(ref)}
                    >
                      Delete
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>

          <button
            className="btn btn-secondary btn-sm"
            style={{ marginTop: '1rem' }}
            onClick={() => setViewingBank(null)}
          >
            ← Back to bank list
          </button>
        </div>
      )}
    </div>
  );
}
