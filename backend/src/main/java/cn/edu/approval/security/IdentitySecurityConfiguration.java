package cn.edu.approval.security;

import cn.edu.approval.identity.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Persistent identity is available only with the explicit database profile. */
@Configuration
@Profile("db & !migrate")
public class IdentitySecurityConfiguration {
  @Bean
  org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
      identityJsonTypes() {
    return builder ->
        builder.postConfigurer(
            mapper -> {
              var textual = mapper.coercionConfigFor(LogicalType.Textual);
              textual.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
              textual.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
              textual.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
              var bool = mapper.coercionConfigFor(LogicalType.Boolean);
              bool.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
              bool.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
              bool.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            });
  }

  @Bean
  Clock identityClock() {
    return Clock.systemUTC();
  }

  @Bean
  WebCredentials webCredentials(Environment environment, Clock clock) {
    return new WebCredentials(
        environment.getRequiredProperty("APP_CSRF_SECRET"),
        environment.getProperty("APP_WEB_ORIGIN", "https://localhost"),
        environment.getProperty("APP_COOKIE_SECURE", Boolean.class, true),
        clock);
  }

  @Bean
  SecurityFilterChain identitySecurity(
      HttpSecurity http, AuthService service, WebCredentials web, ObjectMapper mapper)
      throws Exception {
    return http
        // Replaced by the transport-aware Origin + signed, session-bound CSRF protocol.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c -> c.disable())
        .addFilterBefore(
            new IdentityRequestFilter(service, web, mapper),
            UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/health",
                        "/api/v1/auth/csrf",
                        "/api/v1/departments",
                        "/api/v1/auth/me",
                        "/api/v1/profile")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/auth/logout",
                        "/api/v1/native-auth/login",
                        "/api/v1/native-auth/register",
                        "/api/v1/profile/password",
                        "/api/v1/profile/confirmation-request")
                    .permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/api/v1/profile")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/admin/**", "/api/v1/applications", "/api/v1/applications/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (req, res, ex) -> ApiErrors.write(res, mapper, 401, "UNAUTHENTICATED", 0))
                    .accessDeniedHandler(
                        (req, res, ex) -> ApiErrors.write(res, mapper, 403, "FORBIDDEN", 0)))
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    csp ->
                        csp.policyDirectives(
                            "default-src 'none'; object-src 'none'; frame-ancestors 'none'")))
        .build();
  }
}
