import { useState, useEffect, useCallback } from 'react';
import { api, Bank, FieldSchemaEntry, TemplateUploadResponse } from '../api/client';

export default function AdminPage() {
  const [banks, setBanks] = useState<Bank[]>([]);
  const [newBankName, setNewBankName] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Template upload state
  const [selectedBank, setSelectedBank] = useState<Bank | null>(null);
  const [templateFile, setTemplateFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [templateResponse, setTemplateResponse] = useState<TemplateUploadResponse | null>(null);
  const [editableSchema, setEditableSchema] = useState<FieldSchemaEntry[]>([]);

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

  const handleAddBank = async () => {
    if (!newBankName.trim()) return;
    try {
      setError('');
      await api.createBank(newBankName.trim());
      setNewBankName('');
      await loadBanks();
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
      await api.saveSchema(selectedBank.id, { derivedSchema: editableSchema });
      // Refresh bank list
      await loadBanks();
      setTemplateResponse(null);
      setEditableSchema([]);
      setTemplateFile(null);
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

  return (
    <div>
      <div className="page-header">
        <h1>Admin Panel</h1>
        <p>Manage banks and their document templates</p>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

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
                  <th>Action</th>
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
                      <button
                        className="btn btn-secondary btn-sm"
                        onClick={() => {
                          setSelectedBank(bank);
                          setTemplateResponse(null);
                          setEditableSchema([]);
                          setTemplateFile(null);
                        }}
                      >
                        {selectedBank?.id === bank.id ? 'Editing...' : 'Add Template'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Template Upload Section */}
      {selectedBank && (
        <div className="card">
          <h2 style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '0.75rem' }}>
            Template for: {selectedBank.name}
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
