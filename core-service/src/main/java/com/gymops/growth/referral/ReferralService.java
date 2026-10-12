package com.gymops.growth.referral;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.growth.GrowthProperties;
import com.gymops.growth.referral.ReferralConversionService.QualifyingPurchase;
import com.gymops.growth.referral.ReferralDtos.AttachRequest;
import com.gymops.growth.referral.ReferralDtos.MemberReferralSummary;
import com.gymops.growth.referral.ReferralDtos.MyReferral;
import com.gymops.growth.referral.ReferralDtos.MyReferralDashboard;
import com.gymops.growth.referral.ReferralDtos.PublicReferral;
import com.gymops.growth.referral.ReferralDtos.ReferralResponse;
import com.gymops.growth.referral.ReferralDtos.ReferralSettings;
import com.gymops.growth.referral.ReferralDtos.RewardView;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Referral capture (shared link, front desk, self sign-up), the member's referral dashboard, the
 * public code lookup and the staff views. Conversion and rewards live in {@link ReferralConversionService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    static final String SHARE_PATH = "/enquire?ref=";

    private final ReferralRepository referrals;
    private final ReferralRewardRepository rewards;
    private final ReferralCodeService codes;
    private final ReferralConversionService conversion;
    private final ReferralQueries queries;
    private final MemberRepository memberRepository;
    private final NotificationService notifications;
    private final AccessPolicy access;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final GrowthProperties props;
    private final Clock clock;

    private record LeadRow(Long id, Long branchId, String firstName, String lastName, String email, String phone,
                           Long memberId, String status) {
        boolean isOpen() {
            return !"CONVERTED".equals(status) && !"LOST".equals(status);
        }
    }

    // ================================================================== capture

    /**
     * A public enquiry arrived with {@code ?ref=CODE}. Never fails the enquiry: unknown codes,
     * self-referrals and already-referred prospects are dropped silently (the public form must not
     * reveal anything about members), everything else becomes a PENDING referral.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void captureFromEnquiry(Long leadId, String rawCode) {
        Optional<Member> referrer = activeReferrer(rawCode);
        Optional<LeadRow> lead = loadLead(leadId);
        if (referrer.isEmpty() || lead.isEmpty()) {
            log.info("referral_capture_ignored leadId={} reason=unknown_code_or_lead", leadId);
            return;
        }
        LeadRow l = lead.get();
        if (referrals.findByRefereeLeadId(l.id()).isPresent()
                || (l.memberId() != null && referrals.findByRefereeMemberId(l.memberId()).isPresent())) {
            return;
        }
        Member r = referrer.get();
        if (ReferralRules.isSelfReferral(party(r), new ReferralRules.Party(l.memberId(), l.email(), l.phone()))) {
            log.info("referral_capture_ignored leadId={} reason=self_referral", leadId);
            return;
        }
        if (existingCustomerWithEmail(l.email())) {
            log.info("referral_capture_ignored leadId={} reason=existing_customer", leadId);
            return;
        }
        Referral referral = referrals.saveAndFlush(new Referral(r.getId(), codes.find(r.getId()).orElseThrow(),
                new Referral.Referee(l.memberId(), l.id(), l.firstName(), l.lastName(), l.email(), l.phone(), l.branchId()),
                ReferralChannel.LINK));
        afterCapture(referral, r);
    }

    /** Front desk: attach a code to a new member (or an open lead) - e.g. while registering them or selling a plan. */
    @Transactional
    public ReferralResponse attach(AttachRequest request) {
        Member referrer = activeReferrer(request.code())
                .orElseThrow(() -> new BusinessRuleException("Referral code " + request.code() + " is not valid"));
        Referral referral;
        if (request.memberId() != null) {
            Member referee = memberRepository.findByIdForUpdate(request.memberId())
                    .filter(m -> m.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Member", request.memberId()));
            access.assertBranchAccess(referee.getHomeBranch().getId());
            referral = createForMember(referrer, referee, ReferralChannel.DESK);
        } else {
            LeadRow lead = loadLead(request.leadId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lead", request.leadId()));
            access.assertBranchAccess(lead.branchId());
            if (!lead.isOpen()) {
                throw new BusinessRuleException("Lead " + lead.id() + " is " + lead.status()
                        + "; attach the code to the member instead");
            }
            if (referrals.findByRefereeLeadId(lead.id()).isPresent()
                    || (lead.memberId() != null && referrals.findByRefereeMemberId(lead.memberId()).isPresent())) {
                throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "This prospect already has a referral");
            }
            if (ReferralRules.isSelfReferral(party(referrer),
                    new ReferralRules.Party(lead.memberId(), lead.email(), lead.phone()))) {
                throw new BusinessRuleException("Members cannot refer themselves");
            }
            referral = referrals.saveAndFlush(new Referral(referrer.getId(), codes.codeFor(referrer),
                    new Referral.Referee(lead.memberId(), lead.id(), lead.firstName(), lead.lastName(), lead.email(),
                            lead.phone(), lead.branchId()), ReferralChannel.DESK));
            afterCapture(referral, referrer);
        }
        return queries.get(referral.getId());
    }

    /** A member who signed up on their own enters the code they were given. */
    @Transactional
    public MyReferralDashboard claim(String code) {
        UserPrincipal caller = CurrentUser.get();
        if (caller.memberId() == null) {
            throw new ForbiddenException("Only members can claim a referral code");
        }
        Member me = memberRepository.findByIdForUpdate(caller.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Member", caller.memberId()));
        Member referrer = activeReferrer(code)
                .orElseThrow(() -> new BusinessRuleException("Referral code " + code + " is not valid"));
        createForMember(referrer, me, ReferralChannel.SELF_SIGNUP);
        return myDashboardFor(me);
    }

    private Referral createForMember(Member referrer, Member referee, ReferralChannel channel) {
        if (ReferralRules.isSelfReferral(party(referrer), party(referee))) {
            throw new BusinessRuleException("Members cannot refer themselves");
        }
        if (referrals.findByRefereeMemberId(referee.getId()).isPresent()) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "This member already has a referral");
        }
        Instant now = Instant.now(clock);
        Optional<QualifyingPurchase> first = conversion.firstQualifyingPurchase(referee.getId());
        if (first.isPresent() && !ReferralRules.isNewCustomer(first.get().activatedAt(), now,
                props.referral().attachGraceDays())) {
            throw new BusinessRuleException("Referral codes are for new members only; this member's first paid "
                    + "membership started on " + RewardTexts.day(first.get().activatedAt(), clock.getZone()));
        }
        Referral referral = referrals.saveAndFlush(new Referral(referrer.getId(), codes.codeFor(referrer),
                new Referral.Referee(referee.getId(), null, referee.getFirstName(), referee.getLastName(),
                        referee.getEmail(), referee.getPhone(), referee.getHomeBranch().getId()), channel));
        afterCapture(referral, referrer);
        if (first.isPresent()) {   // bought just before the code was mentioned (inside the grace window)
            conversion.convertIfQualified(referral, now);
        }
        return referral;
    }

    private void afterCapture(Referral referral, Member referrer) {
        audit.record(AuditEvent.of("REFERRAL", referral.getId(), "CREATED").member(referral.getRefereeMemberId())
                .branch(referral.getBranchId()).with("referrerMemberId", referrer.getId())
                .with("channel", referral.getChannel()).with("leadId", referral.getRefereeLeadId()));
        GrowthProperties.Referral cfg = props.referral();
        notifications.notifyMember(referrer, NotificationType.REFERRAL_SIGNED_UP, "Your friend signed up",
                referral.refereeDisplayName() + " signed up with your referral code " + referral.getCode() + ". You'll earn "
                        + RewardTexts.rupees(cfg.referrerCredit()) + " store credit when they buy their first membership.",
                "referral:" + referral.getId() + ":signup");
    }

    // ================================================================== member & public views

    @Transactional
    public MyReferralDashboard myDashboard() {
        UserPrincipal caller = CurrentUser.get();
        if (caller.memberId() == null) {
            throw new ForbiddenException("Only members have a referral dashboard");
        }
        Member me = memberRepository.findById(caller.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Member", caller.memberId()));
        return myDashboardFor(me);
    }

    private MyReferralDashboard myDashboardFor(Member me) {
        GrowthProperties.Referral cfg = props.referral();
        String code = codes.codeFor(me);
        List<Referral> mine = referrals.findByReferrerMemberIdOrderByCreatedAtDesc(me.getId());
        List<ReferralReward> myRewards = rewards.findByMemberIdOrderByCreatedAtDesc(me.getId());
        Map<Long, ReferralReward> referrerRewardByReferral = myRewards.stream()
                .filter(w -> w.getBeneficiary() == Beneficiary.REFERRER)
                .collect(Collectors.toMap(ReferralReward::getReferralId, Function.identity(), (a, b) -> a));
        Map<Long, ReferralQueries.CodeInfo> codeInfo = queries.codeInfo(myRewards.stream()
                .map(ReferralReward::getDiscountId).filter(Objects::nonNull).toList());

        List<MyReferral> items = mine.stream().map(r -> new MyReferral(r.getId(), r.refereeDisplayName(), r.getChannel(),
                r.getStatus(), r.getRejectionReason(), r.getReferrerRewardSkipped(), r.getCreatedAt(), r.getConvertedAt(),
                r.getHoldUntil(), Optional.ofNullable(referrerRewardByReferral.get(r.getId()))
                .map(w -> view(w, codeInfo)).orElse(null))).toList();
        List<RewardView> welcome = myRewards.stream().filter(w -> w.getBeneficiary() == Beneficiary.REFEREE)
                .map(w -> view(w, codeInfo)).toList();
        BigDecimal earned = sum(myRewards, RewardStatus.AVAILABLE);
        BigDecimal pending = sum(myRewards, RewardStatus.HELD);
        long joined = mine.stream().filter(r -> r.getStatus() == ReferralStatus.CONVERTED).count();
        String referredBy = referrals.findByRefereeMemberId(me.getId())
                .flatMap(r -> memberRepository.findById(r.getReferrerMemberId())).map(Member::getFirstName).orElse(null);
        boolean canClaim = referredBy == null && conversion.firstQualifyingPurchase(me.getId())
                .map(p -> ReferralRules.isNewCustomer(p.activatedAt(), Instant.now(clock), cfg.attachGraceDays()))
                .orElse(true);
        return new MyReferralDashboard(code, SHARE_PATH + code, headline(me.getFirstName()), refereeOfferText(),
                referrerRewardText(), cfg.holdDays(), cfg.monthlyCap(), mine.size(), joined, earned, pending, items,
                welcome, referredBy, canClaim);
    }

    /** Public, unauthenticated: the referrer's first name and the offer only. */
    @Transactional(readOnly = true)
    public PublicReferral lookup(String rawCode) {
        String code = ReferralRules.normalizeCode(rawCode)
                .orElseThrow(() -> new ResourceNotFoundException("Referral code", rawCode));
        Member referrer = activeReferrer(code).orElseThrow(() -> new ResourceNotFoundException("Referral code", code));
        return new PublicReferral(code, referrer.getFirstName(), headline(referrer.getFirstName()), refereeOfferText());
    }

    // ================================================================== staff views

    @Transactional
    public MemberReferralSummary memberSummary(Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        String code = codes.codeFor(member);
        List<Referral> mine = referrals.findByReferrerMemberIdOrderByCreatedAtDesc(memberId);
        long conversions = mine.stream().filter(r -> r.getStatus() == ReferralStatus.CONVERTED).count();
        BigDecimal earned = rewards.findByMemberIdOrderByCreatedAtDesc(memberId).stream()
                .filter(w -> w.getBeneficiary() == Beneficiary.REFERRER && w.getStatus() == RewardStatus.AVAILABLE)
                .map(ReferralReward::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        ReferralResponse referredBy = referrals.findByRefereeMemberId(memberId).map(r -> queries.get(r.getId())).orElse(null);
        return new MemberReferralSummary(memberId, code, SHARE_PATH + code, mine.size(), conversions, earned, referredBy);
    }

    @Transactional(readOnly = true)
    public ReferralSettings settings() {
        GrowthProperties.Referral cfg = props.referral();
        return new ReferralSettings(props.enabled(), cfg.referrerCredit(), cfg.refereeRewardType(),
                cfg.refereeDiscountType(), cfg.refereeRewardValue(), cfg.refereeCodeValidDays(), cfg.holdDays(),
                cfg.monthlyCap(), cfg.attachGraceDays(), referrerRewardText(), refereeReward());
    }

    // ================================================================== helpers

    private Optional<Member> activeReferrer(String rawCode) {
        return codes.ownerOf(rawCode).flatMap(memberRepository::findById)
                .filter(m -> m.getDeletedAt() == null && m.isActive());
    }

    private Optional<LeadRow> loadLead(Long leadId) {
        return jdbc.query("""
                select id, branch_id, first_name, last_name, email, phone, member_id, status from leads where id = :id
                """, Map.of("id", leadId), (rs, n) -> new LeadRow(rs.getLong("id"), rs.getLong("branch_id"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"), rs.getString("phone"),
                (Long) rs.getObject("member_id", Long.class), rs.getString("status"))).stream().findFirst();
    }

    /** True when a member with this email already bought a paid membership (not a new customer). */
    private boolean existingCustomerWithEmail(String email) {
        String normalized = ReferralRules.normalizeEmail(email);
        if (normalized == null) {
            return false;
        }
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from members m join invoices i on i.member_id = m.id
                               where lower(m.email) = :email and m.deleted_at is null
                                 and i.paid_at is not null and i.amount_paid > 0)
                """, Map.of("email", normalized), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private RewardView view(ReferralReward w, Map<Long, ReferralQueries.CodeInfo> codeInfo) {
        ReferralQueries.CodeInfo info = w.getDiscountId() == null ? null : codeInfo.get(w.getDiscountId());
        return new RewardView(w.getId(), w.getReferralId(), w.getBeneficiary(), w.getRewardType(), w.getAmount(),
                w.getDiscountType(), w.getStatus(), RewardTexts.reward(w.getRewardType(), w.getDiscountType(), w.getAmount()),
                w.getAvailableAt(), w.getReleasedAt(), w.getReversedAt(), info == null ? null : info.code(),
                info == null ? null : info.validUntil(), info == null ? null : info.used());
    }

    private static BigDecimal sum(List<ReferralReward> list, RewardStatus status) {
        return list.stream().filter(w -> w.getStatus() == status && w.getRewardType() == RewardType.STORE_CREDIT)
                .map(ReferralReward::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String headline(String firstName) {
        return firstName + " invited you to train with us";
    }

    private String refereeOfferText() {
        return "Buy your first membership with this invitation and get " + refereeReward() + ".";
    }

    private String refereeReward() {
        GrowthProperties.Referral cfg = props.referral();
        return RewardTexts.reward(cfg.refereeRewardType(), cfg.refereeDiscountType(), cfg.refereeRewardValue());
    }

    private String referrerRewardText() {
        return "Earn " + RewardTexts.rupees(props.referral().referrerCredit())
                + " store credit for every friend who buys their first membership.";
    }

    private static ReferralRules.Party party(Member m) {
        return new ReferralRules.Party(m.getId(), m.getEmail(), m.getPhone());
    }
}
