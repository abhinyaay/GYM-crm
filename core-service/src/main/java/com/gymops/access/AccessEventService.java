package com.gymops.access;

import com.gymops.access.dto.AccessDtos.AccessEventResponse;
import com.gymops.access.dto.AccessDtos.AccessStats;
import com.gymops.access.dto.AccessDtos.AlarmResponse;
import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.branch.BranchService;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the access module: event search, live feed, per-member entry log, daily stats, alarms. */
@Service
@RequiredArgsConstructor
public class AccessEventService {

    /** Search filters (all optional). Dates are business-time-zone days, inclusive. */
    public record EventFilter(Long branchId, Long deviceId, Long memberId, Decision decision, Direction direction,
                              AccessReason reason, LocalDate from, LocalDate to) {
    }

    private final AccessEventRepository events;
    private final AccessDeviceRepository devices;
    private final AccessAlarmRepository alarms;
    private final MemberRepository members;
    private final BranchService branchService;
    private final AccessPolicy access;
    private final AccessProperties props;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AccessEventResponse> search(EventFilter filter, Pageable pageable) {
        BranchScope scope = access.scope(filter.branchId());
        ZoneId zone = clock.getZone();
        Specification<AccessEvent> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (!scope.all()) {
                p.add(root.get("branchId").in(scope.ids()));
            }
            if (filter.deviceId() != null) {
                p.add(cb.equal(root.get("deviceId"), filter.deviceId()));
            }
            if (filter.memberId() != null) {
                p.add(cb.equal(root.get("memberId"), filter.memberId()));
            }
            if (filter.decision() != null) {
                p.add(cb.equal(root.get("decision"), filter.decision()));
            }
            if (filter.direction() != null) {
                p.add(cb.equal(root.get("direction"), filter.direction()));
            }
            if (filter.reason() != null) {
                p.add(cb.equal(root.get("reasonCode"), filter.reason()));
            }
            if (filter.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from().atStartOfDay(zone).toInstant()));
            }
            if (filter.to() != null) {
                p.add(cb.lessThan(root.get("occurredAt"), filter.to().plusDays(1).atStartOfDay(zone).toInstant()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<AccessEvent> page = events.findAll(spec, pageable);
        return PageResponse.of(page.map(enricher(page.getContent())));
    }

    /** Latest events (newest first); pass {@code afterId} to fetch only events newer than what the client has. */
    @Transactional(readOnly = true)
    public List<AccessEventResponse> feed(Long branchId, long afterId, int limit) {
        BranchScope scope = access.scope(branchId);
        List<AccessEvent> latest = events.findFeed(scope.all(), scope.ids(), Math.max(afterId, 0),
                PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
        return latest.stream().map(enricher(latest)).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessEventResponse> memberEvents(Long memberId, Pageable pageable) {
        Member member = members.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(memberId, member.getHomeBranch().getId());
        Specification<AccessEvent> spec = (root, query, cb) -> cb.equal(root.get("memberId"), memberId);
        Page<AccessEvent> page = events.findAll(spec, pageable);
        return PageResponse.of(page.map(enricher(page.getContent())));
    }

    @Transactional(readOnly = true)
    public AccessStats stats(Long branchId, LocalDate date) {
        BranchScope scope = access.scope(branchId);
        ZoneId zone = branchId != null ? branchService.load(branchId).zone() : clock.getZone();
        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(zone));
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        long entries = 0;
        long exits = 0;
        long denied = 0;
        Map<String, Long> deniedByReason = new TreeMap<>();
        Map<String, Long> grantedByCredential = new TreeMap<>();
        for (Object[] row : events.stats(scope.all(), scope.ids(), from, to)) {
            Direction direction = (Direction) row[0];
            Decision decision = (Decision) row[1];
            AccessReason reason = (AccessReason) row[2];
            CredentialType type = (CredentialType) row[3];
            long count = ((Number) row[4]).longValue();
            if (decision == Decision.DENIED) {
                denied += count;
                deniedByReason.merge(reason == null ? "UNKNOWN" : reason.name(), count, Long::sum);
            } else {
                if (direction == Direction.ENTRY) {
                    entries += count;
                } else {
                    exits += count;
                }
                grantedByCredential.merge(type.name(), count, Long::sum);
            }
        }
        long offline = events.count((root, query, cb) -> cb.and(
                scope.all() ? cb.conjunction() : root.get("branchId").in(scope.ids()),
                cb.isTrue(root.get("offline")),
                cb.greaterThanOrEqualTo(root.get("occurredAt"), from), cb.lessThan(root.get("occurredAt"), to)));
        Instant now = Instant.now(clock);
        List<AccessDevice> deviceList = devices.findScoped(scope.all(), scope.ids());
        long online = deviceList.stream().filter(d -> d.isOnline(now, props.heartbeatTimeout())).count();
        long offlineDevices = deviceList.stream().filter(d -> d.isActive() && d.getOfflineSince() != null).count();
        return new AccessStats(branchId, day, entries, exits, denied, offline, deniedByReason, grantedByCredential,
                deviceList.size(), online, offlineDevices, alarms.countOpen(scope.all(), scope.ids()));
    }

    @Transactional(readOnly = true)
    public List<AlarmResponse> alarms(Long branchId, boolean openOnly, int limit) {
        BranchScope scope = access.scope(branchId);
        List<AccessAlarm> list = alarms.search(scope.all(), scope.ids(), openOnly,
                PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
        Map<Long, String> names = deviceNames(list.stream().map(AccessAlarm::getDeviceId).toList());
        return list.stream().map(a -> AlarmResponse.from(a, names.get(a.getDeviceId()))).toList();
    }

    @Transactional
    public AlarmResponse acknowledge(Long alarmId) {
        AccessAlarm alarm = alarms.findById(alarmId).orElseThrow(() -> new ResourceNotFoundException("Alarm", alarmId));
        access.assertBranchAccess(alarm.getBranchId());
        alarm.acknowledge(CurrentUser.get().userId(), Instant.now(clock));
        audit.record(AuditEvent.of("ACCESS_ALARM", alarmId, "ACKNOWLEDGED").branch(alarm.getBranchId())
                .with("type", alarm.getAlarmType()));
        return AlarmResponse.from(alarm, deviceNames(List.of(alarm.getDeviceId())).get(alarm.getDeviceId()));
    }

    private Function<AccessEvent, AccessEventResponse> enricher(Collection<AccessEvent> batch) {
        Map<Long, String> deviceNames = deviceNames(batch.stream().map(AccessEvent::getDeviceId).toList());
        List<Long> memberIds = batch.stream().map(AccessEvent::getMemberId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Member> memberById = memberIds.isEmpty() ? Map.of()
                : members.findAllById(memberIds).stream().collect(Collectors.toMap(Member::getId, Function.identity()));
        return e -> {
            Member m = e.getMemberId() == null ? null : memberById.get(e.getMemberId());
            return AccessEventResponse.from(e, deviceNames.get(e.getDeviceId()), m == null ? null : m.getMemberCode(),
                    m == null ? null : m.fullName());
        };
    }

    private Map<Long, String> deviceNames(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return devices.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(AccessDevice::getId, AccessDevice::getName));
    }
}
