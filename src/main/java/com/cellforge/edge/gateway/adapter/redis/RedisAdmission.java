package com.cellforge.edge.gateway.adapter.redis;

import com.cellforge.edge.billing.domain.Plan;
import com.cellforge.edge.gateway.application.Admission;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
class RedisAdmission implements Admission {

    private static final Logger log = LoggerFactory.getLogger(RedisAdmission.class);
    private static final long QUOTA_TTL = Duration.ofDays(40).toSeconds();

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final RedisScript<List<Object>> ADMIT =
        (RedisScript) RedisScript.of(new ClassPathResource("scripts/admit.lua"), List.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Counter failOpen;

    RedisAdmission(StringRedisTemplate redis, Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.clock = clock;
        this.failOpen = meters.counter("cellforge.admission.fail_open");
    }

    @Override
    public Decision admit(UUID tenantId, Plan plan) {
        // The hash tag keeps both keys on one slot if this ever runs against a Redis cluster.
        String tag = "{" + tenantId + "}";
        String month = YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
        List<String> keys = List.of("quota:" + tag + ":" + month, "rl:" + tag);
        double perSecond = plan.requestsPerMinute() / 60.0;
        List<Object> result;
        try {
            result = redis.execute(ADMIT, keys, Long.toString(plan.monthlyApiQuota()), Double.toString(perSecond),
                Integer.toString(plan.burst()), Long.toString(QUOTA_TTL));
        } catch (RedisConnectionFailureException e) {
            // Limits protect the cells, but an outage of the limiter should not become an outage of the product.
            failOpen.increment();
            log.warn("Redis unavailable, admitting tenant {} without limits", tenantId);
            return new Decision(Outcome.ADMITTED, 0);
        }
        long admitted = (Long) result.get(0);
        String reason = (String) result.get(1);
        long wait = (Long) result.get(2);
        if (admitted == 1) {
            return new Decision(Outcome.ADMITTED, 0);
        }
        return new Decision("quota".equals(reason) ? Outcome.QUOTA_EXHAUSTED : Outcome.RATE_LIMITED, wait);
    }
}
