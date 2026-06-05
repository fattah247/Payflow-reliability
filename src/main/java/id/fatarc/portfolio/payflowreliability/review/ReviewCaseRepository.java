package id.fatarc.portfolio.payflowreliability.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewCaseRepository extends JpaRepository<ReviewCase, UUID> {
    List<ReviewCase> findByStatusOrderByCreatedAtAsc(ReviewStatus status);
    Optional<ReviewCase> findFirstByStatusAndReasonAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
            ReviewStatus status,
            ReviewReason reason,
            String referenceType,
            String referenceId
    );
}
