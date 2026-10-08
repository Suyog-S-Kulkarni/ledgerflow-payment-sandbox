package payments.security;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.authorization.AuthorizationManagers.allOf;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasRole;

@Configuration
public class SecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
            String issuerUri,

            @Value("${app.security.audience}")
            String audience
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withIssuerLocation(issuerUri)
                .build();

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuerUri),
                        new PaymentTokenValidator(audience)
                )
        );

        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        JwtAuthenticationConverter authenticationConverter =
                new JwtAuthenticationConverter();

        authenticationConverter.setJwtGrantedAuthoritiesConverter(
                new KeycloakRoleConverter()
        );

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(authorize -> authorize

                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/actuator/health"
                        )
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/payments/*/simulate"
                        )
                        .access(allOf(
                                hasRole("merchant"),
                                hasRole("simulator")
                        ))

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/payments"
                        )
                        .hasRole("merchant")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/payments/*"
                        )
                        .hasRole("merchant")

                        // New: merchant-scoped ledger read endpoints.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/ledger/payments/*",
                                "/api/ledger/balances"
                        )
                        .hasRole("merchant")

                        .anyRequest()
                        .denyAll()
                )

                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        authenticationConverter
                                )
                        )
                );

        return http.build();
    }
}