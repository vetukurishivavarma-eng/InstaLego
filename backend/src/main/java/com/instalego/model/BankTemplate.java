package com.instalego.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "bank_templates")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BankTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(name = "template_pdf_path", nullable = false)
    private String templatePdfPath;

    @Column(name = "field_schema", columnDefinition = "TEXT")
    private String fieldSchema; // JSON string — list of {fieldName, description, type, required}

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
