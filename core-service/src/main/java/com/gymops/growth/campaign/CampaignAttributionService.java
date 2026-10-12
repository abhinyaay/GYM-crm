package com.gymops.growth.campaign;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campaign attribution. A paid invoice converts the campaign message it can be credited to:
 * the message whose personal offer code it used (whenever it was sent), otherwise the most recent
 * message sent to that member within its attribution window (last touch). One invoice converts at
 * most one message ({@code ux_campaign_recipients_conversion}); re-processing is a no-op.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignAttributionService {

    private final NamedParameterJdbcTemplate jdbc;

    private record PaidInvoice(Long id, Long memberId, Long discountId, BigDecimal amountPaid, Instant paidAt) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean attributePayment(Long paymentId) {
        return jdbc.queryForList("select invoice_id from payments where id = :id", Map.of("id", paymentId), Long.class)
                .stream().findFirst().map(this::attribute).orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean attributeSubscription(Long subscriptionId) {
        return jdbc.queryForList("""
                select id from invoices where subscription_id = :id and status <> 'VOID' and paid_at is not null
                """, Map.of("id", subscriptionId), Long.class).stream().findFirst().map(this::attribute).orElse(false);
    }

    private boolean attribute(Long invoiceId) {
        Optional<PaidInvoice> invoice = jdbc.query("""
                select id, member_id, discount_id, amount_paid, paid_at from invoices
                where id = :id and paid_at is not null and status <> 'VOID'
                """, Map.of("id", invoiceId), (rs, n) -> new PaidInvoice(rs.getLong("id"), rs.getLong("member_id"),
                rs.getObject("discount_id", Long.class), rs.getBigDecimal("amount_paid"),
                rs.getTimestamp("paid_at").toInstant())).stream().findFirst();
        if (invoice.isEmpty()) {
            return false;
        }
        PaidInvoice i = invoice.get();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("invoice", i.id()).addValue("member", i.memberId())
                .addValue("discount", i.discountId()).addValue("paidAt", Timestamp.from(i.paidAt()));
        Boolean done = jdbc.queryForObject(
                "select exists (select 1 from campaign_recipients where conversion_invoice_id = :invoice)", p, Boolean.class);
        if (Boolean.TRUE.equals(done)) {
            return false;
        }
        List<Long> candidate = jdbc.queryForList("""
                select id from campaign_recipients
                where member_id = :member and converted_at is null and sent_at <= :paidAt
                  and ((cast(:discount as bigint) is not null and discount_id = cast(:discount as bigint))
                       or attribution_ends_at >= :paidAt)
                order by coalesce(discount_id = cast(:discount as bigint), false) desc, sent_at desc, id desc
                limit 1
                """, p, Long.class);
        if (candidate.isEmpty()) {
            return false;
        }
        int updated = jdbc.update("""
                update campaign_recipients
                set converted_at = :paidAt, conversion_invoice_id = :invoice, conversion_amount = :amount,
                    offer_redeemed = coalesce(discount_id = cast(:discount as bigint), false)
                where id = :id and converted_at is null
                """, p.addValue("amount", i.amountPaid()).addValue("id", candidate.getFirst()));
        if (updated == 1) {
            log.info("campaign_conversion recipientId={} invoiceId={} amount={}", candidate.getFirst(), i.id(), i.amountPaid());
        }
        return updated == 1;
    }
}
