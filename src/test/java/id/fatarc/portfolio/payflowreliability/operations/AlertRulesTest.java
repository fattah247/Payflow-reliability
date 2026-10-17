package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Alert rules must reference metrics the application really exports, and every alert must have a runbook entry. */
@AutoConfigureObservability(tracing = false)
class AlertRulesTest extends IntegrationTest {
    private static final Path RULES = Path.of("ops/prometheus/alerts.yml");
    private static final Path RUNBOOK = Path.of("docs/runbook.md");
    private static final Pattern METRIC = Pattern.compile("\\b(payflow_[a-z_]+|http_server_requests_seconds_count)\\b");

    @Autowired
    private OperationalMetrics operationalMetrics;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rules() throws Exception {
        try (InputStream in = Files.newInputStream(RULES)) {
            Map<String, Object> document = new Yaml().load(in);
            List<Map<String, Object>> rules = new ArrayList<>();
            for (Map<String, Object> group : (List<Map<String, Object>>) document.get("groups")) {
                rules.addAll((List<Map<String, Object>>) group.get("rules"));
            }
            return rules;
        }
    }

    /** Exercises every flow that creates a counter, so lazily registered metrics exist before the scrape. */
    private String scrape() throws Exception {
        PaymentIntent intent = paidIntent("ORDER-ALERT-1", "1000");
        requestRefund(intent, "1000", "refund-alert-1");
        paymentIntentService.cancel(startedIntent("ORDER-ALERT-2", "500").getId(),
                id.fatarc.portfolio.payflowreliability.payment.CancellationReason.ABANDONED, AS_MERCHANT_A);
        drainOutbox();
        postWebhook("{}", "t=1,v1=00").andExpect(status().isUnauthorized());
        sweeper.runOnce();
        ledgerInvariantService.check();
        operationalMetrics.refresh();
        return mockMvc.perform(as(OPS_ANA_KEY, get("/actuator/prometheus")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void shouldOnlyReferenceMetricsTheApplicationExports() throws Exception {
        String exported = scrape();
        Set<String> referenced = new TreeSet<>();
        for (Map<String, Object> rule : rules()) {
            Matcher matcher = METRIC.matcher(String.valueOf(rule.get("expr")));
            while (matcher.find()) {
                referenced.add(matcher.group(1));
            }
        }

        assertThat(referenced).hasSizeGreaterThan(10);
        assertThat(referenced).allSatisfy(metric -> assertThat(exported)
                .as("metric %s is exported", metric).containsPattern("(?m)^" + metric + "[{ ]"));
    }

    @Test
    void shouldGiveEveryAlertASeverityASummaryAndARunbookSection() throws Exception {
        String runbook = Files.readString(RUNBOOK);
        for (Map<String, Object> rule : rules()) {
            @SuppressWarnings("unchecked") Map<String, String> labels = (Map<String, String>) rule.get("labels");
            @SuppressWarnings("unchecked") Map<String, String> annotations = (Map<String, String>) rule.get("annotations");
            assertThat(labels.get("severity")).as(rule.get("alert") + " severity").isIn("page", "ticket");
            assertThat(annotations.get("summary")).as(rule.get("alert") + " summary").isNotBlank();
            String anchor = annotations.get("runbook").substring(annotations.get("runbook").indexOf('#') + 1);
            assertThat(runbook).as(rule.get("alert") + " runbook section").contains("<a id=\"" + anchor + "\"></a>");
        }
    }
}
