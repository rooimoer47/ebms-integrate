package dev.ebms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic authentication over the service APIs.
 *
 * <p>All three clients already send Basic credentials from {@code osdbk.ebms.service-secret} and
 * have done all along -- there was simply no Spring Security on the classpath to read them. So
 * this closes an open MSH admin surface at no cost to jms-producer, jms-consumer or cpa-service.
 *
 * <p>Two surfaces are deliberately left open, and both would break loudly if they were not:
 * <ul>
 *   <li>{@code /ebms/msh} is the <em>partner-facing</em> ebMS endpoint. Partners authenticate with
 *       a client certificate and sign their messages; they have no Basic credentials and never
 *       will. Requiring them here would reject every inbound message from every partner.</li>
 *   <li>{@code /actuator/health} is what the kubelet probes. A 401 there is an unready pod and
 *       then a CrashLoopBackOff.</li>
 * </ul>
 *
 * <p>{@code /actuator/prometheus} and {@code /actuator/info} are <em>not</em> left open. Nothing
 * scrapes them today -- neither chart defines a ServiceMonitor or scrape annotation, and metrics
 * leave over OTLP -- so if one is added later it has to carry the credentials.
 */
@Configuration
public class ApiSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityConfig.class);

    /** The paths that carry the client contract, plus the actuator endpoints that are not probes. */
    static final String[] PROTECTED_PATHS = {
            "/service/rest/**", "/api/**", "/actuator/prometheus", "/actuator/info"
    };

    private final EbmsSecurityProperties properties;

    public ApiSecurityConfig(EbmsSecurityProperties properties) {
        this.properties = properties;
        properties.getApi().validate();
    }

    @Bean
    UserDetailsService apiUserDetailsService() {
        EbmsSecurityProperties.Api api = properties.getApi();
        if (!api.isEnabled()) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(User.withUsername(api.getUsername())
                // {noop}: the credential is a shared service account supplied as plaintext in the
                // environment, so hashing it protects nothing that is not already readable -- and
                // Basic auth authenticates every single request, where a BCrypt comparison would
                // cost more than the message handling it guards.
                .password("{noop}" + api.getPassword())
                .authorities("ROLE_SERVICE")
                .build());
    }

    @Bean
    @Order(1)
    SecurityFilterChain serviceApiSecurity(HttpSecurity http) throws Exception {
        if (!properties.getApi().isEnabled()) {
            log.warn("Service API authentication is DISABLED (ebms.security.api.enabled=false). "
                    + "Every endpoint under {} is open to anyone who can reach this pod.",
                    String.join(", ", PROTECTED_PATHS));
            return http.securityMatcher(PROTECTED_PATHS)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .build();
        }
        return http.securityMatcher(PROTECTED_PATHS)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(basic -> basic.realmName("ebms"))
                // No browser, no session, no form: a CSRF token would only reject the clients.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /**
     * Everything else -- the partner MSH and the health probes. Declared explicitly rather than
     * left to fall off the end of the chain, so that what is open is readable in one place.
     */
    @Bean
    @Order(2)
    SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
