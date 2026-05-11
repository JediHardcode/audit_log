package kirill.ked.auditlog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Basic-auth security for the audit-log API.
 *
 * <p>Roles per {@code .specs/query-api/design.md#auth}:
 * <ul>
 *   <li>{@code AUDIT_WRITER} — required for {@code POST /audit-events}</li>
 *   <li>{@code AUDIT_READER} — required for {@code GET /audit-events}</li>
 * </ul>
 *
 * <p>Stateless (no HTTP session), CSRF disabled — this is a machine-to-machine API.
 * Real user store is out of scope; users are configured via
 * {@code spring.security.user.*} properties.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/audit-events")
                        .hasRole("AUDIT_WRITER")
                        .requestMatchers(HttpMethod.GET, "/audit-events")
                        .hasRole("AUDIT_READER")
                        .anyRequest()
                        .authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
