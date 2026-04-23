package com.example.auditlog.api;

import com.example.auditlog.domain.Outcome;
import com.example.auditlog.persistence.AuditEventEntity;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Value
@Builder
@Jacksonized
public class AuditEventResponse {

    UUID id;
    Instant timestamp;
    String actor;
    String action;
    String resource;
    Outcome outcome;
    Map<String, Object> context;
    String prevHash;
    String eventHash;

    public static AuditEventResponse from(AuditEventEntity entity) {
        return AuditEventResponse.builder()
                .id(entity.getId())
                .timestamp(entity.getTimestamp())
                .actor(entity.getActor())
                .action(entity.getAction())
                .resource(entity.getResource())
                .outcome(entity.getOutcome())
                .context(entity.getContext())
                .prevHash(entity.getPrevHash())
                .eventHash(entity.getEventHash())
                .build();
    }
}
