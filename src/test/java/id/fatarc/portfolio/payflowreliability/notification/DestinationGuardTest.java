package id.fatarc.portfolio.payflowreliability.notification;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A shop chooses the URL our servers send to, so the URL must not reach our own network: no loopback, private,
 * link-local (cloud metadata) or otherwise non-public address, by literal or by a name that resolves to one.
 */
class DestinationGuardTest {
    /** Names resolve from this table only: no real DNS in tests. */
    private static final Map<String, String> DNS = Map.of(
            "hooks.shop.example", "203.0.113.10",
            "sneaky.shop.example", "10.1.2.3",
            "metadata.shop.example", "169.254.169.254");

    private final DestinationGuard guard = new DestinationGuard(false, host -> {
        String address = DNS.get(host);
        if (address != null) {
            return new InetAddress[]{InetAddress.getByName(address)};
        }
        if (host.startsWith("[") || Character.isDigit(host.charAt(0))) {
            return InetAddress.getAllByName(host);
        }
        throw new UnknownHostException(host);
    });

    @Test
    void shouldAcceptHttpsToAPublicHost() {
        assertThat(guard.check("https://hooks.shop.example/payflow?shop=1").getHost()).isEqualTo("hooks.shop.example");
        assertThat(guard.check("https://8.8.8.8/hooks")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hooks",
            "https://localhost.localdomain.invalid/hooks",
            "https://10.0.0.5/hooks",
            "https://172.16.0.1/hooks",
            "https://192.168.1.1/hooks",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/hooks",
            "https://0.0.0.0/hooks",
            "https://224.0.0.1/hooks",
            "https://[::1]/hooks",
            "https://[fd00::1]/hooks",
            "https://[fe80::1]/hooks",
            "https://[::ffff:127.0.0.1]/hooks",
            "https://[64:ff9b::a00:1]/hooks",
            "https://sneaky.shop.example/hooks",
            "https://metadata.shop.example/hooks",
            "http://hooks.shop.example/hooks",
            "ftp://hooks.shop.example/hooks",
            "https://user:secret@hooks.shop.example/hooks",
            "https://hooks.shop.example/hooks#fragment",
            "https:///hooks",
            "not a url",
            ""
    })
    void shouldRefuseWhatCouldReachOurOwnNetwork(String url) {
        assertThatThrownBy(() -> guard.check(url))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.WEBHOOK_URL_NOT_ALLOWED));
    }

    @Test
    void shouldSayWhichAddressWasRefused() {
        assertThatThrownBy(() -> guard.check("https://metadata.shop.example/hooks"))
                .hasMessageContaining("resolves to 169.254.169.254");
    }

    @Test
    void shouldAllowLocalAddressesOnlyWhenConfiguredForLocalDemos() {
        DestinationGuard local = new DestinationGuard(true, InetAddress::getAllByName);

        assertThat(local.check("http://localhost:8080/merchant-sim/v1/shops/demo/webhooks")).isNotNull();
        assertThatThrownBy(() -> local.check("ftp://localhost/hooks")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> local.check("http://user:pw@localhost/hooks")).isInstanceOf(BusinessException.class);
    }
}
