package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.banksim.BankFaults;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.risk.RiskAction;
import id.fatarc.portfolio.payflowreliability.risk.RiskEvidence;
import id.fatarc.portfolio.payflowreliability.risk.SignalSeverity;
import id.fatarc.portfolio.payflowreliability.risk.SignalSource;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One view of the money at risk, whatever put it there: a held payment, a payout the bank never credited, a step-up
 * nobody answered, a block. Amounts and counts in the summary, the same in the gauges.
 */
class MoneyViewTest extends IntegrationTest {
    @Autowired
    private MoneyAtRiskService moneyAtRiskService;
    @Autowired
    private OperationalMetrics operationalMetrics;
    @Autowired
    private MeterRegistry meterRegistry;

    private PaymentIntent create(String reference, String amount, String signal, SignalSeverity severity) {
        RiskEvidence evidence = new RiskEvidence("device-9", null, false, true,
                List.of(new RiskEvidence.Signal(signal, "ROOT", severity, SignalSource.DEVICE_CHECK)));
        return paymentIntentService.create(new CreatePaymentIntentRequest(null, reference, new BigDecimal(amount), "IDR",
                RiskAction.PHYSICAL_GOODS, evidence), "key-" + reference, AS_MERCHANT_A).intent();
    }

    private MoneyAtRiskService.Item at(MoneyAtRiskService.Kind kind) {
        return moneyAtRiskService.compute().get(kind);
    }

    private double gauge(String name, MoneyAtRiskService.Kind kind) {
        return meterRegistry.get(name).tag("kind", kind.name().toLowerCase(Locale.ROOT)).gauge().value();
    }

    private void assertAtRisk(MoneyAtRiskService.Kind kind, long count, String amount) {
        MoneyAtRiskService.Item item = at(kind);
        assertThat(item.count()).as(kind + " count").isEqualTo(count);
        if (amount == null) {
            assertThat(item.amount()).as(kind + " amount").isEmpty();
        } else {
            assertThat(item.amount().get("IDR")).as(kind + " amount").isEqualByComparingTo(amount);
        }
    }

    @Test
    void shouldShowEveryKindOfMoneyAtRiskInOnePlaceAndInTheGauges() throws Exception {
        PaymentIntent held = startedIntent("VIEW-HOLD", "4000");
        webhook(held.getId(), "evt-view-hold", "tx-view-hold", ProviderStatus.SUCCESS, "4100");
        assertThat(stateOf(held)).isEqualTo(PaymentState.MANUAL_REVIEW);
        create("VIEW-STEP", "2000", "test_keys", SignalSeverity.MEDIUM);
        create("VIEW-BLOCK", "1500", "root_su_path", SignalSeverity.HIGH);

        bankFaults.inject(BankFaults.Mode.DROP_CREDIT, 1, null, null);
        paidIntent("VIEW-BREAK", "3000");
        SettlementBatch batch = settle(MERCHANT_A);
        drainOutbox();
        closeBankDays("IDR", reload(batch).getExpectedValueDate().plusDays(2));
        importStatements();
        threeWayReconciliationService.run(OPS_ANA.name());

        assertAtRisk(MoneyAtRiskService.Kind.HELD_FOR_REVIEW, 1, "4000");
        assertAtRisk(MoneyAtRiskService.Kind.AWAITING_CONFIRMATION, 1, "2000");
        assertAtRisk(MoneyAtRiskService.Kind.BLOCKED_TODAY, 1, "1500");
        assertAtRisk(MoneyAtRiskService.Kind.BREAKS_OPEN_UNDER_1_DAY, 1, "2991.00");
        assertAtRisk(MoneyAtRiskService.Kind.BREAKS_OPEN_1_TO_3_DAYS, 0, null);

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/summary"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.moneyAtRisk.items.length()").value(MoneyAtRiskService.Kind.values().length))
                .andExpect(jsonPath("$.moneyAtRisk.items[?(@.kind=='HELD_FOR_REVIEW')].amount.IDR").value(4000.0))
                .andExpect(jsonPath("$.moneyAtRisk.items[?(@.kind=='BREAKS_OPEN_UNDER_1_DAY')].count").value(1));
        operationalMetrics.refresh();
        assertThat(gauge("payflow.money.at.risk.count", MoneyAtRiskService.Kind.HELD_FOR_REVIEW)).isEqualTo(1);
        assertThat(gauge("payflow.money.at.risk.count", MoneyAtRiskService.Kind.AWAITING_CONFIRMATION)).isEqualTo(1);
        assertThat(gauge("payflow.money.at.risk.count", MoneyAtRiskService.Kind.BLOCKED_TODAY)).isEqualTo(1);
        assertThat(meterRegistry.get("payflow.money.at.risk.amount").tag("kind", "breaks_open_under_1_day").tag("currency", "IDR")
                .gauge().value()).isEqualTo(2991.0);

        // The break ages from one bucket into the next; the blocked payment was blocked on an earlier day.
        clock.advance(Duration.ofDays(2));
        assertAtRisk(MoneyAtRiskService.Kind.BREAKS_OPEN_UNDER_1_DAY, 0, null);
        assertAtRisk(MoneyAtRiskService.Kind.BREAKS_OPEN_1_TO_3_DAYS, 1, "2991.00");
        assertAtRisk(MoneyAtRiskService.Kind.BLOCKED_TODAY, 0, null);
        clock.advance(Duration.ofDays(2));
        assertAtRisk(MoneyAtRiskService.Kind.BREAKS_OPEN_OVER_3_DAYS, 1, "2991.00");
        operationalMetrics.refresh();
        assertThat(gauge("payflow.money.at.risk.count", MoneyAtRiskService.Kind.BREAKS_OPEN_OVER_3_DAYS)).isEqualTo(1);
    }
}
