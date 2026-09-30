package payments.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super(
                "The idempotency key has already been used "
                        + "with different payment details"
        );
    }
}