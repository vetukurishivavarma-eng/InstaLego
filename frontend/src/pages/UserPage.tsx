import { useState, useEffect, useRef, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api, Bank } from '../api/client';

const POLL_INTERVAL_MS = 1500;

interface ChatTurn {
  role: 'user' | 'assistant';
  content: string;
}

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

  // Chat state (follow-up Q&A once the report is ready)
  const [chatHistory, setChatHistory] = useState<ChatTurn[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [asking, setAsking] = useState(false);
  const [chatError, setChatError] = useState('');
  const chatEndRef = useRef<HTMLDivElement>(null);

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

  // Poll session status
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

          if (status.status === 'DONE' && status.report) {
            setReport(status.report);
            if (Array.isArray(status.chatHistory)) {
              setChatHistory(status.chatHistory);
            }
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
      const result = await api.addVerificationDocument(sessionId, uploadFile, label);
      setSuccess(`"${label}" added to verification`);
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
      // Start with initial thinking step
      setThinkingSteps(['📄 Starting verification...']);
      const result = await api.runVerification(sessionId);
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
    setUploadFile(null);
    setUploadLabel('');
    setSuccess('');
    setError('');
    setChatHistory([]);
    setChatInput('');
    setChatError('');
    if (pollRef.current) clearInterval(pollRef.current);
  };

  const handleAskQuestion = async () => {
    const question = chatInput.trim();
    if (!sessionId || !question || asking) return;

    setChatError('');
    setChatInput('');
    setAsking(true);
    // Optimistically show the user's question right away
    setChatHistory(prev => [...prev, { role: 'user', content: question }]);

    try {
      const result = await api.askVerification(sessionId, question);
      if (Array.isArray(result.chatHistory)) {
        setChatHistory(result.chatHistory);
      } else if (result.answer) {
        setChatHistory(prev => [...prev, { role: 'assistant', content: result.answer }]);
      }
    } catch (e: any) {
      setChatError(e.message || 'Failed to get an answer. Please try again.');
      // Roll back the optimistic user turn since it wasn't answered
      setChatHistory(prev => prev.slice(0, -1));
      setChatInput(question);
    } finally {
      setAsking(false);
    }
  };

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [chatHistory, asking]);

  const getStatusBadge = (status: string) => {
    const cls =
      status === 'DONE' ? 'badge-done'
        : status === 'FAILED' ? 'badge-failed'
        : status === 'VERIFYING' || status === 'EXTRACTING' ? 'badge-processing'
        : 'badge-pending';
    return <span className={`badge ${cls}`}>{status}</span>;
  };

  const getVerdictBadge = (verdict: string) => {
    const cls = verdict === 'PASS' ? 'badge-done'
      : verdict === 'PASS_WITH_CAVEATS' ? 'badge-pending'
      : verdict === 'FAIL' || verdict === 'ERROR' ? 'badge-failed'
      : 'badge-pending';
    const label = verdict === 'PASS' ? '✅ Pass'
      : verdict === 'PASS_WITH_CAVEATS' ? '⚠️ Pass with Caveats'
      : verdict === 'FAIL' ? '❌ Fail'
      : verdict === 'ERROR' ? '⚠️ Error'
      : verdict;
    return <span className={`badge ${cls}`}>{label}</span>;
  };

  return (
    <div>
      <div className="page-header">
        <h1>Verify Legal Documents</h1>
        <p>Upload multiple legal documents and verify them against each other with AI-powered cross-referencing</p>
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
            Start Verification Session
          </button>
        </div>
      )}

      {/* Document Upload (during session) */}
      {sessionId && sessionStatus === 'PENDING' && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 600 }}>Upload Documents</h2>
            <span className="badge badge-processing">{documents.length} document(s)</span>
          </div>

          <p style={{ fontSize: '0.875rem', color: 'var(--gray-500)', marginBottom: '1rem' }}>
            Add documents one at a time. The AI will cross-reference all documents when you click "Run Verification".
          </p>

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

          {/* Run Verification Button */}
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
              Run Verification ({documents.length} document{documents.length > 1 ? 's' : ''})
            </button>
          )}

          <button className="btn btn-secondary btn-sm" style={{ marginTop: '0.75rem' }} onClick={handleReset}>
            ← Cancel & go back
          </button>
        </div>
      )}

      {/* Running verification */}
      {sessionStatus && sessionStatus !== 'PENDING' && sessionStatus !== 'DONE' && sessionStatus !== 'FAILED' && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 600 }}>Verification in Progress</h2>
            {getStatusBadge(sessionStatus)}
          </div>

          {/* Current Phase */}
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

          {/* Thinking Steps - Streaming */}
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
                    {step.startsWith('🤖') ? (
                      <span style={{ fontSize: '1rem' }}>🤖</span>
                    ) : step.startsWith('⚠️') ? (
                      <span style={{ fontSize: '1rem' }}>⚠️</span>
                    ) : (
                      <span style={{ fontSize: '1rem' }}>📄</span>
                    )}
                    <span>{step.replace(/^[🤖📄⚠️]\s*/, '')}</span>
                  </div>
                ))}
                {/* Animated "thinking" indicator during verification */}
                {(sessionStatus === 'VERIFYING' || sessionStatus === 'EXTRACTING') && (
                  <div style={{
                    padding: '0.5rem 0.75rem', borderRadius: 'var(--radius)',
                    background: 'var(--gray-50)', border: '1px solid var(--gray-200)',
                    fontSize: '0.8125rem', color: 'var(--gray-500)', fontStyle: 'italic',
                    display: 'flex', alignItems: 'center', gap: '0.5rem'
                  }}>
                    <span className="spinner" style={{ width: '0.75rem', height: '0.75rem' }} />
                    Processing...
                  </div>
                )}
              </div>
            </div>
          )}

          <button className="btn btn-secondary btn-sm" style={{ marginTop: '1rem' }} onClick={handleReset}>
            ← Cancel
          </button>
        </div>
      )}

      {/* Results */}
      {report && (
        <div className="card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 600 }}>{report.title || 'Verification Report'}</h2>
            {report.verdict && getVerdictBadge(report.verdict)}
          </div>

          {/* Chat-style conversation: initial AI summary + follow-up Q&A */}
          <div style={{
            border: '1px solid var(--gray-200)', borderRadius: 'var(--radius)',
            background: 'var(--gray-50)', marginBottom: '1rem', overflow: 'hidden'
          }}>
            <div style={{
              maxHeight: '32rem', overflowY: 'auto', padding: '1rem',
              display: 'flex', flexDirection: 'column', gap: '0.75rem'
            }}>
              {/* Initial AI summary bubble */}
              <div style={{
                background: 'white', border: '1px solid var(--gray-200)',
                borderRadius: 'var(--radius)', padding: '0.875rem 1rem',
                fontSize: '0.875rem', lineHeight: 1.6
              }}>
                <div className="chat-markdown">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>
                    {report.conversationalSummary || report.overallVerdict || 'Analysis complete.'}
                  </ReactMarkdown>
                </div>
              </div>

              {/* Follow-up Q&A turns */}
              {chatHistory.map((turn, i) => (
                <div key={i} style={{ alignSelf: turn.role === 'user' ? 'flex-end' : 'flex-start', maxWidth: '85%' }}>
                  {turn.role === 'user' ? (
                    <div style={{
                      background: 'var(--primary)', color: 'white',
                      borderRadius: 'var(--radius)', padding: '0.625rem 0.875rem',
                      fontSize: '0.875rem', lineHeight: 1.5
                    }}>
                      {turn.content}
                    </div>
                  ) : (
                    <div style={{
                      background: 'white', border: '1px solid var(--gray-200)',
                      borderRadius: 'var(--radius)', padding: '0.875rem 1rem',
                      fontSize: '0.875rem', lineHeight: 1.6
                    }}>
                      <div className="chat-markdown">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{turn.content}</ReactMarkdown>
                      </div>
                    </div>
                  )}
                </div>
              ))}

              {/* Thinking indicator while waiting for an answer */}
              {asking && (
                <div style={{ alignSelf: 'flex-start' }}>
                  <div style={{
                    background: 'white', border: '1px solid var(--gray-200)',
                    borderRadius: 'var(--radius)', padding: '0.625rem 0.875rem',
                    fontSize: '0.8125rem', color: 'var(--gray-500)',
                    display: 'flex', alignItems: 'center', gap: '0.5rem'
                  }}>
                    <span className="spinner" style={{ width: '0.875rem', height: '0.875rem' }} />
                    Thinking...
                  </div>
                </div>
              )}
              <div ref={chatEndRef} />
            </div>

            {/* Ask a follow-up question */}
            <div style={{
              display: 'flex', gap: '0.5rem', padding: '0.75rem',
              borderTop: '1px solid var(--gray-200)', background: 'white'
            }}>
              <input
                className="form-input"
                placeholder='Ask a question about this document — e.g. "What is a link document?"'
                value={chatInput}
                onChange={e => setChatInput(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleAskQuestion(); } }}
                disabled={asking}
                style={{ flex: 1 }}
              />
              <button
                className="btn btn-primary btn-sm"
                onClick={handleAskQuestion}
                disabled={asking || !chatInput.trim()}
              >
                {asking ? <span className="spinner" /> : 'Ask'}
              </button>
            </div>
          </div>
          {chatError && <div className="alert alert-error">{chatError}</div>}

          <details style={{ marginBottom: '1.5rem' }}>
            <summary style={{ cursor: 'pointer', fontWeight: 600, fontSize: '0.8125rem', color: 'var(--gray-500)', marginBottom: '0.75rem' }}>
              View full structured report
            </summary>

          {/* Thinking Steps */}
          {thinkingSteps.length > 0 && (
            <div style={{ marginBottom: '1.5rem' }}>
              <label style={{ fontWeight: 600, fontSize: '0.8125rem', color: 'var(--gray-500)', marginBottom: '0.5rem', display: 'block' }}>
                AI Reasoning Process
              </label>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                {thinkingSteps.map((step, i) => (
                  <div key={i} style={{
                    padding: '0.375rem 0.75rem', fontSize: '0.8125rem',
                    color: 'var(--gray-600)', borderLeft: '2px solid var(--gray-300)',
                    marginLeft: '0.5rem'
                  }}>
                    {step}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Document Analysis */}
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

          {/* Cross-Reference Check */}
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

          {/* Recommendations */}
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
          </details>

          <button className="btn btn-primary" onClick={handleReset}>
            Verify Another Set of Documents
          </button>
        </div>
      )}
    </div>
  );
}
