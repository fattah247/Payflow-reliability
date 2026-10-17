package id.fatarc.portfolio.payflowreliability.notification;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Decides whether a shop may have us send requests to a URL. A webhook URL is chosen by the shop, and our servers
 * send to it: without this check a shop could point us at our own network (a metadata service, an admin port, the
 * database) and use the delivery as a probe. Only https URLs whose host resolves to public addresses are accepted,
 * checked when the endpoint is registered and again before every delivery (the name may resolve differently later).
 *
 * <p>With {@code payflow.merchant-webhooks.allow-private-addresses} (local demos, where the merchant simulator listens
 * on localhost) every address and plain http are allowed; the application refuses to start with that next to a real
 * provider.
 */
@Component
public class DestinationGuard {
    /** Name resolution, replaceable in tests. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowPrivateAddresses;
    private final Resolver resolver;

    @Autowired
    public DestinationGuard(MerchantWebhookProperties properties) {
        this(properties.allowPrivateAddresses(), InetAddress::getAllByName);
    }

    public DestinationGuard(boolean allowPrivateAddresses, Resolver resolver) {
        this.allowPrivateAddresses = allowPrivateAddresses;
        this.resolver = resolver;
    }

    /** The URL to send to, or a {@code WEBHOOK_URL_NOT_ALLOWED} refusal that says why. */
    public URI check(String url) {
        if (url == null || url.isBlank() || url.length() > 500) {
            throw refuse("The webhook URL must be 1-500 characters.");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw refuse("The webhook URL is not a valid URL: " + ex.getReason() + ".");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && allowPrivateAddresses)) {
            throw refuse("The webhook URL must use https.");
        }
        if (uri.getRawUserInfo() != null) {
            throw refuse("The webhook URL must not carry credentials; verify our signature instead.");
        }
        if (uri.getRawFragment() != null) {
            throw refuse("The webhook URL must not have a fragment.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw refuse("The webhook URL must name a host.");
        }
        if (allowPrivateAddresses) {
            return uri;
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            throw refuse("The webhook host " + host + " does not resolve.");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw refuse("The webhook host " + host + " resolves to " + address.getHostAddress()
                        + ", a loopback, private, link-local or otherwise non-public address.");
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(bytes[0] & 0xff, bytes[1] & 0xff, bytes[2] & 0xff);
        }
        if ((bytes[0] & 0xfe) == 0xfc) {
            return false; // fc00::/7, unique local
        }
        if (bytes[0] == 0 && bytes[1] == 0x64 && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b) {
            return isPublicV4(bytes[12] & 0xff, bytes[13] & 0xff, bytes[14] & 0xff); // 64:ff9b::/96, NAT64
        }
        boolean v4Compatible = true;
        for (int i = 0; i < 12; i++) {
            v4Compatible &= bytes[i] == 0;
        }
        return !v4Compatible; // ::/96 (deprecated IPv4-compatible) and ::
    }

    private static boolean isPublicV4(int first, int second, int third) {
        return first != 0                                          // 0.0.0.0/8, "this network"
                && first != 10 && first != 127                     // private, loopback
                && !(first == 100 && second >= 64 && second < 128) // 100.64.0.0/10, carrier-grade NAT
                && !(first == 169 && second == 254)                // link-local, cloud metadata services
                && !(first == 172 && second >= 16 && second < 32)  // private
                && !(first == 192 && second == 168)                // private
                && !(first == 192 && second == 0 && third == 0)    // 192.0.0.0/24, protocol assignments
                && !(first == 198 && (second == 18 || second == 19)) // benchmarking
                && first < 224;                                    // multicast, reserved, broadcast
    }

    private static BusinessException refuse(String message) {
        return new BusinessException(ErrorCode.WEBHOOK_URL_NOT_ALLOWED, message);
    }
}
