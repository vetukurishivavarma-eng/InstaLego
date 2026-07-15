import { useState, useEffect, useRef, useCallback } from 'react';
import { api, Bank } from '../api/client';

const POLL_INTERVAL_MS = 1500;
const ACTIVE_STATUSES = ['PENDING', 'NEEDS_MORE_DOCUMENTS'];
const RUNNING_STATUSES = ['EXTRACTING', 'VERIFYING'];

export default function UserPage() {
  const [banks, setBanks] = useState<Bank[]>([]);
  const [selectedBankId, setSelectedBankId] = useState<number | ''>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Session state
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [sessionStatus, setSessionStatus] = useState<string>('');
  const [currentPhase, setCurrentPhase] = useState<string>('');
  const [thinkingSteps, setThinkingSteps] = useState<string[]>([]);
  const [report, setReport] = useState<any>(null);
  const [documents, setDocuments] = useState<any[]>([]);
  const [missingDocuments, setMissingDocuments] = useState<any[]>([]);

  // Upload state
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadLabel, setUploadLabel] = useState('');
  const [uploading, setUploading] = useState(false);

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

  // Poll session status while a run is in progress
  useEffect(() => {
    if (sessionId) {
      pollRef.current = setInterval(async () => {
        try {
          const status = await api.getVerificationStatus(sessionId);
          setSessionStatus(status.status);
          setCurrentPhase(status.currentPhase || '');

          if (status.thinkingSteps && Array.isArray(status.thinkingSteps)) {
            setThinkingSteps(status.thinkingSteps);
          }

          if (status.documents) {
            setDocuments(status.documents);
          }

          if (status.report) {
            setReport(status.report);
          }

          if (Array.isArray(status.missingDocuments)) {
            setMissingDocuments(status.missingDocuments);
          }

          if (status.status === 'DONE' || status.status === 'NEEDS_MORE_DOCUMENTS') {
            if (pollRef.current) clearInterval(pollRef.current);
          }

          if (status.status === 'FAILED') {
            setError(status.errorMessage || 'Verification failed');
            if (pollRef.current) clearInterval(pollRef.current);
          }
        } catch (e: any) {
          // Don't set error for transient poll failures
          console.warn('Poll failed:', e.message);
        }
      }, POLL_INTERVAL_MS);

      return () => {
        if (pollRef.current) clearInterval(pollRef.current);
      };
    }
  }, [sessionId]);

  const handleStartSession = async () => {
    if (!selectedBankId) {
      setError('Please select a bank first.');
      return;
    }
    try {
      setError('');
      setSuccess('');
      const session = await api.startVerification(selectedBankId as number);
      setSessionId(session.id);
      setSessionStatus('PENDING');
      setCurrentPhase('Add your documents one at a time');
      setDocuments([]);
      setThinkingSteps([]);
      setReport(null);
      setMissingDocuments([]);
      setUploadFile(null);
      setUploadLabel('');
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleAddDocument = async () => {
    if (!sessionId || !uploadFile) return;
    setUploading(true);
    setError('');
    try {
      const label = uploadLabel.trim() || uploadFile.name;
      await api.addVerificationDocument(sessionId, uploadFile, label);
      setSuccess(`"${label}" added`);
      setUploadFile(null);
      setUploadLabel('');
      // Refresh document list
      const status = await api.getVerificationStatus(sessionId);
      if (status.documents) setDocuments(status.documents);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setUploading(false);
    }
  };

  const handleRunVerification = async () => {
    if (!sessionId) return;
    try {
      setError('');
      setSuccess('');
      setThinkingSteps([]);
      setReport(null);
      setMissingDocuments([]);
      setThinkingSteps(['📄 Starting verification...']);
      await api.runVerification(sessionId);
      setSessionStatus('VERIFYING');
      setCurrentPhase('Processing documents...');
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleReset = () => {
    setSessionId(null);
    setSessionStatus('');
    setCurrentPhase('');
    setThinkingSteps([]);
    setReport(null);
    setDocuments([]);
    setMissingDocuments([]);
    setUploadFile(null);
    setUploadLabel('');
    setSuccess('');
    setError('');
    if (pollRef.current) clearInterval(pollRef.current);
  };

  const getStatusBadge = (status: string) => {
    const cls =
      status === 'DONE' ? 'badge-done'
        : status === 'FAILED' ? 'badge-failed'
        : status === 'NEEDS_MORE_DOCUMENTS' ? 'badge-pending'
        : RUNNING_STATUSES.includes(status) ? 'badge-processing'
        : 'badge-pending';
    return <span className={`badge ${cls}`}>{status.replace(/_/g, ' ')}</span>;
  };

  const getVerdictBadge = (verdict: string) => {
    const cls = verdict === 'PASS' ? 'badge-done'
      : verdict === 'FAIL' || verdict === 'ERROR' ? 'badge-failed'
      : 'badge-pending';
    const label = verdict === 'PASS' ? '✅ Legally Valid (Pass)'
      : verdict === 'FAIL' ? '❌ Fail'
      : verdict === 'INCOMPLETE' ? '📎 Incomplete — documents needed'
      : verdict === 'ERROR' ? '⚠️ Error'
      : verdict;
    return <span className={`badge ${cls}`}>{label}</span>;
  };

  const isUploadStage = sessionId && ACTIVE_STATUSES.includes(sessionStatus);
  const isRunning = RUNNING_STATUSES.includes(sessionStatus);

  return (
    <div>
      <div className="page-header">
        <h1>Legal Document Verification</h1>
        <p>Upload documents for a bank and get a legal verification report — if a referenced document is missing, you'll be asked to add it.</p>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {success && <div className="alert alert-success">{success}</div>}

      {/* Bank Selection (before session starts) */}
      {!sessionId && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          <div className="form-group">
            <label htmlFor="bank-select">Select Bank</label>
            {loading ? (
              <div style={{ padding: '0.5rem 0' }}><span className="spinner" /> Loading banks...</div>
            ) : banks.length === 0 ? (
              <p style={{ color: 'var(--gray-500)', fontSize: '0.875rem' }}>
                No banks configured yet. Ask an admin to set up a bank first.
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

          <button
            className="btn btn-primary"
            onClick={handleStartSession}
            disabled={!selectedBankId || loading}
            style={{ width: '100%', justifyContent: 'center' }}
          >
            Start Verification
          </button>
        </div>
      )}

      {/* Upload stage: initial upload (PENDING) or supplying a missing referenced document (NEEDS_MORE_DOCUMENTS) */}
      {isUploadStage && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          {sessionStatus === 'NEEDS_MORE_DOCUMENTS' && missingDocuments.length > 0 && (
            <div style={{
              padding: '1rem', borderRadius: 'var(--radius)',
              background: '#fffbeb', border: '1px solid #fde68a', marginBottom: '1.5rem'
            }}>
              <p style={{ fontWeight: 600, fontSize: '0.9375rem', marginBottom: '0.5rem', color: '#92400e' }}>
                📎 {missingDocuments.length} more document{missingDocuments.length > 1 ? 's are' : ' is'} needed to complete verification
              </p>
              {missingDocuments.map((md: any, i: number) => (
                <div key={i} style={{ marginBottom: '0.625rem', fontSize: '0.875rem', color: '#78350f' }}>
                  <div style={{ fontWeight: 600 }}>{md.description || `Missing document ${i + 1}`}</div>
                  {md.reason && <div>{md.reason}</div>}
                  {md.referencedIn && (
                    <div style={{ fontSize: '0.8125rem', color: '#92400e' }}>Referenced in: {md.referencedIn}</div>
                  )}
                </div>
              ))}
              <p style={{ fontSize: '0.8125rem', color: '#92400e' }}>
                Upload the document(s) above, then click "Continue Verification" below.
              </p>
            </div>
          )}

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 600 }}>Upload Documents</h2>
            <span className="badge badge-processing">{documents.length} document(s)</span>
          </div>

          {/* Document List */}
          {documents.length > 0 && (
            <div style={{ marginBottom: '1rem' }}>
              {documents.map((doc: any, i: number) => (
                <div
                  key={i}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '0.5rem',
                    padding: '0.5rem 0.75rem', background: 'var(--gray-50)',
                    borderRadius: 'var(--radius)', border: '1px solid var(--gray-200)',
                    marginBottom: '0.375rem'
                  }}
                >
                  <span style={{ color: 'var(--gray-400)', fontWeight: 600, fontSize: '0.8125rem' }}>#{i + 1}</span>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--gray-500)"
                       strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                    <polyline points="14 2 14 8 20 8"/>
                  </svg>
                  <span style={{ fontWeight: 500, fontSize: '0.875rem', flex: 1 }}>
                    {doc.label || doc.fileName}
                  </span>
                  <span className="badge badge-done" style={{ fontSize: '0.6875rem' }}>{doc.fileType}</span>
                </div>
              ))}
            </div>
          )}

          {/* Upload Form */}
          <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-end', marginBottom: '1rem' }}>
            <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
              <input
                className="form-input"
                placeholder="Label (e.g., 'Sale Deed')"
                value={uploadLabel}
                onChange={e => setUploadLabel(e.target.value)}
              />
            </div>
            <div
              className="file-upload-area"
              style={{ padding: '0.625rem 1rem', cursor: 'pointer', flexShrink: 0 }}
              onClick={() => document.getElementById('verify-file-input')?.click()}
            >
              <input
                id="verify-file-input"
                type="file"
                accept=".pdf,.txt,.docx,.jpg,.jpeg,.png"
                onChange={e => setUploadFile(e.target.files?.[0] || null)}
              />
              {uploadFile ? (
                <span style={{ fontWeight: 500, fontSize: '0.8125rem', color: 'var(--success)' }}>
                  ✓ {uploadFile.name}
                </span>
              ) : (
                <span style={{ fontSize: '0.8125rem', color: 'var(--gray-500)' }}>Browse...</span>
              )}
            </div>
            <button
              className="btn btn-primary btn-sm"
              onClick={handleAddDocument}
              disabled={!uploadFile || uploading}
            >
              {uploading ? <><span className="spinner" /> Adding...</> : 'Add'}
            </button>
          </div>

          {/* Run / Continue Verification Button */}
          {documents.length >= 1 && (
            <button
              className="btn btn-primary"
              onClick={handleRunVerification}
              style={{ width: '100%', justifyContent: 'center' }}
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                   strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polygon points="5 3 19 12 5 21 5 3"/>
              </svg>
              {sessionStatus === 'NEEDS_MORE_DOCUMENTS' ? 'Continue Verification' : 'Run Verification'} ({documents.length} document{documents.length > 1 ? 's' : ''})
            </button>
          )}

          <button className="btn btn-secondary btn-sm" style={{ marginTop: '0.75rem' }} onClick={handleReset}>
            ← Cancel & go back
          </button>
        </div>
      )}

      {/* Running verification */}
      {isRunning && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 600 }}>Verification in Progress</h2>
            {getStatusBadge(sessionStatus)}
          </div>

          {currentPhase && (
            <div style={{
              padding: '0.75rem 1rem', borderRadius: 'var(--radius)',
              background: 'var(--primary-light)', border: '1px solid var(--primary)30',
              marginBottom: '1rem', fontWeight: 500, fontSize: '0.9375rem',
              color: 'var(--primary)', display: 'flex', alignItems: 'center', gap: '0.5rem'
            }}>
              <span className="spinner" style={{ width: '1.25rem', height: '1.25rem', flexShrink: 0 }} />
              {currentPhase}
            </div>
          )}

          {thinkingSteps.length > 0 && (
            <div>
              <label style={{ fontWeight: 600, fontSize: '0.8125rem', color: 'var(--gray-500)', marginBottom: '0.5rem', display: 'block' }}>
                AI Thinking Log
              </label>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.375rem' }}>
                {thinkingSteps.map((step, i) => (
                  <div
                    key={i}
                    style={{
                      padding: '0.5rem 0.75rem', borderRadius: 'var(--radius)',
                      background: step.startsWith('🤖') ? '#f0fdf4' : step.startsWith('⚠️') ? '#fffbeb' : 'var(--gray-50)',
                      border: '1px solid var(--gray-200)',
                      fontSize: '0.8125rem', color: 'var(--gray-700)',
                      animation: i === thinkingSteps.length - 1 ? 'fadeIn 0.3s ease' : 'none',
                      display: 'flex', alignItems: 'center', gap: '0.5rem'
                    }}
                  >
                    <span>{step}</span>
                  </div>
                ))}
                <div style={{
                  padding: '0.5rem 0.75rem', borderRadius: 'var(--radius)',
                  background: 'var(--gray-50)', border: '1px solid var(--gray-200)',
                  fontSize: '0.8125rem', color: 'var(--gray-500)', fontStyle: 'italic',
                  display: 'flex', alignItems: 'center', gap: '0.5rem'
                }}>
                  <span className="spinner" style={{ width: '0.75rem', height: '0.75rem' }} />
                  Processing...
                </div>
              </div>
            </div>
          )}

          <button className="btn btn-secondary btn-sm" style={{ marginTop: '1rem' }} onClick={handleReset}>
            ← Cancel
          </button>
        </div>
      )}

      {/* Final report */}
      {sessionStatus === 'DONE' && report && (
        <div className="card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 600 }}>{report.title || 'Legal Verification Report'}</h2>
            {report.verdict && getVerdictBadge(report.verdict)}
          </div>

          {report.overallVerdict && (
            <div style={{
              padding: '1rem', borderRadius: 'var(--radius)',
              background: report.verdict === 'PASS' ? '#f0fdf4' : report.verdict === 'FAIL' ? '#fef2f2' : '#fffbeb',
              border: `1px solid ${report.verdict === 'PASS' ? '#86efac' : report.verdict === 'FAIL' ? '#fecaca' : '#fde68a'}`,
              marginBottom: '1.5rem'
            }}>
              <p style={{ fontWeight: 500, fontSize: '0.9375rem', lineHeight: 1.6 }}>{report.overallVerdict}</p>
            </div>
          )}

          {report.documentsAnalyzed && report.documentsAnalyzed.length > 0 && (
            <div style={{ marginBottom: '1.5rem' }}>
              <label style={{ fontWeight: 600, fontSize: '0.875rem', marginBottom: '0.75rem', display: 'block' }}>
                Document Analysis ({report.documentsAnalyzed.length})
              </label>
              {report.documentsAnalyzed.map((doc: any, i: number) => (
                <div key={i} style={{
                  padding: '0.75rem', borderRadius: 'var(--radius)',
                  border: '1px solid var(--gray-200)', marginBottom: '0.625rem',
                  background: 'var(--gray-50)'
                }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                    <span style={{ fontWeight: 600, fontSize: '0.875rem' }}>{doc.name || doc.type || `Document ${i + 1}`}</span>
                    <span className={`badge ${
                      doc.status === 'VALID' ? 'badge-done'
                        : doc.status === 'MINOR_ISSUES' ? 'badge-pending'
                        : doc.status === 'INVALID' ? 'badge-failed'
                        : 'badge-pending'
                    }`} style={{ fontSize: '0.6875rem' }}>
                      {doc.status || 'ANALYZED'}
                    </span>
                  </div>

                  {doc.keyDetails && (
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.375rem', marginBottom: '0.5rem', fontSize: '0.8125rem' }}>
                      {doc.keyDetails.date && (
                        <div><span style={{ color: 'var(--gray-500)' }}>Date:</span> {doc.keyDetails.date}</div>
                      )}
                      {doc.keyDetails.parties && doc.keyDetails.parties.length > 0 && (
                        <div><span style={{ color: 'var(--gray-500)' }}>Parties:</span> {doc.keyDetails.parties.join(', ')}</div>
                      )}
                      {doc.keyDetails.referenceNumbers && doc.keyDetails.referenceNumbers.length > 0 && (
                        <div><span style={{ color: 'var(--gray-500)' }}>Ref #:</span> {doc.keyDetails.referenceNumbers.join(', ')}</div>
                      )}
                      {doc.keyDetails.amounts && doc.keyDetails.amounts.length > 0 && (
                        <div><span style={{ color: 'var(--gray-500)' }}>Amounts:</span> {doc.keyDetails.amounts.join(', ')}</div>
                      )}
                    </div>
                  )}

                  {doc.findings && doc.findings.length > 0 && (
                    <div style={{ marginBottom: '0.375rem' }}>
                      {doc.findings.map((f: string, fi: number) => (
                        <p key={fi} style={{ fontSize: '0.8125rem', color: 'var(--gray-600)', marginBottom: '0.125rem' }}>
                          • {f}
                        </p>
                      ))}
                    </div>
                  )}

                  {doc.issues && doc.issues.length > 0 && (
                    <div style={{ padding: '0.5rem', borderRadius: 'var(--radius)', background: '#fef2f2', border: '1px solid #fecaca' }}>
                      {doc.issues.map((issue: string, ii: number) => (
                        <p key={ii} style={{ fontSize: '0.8125rem', color: '#991b1b', marginBottom: '0.125rem' }}>
                          ⚠️ {issue}
                        </p>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {report.crossReferenceCheck && report.crossReferenceCheck.length > 0 && (
            <div style={{ marginBottom: '1.5rem' }}>
              <label style={{ fontWeight: 600, fontSize: '0.875rem', marginBottom: '0.75rem', display: 'block' }}>
                Cross-Reference Check
              </label>
              {report.crossReferenceCheck.map((cr: any, i: number) => {
                const crColor = cr.status === 'MATCH' ? 'var(--success)'
                  : cr.status === 'MISMATCH' ? 'var(--danger)'
                  : 'var(--gray-500)';
                return (
                  <div key={i} style={{
                    padding: '0.75rem', borderRadius: 'var(--radius)',
                    border: `1px solid ${crColor}30`, marginBottom: '0.5rem',
                    background: `${crColor}05`
                  }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.25rem' }}>
                      <span style={{ fontWeight: 600, fontSize: '0.875rem' }}>
                        {cr.documents ? cr.documents.join(' ↔ ') : 'Cross-reference'}
                      </span>
                      <span style={{
                        fontSize: '0.75rem', fontWeight: 600, padding: '0.125rem 0.5rem',
                        borderRadius: '9999px', background: `${crColor}15`, color: crColor
                      }}>
                        {cr.status || 'CHECKED'}
                      </span>
                    </div>
                    <p style={{ fontSize: '0.8125rem', color: 'var(--gray-600)', marginBottom: '0.125rem' }}>
                      <span style={{ fontWeight: 500 }}>Field:</span> {cr.field || cr.detail}
                    </p>
                    {cr.valueInDocA && cr.valueInDocB && (
                      <div style={{ fontSize: '0.8125rem', color: 'var(--gray-500)' }}>
                        <span>Doc A: {cr.valueInDocA} | Doc B: {cr.valueInDocB}</span>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          {report.recommendations && report.recommendations.length > 0 && (
            <div style={{ marginBottom: '1.5rem' }}>
              <label style={{ fontWeight: 600, fontSize: '0.875rem', marginBottom: '0.5rem', display: 'block' }}>
                Recommendations
              </label>
              <ul style={{ paddingLeft: '1.25rem' }}>
                {report.recommendations.map((rec: string, i: number) => (
                  <li key={i} style={{ fontSize: '0.875rem', color: 'var(--gray-700)', marginBottom: '0.25rem' }}>
                    {rec}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <button className="btn btn-primary" onClick={handleReset}>
            Verify Another Document
          </button>
        </div>
      )}
    </div>
  );
}
