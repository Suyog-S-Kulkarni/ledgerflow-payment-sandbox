package payments.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreatePaymentRequest(

        @NotNull(message = "Amount is required")
        @Min(value = 1, message = "Amount must be at least 1 paisa")
        @Max(
                value = 100_000_000,
                message = "Amount must not exceed 100000000 paise"
        )
        Long amountMinor,

        @NotNull(message = "Currency is required")
        @Pattern(
                regexp = "INR",
                message = "Only INR is supported"
        )
        String currency,

        @NotNull(message = "Order reference is required")
        @Pattern(
                regexp = "[A-Za-z0-9_-]{1,64}",
                message = "Order reference must contain 1–64 letters, digits, underscores or hyphens"
        )
        String orderReference

) {}