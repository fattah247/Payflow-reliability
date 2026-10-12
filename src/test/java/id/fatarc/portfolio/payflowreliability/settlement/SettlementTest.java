package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.refund.RefundService;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementTest extends IntegrationTest {
    @Autowired
    private SettlementBatchItemRepository settlementBatchItemRepository;
    @Autowired
    private SettlementBatchRepository settlementBatchRepository;
    @Autowired
    private RefundService refundService;

    @Test
    void shouldCreateSettlementBatchAndMarkPaymentAsSettled() {
        PaymentIntent intent = capturedIntent("ORDER-2001", "77000");

        SettlementBatch batch = settlementService.createBatch(MERCHANT, null);

        assertThat(batch.getTransactionCount()).isEqualTo(1);
        assertThat(batch.getTotalAmount()).isEqualByComparingTo("77000");
        assertThat(settlementBatchItemRepository.findByBatchId(batch.getId())).hasSize(1);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SETTLED);
    }

    @Test
    void shouldSkipPaymentsThatMovedOnInsteadOfFailingTheWholeBatch() {
        PaymentIntent refunded = capturedIntent("ORDER-2002", "100");
        refundService.requestRefund(refunded.getId(), new CreateRefundRequest(new BigDecimal("100"), "customer changed mind"));
        PaymentIntent reversal = capturedIntent("ORDER-2003", "300");
        webhook(reversal.getId(), "evt-2003-reversal", "tx-ORDER-2003", ProviderStatus.REVERSAL_REQUIRED, "300");
        PaymentIntent healthy = capturedIntent("ORDER-2004", "200");

        SettlementBatch batch = settlementService.createBatch(MERCHANT, null);

        assertThat(batch.getTransactionCount()).isEqualTo(1);
        assertThat(batch.getTotalAmount()).isEqualByComparingTo("200");
        assertThat(stateOf(healthy)).isEqualTo(PaymentState.SETTLED);
        assertThat(stateOf(refunded)).isEqualTo(PaymentState.REFUND_REQUESTED);
        assertThat(stateOf(reversal)).isEqualTo(PaymentState.REVERSAL_REQUIRED);
    }

    @Test
    void shouldNeverSettleTheSameTransactionTwiceUnderConcurrentRuns() throws Exception {
        for (int i = 0; i < 20; i++) {
            capturedIntent("ORDER-21" + String.format("%02d", i), "10");
        }

        List<Object> results = race(4, () -> settlementService.createBatch(MERCHANT, null));

        // The losing runs wait for the winner's payment locks, then find nothing left to settle.
        assertThat(results).filteredOn(SettlementBatch.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(r -> !(r instanceof SettlementBatch))
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(BusinessException.class)
                        .extracting("code").isEqualTo(ErrorCode.NOTHING_TO_SETTLE));
        assertThat(settlementBatchRepository.count()).isEqualTo(1);
        assertThat(settlementBatchItemRepository.count()).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("select sum(total_amount) from settlement_batches", BigDecimal.class))
                .isEqualByComparingTo("200");
    }

    @Test
    void shouldStayConsistentWhenRefundsAndReversalsRaceASettlementRun() throws Exception {
        List<PaymentIntent> payments = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            payments.add(capturedIntent("ORDER-22" + i, "1000"));
        }
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();

        List<Object> results = race(7, () -> {
            int task = turn.getAndIncrement();
            if (task == 0) {
                return settlementService.createBatch(MERCHANT, null);
            }
            PaymentIntent payment = payments.get(task);
            if (task <= 3) {
                return refundService.requestRefund(payment.getId(), new CreateRefundRequest(new BigDecimal("1000"), "race"));
            }
            return webhook(payment.getId(), "evt-reversal-" + task, "tx-" + payment.getMerchantReference(),
                    ProviderStatus.REVERSAL_REQUIRED, "1000");
        });

        assertThat(results).doesNotHaveAnyElementsOfTypes(Throwable.class);
        SettlementBatch batch = (SettlementBatch) results.stream().filter(SettlementBatch.class::isInstance).findFirst().orElseThrow();
        List<SettlementBatchItem> items = settlementBatchItemRepository.findByBatchId(batch.getId());
        assertThat(batch.getTransactionCount()).isEqualTo(items.size());
        assertThat(batch.getTotalAmount()).isEqualByComparingTo(new BigDecimal(1000L * items.size()));
        assertThat(items.size()).isBetween(3, 9);
        for (PaymentIntent payment : payments) {
            boolean settled = paymentTransactionRepository.findByPaymentIntentIdOrderByCreatedAtAsc(payment.getId()).getFirst().isSettled();
            boolean inBatch = items.stream().anyMatch(item -> paymentTransactionRepository.findById(item.getTransactionId())
                    .orElseThrow().getPaymentIntentId().equals(payment.getId()));
            assertThat(settled).as("payment %s settled flag matches batch membership", payment.getMerchantReference()).isEqualTo(inBatch);
        }
    }

    @Test
    void shouldKeepAPaymentTheProviderContradictsOutOfSettlement() {
        PaymentIntent contradicted = capturedIntent("ORDER-2008", "700");
        webhook(contradicted.getId(), "evt-2008-failed", "tx-ORDER-2008", ProviderStatus.FAILED, "700");
        capturedIntent("ORDER-2009", "300");

        SettlementBatch batch = settlementService.createBatch(MERCHANT, null);

        assertThat(batch.getTotalAmount()).isEqualByComparingTo("300");
        assertThat(stateOf(contradicted)).isEqualTo(PaymentState.MANUAL_REVIEW);
    }

    @Test
    void shouldReportNothingToSettleAfterTheBatchHasRun() {
        capturedIntent("ORDER-2005", "500");
        settlementService.createBatch(MERCHANT, null);

        assertThatThrownBy(() -> settlementService.createBatch(MERCHANT, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.NOTHING_TO_SETTLE);
    }

    @Test
    void shouldAskForACurrencyWhenAMerchantHasSeveralAndSettleEachSeparately() {
        PaymentIntent idr = startedIntent(MERCHANT, "ORDER-2006", "150000", "IDR");
        webhook(idr.getId(), "evt-2006", "tx-2006", ProviderStatus.SUCCESS, "150000", "IDR");
        PaymentIntent usd = startedIntent(MERCHANT, "ORDER-2007", "12.50", "USD");
        webhook(usd.getId(), "evt-2007", "tx-2007", ProviderStatus.SUCCESS, "12.50", "USD");

        assertThatThrownBy(() -> settlementService.createBatch(MERCHANT, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("[IDR, USD]")
                .extracting("code").isEqualTo(ErrorCode.SETTLEMENT_CURRENCY_REQUIRED);

        SettlementBatch usdBatch = settlementService.createBatch(MERCHANT, "USD");
        SettlementBatch idrBatch = settlementService.createBatch(MERCHANT, null);

        assertThat(usdBatch.getCurrency()).isEqualTo("USD");
        assertThat(idrBatch.getCurrency()).isEqualTo("IDR");
        assertThat(stateOf(idr)).isEqualTo(PaymentState.SETTLED);
        assertThat(stateOf(usd)).isEqualTo(PaymentState.SETTLED);
    }

    @Test
    void shouldReturnNotFoundForItemsOfAnUnknownBatch() {
        assertThatThrownBy(() -> settlementService.getItems(java.util.UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.SETTLEMENT_BATCH_NOT_FOUND);
    }
}
