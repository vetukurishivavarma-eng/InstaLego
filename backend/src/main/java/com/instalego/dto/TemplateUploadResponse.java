package com.instalego.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateUploadResponse {
    private Long templateId;
    private Long bankId;
    private String templatePdfPath;
    private Integer version;
    private List<FieldSchemaEntry> derivedSchema;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FieldSchemaEntry {
        private String fieldName;
        private String description;
        private String type;
        private boolean required;
    }
}
