package id.fatarc.portfolio.payflowreliability.ledger.dto;

import id.fatarc.portfolio.payflowreliability.ledger.JournalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JournalView(UUID id, JournalType type, String reference, UUID paymentIntentId, String currency,
                          String description, String requestId, Instant createdAt, List<PostingView> postings) {
    public record PostingView(String account, BigDecimal amount) {
    }
}
