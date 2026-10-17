package id.fatarc.portfolio.payflowreliability.support;

import id.fatarc.portfolio.payflowreliability.provider.PaymentProviderClient;
import id.fatarc.portfolio.payflowreliability.provider.ProviderPaymentStatus;
import id.fatarc.portfolio.payflowreliability.provider.ProviderResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The real HTTP provider client, with one addition for tests: it can be armed so that the process "dies"
 * right after the provider has answered a money-moving call. The answer is lost, nothing after the call
 * runs, and whatever the worker committed before the call stays committed: exactly what a crash leaves.
 */
public class CrashableProviderClient implements PaymentProviderClient {
    private final PaymentProviderClient delegate;
    private final AtomicBoolean armed = new AtomicBoolean();

    public CrashableProviderClient(PaymentProviderClient delegate) {
        this.delegate = delegate;
    }

    /** The next refund, reversal or payout call reaches the provider, then the caller dies. */
    public void crashAfterNextCall() {
        armed.set(true);
    }

    public void reset() {
        armed.set(false);
    }

    @Override
    public ProviderResult refund(String idempotencyKey, String providerTransactionId, BigDecimal amount, String currency,
                                 String reference) {
        return crashIfArmed(delegate.refund(idempotencyKey, providerTransactionId, amount, currency, reference));
    }

    @Override
    public ProviderResult reverse(String idempotencyKey, String providerTransactionId, String reference) {
        return crashIfArmed(delegate.reverse(idempotencyKey, providerTransactionId, reference));
    }

    @Override
    public Optional<ProviderResult> findRefund(String idempotencyKey) {
        return delegate.findRefund(idempotencyKey);
    }

    @Override
    public Optional<ProviderResult> findReversal(String idempotencyKey) {
        return delegate.findReversal(idempotencyKey);
    }

    @Override
    public ProviderResult payout(String idempotencyKey, String reference, String merchantId, BigDecimal amount, String currency,
                                 List<String> providerTransactionIds) {
        return crashIfArmed(delegate.payout(idempotencyKey, reference, merchantId, amount, currency, providerTransactionIds));
    }

    @Override
    public Optional<ProviderResult> findPayout(String idempotencyKey) {
        return delegate.findPayout(idempotencyKey);
    }

    @Override
    public Optional<ProviderPaymentStatus> paymentStatus(UUID paymentIntentId) {
        return delegate.paymentStatus(paymentIntentId);
    }

    private <T> T crashIfArmed(T answer) {
        if (armed.compareAndSet(true, false)) {
            throw new SimulatedCrash();
        }
        return answer;
    }

    /** An Error, not an exception: no catch block in the application handles it, just as none survives a crash. */
    public static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("Simulated crash after the provider answered");
        }
    }
}
