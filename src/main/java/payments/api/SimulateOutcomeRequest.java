package payments.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import payments.domain.PaymentStatus;

public record SimulateOutcomeRequest(

        @NotNull(message = "Outcome is required")
        PaymentStatus outcome

) {

    @AssertTrue(message = "Outcome must be SUCCEEDED or FAILED")
    public boolean isTerminalOutcome() {
        return outcome == null
                || outcome == PaymentStatus.SUCCEEDED
                || outcome == PaymentStatus.FAILED;
    }
}