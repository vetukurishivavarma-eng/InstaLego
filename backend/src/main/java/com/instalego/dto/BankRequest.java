package com.instalego.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BankRequest {
    @NotBlank(message = "Bank name is required")
    private String name;
}
