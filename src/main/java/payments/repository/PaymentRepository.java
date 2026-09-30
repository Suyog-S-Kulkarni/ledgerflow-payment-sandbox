package payments.repository;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import payments.domain.Payment;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends Repository<Payment, UUID> {

    Optional<Payment> findByMerchantIdAndId(
            UUID merchantId,
            UUID id
    );

    Optional<Payment> findByMerchantIdAndIdempotencyKey(
            UUID merchantId,
            String idempotencyKey
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
            from Payment p
            where p.merchantId = :merchantId
              and p.id = :paymentId
            """)
    Optional<Payment> findForUpdate(
            @Param("merchantId") UUID merchantId,
            @Param("paymentId") UUID paymentId
    );
}