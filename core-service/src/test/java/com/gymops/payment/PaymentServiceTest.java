package com.gymops.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gymops.audit.AuditService;
import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.util.Hashing;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.payment.dto.PaymentRequest;
import com.gymops.payment.credit.CreditEntryType;
import com.gymops.payment.credit.StoreCreditService;
import com.gymops.payment.gateway.PaymentGateway;
import com.gymops.payment.gateway.PaymentGateways;
import com.gymops.payment.gateway.SimulatedPaymentGateway;
import com.gymops.payment.method.PaymentMethodService;
import com.gymops.security.AccessPolicy;
import com.gymops.support.Fixtures;
import com.gymops.support.SecurityTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String KEY = "key-0001-abcdef";
    private static final BigDecimal TOTAL = new BigDecimal("3538.82");

    @Mock PaymentRepository paymentRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock MemberRepository memberRepository;
    @Mock SimulatedPaymentGateway gateway;
    @Mock PaidInvoiceHandler paidInvoiceHandler;
    @Mock PaymentMethodService paymentMethods;
    @Mock StoreCreditService storeCredit;
    @Mock AccessPolicy access;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;

    private PaymentService service;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        var meters = new SimpleMeterRegistry();
        when(gateway.provider()).thenReturn(PaymentProvider.SIMULATED);
        var recorder = new PaymentRecorder(paymentRepository, paidInvoiceHandler, audit, events, meters, clock);
        service = new PaymentService(paymentRepository, invoiceRepository, memberRepository,
                new PaymentGateways(List.of(gateway), PaymentProvider.SIMULATED), recorder, paymentMethods, storeCredit,
                access, meters, clock);
        Member member = Fixtures.member(5, Fixtures.branch(1));
        lenient().when(memberRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(member));
        var quote = new PricingCalculator.Quote(List.of(new PricingCalculator.Line(InvoiceLineType.PLAN_FEE, "Plan",
                new BigDecimal("2999.00"))), new BigDecimal("2999.00"), BigDecimal.ZERO, new BigDecimal("0.18"),
                new BigDecimal("539.82"), TOTAL);
        invoice = new Invoice("INV-2026-000001", member, null, Fixtures.branch(1), "INR", quote, null,
                LocalDate.of(2026, 3, 8), Instant.parse("2026-03-01T09:00:00Z"));
        ReflectionTestUtils.setField(invoice, "id", 10L);
        lenient().when(invoiceRepository.findMemberIdById(10L)).thenReturn(Optional.of(5L));
        lenient().when(invoiceRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(invoice));
        SecurityTestSupport.receptionist(1);
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private PaymentRequest request(BigDecimal amount, PaymentMethod method) {
        return new PaymentRequest(10L, amount, method, "tok_ok", null);
    }

    private static String hash(BigDecimal amount, PaymentMethod method) {
        return Hashing.sha256Hex("10|" + amount.stripTrailingZeros().toPlainString() + "|" + method);
    }

    @Test
    void sameKeyAndSameRequestReplaysOriginalWithoutCharging() {
        Payment original = Payment.succeeded(invoice, TOTAL, PaymentMethod.UPI, KEY, hash(TOTAL, PaymentMethod.UPI),
                "SIM-1", Instant.now(), 100L, Payment.Source.of(PaymentProvider.SIMULATED, null));
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(original));

        var outcome = service.pay(request(TOTAL, PaymentMethod.UPI), KEY);

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.payment().gatewayReference()).isEqualTo("SIM-1");
        verify(gateway, never()).chargeTestToken(any(), anyLong(), any(), any(), any());
        verify(paidInvoiceHandler, never()).onInvoicePaid(any());
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        Payment original = Payment.succeeded(invoice, TOTAL, PaymentMethod.UPI, KEY, hash(TOTAL, PaymentMethod.UPI),
                "SIM-1", Instant.now(), 100L, Payment.Source.of(PaymentProvider.SIMULATED, null));
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.pay(request(TOTAL, PaymentMethod.CARD), KEY))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void successfulChargeSettlesInvoiceAndActivatesSubscription() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(gateway.chargeTestToken(any(), anyLong(), any(), any(), any()))
                .thenReturn(PaymentGateway.ChargeResult.captured("SIM-OK", null, null));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var outcome = service.pay(request(TOTAL, PaymentMethod.UPI), KEY);

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        verify(paidInvoiceHandler).onInvoicePaid(invoice);
        verify(events).publishEvent(any(DomainEvents.PaymentSucceeded.class));
    }

    @Test
    void declinedChargeIsRecordedButInvoiceStaysOpen() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(gateway.chargeTestToken(any(), anyLong(), any(), any(), any()))
                .thenReturn(PaymentGateway.ChargeResult.failed("card_declined"));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var outcome = service.pay(request(TOTAL, PaymentMethod.CARD), KEY);

        assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(outcome.payment().failureReason()).isEqualTo("card_declined");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        verify(paidInvoiceHandler, never()).onInvoicePaid(any());
        verify(events).publishEvent(any(DomainEvents.PaymentFailed.class));
    }

    @Test
    void partialAmountIsRejected() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.pay(request(new BigDecimal("100.00"), PaymentMethod.UPI), KEY))
                .isInstanceOf(ApiException.class).hasMessageContaining("partial payments");
        verify(gateway, never()).chargeTestToken(any(), anyLong(), any(), any(), any());
    }

    @Test
    void paidInvoiceCannotBePaidAgainWithNewKey() {
        invoice.markPaid(TOTAL, Instant.now());
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.pay(request(TOTAL, PaymentMethod.UPI), KEY)).isInstanceOf(ConflictException.class);
        verify(gateway, never()).chargeTestToken(any(), anyLong(), any(), any(), any());
    }

    @Test
    void malformedIdempotencyKeyIsRejectedBeforeAnyWork() {
        assertThatThrownBy(() -> service.pay(request(TOTAL, PaymentMethod.UPI), "x"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void cashIsRecordedAsOfflineWithoutTouchingTheGateway() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var outcome = service.pay(new PaymentRequest(10L, TOTAL, PaymentMethod.CASH, null, "RCPT-77"), KEY);

        assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(outcome.payment().provider()).isEqualTo(PaymentProvider.OFFLINE);
        assertThat(outcome.payment().gatewayReference()).isEqualTo("RCPT-77");
        verify(gateway, never()).chargeTestToken(any(), anyLong(), any(), any(), any());
    }

    @Test
    void storeCreditPaymentDebitsTheLedger() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storeCredit.balance(5L)).thenReturn(new BigDecimal("5000.00"));

        var outcome = service.pay(new PaymentRequest(10L, TOTAL, PaymentMethod.STORE_CREDIT, null, null), KEY);

        assertThat(outcome.payment().provider()).isEqualTo(PaymentProvider.INTERNAL);
        verify(storeCredit).post(eq(5L), eq(CreditEntryType.PAYMENT), eq(TOTAL.negate()), any(), any(), eq(null));
    }

    @Test
    void insufficientStoreCreditIsRejectedWithoutRecordingAPayment() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(storeCredit.balance(5L)).thenReturn(new BigDecimal("10.00"));

        assertThatThrownBy(() -> service.pay(new PaymentRequest(10L, TOTAL, PaymentMethod.STORE_CREDIT, null, null), KEY))
                .isInstanceOf(ApiException.class).hasMessageContaining("Insufficient store credit");
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void netBankingMustGoThroughCheckout() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.pay(new PaymentRequest(10L, TOTAL, PaymentMethod.NET_BANKING, null, null), KEY))
                .isInstanceOf(ApiException.class).hasMessageContaining("checkout");
    }
}
