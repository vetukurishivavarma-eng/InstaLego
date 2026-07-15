package com.instalego.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "verification_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerificationJob {

    public enum Status {
        PENDING, EXTRACTING, VERIFYING, NEEDS_MORE_DOCUMENTS, DONE, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private Status status = Status.PENDING;

    /**
     * JSON array of uploaded document metadata:
     * [{"fileName":"...", "filePath":"...", "fileType":"...", "orderIndex":0, "label":"..."}, ...]
     */
    @Column(name = "documents_json", columnDefinition = "TEXT")
    private String documentsJson;

    /**
     * JSON of the final verification report (formatted according to admin's report template)
     */
    @Column(name = "report_json", columnDefinition = "TEXT")
    private String reportJson;

    /**
     * JSON array of reasoning/thinking steps:
     * [{"phase":"Extracting text from Sale Deed...", "detail":"Found reference number ABC123", "order":0}, ...]
     */
    @Column(name = "thinking_steps", columnDefinition = "TEXT")
    private String thinkingSteps;

    /**
     * Current phase description for the frontend to display in real-time
     */
    @Column(name = "current_phase", columnDefinition = "TEXT")
    private String currentPhase;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * JSON array of documents that are referenced inside the uploaded documents (e.g. a prior
     * sale deed, a link document, an NOC) but were not themselves uploaded. Verification cannot
     * conclude PASS/FAIL until these are provided:
     * [{"description":"...", "reason":"...", "referencedIn":"..."}, ...]
     */
    @Column(name = "missing_documents_json", columnDefinition = "TEXT")
    private String missingDocumentsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
