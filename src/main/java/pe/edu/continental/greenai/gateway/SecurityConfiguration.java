package pe.edu.continental.greenai.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Configuration
class SecurityConfiguration {
    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${gateway.auth.issuer}") String issuer,
            @Value("${gateway.auth.jwks}") String jwks,
            @Value("${gateway.auth.audience}") String audience) {
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithm(SignatureAlgorithm.RS256).jwsAlgorithm(SignatureAlgorithm.ES256).build();
        OAuth2TokenValidator<Jwt> required = jwt ->
            jwt.getAudience().contains(audience) && jwt.getExpiresAt() != null
                && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                && "authenticated".equals(jwt.getClaimAsString("role"))
                && !Boolean.TRUE.equals(jwt.getClaim("is_anonymous"))
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), required));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http,
            @Value("${GATEWAY_CORS_ALLOWED_ORIGIN:http://127.0.0.1:3001}") String origin) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Accept", "Content-Type", "Authorization", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        var converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("user_role");
            return List.of("ADMIN", "OPERATOR").contains(role == null ? "" : role)
                ? Flux.just(new SimpleGrantedAuthority("ROLE_" + role)) : Flux.empty();
        });
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .logout(ServerHttpSecurity.LogoutSpec::disable)
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .cors(spec -> spec.configurationSource(source))
            .authorizeExchange(auth -> auth
                .pathMatchers("/actuator/health/**", "/openapi/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/monitoring/v1/metrics/catalog",
                    "/api/monitoring/v1/metrics/current", "/api/monitoring/v1/metrics/history")
                    .hasAnyRole("OPERATOR", "ADMIN")
                .anyExchange().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((exchange, error) -> problem(exchange, HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((exchange, error) -> problem(exchange, HttpStatus.FORBIDDEN)))
            .oauth2ResourceServer(oauth -> oauth
                .authenticationEntryPoint((exchange, error) -> problem(exchange, HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((exchange, error) -> problem(exchange, HttpStatus.FORBIDDEN))
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .build();
    }

    private static Mono<Void> problem(ServerWebExchange exchange, HttpStatus status) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.UNAUTHORIZED) response.getHeaders().set("WWW-Authenticate", "Bearer");
        String detail = status == HttpStatus.UNAUTHORIZED ? "Inicia sesión con un token válido."
                : "Tu cuenta no tiene permiso para esta operación.";
        String body = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
