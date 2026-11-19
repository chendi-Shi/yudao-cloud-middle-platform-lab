package cn.iocoder.yudao.gateway.filter.ratelimit;

import cn.iocoder.yudao.gateway.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.gateway.util.WebFrameworkUtils;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.TOO_MANY_REQUESTS;

/**
 * 网关全局限流过滤器
 *
 * 在过滤器链中的位置：TokenAuthenticationFilter(-100) 之后、路由转发之前，
 * 这样限流维度可以识别出「已登录用户」，未登录则退化为按 IP 限流。
 *
 * 请求超限时返回 HTTP 429 + CommonResult，并带上 X-RateLimit-* 响应头，方便前端做退避重试。
 *
 * @author campus-project
 */
@Component
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final RateLimitProperties properties;

    private final TokenBucketRateLimiter rateLimiter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        // 健康检查、静态资源等不做限流
        if (isIgnored(path)) {
            return chain.filter(exchange);
        }

        RateLimitProperties.Rule rule = matchRule(path);
        String key = buildKey(exchange, rule);
        return rateLimiter.tryAcquire(key, rule.getQps(), rule.getCapacity())
                .flatMap(result -> handleResult(exchange, chain, result, rule))
                .onErrorResume(ex -> {
                    // Redis 故障降级策略：fail-open 保可用，fail-close 保安全
                    if (properties.isFailOpen()) {
                        log.error("[filter][限流器异常，fail-open 放行 key({})]", key, ex);
                        return chain.filter(exchange);
                    }
                    log.error("[filter][限流器异常，fail-close 拒绝 key({})]", key, ex);
                    return writeTooManyRequests(exchange);
                });
    }

    private Mono<Void> handleResult(ServerWebExchange exchange, GatewayFilterChain chain,
                                    TokenBucketRateLimiter.RateLimitResult result, RateLimitProperties.Rule rule) {
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set("X-RateLimit-Limit", String.valueOf(rule.getQps()));
        response.getHeaders().set("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        if (!result.allowed()) {
            log.warn("[handleResult][uri({}) 触发限流，qps({})]", exchange.getRequest().getURI(), rule.getQps());
            return writeTooManyRequests(exchange);
        }
        return chain.filter(exchange);
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        // 提示客户端 1 秒后重试（令牌桶按秒补充）
        response.getHeaders().set("Retry-After", "1");
        return WebFrameworkUtils.writeJSON(exchange, CommonResult.error(TOO_MANY_REQUESTS));
    }

    private boolean isIgnored(String path) {
        return properties.getIgnorePaths().stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private RateLimitProperties.Rule matchRule(String path) {
        for (RateLimitProperties.Rule rule : properties.getRules()) {
            if (rule.getPath() != null && PATH_MATCHER.match(rule.getPath(), path)) {
                // 规则未显式配置 qps/capacity 时，回落到默认值
                if (rule.getQps() <= 0) {
                    rule.setQps(properties.getDefaultQps());
                }
                if (rule.getCapacity() <= 0) {
                    rule.setCapacity(properties.getDefaultCapacity());
                }
                return rule;
            }
        }
        RateLimitProperties.Rule fallback = new RateLimitProperties.Rule();
        fallback.setPath("default");
        fallback.setQps(properties.getDefaultQps());
        fallback.setCapacity(properties.getDefaultCapacity());
        return fallback;
    }

    /**
     * 构建限流的维度 key
     *
     * TENANT_USER：tenant:{tenantId}:user:{userId}，未登录/无法识别用户时退化为 IP
     * IP：ip:{clientIp}
     */
    private String buildKey(ServerWebExchange exchange, RateLimitProperties.Rule rule) {
        StringBuilder key = new StringBuilder(properties.getKeyPrefix());
        // 规则维度：不同规则的桶不互相影响（登录接口被限流不影响订单接口）
        key.append(rule.getPath()).append(':');
        Long userId = SecurityFrameworkUtils.getLoginUserId(exchange);
        if ("IP".equalsIgnoreCase(properties.getDimension()) || userId == null) {
            key.append("ip:").append(WebFrameworkUtils.getClientIP(exchange));
        } else {
            Long tenantId = WebFrameworkUtils.getTenantId(exchange);
            key.append("t:").append(tenantId).append(":u:").append(userId);
        }
        return key.toString();
    }

    @Override
    public int getOrder() {
        // 在 TokenAuthenticationFilter(-100) 之后，保证能拿到登录用户；在路由转发(0) 之前
        return -50;
    }

}
