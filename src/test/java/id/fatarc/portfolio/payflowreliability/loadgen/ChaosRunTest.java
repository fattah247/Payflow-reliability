package id.fatarc.portfolio.payflowreliability.loadgen;

import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The live chaos run: a seeded sequence of faults played through the public APIs of the shop, the provider and the
 * bank, then every money rule. The property-based test explores many sequences; this is the one a reader can run
 * against the packaged application.
 */
class ChaosRunTest extends IntegrationTest {
    @Autowired
    private ChaosRunner chaosRunner;

    @Test
    void shouldKeepEveryMoneyRuleThroughASeededSequenceOfFaults() {
        ChaosRunner.Report report = chaosRunner.run(new ChaosRunner.Request(42, 120, merchantAKey));

        assertThat(report.invariants().rules()).allSatisfy(rule ->
                assertThat(rule.violations()).as(rule.name() + ": " + rule.statement()).isEmpty());
        assertThat(report.actions().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(120);
        assertThat(report.merchantId()).isEqualTo(MERCHANT_A);
        assertThat(report.payments()).isNotEmpty();
        assertThat(report.payouts()).as("money reached the bank").containsKey("LANDED");
        assertThat(report.faults()).isNotEmpty();
        assertThat(report.disclaimer()).contains("not after every possible one");
    }

    @Test
    void shouldPlayTheSameSequenceForTheSameSeed() throws Exception {
        ChaosRunner.Report first = chaosRunner.run(new ChaosRunner.Request(7, 60, merchantAKey));
        ChaosRunner.Report second = chaosRunner.run(new ChaosRunner.Request(7, 60, merchantBKey));

        assertThat(second.actions()).isEqualTo(first.actions());
        assertThat(second.invariants().allHold()).isTrue();

        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/simulations/chaos-runs")), "{\"seed\":3,\"steps\":20,\"merchantApiKey\":\"%s\"}"
                        .formatted(merchantBKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invariants.allHold").value(true))
                .andExpect(jsonPath("$.steps").value(20));
        mockMvc.perform(json(as(merchantBKey, post("/api/simulations/chaos-runs")), "{\"seed\":3,\"merchantApiKey\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
