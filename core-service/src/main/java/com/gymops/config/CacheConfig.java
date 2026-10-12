package com.gymops.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gymops.dashboard.dto.AdminDashboardResponse;
import com.gymops.member.dto.MemberResponse;
import com.gymops.plan.dto.PlanResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis-backed caching for read-heavy, rarely-changing data.
 *
 * <ul>
 *   <li>{@code plans} / {@code activePlans}: public plan catalogue (GET /plans), the most-read, rarely-changing data</li>
 *   <li>{@code memberProfiles}: member profile lookups at the front desk</li>
 *   <li>{@code adminDashboard}: aggregate KPIs, acceptable to be 60s stale</li>
 * </ul>
 *
 * The cache manager is transaction-aware: puts/evicts happen only after the surrounding DB
 * transaction commits, so a rolled-back update never evicts or poisons the cache.
 * Redis is an optimisation, never a dependency: {@link #errorHandler()} downgrades Redis failures to
 * cache misses so the API keeps serving from PostgreSQL.
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String PLANS = "plans";
    public static final String ACTIVE_PLANS = "activePlans";
    public static final String MEMBER_PROFILES = "memberProfiles";
    public static final String ADMIN_DASHBOARD = "adminDashboard";

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        TypeFactory types = mapper.getTypeFactory();

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("gymops:")
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));

        // One concrete type per cache: no polymorphic default typing (and no deserialization gadgets).
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(10)))
                .withInitialCacheConfigurations(Map.of(
                        PLANS, typed(base, mapper, types.constructType(PlanResponse.class), Duration.ofHours(1)),
                        ACTIVE_PLANS, typed(base, mapper,
                                types.constructCollectionType(List.class, PlanResponse.class), Duration.ofHours(1)),
                        MEMBER_PROFILES, typed(base, mapper, types.constructType(MemberResponse.class), Duration.ofMinutes(10)),
                        ADMIN_DASHBOARD, typed(base, mapper,
                                types.constructType(AdminDashboardResponse.class), Duration.ofSeconds(60))))
                .transactionAware()
                .build();
    }

    private static RedisCacheConfiguration typed(RedisCacheConfiguration base, ObjectMapper mapper, JavaType type,
                                                 Duration ttl) {
        return base.entryTtl(ttl).serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, type)));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache GET failed (cache={}), falling back to database: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed (cache={}): {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.error("Cache EVICT failed (cache={}, key={}); entry may be stale until TTL: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.error("Cache CLEAR failed (cache={}): {}", cache.getName(), e.getMessage());
            }
        };
    }
}
