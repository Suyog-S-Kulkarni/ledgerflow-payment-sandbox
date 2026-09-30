package payments.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PaymentRules {

    private PaymentRules() {
        // Utility class: no objects need to be created.
    }

    public static String fingerprint(
            long amountMinor,
            String currency,
            String orderReference) {

        String requestData =
                amountMinor + "|" + currency + "|" + orderReference;

        byte[] requestBytes =
                requestData.getBytes(StandardCharsets.UTF_8);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hashBytes = digest.digest(requestBytes);

            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is unavailable",
                    exception
            );
        }
    }

    public static PaymentStatus transition(
            PaymentStatus current,
            PaymentStatus outcome) {

        if (current == null || outcome == null) {
            throw new IllegalArgumentException(
                    "Current status and outcome are required"
            );
        }

        if (outcome == PaymentStatus.CREATED) {
            throw new IllegalArgumentException(
                    "Outcome must be SUCCEEDED or FAILED"
            );
        }

        if (current == outcome) {
            return current;
        }

        if (current == PaymentStatus.CREATED) {
            return outcome;
        }

        throw new IllegalStateException(
                "A terminal payment outcome cannot be changed"
        );
    }
}