package id.fatarc.portfolio.payflowreliability.risk;

import id.fatarc.portfolio.payflowreliability.loadgen.RiskTrafficGenerator;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Labelled, seeded traffic through the public API. The simulation knows who was honest; the payment system only knows
 * its decisions and the labels disclosed afterwards. The two views have to agree to the rupiah, and moving a
 * threshold has to trade friction for fraud loss in the expected direction.
 */
class RiskReportTest extends IntegrationTest {
    private static final String ALLOWED = "allowed";
    private static final String CONFIRMED = "stepped up, confirmed";
    private static final String NOT_CONFIRMED = "stepped up, not confirmed";
    private static final String BLOCKED = "blocked";

    @Autowired
    private RiskTrafficGenerator generator;
    @Autowired
    private RiskReportService reportService;

    private RiskTrafficGenerator.Report run(long seed) {
        RiskTrafficGenerator.Report report = generator.run(new RiskTrafficGenerator.Request(seed, "risk", merchantAKey, 120));
        deliverWebhooks();
        return report;
    }

    private static RiskTrafficGenerator.Tally of(Map<String, RiskTrafficGenerator.Tally> by, String... what) {
        long count = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (String key : what) {
            RiskTrafficGenerator.Tally tally = by.get(key);
            if (tally != null) {
                count += tally.count();
                amount = amount.add(tally.amount());
            }
        }
        return new RiskTrafficGenerator.Tally(count, amount);
    }

    private static void assertSame(RiskReportService.Tally measured, RiskTrafficGenerator.Tally truth, String what) {
        assertThat(measured.count()).as(what + " (count)").isEqualTo(truth.count());
        assertThat(measured.amount()).as(what + " (amount)").isEqualByComparingTo(truth.amount());
    }

    @Test
    void shouldMeasureFraudStoppedAndFrictionExactlyAsTheGroundTruthHasIt() {
        RiskTrafficGenerator.Report truth = run(42);
        RiskReportService.Report measured = reportService.report("IDR");

        // The seeded run exercises every outcome, for both groups.
        assertThat(truth.fraud().count()).isPositive();
        assertThat(of(truth.fraudBy(), BLOCKED).count()).as("fraud blocked").isPositive();
        assertThat(of(truth.fraudBy(), ALLOWED, CONFIRMED).count()).as("fraud that got through").isPositive();
        assertThat(of(truth.legitimateBy(), CONFIRMED).count()).as("honest customers who confirmed").isPositive();
        assertThat(of(truth.legitimateBy(), BLOCKED, NOT_CONFIRMED).count()).as("honest customers lost").isPositive();

        assertSame(measured.fraud(), truth.fraud(), "fraud");
        assertSame(measured.legitimate(), truth.legitimate(), "legitimate");
        assertSame(measured.fraudStopped(), of(truth.fraudBy(), BLOCKED, NOT_CONFIRMED), "fraud stopped");
        assertSame(measured.fraudLoss(), of(truth.fraudBy(), ALLOWED, CONFIRMED), "fraud loss");
        assertSame(measured.legitimateLostToFriction(), of(truth.legitimateBy(), BLOCKED, NOT_CONFIRMED), "legitimate lost to friction");
        assertSame(measured.stepUpConfirmed(), new RiskTrafficGenerator.Tally(
                of(truth.fraudBy(), CONFIRMED).count() + of(truth.legitimateBy(), CONFIRMED).count(),
                of(truth.fraudBy(), CONFIRMED).amount().add(of(truth.legitimateBy(), CONFIRMED).amount())), "step-ups confirmed");
        assertThat(measured.stepUpAwaiting().count()).as("every unconfirmed step-up expired").isZero();
        assertThat(reportService.report("IDR", MERCHANT_A).fraudLoss()).as("all of it is merchant A's").isEqualTo(measured.fraudLoss());
        assertThat(reportService.report("IDR", MERCHANT_B).decided().count()).isZero();

        long fraudFlagged = of(truth.fraudBy(), BLOCKED, CONFIRMED, NOT_CONFIRMED).count();
        long flagged = fraudFlagged + of(truth.legitimateBy(), BLOCKED, CONFIRMED, NOT_CONFIRMED).count();
        assertThat(measured.precision()).isCloseTo((double) fraudFlagged / flagged, offset(0.0005));
        assertThat(measured.recall()).isCloseTo((double) fraudFlagged / truth.fraud().count(), offset(0.0005));

        // What the gate let through was charged; what it stopped never reached the provider.
        List<PaymentIntent> payments = paymentIntentRepository.findAll();
        assertThat(payments).filteredOn(intent -> intent.getState() == PaymentState.SUCCESS).hasSize(
                (int) (of(truth.fraudBy(), ALLOWED, CONFIRMED).count() + of(truth.legitimateBy(), ALLOWED, CONFIRMED).count()));
        assertThat(payments).filteredOn(intent -> intent.getState() == PaymentState.BLOCKED || intent.getState() == PaymentState.EXPIRED)
                .allSatisfy(intent -> {
                    assertThat(intent.getStartedAt()).isNull();
                    assertThat(providerSimulator.payment(intent.getId())).isEmpty();
                });
    }

    @Test
    void shouldTradeFrictionForFraudLossAsTheStepUpThresholdMoves() throws Exception {
        run(42);
        RiskReportService.Report measured = reportService.report("IDR");
        RiskReportService.Sweep sweep = reportService.sweep(RiskReportService.Parameter.STEP_UP, 1, 6, "IDR");

        assertThat(sweep.confirmRateSource()).isEqualTo("observed for legitimate, observed for fraud");
        RiskReportService.SweepRow current = sweep.rows().stream().filter(RiskReportService.SweepRow::current).findFirst().orElseThrow();
        assertThat(current.value()).isEqualTo(2);
        assertThat(current.fraudLoss()).as("the configured threshold reproduces what happened").isEqualByComparingTo(measured.fraudLoss().amount());
        assertThat(current.fraudStopped()).isEqualByComparingTo(measured.fraudStopped().amount());
        assertThat(current.legitimateLostToFriction()).isEqualByComparingTo(measured.legitimateLostToFriction().amount());

        for (int i = 1; i < sweep.rows().size(); i++) {
            RiskReportService.SweepRow lower = sweep.rows().get(i - 1);
            RiskReportService.SweepRow higher = sweep.rows().get(i);
            assertThat(higher.steppedUp() + higher.blocked()).as("a higher step-up threshold flags no more")
                    .isLessThanOrEqualTo(lower.steppedUp() + lower.blocked());
            assertThat(higher.legitimateSteppedUp()).isLessThanOrEqualTo(lower.legitimateSteppedUp());
            assertThat(higher.legitimateLostToFriction()).as("less friction").isLessThanOrEqualTo(lower.legitimateLostToFriction());
            assertThat(higher.fraudLoss()).as("more fraud gets through").isGreaterThanOrEqualTo(lower.fraudLoss());
        }
        RiskReportService.SweepRow first = sweep.rows().getFirst();
        RiskReportService.SweepRow last = sweep.rows().getLast();
        assertThat(last.fraudLoss()).as("the trade is real, not flat").isGreaterThan(first.fraudLoss());
        assertThat(last.legitimateLostToFriction()).isLessThan(first.legitimateLostToFriction());

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/risk/threshold-sweep").param("parameter", "BLOCK").param("from", "3").param("to", "8")
                        .param("merchantId", MERCHANT_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(6))
                .andExpect(jsonPath("$.merchantId").value(MERCHANT_A))
                .andExpect(jsonPath("$.rows[2].current").value(true));
    }

    @Test
    void shouldExpireOnlyItsOwnCustomersStepUps() {
        RiskEvidence emulator = new RiskEvidence("device-other", null, false, true, List.of(
                new RiskEvidence.Signal("emulator_fingerprint", "EMULATOR", SignalSeverity.MEDIUM, SignalSource.DEVICE_CHECK)));
        PaymentIntent waiting = paymentIntentService.create(new CreatePaymentIntentRequest(null, "OTHER-SHOP-1",
                new BigDecimal("200000"), "IDR", RiskAction.PHYSICAL_GOODS, emulator), "key-OTHER-SHOP-1", AS_MERCHANT_B).intent();

        run(42);

        assertThat(stateOf(waiting)).as("another shop's customer is still deciding").isEqualTo(PaymentState.REQUIRES_CONFIRMATION);
        assertThat(reportService.report("IDR", MERCHANT_A).stepUpAwaiting().count()).isZero();
        assertThat(reportService.report("IDR", MERCHANT_B).stepUpAwaiting().count()).isEqualTo(1);
    }

    @Test
    void shouldGiveTheSameNumbersForTheSameSeed() {
        RiskTrafficGenerator.Report first = run(7);
        RiskReportService.Report firstMeasured = reportService.report("IDR");
        resetWorld();
        RiskTrafficGenerator.Report second = run(7);
        RiskReportService.Report secondMeasured = reportService.report("IDR");

        assertThat(second.legitimateBy()).isEqualTo(first.legitimateBy());
        assertThat(second.fraudBy()).isEqualTo(first.fraudBy());
        assertThat(secondMeasured.fraudLoss()).isEqualTo(firstMeasured.fraudLoss());
        assertThat(secondMeasured.byRule()).isEqualTo(firstMeasured.byRule());
        assertThat(first.disclaimer()).contains("not observed fraud data");
    }
}
