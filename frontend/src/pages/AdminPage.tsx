import { useState, useEffect, useCallback } from 'react';
import { api, Bank, FieldSchemaEntry, TemplateUploadResponse, BankTemplate, LegalReference } from '../api/client';

export default function AdminPage() {
  const [banks, setBanks] = useState<Bank[]>([]);
  const [newBankName, setNewBankName] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Template upload state
  const [selectedBank, setSelectedBank] = useState<Bank | null>(null);
  const [templateFile, setTemplateFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [templateResponse, setTemplateResponse] = useState<TemplateUploadResponse | null>(null);
  const [editableSchema, setEditableSchema] = useState<FieldSchemaEntry[]>([]);

  // Template view state
  const [viewingBank, setViewingBank] = useState<Bank | null>(null);
  const [bankTemplate, setBankTemplate] = useState<BankTemplate | null>(null);
  const [loadingTemplate, setLoadingTemplate] = useState(false);

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

  // Load template and references when viewing a bank
  useEffect(() => {
    if (viewingBank) {
      loadTemplate(viewingBank.id);
      loadReferences(viewingBank.id);
    }
  }, [viewingBank]);

  const loadTemplate = async (bankId: number) => {
    setLoadingTemplate(true);
    try {
      const tmpl = await api.getTemplate(bankId);
      setBankTemplate(tmpl);
    } catch {
      setBankTemplate(null);
    } finally {
      setLoadingTemplate(false);
    }
  };

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

  const handleTemplateUpload = async () => {
    if (!selectedBank || !templateFile) return;
    setUploading(true);
    setError('');
    try {
      const response = await api.uploadTemplate(selectedBank.id, templateFile);
      setTemplateResponse(response);
      setEditableSchema(response.derivedSchema.length > 0
        ? response.derivedSchema
        : [{ fieldName: '', description: '', type: 'text', required: false }]);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setUploading(false);
    }
  };

  const handleSaveSchema = async () => {
    if (!selectedBank) return;
    try {
      setError('');
      setSuccess('');
      await api.saveSchema(selectedBank.id, { derivedSchema: editableSchema });
      await loadBanks();
      setSuccess('Schema saved successfully!');
      setTemplateResponse(null);
      setEditableSchema([]);
      setTemplateFile(null);
      setSelectedBank(null);
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleDeleteTemplate = async (bank: Bank) => {
    if (!window.confirm(`Are you sure you want to delete the template for "${bank.name}"? This action cannot be undone.`)) {
      return;
    }
    try {
      setError('');
      setSuccess('');
      await api.deleteTemplate(bank.id);
      setSuccess(`Template for "${bank.name}" deleted.`);
      setBankTemplate(null);
      setViewingBank(null);
      await loadBanks();
    } catch (e: any) {
      setError(e.message);
    }
  };

  const updateSchemaField = (index: number, updates: Partial<FieldSchemaEntry>) => {
    setEditableSchema(prev =>
      prev.map((field, i) => i === index ? { ...field, ...updates } : field)
    );
  };

  const addSchemaField = () => {
    setEditableSchema(prev => [
      ...prev,
      { fieldName: '', description: '', type: 'text', required: false },
    ]);
  };

  const removeSchemaField = (index: number) => {
    setEditableSchema(prev => prev.filter((_, i) => i !== index));
  };

  const parseFieldSchema = (schemaJson: string): FieldSchemaEntry[] => {
    try {
      return JSON.parse(schemaJson);
    } catch {
      return [];
    }
  };

  return (
    <div>
      <div className="page-header">
        <h1>Admin Panel</h1>
        <p>Manage banks and their document templates</p>
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
                  <th>Template</th>
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
                      {bankTemplate && viewingBank?.id === bank.id ? (
                        <span className="badge badge-done">Active</span>
                      ) : null}
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: '0.375rem', flexWrap: 'wrap' }}>
                        <button
                          className="btn btn-secondary btn-sm"
                          onClick={() => {
                            setViewingBank(bank);
                            setSelectedBank(null);
                          }}
                        >
                          View
                        </button>
                        <button
                          className="btn btn-secondary btn-sm"
                          onClick={() => {
                            setSelectedBank(bank);
                            setTemplateResponse(null);
                            setEditableSchema([]);
                            setTemplateFile(null);
                            setViewingBank(null);
                          }}
                        >
                          {selectedBank?.id === bank.id ? 'Editing...' : 'Upload'}
                        </button>
                        {bankTemplate && viewingBank?.id === bank.id && (
                          <button
                            className="btn btn-danger btn-sm"
                            onClick={() => handleDeleteTemplate(bank)}
                          >
                            Delete
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* View Template Section */}
      {viewingBank && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          <h2 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '0.75rem' }}>
            Template: {viewingBank.name}
          </h2>

          {loadingTemplate ? (
            <div style={{ padding: '1rem 0' }}><span className="spinner" /> Loading template...</div>
          ) : bankTemplate ? (
            <>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem', marginBottom: '1rem' }}>
                <div>
                  <label style={{ fontWeight: 600, fontSize: '0.8125rem', color: 'var(--gray-500)' }}>Version</label>
                  <p style={{ fontSize: '0.9375rem' }}>v{bankTemplate.version}</p>
                </div>
                <div>
                  <label style={{ fontWeight: 600, fontSize: '0.8125rem', color: 'var(--gray-500)' }}>Created</label>
                  <p style={{ fontSize: '0.9375rem' }}>
                    {bankTemplate.createdAt ? new Date(bankTemplate.createdAt).toLocaleString() : '-'}
                  </p>
                </div>
              </div>

              {/* Schema Fields */}
              <div style={{ marginBottom: '1rem' }}>
                <label style={{ fontWeight: 600, fontSize: '0.875rem', marginBottom: '0.5rem', display: 'block' }}>
                  Field Schema ({parseFieldSchema(bankTemplate.fieldSchema).length} fields)
                </label>
                {parseFieldSchema(bankTemplate.fieldSchema).length > 0 ? (
                  <div className="table-container">
                    <table>
                      <thead>
                        <tr>
                          <th>Field Name</th>
                          <th>Description</th>
                          <th>Type</th>
                          <th>Required</th>
                        </tr>
                      </thead>
                      <tbody>
                        {parseFieldSchema(bankTemplate.fieldSchema).map((field, i) => (
                          <tr key={i}>
                            <td style={{ fontWeight: 500 }}>{field.fieldName}</td>
                            <td style={{ color: 'var(--gray-600)' }}>{field.description}</td>
                            <td><span className="badge badge-pending">{field.type}</span></td>
                            <td>{field.required ? '✅' : '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p style={{ color: 'var(--gray-500)', fontSize: '0.875rem' }}>No fields configured</p>
                )}
              </div>

              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <a
                  href={api.getTemplateDownloadUrl(viewingBank.id)}
                  className="btn btn-primary"
                  target="_blank"
                  rel="noopener noreferrer"
                  style={{ textDecoration: 'none' }}
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                       strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                    <circle cx="12" cy="12" r="3"/>
                  </svg>
                  View Template PDF
                </a>
                <button
                  className="btn btn-danger"
                  onClick={() => handleDeleteTemplate(viewingBank)}
                >
                  Delete Template
                </button>
              </div>
            </>
          ) : (
            <div>
              <p style={{ color: 'var(--gray-500)', marginBottom: '0.75rem' }}>
                No template uploaded for this bank yet.
              </p>
              <button
                className="btn btn-primary btn-sm"
                onClick={() => {
                  setSelectedBank(viewingBank);
                  setViewingBank(null);
                }}
              >
                Upload Template
              </button>
            </div>
          )}

          {/* Legal References Section */}
          <div style={{ marginTop: '2rem', borderTop: '1px solid var(--gray-200)', paddingTop: '1.5rem' }}>
            <h2 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '0.75rem' }}>
              Legal References — {viewingBank.name}
            </h2>
            <p style={{ fontSize: '0.8125rem', color: 'var(--gray-500)', marginBottom: '0.75rem' }}>
              Upload bank policies, regulatory guidelines, or legal requirements. These are used to verify user-submitted documents.
            </p>

            {/* Upload reference doc */}
            <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem', alignItems: 'flex-end' }}>
              <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                <div
                  className={`file-upload-area`}
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

      {/* Template Upload Section */}
      {selectedBank && !viewingBank && (
        <div className="card">
          <h2 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '0.75rem' }}>
            Upload Template for: {selectedBank.name}
          </h2>

          {!templateResponse ? (
            <>
              <div className="form-group">
                <label>Upload a sample/template PDF</label>
                <div
                  className={`file-upload-area ${templateFile ? 'has-file' : ''}`}
                  onClick={() => document.getElementById('template-file-input')?.click()}
                >
                  <input
                    id="template-file-input"
                    type="file"
                    accept=".pdf"
                    onChange={e => setTemplateFile(e.target.files?.[0] || null)}
                  />
                  {templateFile ? (
                    <div>
                      <p style={{ fontWeight: 600, color: 'var(--success)' }}>✓ {templateFile.name}</p>
                      <p style={{ fontSize: '0.8125rem', color: 'var(--gray-500)', marginTop: '0.25rem' }}>
                        {(templateFile.size / 1024).toFixed(1)} KB — click to change
                      </p>
                    </div>
                  ) : (
                    <div>
                      <p style={{ fontWeight: 500, color: 'var(--gray-600)' }}>
                        Drop a PDF here or click to browse
                      </p>
                      <p style={{ fontSize: '0.8125rem', color: 'var(--gray-400)', marginTop: '0.25rem' }}>
                        The template PDF should represent the desired output format
                      </p>
                    </div>
                  )}
                </div>
              </div>

              <button
                className="btn btn-primary"
                onClick={handleTemplateUpload}
                disabled={!templateFile || uploading}
              >
                {uploading ? <><span className="spinner" /> Analyzing...</> : 'Upload & Derive Schema'}
              </button>
            </>
          ) : (
            <>
              <div className="alert alert-info" style={{ marginBottom: '1rem' }}>
                Gemini analyzed the template. Review and edit the derived fields below before saving.
              </div>

              {/* Schema Editor */}
              <div style={{ marginBottom: '1rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
                  <label style={{ fontWeight: 600, fontSize: '0.875rem' }}>Field Schema</label>
                  <button className="btn btn-secondary btn-sm" onClick={addSchemaField}>
                    + Add Field
                  </button>
                </div>

                {editableSchema.map((field, index) => (
                  <div key={index} className="schema-row">
                    <div className="form-group">
                      <input
                        className="form-input"
                        placeholder="Field name"
                        value={field.fieldName}
                        onChange={e => updateSchemaField(index, { fieldName: e.target.value })}
                      />
                    </div>
                    <div className="form-group">
                      <input
                        className="form-input"
                        placeholder="Description"
                        value={field.description}
                        onChange={e => updateSchemaField(index, { description: e.target.value })}
                      />
                    </div>
                    <div className="form-group">
                      <select
                        className="form-input"
                        value={field.type}
                        onChange={e => updateSchemaField(index, { type: e.target.value as any })}
                      >
                        <option value="text">Text</option>
                        <option value="date">Date</option>
                        <option value="number">Number</option>
                        <option value="boolean">Boolean</option>
                      </select>
                    </div>
                    <label className="checkbox-label" style={{ marginTop: '1.5rem' }}>
                      <input
                        type="checkbox"
                        checked={field.required}
                        onChange={e => updateSchemaField(index, { required: e.target.checked })}
                      />
                      Required
                    </label>
                    <button
                      className="btn btn-danger btn-sm"
                      style={{ marginTop: '1.5rem' }}
                      onClick={() => removeSchemaField(index)}
                      disabled={editableSchema.length <= 1}
                    >
                      ✕
                    </button>
                  </div>
                ))}
              </div>

              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button className="btn btn-primary" onClick={handleSaveSchema}>
                  Save Schema
                </button>
                <button
                  className="btn btn-secondary"
                  onClick={() => {
                    setTemplateResponse(null);
                    setEditableSchema([]);
                    setTemplateFile(null);
                  }}
                >
                  Cancel
                </button>
              </div>
            </>
          )}

          <button
            className="btn btn-secondary btn-sm"
            style={{ marginTop: '1rem' }}
            onClick={() => setSelectedBank(null)}
          >
            ← Back to bank list
          </button>
        </div>
      )}
    </div>
  );
}
