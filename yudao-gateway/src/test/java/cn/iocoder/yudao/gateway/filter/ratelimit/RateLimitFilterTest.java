package cn.iocoder.yudao.gateway.filter.ratelimit;

import cn.iocoder.yudao.gateway.util.SecurityFrameworkUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 限流过滤器的集成测试（限流器本身用 mock，真实 Redis 的测试见 {@link TokenBucketRateLimiterContainerTest}）
 *
 * 覆盖：放行、超限 429、响应头、key 维度（租户+用户）、Redis 故障时 fail-open。
 *
 * @author campus-project
 */
class RateLimitFilterTest {

    private RateLimitProperties properties;

    private TokenBucketRateLimiter rateLimiter;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setDefaultQps(100);
        properties.setDefaultCapacity(200);
        properties.setFailOpen(true);

        RateLimitProperties.Rule loginRule = new RateLimitProperties.Rule();
        loginRule.setPath("/admin-api/system/auth/login");
        loginRule.setQps(5);
        loginRule.setCapacity(10);
        properties.setRules(new ArrayList<>(List.of(loginRule)));

        rateLimiter = mock(TokenBucketRateLimiter.class);
        filter = new RateLimitFilter(properties, rateLimiter);
    }

    private MockServerWebExchange buildExchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).header("tenant-id", "1").build());
    }

    private boolean chainCalled(MockServerWebExchange exchange) {
        AtomicBoolean called = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            called.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return called.get();
    }

    @Test
    void shouldPassWhenTokenAcquired() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(Mono.just(new TokenBucketRateLimiter.RateLimitResult(true, 9, 10)));
        MockServerWebExchange exchange = buildExchange("/admin-api/system/auth/login");

        assertTrue(chainCalled(exchange));
        assertEquals("5", exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));
        assertEquals("9", exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldRejectWith429WhenTokenExhausted() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(Mono.just(new TokenBucketRateLimiter.RateLimitResult(false, 0, 10)));
        MockServerWebExchange exchange = buildExchange("/admin-api/system/auth/login");

        assertFalse(chainCalled(exchange), "超限时不能继续转发");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("1", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertTrue(body.contains("\"code\":429"), "响应体应为 CommonResult 格式，实际：" + body);
    }

    @Test
    void shouldUseRuleSpecificLimitAndTenantUserKey() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(Mono.just(new TokenBucketRateLimiter.RateLimitResult(true, 1, 10)));
        MockServerWebExchange exchange = buildExchange("/admin-api/system/auth/login");
        exchange.getAttributes().put("login-user-id", 42L);

        chainCalled(exchange);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(keyCaptor.capture(), eq(5), eq(10));
        String key = keyCaptor.getValue();
        assertTrue(key.contains("t:1"), "key 必须带租户编号，实际：" + key);
        assertTrue(key.contains("u:42"), "key 必须带用户编号，实际：" + key);
        assertTrue(key.contains("/admin-api/system/auth/login"), "不同规则要用不同的桶，实际：" + key);
    }

    @Test
    void shouldFailOpenWhenRedisIsDown() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));
        MockServerWebExchange exchange = buildExchange("/admin-api/system/user/page");

        assertTrue(chainCalled(exchange), "fail-open 时 Redis 故障应放行，而不是把全站打挂");
    }

    @Test
    void shouldFailCloseWhenConfigured() {
        properties.setFailOpen(false);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));
        MockServerWebExchange exchange = buildExchange("/admin-api/system/user/page");

        assertFalse(chainCalled(exchange));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);
        MockServerWebExchange exchange = buildExchange("/admin-api/system/user/page");

        assertTrue(chainCalled(exchange));
        verifyNoInteractions(rateLimiter);
    }

}
