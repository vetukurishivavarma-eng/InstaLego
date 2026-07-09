package com.instalego.dto;

import lombok.Data;

import java.util.List;

@Data
public class FieldSchemaRequest {
    private List<FieldSchemaEntry> fields;

    @Data
    public static class FieldSchemaEntry {
        private String fieldName;
        private String description;
        private String type; // text, date, number, boolean
        private boolean required;
    }
}
