package com.picsou.config;

import com.picsou.mcp.AccessKeyService;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtUtil jwtUtil,
                                           AppUserRepository appUserRepository,
                                           SetupFilter setupFilter,
                                           PersistentSessionService persistentSessionService,
                                           AuthCookieWriter authCookieWriter,
                                           MfaService mfaService,
                                           AccessKeyService accessKeyService,
                                           @Qualifier("mcpKeyBuckets") Map<Long, Bucket> mcpKeyBuckets) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())   // stateless JWT + SameSite cookies cover this
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .frameOptions(fo -> fo.deny())
                .contentTypeOptions(cto -> {})
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31536000)
                    .includeSubDomains(true)
                )
                .referrerPolicy(rp -> rp
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/setup/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/mfa/verify").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/activate/*").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/mcp/**").authenticated()
                .anyRequest().authenticated()
            )
            // All custom filters anchor to UsernamePasswordAuthenticationFilter because
            // Spring Security's FilterOrderRegistration only knows the order of its own
            // well-known filter classes — passing a custom class as anchor throws
            // "does not have a registered order". SetupFilter returns 503/410 before
            // setup is complete; on a fresh install no JWT cookie exists anyway. The
            // PersistentTokenAuthFilter must run AFTER JwtAuthenticationFilter so an
            // active access cookie short-circuits and we don't pay the DB hit per
            // request — registration order below preserves that ordering.
            .addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new JwtAuthenticationFilter(jwtUtil, appUserRepository), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(
                new PersistentTokenAuthFilter(persistentSessionService, appUserRepository, jwtUtil, authCookieWriter, mfaService),
                UsernamePasswordAuthenticationFilter.class
            )
            // Last of the same-anchor filters: only acts on /mcp/** (shouldNotFilter), validates the
            // Bearer access-key, and sets an AccessKeyAuthentication carrying scope authorities only.
            .addFilterBefore(
                new AccessKeyAuthFilter(accessKeyService, mcpKeyBuckets),
                UsernamePasswordAuthenticationFilter.class
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) -> {
                    res.setStatus(401);
                    res.setContentType("application/problem+json");
                    res.getWriter().write("""
                        {"status":401,"title":"Unauthorized","detail":"Authentication required"}
                        """);
                })
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        CorsFilter filter = new CorsFilter(corsConfigurationSource);
        filter.setCorsProcessor(new LoggingCorsProcessor());
        return filter;
    }

    /**
     * Dynamic CORS: reads {@code cors.allowed-origins} from {@code app_setting}
     * per request, so changes made through the setup wizard's Security step
     * take effect without a container restart. Falls back to the env var for
     * fresh installs (and for env-only operators who never run the wizard).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppSettingRepository settingRepository) {
        return new DynamicCorsConfigurationSource(settingRepository, allowedOrigins);
    }
}
