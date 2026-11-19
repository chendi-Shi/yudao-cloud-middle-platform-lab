package cn.iocoder.yudao.gateway.filter.security;

import cn.iocoder.yudao.framework.common.util.sign.InternalSignUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 内部调用签名过滤器（网关侧）
 *
 * 对 /admin-api/** 与 /app-api/** 的请求，在转发给下游服务前，附加 HMAC 签名头：
 * X-Yudao-Ts / X-Yudao-Nonce / X-Yudao-Sign
 *
 * 为什么只签这两类路径？
 * - /admin-api、/app-api 是「外部入口」，必须经过网关；
 * - /rpc-api/** 是服务之间的 Feign 调用，本来就不经过网关，签了也没人校验；
 * - /actuator/**、knife4j 文档等运维路径不签，避免健康检查被误伤。
 *
 * @author campus-project
 */
@Slf4j
@Component
@EnableConfigurationProperties(InternalSignFilter.Properties.class)
public class InternalSignFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final Properties properties;

    public InternalSignFilter(Properties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (properties.getPaths().stream().noneMatch(pattern -> PATH_MATCHER.match(pattern, path))) {
            return chain.filter(exchange);
        }

        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = InternalSignUtils.buildPayload(timestamp, nonce, request.getMethod().name(), path);
        String sign = InternalSignUtils.sign(properties.getSecret(), payload);

        // 先删掉客户端可能伪造的签名头，再写入网关自己的签名
        ServerWebExchange newExchange = exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.remove(InternalSignUtils.HEADER_TIMESTAMP);
                    headers.remove(InternalSignUtils.HEADER_NONCE);
                    headers.remove(InternalSignUtils.HEADER_SIGN);
                    headers.set(InternalSignUtils.HEADER_TIMESTAMP, String.valueOf(timestamp));
                    headers.set(InternalSignUtils.HEADER_NONCE, nonce);
                    headers.set(InternalSignUtils.HEADER_SIGN, sign);
                }))
                .build();
        return chain.filter(newExchange);
    }

    @Override
    public int getOrder() {
        // 在限流(-50) 之后、路由转发(0) 之前，保证签名覆盖真实的请求路径
        return -10;
    }

    @Data
    @ConfigurationProperties(prefix = "yudao.gateway.internal-sign")
    public static class Properties {

        /**
         * 是否开启（需与服务端的 yudao.security.internal-sign.enabled 保持一致）
         */
        private boolean enabled = false;

        /**
         * 共享密钥（生产环境应从配置中心/密钥管理系统注入，不要写死在配置文件）
         */
        private String secret = "yudao-internal-sign-secret-change-me";

        /**
         * 需要签名的路径
         */
        private List<String> paths = new ArrayList<>(List.of("/admin-api/**", "/app-api/**"));

    }

}
