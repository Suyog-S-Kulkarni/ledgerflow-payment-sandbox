package payments.exception;

public class PaymentStateConflictException extends RuntimeException {

    public PaymentStateConflictException(Throwable cause) {
        super(
                "A completed payment's outcome cannot be changed",
                cause
        );
    }
}