package com.gymops.growth;

import com.gymops.growth.campaign.CampaignRepository;
import com.gymops.growth.campaign.CampaignRunner;
import com.gymops.growth.campaign.RunTrigger;
import com.gymops.growth.referral.ReferralConversionService;
import com.gymops.growth.referral.ReferralRepository;
import com.gymops.growth.referral.ReferralRewardRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Growth housekeeping, hourly by default: release referral rewards whose hold has ended, re-check
 * pending referrals (a missed activation event can never lose a reward), and send the scheduled
 * campaigns that are due. Each item runs in its own transaction; every step is idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrowthJobs {

    private final ReferralRewardRepository rewards;
    private final ReferralRepository referrals;
    private final ReferralConversionService conversion;
    private final CampaignRepository campaigns;
    private final CampaignRunner runner;
    private final GrowthProperties props;
    private final Clock clock;

    public record RunSummary(int rewardsReleased, int referralsConverted, int campaignsRun, int failures) {
    }

    @Scheduled(cron = "${app.growth.job-cron:0 10 * * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "growth-jobs", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduled() {
        if (!props.enabled()) {
            return;
        }
        RunSummary s = runAll();
        log.info("growth_jobs rewardsReleased={} referralsConverted={} campaignsRun={} failures={}",
                s.rewardsReleased(), s.referralsConverted(), s.campaignsRun(), s.failures());
    }

    public RunSummary runAll() {
        int[] failures = {0};
        int converted = each(referrals.findPendingRefereeMemberIds(), conversion::reconcile, failures, "reconcile referral");
        int released = each(rewards.findDueIds(Instant.now(clock)), conversion::release, failures, "release reward");
        int ran = each(campaigns.findDueIds(Instant.now(clock)), id -> {
            runner.run(id, RunTrigger.SCHEDULED);
            return true;
        }, failures, "run campaign");
        return new RunSummary(released, converted, ran, failures[0]);
    }

    private static int each(List<Long> ids, Predicate<Long> action, int[] failures, String what) {
        int done = 0;
        for (Long id : ids) {
            try {
                if (action.test(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                failures[0]++;
                log.error("Growth job failed to {} {}: {}", what, id, e.toString(), e);
            }
        }
        return done;
    }
}
