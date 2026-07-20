import { useState, useEffect, useRef, useCallback } from 'react';
import { api, Bank, MySession } from '../api/client';

const POLL_INTERVAL_MS = 1500;
const ACTIVE_STATUSES = ['PENDING', 'NEEDS_MORE_DOCUMENTS'];
const RUNNING_STATUSES = ['EXTRACTING', 'VERIFYING'];

export default function UserPage() {
  const [banks, setBanks] = useState<Bank[]>([]);
  const [selectedBankId, setSelectedBankId] = useState<number | ''>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const [recentSessions, setRecentSessions] = useState<MySession[]>([]);

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
  const [downloadingPdf, setDownloadingPdf] = useState(false);

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

  const loadRecentSessions = useCallback(async () => {
    try {
      const sessions = await api.getMySessions();
      setRecentSessions(sessions.slice(0, 5));
    } catch {
      setRecentSessions([]);
    }
  }, []);

  useEffect(() => {
    loadBanks();
    loadRecentSessions();
  }, [loadBanks, loadRecentSessions]);

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
          if (status.documents) setDocuments(status.documents);
          if (status.report) setReport(status.report);
          if (Array.isArray(status.missingDocuments)) setMissingDocuments(status.missingDocuments);

          if (status.status === 'DONE' || status.status === 'NEEDS_MORE_DOCUMENTS') {
            if (pollRef.current) clearInterval(pollRef.current);
            if (status.status === 'DONE') loadRecentSessions();
          }
          if (status.status === 'FAILED') {
            setError(status.errorMessage || 'Verification failed');
            if (pollRef.current) clearInterval(pollRef.current);
          }
        } catch (e: any) {
          console.warn('Poll failed:', e.message);
        }
      }, POLL_INTERVAL_MS);

      return () => {
        if (pollRef.current) clearInterval(pollRef.current);
      };
    }
  }, [sessionId, loadRecentSessions]);

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
    const previousStatus = sessionStatus;
    setError('');
    setSuccess('');
    setThinkingSteps(['Starting analysis...']);
    setReport(null);
    setMissingDocuments([]);
    // Show the loading screen immediately on click rather than waiting on the network
    // round-trip — the poll corrects this to the real status moments later regardless.
    setSessionStatus('EXTRACTING');
    setCurrentPhase('Extracting text from your documents...');
    try {
      await api.runVerification(sessionId);
    } catch (e: any) {
      setSessionStatus(previousStatus);
      setError(e.message);
    }
  };

  const handleDownloadPdf = async () => {
    if (!sessionId) return;
    setDownloadingPdf(true);
    setError('');
    try {
      const blob = await api.downloadOpinionPdf(sessionId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `legal-opinion-${sessionId}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setDownloadingPdf(false);
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

  const getVerdictBannerClass = (verdict: string) => {
    if (verdict === 'PASS') return 'verdict-banner pass';
    if (verdict === 'FAIL' || verdict === 'ERROR') return 'verdict-banner fail';
    return 'verdict-banner incomplete';
  };

  const getVerdictLabel = (verdict: string) => {
    if (verdict === 'PASS') return 'Legally Valid — Verdict: Pass';
    if (verdict === 'FAIL') return 'Verdict: Fail — Defects Found';
    if (verdict === 'INCOMPLETE') return 'Verdict: Incomplete — Documents Pending';
    if (verdict === 'ERROR') return 'Verdict: Analysis Error';
    return `Verdict: ${verdict}`;
  };

  const isUploadStage = sessionId && ACTIVE_STATUSES.includes(sessionStatus);
  const isRunning = RUNNING_STATUSES.includes(sessionStatus);

  // Stepper phase: 1 = select bank, 2 = upload/run, 3 = opinion ready
  const stepPhase = !sessionId ? 1 : (sessionStatus === 'DONE' ? 3 : 2);

  return (
    <div>
      <div className="page-header">
        <h1>Submit for Legal Opinion</h1>
        <p>Upload your legal documents and receive a structured legal opinion — in the bank's own format when one has been configured, or our standard format otherwise.</p>
      </div>

      <div className="stepper">
        <div className={`stepper-step ${stepPhase === 1 ? 'active' : 'done'}`}>
          <span className="stepper-dot">{stepPhase > 1 ? '✓' : '1'}</span>
          <span className="stepper-label">Select Bank</span>
        </div>
        <div className="stepper-line" />
        <div className={`stepper-step ${stepPhase === 2 ? 'active' : stepPhase > 2 ? 'done' : ''}`}>
          <span className="stepper-dot">{stepPhase > 2 ? '✓' : '2'}</span>
          <span className="stepper-label">Upload Documents</span>
        </div>
        <div className="stepper-line" />
        <div className={`stepper-step ${stepPhase === 3 ? 'active' : ''}`}>
          <span className="stepper-dot">3</span>
          <span className="stepper-label">Legal Opinion</span>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {success && <div className="alert alert-success">{success}</div>}

      {/* Bank Selection */}
      {!sessionId && (
        <>
          <div className="card" style={{ marginBottom: '1.5rem' }}>
            <div className="form-group">
              <label htmlFor="bank-select">Select Bank</label>
              {loading ? (
                <div style={{ padding: '0.5rem 0' }}><span className="spinner" /> Loading banks...</div>
              ) : banks.length === 0 ? (
                <p className="form-hint">No banks configured yet. Ask an admin to set up a bank first.</p>
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

            <button className="btn btn-primary btn-block" onClick={handleStartSession} disabled={!selectedBankId || loading}>
              Begin Submission
            </button>
          </div>

          {recentSessions.length > 0 && (
            <div className="card">
              <label style={{ fontWeight: 700, fontSize: '0.8125rem', textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--ink-muted)', marginBottom: '0.75rem', display: 'block' }}>
                Recent Submissions
              </label>
              <div className="dossier" style={{ border: 'none' }}>
                {recentSessions.map(s => (
                  <div key={s.id} className="dossier-item" style={{ border: '1px solid var(--rule)', borderRadius: 'var(--radius)', marginBottom: '0.5rem' }}>
                    <span className="doc-name">{s.bankName || `Bank #${s.bankId}`}</span>
                    <span style={{ fontSize: '0.75rem', color: 'var(--ink-faint)' }}>
                      {s.createdAt ? new Date(s.createdAt).toLocaleDateString() : ''}
                    </span>
                    <span className={`badge ${s.status === 'DONE' ? 'badge-done' : s.status === 'FAILED' ? 'badge-failed' : 'badge-pending'}`}>
                      {s.verdict || s.status.replace(/_/g, ' ')}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}

      {/* Upload stage */}
      {isUploadStage && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          {sessionStatus === 'NEEDS_MORE_DOCUMENTS' && missingDocuments.length > 0 && (
            <div className="missing-doc-notice">
              <p className="missing-doc-title">
                {missingDocuments.length} more document{missingDocuments.length > 1 ? 's are' : ' is'} needed to complete your opinion
              </p>
              {missingDocuments.map((md: any, i: number) => (
                <div key={i} className="missing-doc-entry">
                  <div style={{ fontWeight: 600 }}>{md.description || `Missing document ${i + 1}`}</div>
                  {md.reason && <div>{md.reason}</div>}
                  {md.referencedIn && <div style={{ fontSize: '0.8125rem' }}>Referenced in: {md.referencedIn}</div>}
                </div>
              ))}
              <p style={{ fontSize: '0.8125rem', color: 'var(--warning)' }}>
                Upload the document(s) above, then click "Continue".
              </p>
            </div>
          )}

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h2 style={{ fontFamily: 'var(--font-serif)', fontSize: '1.125rem' }}>Case Documents</h2>
            <span className="badge badge-processing">{documents.length} document(s)</span>
          </div>

          {documents.length > 0 && (
            <div className="dossier">
              {documents.map((doc: any, i: number) => (
                <div key={i} className="dossier-item">
                  <span className="doc-index">{String(i + 1).padStart(2, '0')}</span>
                  <span className="doc-name">{doc.label || doc.fileName}</span>
                  <span className="badge badge-done">{doc.fileType}</span>
                </div>
              ))}
            </div>
          )}

          <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-end', marginBottom: '1.25rem' }}>
            <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
              <input
                className="form-input"
                placeholder="Label (e.g., 'Sale Deed')"
                value={uploadLabel}
                onChange={e => setUploadLabel(e.target.value)}
              />
            </div>
            <div
              className={`file-upload-area ${uploadFile ? 'has-file' : ''}`}
              style={{ padding: '0.65rem 1rem', cursor: 'pointer', flexShrink: 0 }}
              onClick={() => document.getElementById('verify-file-input')?.click()}
            >
              <input
                id="verify-file-input"
                type="file"
                accept=".pdf,.txt,.docx,.jpg,.jpeg,.png"
                onChange={e => setUploadFile(e.target.files?.[0] || null)}
              />
              {uploadFile ? (
                <span style={{ fontWeight: 600, fontSize: '0.8125rem', color: 'var(--success)' }}>✓ {uploadFile.name}</span>
              ) : (
                <span style={{ fontSize: '0.8125rem', color: 'var(--ink-muted)' }}>Browse... (up to 30MB)</span>
              )}
            </div>
            <button className="btn btn-primary btn-sm" onClick={handleAddDocument} disabled={!uploadFile || uploading}>
              {uploading ? <><span className="spinner" /> Adding...</> : 'Add'}
            </button>
          </div>

          {documents.length >= 1 && (
            <button className="btn btn-primary btn-block" onClick={handleRunVerification}>
              {sessionStatus === 'NEEDS_MORE_DOCUMENTS' ? 'Continue to Legal Opinion' : 'Submit for Legal Opinion'} ({documents.length} document{documents.length > 1 ? 's' : ''})
            </button>
          )}

          <button className="btn btn-ghost btn-sm" style={{ marginTop: '0.75rem' }} onClick={handleReset}>
            ← Cancel & go back
          </button>
        </div>
      )}

      {/* Running verification */}
      {isRunning && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          <h2 style={{ fontFamily: 'var(--font-serif)', fontSize: '1.125rem', marginBottom: '1rem' }}>Preparing Your Legal Opinion</h2>

          {currentPhase && (
            <div style={{
              padding: '0.75rem 1rem', borderRadius: 'var(--radius)',
              background: 'var(--accent-light)', marginBottom: '1.25rem',
              fontWeight: 600, fontSize: '0.9375rem', color: 'var(--accent)',
              display: 'flex', alignItems: 'center', gap: '0.5rem'
            }}>
              <span className="spinner" style={{ width: '1.1rem', height: '1.1rem', flexShrink: 0 }} />
              {currentPhase}
            </div>
          )}

          {thinkingSteps.length > 0 && (
            <div className="margin-log">
              {thinkingSteps.map((step, i) => {
                const isAi = step.startsWith('🤖');
                const isWarn = step.startsWith('⚠️') || step.startsWith('📎');
                return (
                  <div
                    key={i}
                    className={`margin-log-entry ${isAi ? 'ai' : isWarn ? 'warn' : ''}`}
                    style={{ animation: i === thinkingSteps.length - 1 ? 'fadeIn 0.3s ease' : 'none' }}
                  >
                    {step}
                  </div>
                );
              })}
              <div className="margin-log-entry" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <span className="spinner" style={{ width: '0.7rem', height: '0.7rem' }} />
                Processing...
              </div>
            </div>
          )}

          <button className="btn btn-ghost btn-sm" style={{ marginTop: '1rem' }} onClick={handleReset}>
            ← Cancel
          </button>
        </div>
      )}

      {/* Failed run */}
      {sessionStatus === 'FAILED' && (
        <div className="card">
          <h2 style={{ fontFamily: 'var(--font-serif)', fontSize: '1.125rem', marginBottom: '1rem' }}>Analysis Could Not Complete</h2>
          <div className="alert alert-error" style={{ marginBottom: '1.25rem' }}>
            {error || 'Something went wrong while preparing your legal opinion.'}
          </div>
          <p className="form-hint" style={{ marginBottom: '1.25rem' }}>
            This is often caused by a document with no readable text (e.g. a low-quality scan). You can try
            again, add another copy of the document, or start over.
          </p>
          <div style={{ display: 'flex', gap: '0.75rem' }}>
            <button className="btn btn-primary" onClick={handleRunVerification}>Try Again</button>
            <button className="btn btn-secondary" onClick={handleReset}>Start Over</button>
          </div>
        </div>
      )}

      {/* Final legal opinion */}
      {sessionStatus === 'DONE' && report && (
        <div className="opinion-letter">
          <h2>{report.title || 'Legal Opinion'}</h2>
          <p className="opinion-byline">Prepared by InstaLego · {new Date().toLocaleDateString()}</p>

          {report.verdict && (
            <div className={getVerdictBannerClass(report.verdict)}>{getVerdictLabel(report.verdict)}</div>
          )}

          {report.overallVerdict && (
            <div className="opinion-section">
              <label>Overall Assessment</label>
              <p style={{ fontSize: '0.9375rem', lineHeight: 1.7 }}>{report.overallVerdict}</p>
            </div>
          )}

          {report.documentsAnalyzed && report.documentsAnalyzed.length > 0 && (
            <div className="opinion-section">
              <label>Document-by-Document Analysis ({report.documentsAnalyzed.length})</label>
              {report.documentsAnalyzed.map((doc: any, i: number) => (
                <div key={i} className="doc-analysis-card">
                  <div className="doc-analysis-head">
                    <span className="doc-analysis-title">{doc.name || doc.type || `Document ${i + 1}`}</span>
                    <span className={`badge ${
                      doc.status === 'VALID' ? 'badge-done'
                        : doc.status === 'MINOR_ISSUES' ? 'badge-pending'
                        : doc.status === 'INVALID' ? 'badge-failed'
                        : 'badge-pending'
                    }`}>
                      {doc.status || 'ANALYZED'}
                    </span>
                  </div>

                  {doc.keyDetails && (
                    <div className="doc-details">
                      {doc.keyDetails.date && <div><strong>Date:</strong> {doc.keyDetails.date}</div>}
                      {doc.keyDetails.parties?.length > 0 && <div><strong>Parties:</strong> {doc.keyDetails.parties.join(', ')}</div>}
                      {doc.keyDetails.referenceNumbers?.length > 0 && <div><strong>Ref #:</strong> {doc.keyDetails.referenceNumbers.join(', ')}</div>}
                      {doc.keyDetails.amounts?.length > 0 && <div><strong>Amounts:</strong> {doc.keyDetails.amounts.join(', ')}</div>}
                    </div>
                  )}

                  {doc.findings?.map((f: string, fi: number) => (
                    <p key={fi} className="doc-finding">• {f}</p>
                  ))}

                  {doc.issues?.length > 0 && (
                    <div className="doc-issues">
                      {doc.issues.map((issue: string, ii: number) => <p key={ii}>⚠ {issue}</p>)}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {report.crossReferenceCheck && report.crossReferenceCheck.length > 0 && (
            <div className="opinion-section">
              <label>Cross-Reference Check</label>
              {report.crossReferenceCheck.map((cr: any, i: number) => {
                const color = cr.status === 'MATCH' ? 'var(--success)' : cr.status === 'MISMATCH' ? 'var(--danger)' : 'var(--ink-muted)';
                return (
                  <div key={i} className="crossref-item" style={{ borderColor: color + '55' }}>
                    <div className="crossref-head">
                      <span className="crossref-title">{cr.documents ? cr.documents.join(' ↔ ') : 'Cross-reference'}</span>
                      <span style={{ fontSize: '0.75rem', fontWeight: 700, color }}>{cr.status || 'CHECKED'}</span>
                    </div>
                    <p style={{ fontSize: '0.8125rem', color: 'var(--ink-muted)' }}>
                      <strong>{cr.field || cr.detail}</strong>
                    </p>
                    {cr.valueInDocA && cr.valueInDocB && (
                      <div style={{ fontSize: '0.8125rem', color: 'var(--ink-faint)' }}>
                        Doc A: {cr.valueInDocA} | Doc B: {cr.valueInDocB}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          {report.recommendations && report.recommendations.length > 0 && (
            <div className="opinion-section">
              <label>Recommendations</label>
              <ul style={{ paddingLeft: '1.25rem' }}>
                {report.recommendations.map((rec: string, i: number) => (
                  <li key={i} style={{ fontSize: '0.9375rem', marginBottom: '0.3rem' }}>{rec}</li>
                ))}
              </ul>
            </div>
          )}

          <div style={{ display: 'flex', gap: '0.75rem', marginTop: '2rem', paddingTop: '1.5rem', borderTop: '1px solid var(--rule)' }}>
            <button className="btn btn-primary" onClick={handleDownloadPdf} disabled={downloadingPdf}>
              {downloadingPdf ? <><span className="spinner" /> Preparing PDF...</> : 'Download Legal Opinion (PDF)'}
            </button>
            <button className="btn btn-secondary" onClick={handleReset}>Start a New Submission</button>
          </div>
        </div>
      )}
    </div>
  );
}
