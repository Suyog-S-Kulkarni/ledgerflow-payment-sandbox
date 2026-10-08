package payments.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DatabaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void connectsToTestDatabaseAndFindsPaymentsTable() {
        String databaseName = jdbcTemplate.queryForObject(
                "SELECT DATABASE()",
                String.class
        );

        assertThat(databaseName)
                .as("Integration tests must use the dedicated test database")
                .isEqualTo("ledgerflow_test");

        Integer paymentsTableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'payments'
                """,
                Integer.class
        );

        assertThat(paymentsTableCount)
                .as("Flyway must create the payments table")
                .isEqualTo(1);

        Long paymentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments",
                Long.class
        );

        assertThat(paymentCount).isNotNull();
        assertThat(paymentCount).isGreaterThanOrEqualTo(0L);
    }
}