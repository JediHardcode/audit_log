package kirill.ked.auditlog.retention;

import kirill.ked.auditlog.persistence.AuditEventEntity;
import kirill.ked.auditlog.persistence.RetentionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionJob {

    private final RetentionRepository retentionRepository;
    private final ObjectMapper objectMapper;

    @Value("${audit.retention.days:365}")
    private int retentionDays;

    @Value("${audit.retention.archive-dir}")
    private String archiveDir;

    /**
     * Archives and deletes events older than audit.retention.days.
     * Deletion happens only after successful archive write.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void run() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<AuditEventEntity> events = retentionRepository.findOlderThan(cutoff);

        if (events.isEmpty()) {
            return;
        }

        Path archiveFile = resolveArchiveFile(cutoff);
        try {
            writeArchive(archiveFile, events);
        } catch (IOException e) {
            log.error("Retention archive write failed — skipping deletion", e);
            return;
        }

        retentionRepository.deleteOlderThan(cutoff);
        log.info("Archived {} events older than {} to {}", events.size(), cutoff, archiveFile);
    }

    private void writeArchive(Path file, List<AuditEventEntity> events) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
            for (AuditEventEntity event : events) {
                writer.write(objectMapper.writeValueAsString(event));
                writer.newLine();
            }
        }
    }

    private Path resolveArchiveFile(Instant cutoff) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(cutoff);
        return Path.of(archiveDir, "audit-archive-" + timestamp + ".jsonl");
    }
}
