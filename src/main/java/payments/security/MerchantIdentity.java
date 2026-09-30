package payments.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.UUID;

public final class MerchantIdentity {

    private MerchantIdentity() {
        // Utility class: no instances needed.
    }

    public static UUID from(Jwt jwt) {
        if (jwt == null) {
            throw new InvalidBearerTokenException(
                    "Authenticated merchant identity is required"
            );
        }

        Object claim = jwt.getClaims().get("merchant_id");

        if (!(claim instanceof String merchantId)) {
            throw new InvalidBearerTokenException(
                    "Token merchant_id is missing or invalid"
            );
        }

        UUID parsed;

        try {
            parsed = UUID.fromString(merchantId);
        } catch (IllegalArgumentException exception) {
            throw new InvalidBearerTokenException(
                    "Token merchant_id is invalid"
            );
        }

        if (!parsed.toString().equalsIgnoreCase(merchantId)) {
            throw new InvalidBearerTokenException(
                    "Token merchant_id is invalid"
            );
        }

        return parsed;
    }
}