package cn.iocoder.yudao.gateway.filter.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis + Lua 的令牌桶限流器
 *
 * Q：为什么用 Redis 而不是网关本地内存（Guava RateLimiter）？
 * A：网关是多实例部署的，本地令牌桶是「每实例 100 QPS」，3 个实例就是 300 QPS，限不住全局流量；
 *    而且实例重启 / 扩容会重置桶状态。Redis 让所有实例共享同一个桶，限流阈值才是「集群级」的。
 *
 * Q：为什么用 Lua 脚本而不是 Java 里 GET 再 SET？
 * A：GET 和 SET 之间有时间窗口，并发下会「超卖」令牌（check-then-act 竞态）。Lua 在 Redis 单线程里
 *    原子执行整段逻辑，天然没有竞态，也不需要额外加分布式锁。
 *
 * Q：为什么 now_ms 由应用传入，而不是在 Lua 里调 redis.call('TIME')？
 * A：TIME 在 Redis 5+ 的脚本里属于「非确定性命令」，在副本/AOF 重放时行为不一致（Redis 会拒绝或告警）。
 *    由应用传入时间戳更稳妥；多实例时钟漂移对本场景（秒级限流）可忽略。
 *
 * @author campus-project
 */
@Slf4j
@Component
public class TokenBucketRateLimiter {

    private final ReactiveStringRedisTemplate redisTemplate;

    private final DefaultRedisScript<List> tokenBucketScript;

    @SuppressWarnings("unchecked")
    public TokenBucketRateLimiter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/rate_limit_token_bucket.lua")));
        script.setResultType(List.class);
        this.tokenBucketScript = script;
    }

    /**
     * 尝试获取一个令牌
     *
     * @param key     桶的 key
     * @param qps     每秒补充令牌数
     * @param capacity 桶容量
     * @return 限流结果（是否放行 + 剩余令牌）
     */
    public Mono<RateLimitResult> tryAcquire(String key, int qps, int capacity) {
        int safeQps = Math.max(qps, 1);
        int safeCapacity = Math.max(capacity, 1);
        long now = System.currentTimeMillis();
        // 桶空闲多久后可以被回收：装满一桶所需时间 + 1 秒缓冲
        long ttlMillis = (long) Math.ceil(safeCapacity * 1000.0 / safeQps) + 1000L;
        return redisTemplate.execute(tokenBucketScript, Collections.singletonList(key),
                        String.valueOf(safeQps), String.valueOf(safeCapacity), String.valueOf(now),
                        "1", String.valueOf(ttlMillis))
                .next()
                .map(raw -> parseResult(raw, safeCapacity))
                // 脚本没有返回值时（理论上不会发生），按放行处理，避免误伤
                .defaultIfEmpty(RateLimitResult.allowed(safeCapacity));
    }

    private RateLimitResult parseResult(Object raw, int capacity) {
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            log.warn("[parseResult][限流脚本返回值不符合预期({})，按放行处理]", raw);
            return RateLimitResult.allowed(capacity);
        }
        boolean allowed = toLong(list.get(0)) == 1L;
        int remaining = (int) toLong(list.get(1));
        return new RateLimitResult(allowed, remaining, capacity);
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * 限流结果
     *
     * @param allowed   是否放行
     * @param remaining 剩余令牌数
     * @param capacity  桶容量
     */
    public record RateLimitResult(boolean allowed, int remaining, int capacity) {

        public static RateLimitResult allowed(int capacity) {
            return new RateLimitResult(true, capacity, capacity);
        }

    }

}
