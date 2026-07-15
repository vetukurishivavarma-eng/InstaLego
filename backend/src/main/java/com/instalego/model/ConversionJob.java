package com.instalego.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "conversion_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversionJob {

    public enum Status {
        PENDING, PROCESSING, DONE, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(name = "source_file_path", nullable = false)
    private String sourceFilePath;

    @Column(name = "source_file_type", nullable = false)
    private String sourceFileType; // PDF, IMAGE, DOCX

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private Status status = Status.PENDING;

    @Column(name = "extracted_json", columnDefinition = "TEXT")
    private String extractedJson; // JSONB — structured data from LLM

    @Column(name = "output_file_path")
    private String outputFilePath;

    @Column(name = "verification_report", columnDefinition = "TEXT")
    private String verificationReport; // JSON — verification findings against bank legal references

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
