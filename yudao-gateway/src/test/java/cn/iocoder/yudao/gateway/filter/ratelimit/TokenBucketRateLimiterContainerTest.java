package cn.iocoder.yudao.gateway.filter.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 令牌桶限流器的集成测试：用**真实 Redis** 跑 Lua 脚本
 *
 * 为什么必须用真 Redis：限流的核心逻辑在 Lua 里，用 mock 测等于什么都没测。
 * Testcontainers 会在 CI 里拉起一个一次性 Redis 容器，跑完即销毁。
 *
 * @author campus-project
 */
@Testcontainers(disabledWithoutDocker = true)
class TokenBucketRateLimiterContainerTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        limiter = new TokenBucketRateLimiter(new ReactiveStringRedisTemplate(connectionFactory));
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldAllowBurstUpToCapacityThenReject() {
        String key = "test:burst:" + System.nanoTime();
        // 每秒补 1 个，桶容量 3
        for (int i = 1; i <= 3; i++) {
            TokenBucketRateLimiter.RateLimitResult result = limiter.tryAcquire(key, 1, 3).block();
            assertNotNull(result);
            assertTrue(result.allowed(), "第 " + i + " 个请求应在桶容量内被放行");
        }
        TokenBucketRateLimiter.RateLimitResult rejected = limiter.tryAcquire(key, 1, 3).block();
        assertNotNull(rejected);
        assertFalse(rejected.allowed(), "超过桶容量应被拒绝");
        assertEquals(0, rejected.remaining());
    }

    @Test
    void shouldRefillTokensOverTime() throws InterruptedException {
        String key = "test:refill:" + System.nanoTime();
        // 先耗尽容量 2
        limiter.tryAcquire(key, 1, 2).block();
        limiter.tryAcquire(key, 1, 2).block();
        assertFalse(Boolean.TRUE.equals(limiter.tryAcquire(key, 1, 2).block().allowed()));

        // 等待约 1.2 秒，应补充 1 个令牌
        Thread.sleep(1200);
        TokenBucketRateLimiter.RateLimitResult afterWait = limiter.tryAcquire(key, 1, 2).block();
        assertNotNull(afterWait);
        assertTrue(afterWait.allowed(), "等待一个补充周期后应重新放行");
    }

    @Test
    void shouldIsolateBucketsByKey() {
        String first = "test:isolate-a:" + System.nanoTime();
        String second = "test:isolate-b:" + System.nanoTime();
        // 耗尽第一个桶
        limiter.tryAcquire(first, 1, 1).block();
        assertFalse(Boolean.TRUE.equals(limiter.tryAcquire(first, 1, 1).block().allowed()));
        // 第二个桶不受影响
        assertTrue(Boolean.TRUE.equals(limiter.tryAcquire(second, 1, 1).block().allowed()),
                "不同 key 的桶必须互相隔离（多租户/多用户的限流互不影响）");
    }

}
