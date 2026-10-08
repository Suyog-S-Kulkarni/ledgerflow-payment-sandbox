package payments.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentApiIntegrationTest {

    private static final String MERCHANT_TOKEN = "test-merchant-token";

    private static final String PAYMENT_JSON = """
            {
              "amountMinor": 12550,
              "currency": "INR",
              "orderReference": "ORDER-API-1001"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID merchantId;
    private boolean testDatabaseVerified;

    @BeforeEach
    void setUp() {
        String databaseName = jdbcTemplate.queryForObject(
                "SELECT DATABASE()",
                String.class
        );

        assertThat(databaseName)
                .as("API tests must use the dedicated test database")
                .isEqualTo("ledgerflow_test");

        testDatabaseVerified = true;
        merchantId = UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        if (testDatabaseVerified && merchantId != null) {
            jdbcTemplate.update(
                    "DELETE FROM payments WHERE merchant_id = ?",
                    merchantId.toString()
            );
        }
    }

    @Test
    void authenticatedMerchantCanCreatePayment() throws Exception {
        when(jwtDecoder.decode(MERCHANT_TOKEN))
                .thenReturn(merchantJwt());

        var result = mockMvc.perform(post("/api/payments")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + MERCHANT_TOKEN
                        )
                        .header("Idempotency-Key", "api-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Idempotency-Replayed", "false"
                ))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.amountMinor").value(12550))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.orderReference")
                        .value("ORDER-API-1001"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();

        // Read the persisted ID independently from MySQL.
        String storedId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM payments
                WHERE merchant_id = ?
                  AND idempotency_key = ?
                """,
                String.class,
                merchantId.toString(),
                "api-create-key"
        );

        assertThat(storedId).isNotNull();

        jsonPath("$.id").value(storedId).match(result);

        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/payments/" + storedId);

        verify(jwtDecoder).decode(MERCHANT_TOKEN);
    }

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", "api-no-token-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_JSON))
                .andExpect(status().isUnauthorized());

        // With no bearer token, the decoder should not be called.
        verifyNoInteractions(jwtDecoder);
    }

    private Jwt merchantJwt() {
        Instant now = Instant.now();

        return Jwt.withTokenValue(MERCHANT_TOKEN)
                .header("alg", "RS256")
                .subject("integration-test-merchant")
                .issuer("http://localhost:8081/realms/ledgerflow")
                .audience(List.of("ledgerflow-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("merchant_id", merchantId.toString())
                .claim(
                        "realm_access",
                        Map.of("roles", List.of("merchant"))
                )
                .build();
    }

    @Test
    void sameRequestReturnsExistingPaymentWithReplayHeader() throws Exception {
        when(jwtDecoder.decode(MERCHANT_TOKEN))
                .thenReturn(merchantJwt());

        String idempotencyKey = "api-replay-key";

        var first = mockMvc.perform(post("/api/payments")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + MERCHANT_TOKEN
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Idempotency-Replayed", "false"
                ))
                .andReturn();

        mockMvc.perform(post("/api/payments")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + MERCHANT_TOKEN
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Idempotency-Replayed", "true"
                ))
                .andExpect(content().json(
                        first.getResponse().getContentAsString()
                ));

        Long paymentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payments
                WHERE merchant_id = ?
                  AND idempotency_key = ?
                """,
                Long.class,
                merchantId.toString(),
                idempotencyKey
        );

        assertThat(paymentCount).isEqualTo(1L);
    }

    @Test
    void sameKeyWithDifferentAmountReturnsConflict() throws Exception {
        when(jwtDecoder.decode(MERCHANT_TOKEN))
                .thenReturn(merchantJwt());

        String idempotencyKey = "api-conflict-key";

        mockMvc.perform(post("/api/payments")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + MERCHANT_TOKEN
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_JSON))
                .andExpect(status().isCreated());

        String changedRequest = """
            {
              "amountMinor": 20000,
              "currency": "INR",
              "orderReference": "ORDER-API-1001"
            }
            """;

        mockMvc.perform(post("/api/payments")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + MERCHANT_TOKEN
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changedRequest))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        Long storedAmount = jdbcTemplate.queryForObject(
                """
                SELECT amount_minor
                FROM payments
                WHERE merchant_id = ?
                  AND idempotency_key = ?
                """,
                Long.class,
                merchantId.toString(),
                idempotencyKey
        );

        assertThat(storedAmount).isEqualTo(12_550L);

        Long paymentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payments
                WHERE merchant_id = ?
                  AND idempotency_key = ?
                """,
                Long.class,
                merchantId.toString(),
                idempotencyKey
        );

        assertThat(paymentCount).isEqualTo(1L);
    }
}