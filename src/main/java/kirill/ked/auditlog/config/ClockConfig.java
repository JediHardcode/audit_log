package kirill.ked.auditlog.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a {@link Clock} bean so tests can swap in a fixed/controllable clock for
 * deterministic seeding. Production code uses {@code Clock.systemUTC()}.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
