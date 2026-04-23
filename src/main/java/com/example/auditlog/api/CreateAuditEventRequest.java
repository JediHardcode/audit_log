package com.example.auditlog.api;

import com.example.auditlog.domain.Outcome;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateAuditEventRequest {

    @NotBlank
    private String actor;

    @NotBlank
    private String action;

    @NotBlank
    private String resource;

    @NotNull
    private Outcome outcome;

    private Map<String, Object> context;
}
