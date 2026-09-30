package payments.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaymentRulesTest {

    @Test
    void samePaymentDetailsProduceSameFingerprint() {
        String first = PaymentRules.fingerprint(
                12500L, "INR", "order-001"
        );

        String retry = PaymentRules.fingerprint(
                12500L, "INR", "order-001"
        );

        assertEquals(first, retry);
    }

    @Test
    void changingAnyPaymentFieldChangesFingerprint() {
        String original = PaymentRules.fingerprint(
                12500L, "INR", "order-001"
        );

        String changedAmount = PaymentRules.fingerprint(
                15000L, "INR", "order-001"
        );

        String changedCurrency = PaymentRules.fingerprint(
                12500L, "USD", "order-001"
        );

        String changedOrder = PaymentRules.fingerprint(
                12500L, "INR", "order-002"
        );

        assertAll(
                () -> assertNotEquals(original, changedAmount),
                () -> assertNotEquals(original, changedCurrency),
                () -> assertNotEquals(original, changedOrder)
        );
    }

    @Test
    void createdPaymentCanSucceed() {
        PaymentStatus result = PaymentRules.transition(
                PaymentStatus.CREATED,
                PaymentStatus.SUCCEEDED
        );

        assertEquals(PaymentStatus.SUCCEEDED, result);
    }

    @Test
    void createdPaymentCanFail() {
        PaymentStatus result = PaymentRules.transition(
                PaymentStatus.CREATED,
                PaymentStatus.FAILED
        );

        assertEquals(PaymentStatus.FAILED, result);
    }

    @Test
    void repeatingTerminalOutcomeIsAllowed() {
        assertAll(
                () -> assertEquals(
                        PaymentStatus.SUCCEEDED,
                        PaymentRules.transition(
                                PaymentStatus.SUCCEEDED,
                                PaymentStatus.SUCCEEDED
                        )
                ),
                () -> assertEquals(
                        PaymentStatus.FAILED,
                        PaymentRules.transition(
                                PaymentStatus.FAILED,
                                PaymentStatus.FAILED
                        )
                )
        );
    }

    @Test
    void terminalOutcomeCannotBeReversed() {
        assertAll(
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> PaymentRules.transition(
                                PaymentStatus.SUCCEEDED,
                                PaymentStatus.FAILED
                        )
                ),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> PaymentRules.transition(
                                PaymentStatus.FAILED,
                                PaymentStatus.SUCCEEDED
                        )
                )
        );
    }

    @Test
    void createdIsNotAValidOutcome() {
        for (PaymentStatus current : PaymentStatus.values()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PaymentRules.transition(
                            current,
                            PaymentStatus.CREATED
                    )
            );
        }
    }

    @Test
    void missingStatusIsRejected() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> PaymentRules.transition(
                                null,
                                PaymentStatus.SUCCEEDED
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> PaymentRules.transition(
                                PaymentStatus.CREATED,
                                null
                        )
                )
        );
    }
}