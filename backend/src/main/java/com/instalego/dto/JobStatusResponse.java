package com.instalego.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobStatusResponse {
    private Long id;
    private Long bankId;
    private String bankName;
    private String status;
    private String extractedJson;
    private String verificationReport;
    private String errorMessage;
    private boolean outputAvailable;
    private String createdAt;
}
