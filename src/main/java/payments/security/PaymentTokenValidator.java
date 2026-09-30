package payments.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

public final class PaymentTokenValidator
        implements OAuth2TokenValidator<Jwt> {

    private final String requiredAudience;

    public PaymentTokenValidator(String requiredAudience) {
        if (requiredAudience == null || requiredAudience.isBlank()) {
            throw new IllegalArgumentException(
                    "Required audience must not be blank"
            );
        }

        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audiences = token.getAudience();

        if (audiences == null
                || !audiences.contains(requiredAudience)) {
            return failure("Token audience is invalid");
        }

        Object merchantClaim = token.getClaims().get("merchant_id");

        if (!(merchantClaim instanceof String merchantId)
                || !isValidUuid(merchantId)) {
            return failure("Token merchant_id is missing or invalid");
        }

        if (token.getExpiresAt() == null) {
            return failure("Token expiration is required");
        }

        return OAuth2TokenValidatorResult.success();
    }

    private boolean isValidUuid(String value) {
        if (value.length() != 36) {
            return false;
        }

        try {
            UUID parsed = UUID.fromString(value);

            return parsed.toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private OAuth2TokenValidatorResult failure(String description) {
        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                description,
                null
        );

        return OAuth2TokenValidatorResult.failure(error);
    }
}