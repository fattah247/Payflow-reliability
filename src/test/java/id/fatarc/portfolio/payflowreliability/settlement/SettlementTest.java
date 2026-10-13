package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.ledger.JournalType;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Net payouts: captures minus refunds and reversals not yet paid out, one currency per batch, never twice. */
class SettlementTest extends IntegrationTest {
    @Autowired
    private SettlementBatchItemRepository settlementBatchItemRepository;

    private BigDecimal owedTo(String merchantId, String currency) {
        return ledgerQueryService.balances(merchantId, currency).stream()
                .map(balance -> balance.owedToMerchant()).findFirst().orElse(BigDecimal.ZERO);
    }

    @Test
    void shouldPayOutCapturesAndMarkPaymentsSettled() {
        PaymentIntent intent = capturedIntent("ORDER-2001", "77000");
        assertThat(owedTo(MERCHANT_A, "IDR")).isEqualByComparingTo("77000");

        SettlementBatch batch = settle(MERCHANT_A);

        assertThat(batch.getTransactionCount()).isEqualTo(1);
        assertThat(batch.getCaptureAmount()).isEqualByComparingTo("77000");
        assertThat(batch.getDeductionAmount()).isEqualByComparingTo("0");
        assertThat(batch.getTotalAmount()).isEqualByComparingTo("77000");
        assertThat(settlementBatchItemRepository.findByBatchId(batch.getId())).singleElement()
                .extracting(SettlementBatchItem::getItemType).isEqualTo(SettlementItemType.CAPTURE);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SETTLED);
        assertThat(owedTo(MERCHANT_A, "IDR")).isEqualByComparingTo("0");
        assertThat(ledgerInvariantService.check().healthy()).isTrue();
    }

    @Test
    void shouldDeductRefundsCompletedBeforeThePayout() {
        PaymentIntent intent = paidIntent("ORDER-2002", "100000");
        requestRefund(intent, "30000", "refund-2002");
        drainOutbox();

        SettlementBatch batch = settle(MERCHANT_A);

        assertThat(batch.getCaptureAmount()).isEqualByComparingTo("100000");
        assertThat(batch.getDeductionAmount()).isEqualByComparingTo("30000");
        assertThat(batch.getTotalAmount()).isEqualByComparingTo("70000");
        assertThat(settlementBatchItemRepository.findByBatchId(batch.getId()))
                .extracting(item -> item.getItemType() + ":" + item.getAmount().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("CAPTURE:100000", "REFUND:-30000");
        assertThat(owedTo(MERCHANT_A, "IDR")).isEqualByComparingTo("0");
    }

    @Test
    void shouldDeductARefundMadeAfterPayoutFromTheNextBatch() {
        PaymentIntent first = paidIntent("ORDER-2003", "100000");
        settle(MERCHANT_A);
        requestRefund(reload(first), "40000", "refund-2003");
        drainOutbox();
        paidIntent("ORDER-2004", "50000");

        SettlementBatch next = settle(MERCHANT_A);

        assertThat(next.getCaptureAmount()).isEqualByComparingTo("50000");
        assertThat(next.getDeductionAmount()).isEqualByComparingTo("40000");
        assertThat(next.getTotalAmount()).isEqualByComparingTo("10000");
        assertThat(owedTo(MERCHANT_A, "IDR")).isEqualByComparingTo("0");
        assertThat(ledgerInvariantService.check().healthy()).isTrue();
    }

    @Test
    void shouldCarryANegativeNetForwardInsteadOfPayingIt() {
        PaymentIntent first = paidIntent("ORDER-2005", "100000");
        settle(MERCHANT_A);
        requestRefund(reload(first), "100000", "refund-2005");
        drainOutbox();
        assertThat(owedTo(MERCHANT_A, "IDR")).isEqualByComparingTo("-100000");

        assertThatThrownBy(() -> settle(MERCHANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("carry forward")
                .extracting("code").isEqualTo(ErrorCode.NOTHING_TO_SETTLE);
        assertThat(settlementService.backlog()).singleElement().satisfies(backlog ->
                assertThat(backlog.netAmount()).isEqualByComparingTo("-100000"));

        paidIntent("ORDER-2006", "150000");
        SettlementBatch batch = settle(MERCHANT_A);
        assertThat(batch.getTotalAmount()).isEqualByComparingTo("50000");
        assertThat(owedTo(MERCHANT_A, "IDR")).isEqualByComparingTo("0");
    }

    @Test
    void shouldWaitForPaymentsOnHoldOrMidReversalWithoutFailingTheBatch() {
        PaymentIntent reversal = capturedIntent("ORDER-2007", "300");
        webhook(reversal.getId(), "evt-2007-reversal", "tx-ORDER-2007", ProviderStatus.REVERSAL_REQUIRED, "300");
        PaymentIntent contradicted = capturedIntent("ORDER-2008", "700");
        webhook(contradicted.getId(), "evt-2008-failed", "tx-ORDER-2008", ProviderStatus.FAILED, "700");
        PaymentIntent healthy = capturedIntent("ORDER-2009", "200");

        SettlementBatch batch = settle(MERCHANT_A);

        assertThat(batch.getTransactionCount()).isEqualTo(1);
        assertThat(batch.getTotalAmount()).isEqualByComparingTo("200");
        assertThat(stateOf(healthy)).isEqualTo(PaymentState.SETTLED);
        assertThat(stateOf(reversal)).isEqualTo(PaymentState.REVERSAL_REQUIRED);
        assertThat(stateOf(contradicted)).isEqualTo(PaymentState.MANUAL_REVIEW);
    }

    @Test
    void shouldNeverSettleTheSameCaptureTwiceUnderConcurrentRuns() throws Exception {
        for (int i = 0; i < 20; i++) {
            capturedIntent("ORDER-21" + String.format("%02d", i), "10");
        }

        List<Object> results = race(4, () -> settle(MERCHANT_A));

        // The losing runs wait for the winner's payment locks, then find nothing left to settle.
        assertThat(results).filteredOn(SettlementBatch.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(r -> !(r instanceof SettlementBatch))
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(BusinessException.class)
                        .extracting("code").isEqualTo(ErrorCode.NOTHING_TO_SETTLE));
        assertThat(settlementBatchItemRepository.count()).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("select sum(total_amount) from settlement_batches", BigDecimal.class))
                .isEqualByComparingTo("200");
        assertThat(ledgerJournalRepository.findAll()).filteredOn(journal -> journal.getJournalType() == JournalType.PAYOUT).hasSize(1);
    }

    @Test
    void shouldStayConsistentWhenRefundsAndReversalsRaceASettlementRun() throws Exception {
        List<PaymentIntent> payments = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            payments.add(paidIntent("ORDER-22" + i, "1000"));
        }
        AtomicInteger turn = new AtomicInteger();

        List<Object> results = race(7, () -> {
            int task = turn.getAndIncrement();
            if (task == 0) {
                return settle(MERCHANT_A);
            }
            PaymentIntent payment = payments.get(task);
            if (task <= 3) {
                return requestRefund(payment, "1000", "race-refund-" + task);
            }
            return webhook(payment.getId(), "evt-reversal-" + task, transactionOf(payment).getProviderTransactionId(),
                    ProviderStatus.REVERSAL_REQUIRED, "1000");
        });
        drainOutbox();

        assertThat(results).doesNotHaveAnyElementsOfTypes(Throwable.class);
        SettlementBatch batch = (SettlementBatch) results.stream().filter(SettlementBatch.class::isInstance).findFirst().orElseThrow();
        List<SettlementBatchItem> items = settlementBatchItemRepository.findByBatchId(batch.getId());
        assertThat(batch.getTransactionCount()).isEqualTo(items.size());
        // Capture items are positive and deductions negative, so the items add up to the net payout.
        assertThat(items.stream().map(SettlementBatchItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(batch.getTotalAmount());
        assertThat(ledgerInvariantService.check().violations()).isEmpty();
    }

    /**
     * The candidate query runs before the payments are locked, so a payment can change between the two.
     * Hold the payment's lock, let the run select it and wait for the lock, change the payment, then release.
     */
    @Test
    void shouldReCheckEachPaymentAfterLockingIt() throws Exception {
        PaymentIntent intent = capturedIntent("ORDER-2016", "1000");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                paymentIntentRepository.findForUpdate(intent.getId());
                locked.countDown();
                awaitQuietly(release);
                // The provider asks for the money back while the settlement run waits for this payment.
                webhook(intent.getId(), "evt-2016-reversal", "tx-ORDER-2016", ProviderStatus.REVERSAL_REQUIRED, "1000");
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> run = pool.submit(() -> {
                try {
                    return settle(MERCHANT_A);
                } catch (RuntimeException ex) {
                    return ex;
                }
            });
            awaitASessionBlockedOnALock();
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            assertThat(run.get(30, TimeUnit.SECONDS)).isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ErrorCode.NOTHING_TO_SETTLE);
            assertThat(stateOf(intent)).isEqualTo(PaymentState.REVERSAL_REQUIRED);
            assertThat(settlementBatchItemRepository.count()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitASessionBlockedOnALock() throws InterruptedException {
        String sql = isPostgres()
                ? "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                : "select count(*) from information_schema.sessions where blocker_id is not null";
        for (int attempt = 0; attempt < 200; attempt++) {
            if (jdbcTemplate.queryForObject(sql, Long.class) > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the settlement run never waited for the payment lock");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void shouldPayACaptureOutOnceEvenWhenAChargebackArrivesUnderANewTransactionId() {
        PaymentIntent chargedBack = paidIntent("ORDER-2017", "100000");
        paidIntent("ORDER-2018", "50000");
        webhook(chargedBack.getId(), "evt-2017-chargeback", "chargeback-2017", ProviderStatus.REVERSAL_REQUIRED, "100000");
        drainOutbox();
        assertThat(stateOf(chargedBack)).isEqualTo(PaymentState.REVERSED);
        assertThat(paymentTransactionRepository.findByPaymentIntentIdOrderByCreatedAtAsc(chargedBack.getId())).hasSize(2);

        SettlementBatch first = settle(MERCHANT_A);
        paidIntent("ORDER-2019", "10000");
        SettlementBatch second = settle(MERCHANT_A);

        assertThat(first.getTotalAmount()).isEqualByComparingTo("50000");
        assertThat(second.getTotalAmount()).as("the chargeback row is not a second capture").isEqualByComparingTo("10000");
        assertThat(settlementBatchItemRepository.findByPaymentIntentIdOrderByCreatedAtAsc(chargedBack.getId()))
                .extracting(SettlementBatchItem::getItemType)
                .containsExactlyInAnyOrder(SettlementItemType.CAPTURE, SettlementItemType.REVERSAL);
        assertThat(ledgerInvariantService.check().violations()).isEmpty();
    }

    @Test
    void shouldReportNothingToSettleAfterTheBatchHasRun() {
        capturedIntent("ORDER-2010", "500");
        settle(MERCHANT_A);

        assertThatThrownBy(() -> settle(MERCHANT_A))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.NOTHING_TO_SETTLE);
    }

    @Test
    void shouldAskForACurrencyWhenAMerchantHasSeveralAndSettleEachSeparately() {
        PaymentIntent idr = startedIntent(MERCHANT_A, "ORDER-2011", "150000", "IDR");
        webhook(idr.getId(), "evt-2011", "tx-2011", ProviderStatus.SUCCESS, "150000", "IDR");
        PaymentIntent usd = startedIntent(MERCHANT_A, "ORDER-2012", "12.50", "USD");
        webhook(usd.getId(), "evt-2012", "tx-2012", ProviderStatus.SUCCESS, "12.50", "USD");

        assertThatThrownBy(() -> settle(MERCHANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("[IDR, USD]")
                .extracting("code").isEqualTo(ErrorCode.SETTLEMENT_CURRENCY_REQUIRED);

        SettlementBatch usdBatch = settlementService.createBatch(MERCHANT_A, "USD", OPS_ANA);
        SettlementBatch idrBatch = settle(MERCHANT_A);

        assertThat(usdBatch.getCurrency()).isEqualTo("USD");
        assertThat(usdBatch.getTotalAmount()).isEqualTo(new BigDecimal("12.50"));
        assertThat(idrBatch.getCurrency()).isEqualTo("IDR");
        assertThat(stateOf(idr)).isEqualTo(PaymentState.SETTLED);
        assertThat(stateOf(usd)).isEqualTo(PaymentState.SETTLED);
    }

    @Test
    void shouldShowTheSameNumbersInTheBacklogAsTheNextBatchPays() {
        paidIntent("ORDER-2013", "100000");
        PaymentIntent refunded = paidIntent("ORDER-2014", "50000");
        requestRefund(refunded, "20000", "refund-2014");
        drainOutbox();
        capturedIntent("ORDER-2015", "999");

        SettlementService.Backlog backlog = settlementService.backlog().getFirst();
        SettlementBatch batch = settle(MERCHANT_A);

        assertThat(backlog.captureAmount()).isEqualByComparingTo(batch.getCaptureAmount());
        assertThat(backlog.deductionAmount()).isEqualByComparingTo(batch.getDeductionAmount());
        assertThat(backlog.netAmount()).isEqualByComparingTo(batch.getTotalAmount());
        assertThat(settlementService.backlog()).isEmpty();
    }

    @Test
    void shouldReturnNotFoundForItemsOfAnUnknownBatch() {
        assertThatThrownBy(() -> settlementService.getItems(java.util.UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.SETTLEMENT_BATCH_NOT_FOUND);
    }
}
