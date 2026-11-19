package cn.iocoder.yudao.gateway.filter.grey;

import cn.iocoder.yudao.gateway.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.gateway.util.WebFrameworkUtils;
import com.google.common.hash.Hashing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 灰度分流过滤器：把「按比例分流」的决策结果写入 header[version]，
 * 后续由 {@link GrayLoadBalancer} 依据 metadata[version] 选择实例。
 *
 * 为什么用「一致性哈希取模」而不是随机数？
 * 随机数会导致同一个用户每次刷新页面在 v1/v2 之间来回跳，体验割裂、问题也难复现；
 * 哈希保证「同一用户稳定落在同一版本」，这在大厂灰度里是硬性要求。
 *
 * 为什么 key 用 userId 而不是 IP？
 * 移动端用户 IP 会随基站切换，同一用户可能被分到两个版本；userId 更稳定。
 * 未登录场景没有 userId，才退化为 IP。
 *
 * @author campus-project
 */
@Component
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties(GrayRoutingProperties.class)
public class GrayRoutingFilter implements GlobalFilter, Ordered {

    /**
     * 响应头：本次请求被分流到的灰度版本，便于排查与观测
     */
    public static final String GRAY_VERSION_RESPONSE_HEADER = "X-Gray-Version";

    private final GrayRoutingProperties properties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled() || properties.getRules().isEmpty()) {
            return chain.filter(exchange);
        }
        // 显式指定了版本（例如测试同学带 header 调试），尊重显式值，不做分流
        if (exchange.getRequest().getHeaders().containsKey(properties.getHeaderName())) {
            return chain.filter(exchange);
        }
        String version = chooseVersion(resolveKey(exchange));
        if (version == null) {
            return chain.filter(exchange);
        }
        log.info("[filter][uri({}) 灰度分流到版本({})]", exchange.getRequest().getURI(), version);
        // 回写响应头，便于排查「这个请求到底走了哪个版本」，也方便压测/灰度观察
        exchange.getResponse().getHeaders().set(GRAY_VERSION_RESPONSE_HEADER, version);
        ServerWebExchange newExchange = exchange.mutate()
                .request(builder -> builder.header(properties.getHeaderName(), version))
                .build();
        return chain.filter(newExchange);
    }

    /**
     * 计算分流 key：优先用户，退化为 IP
     */
    private String resolveKey(ServerWebExchange exchange) {
        Long userId = SecurityFrameworkUtils.getLoginUserId(exchange);
        if (userId != null) {
            return "u:" + userId;
        }
        return "ip:" + WebFrameworkUtils.getClientIP(exchange);
    }

    /**
     * 一致性哈希取模，落进 [0, 100) 的桶，再按规则区间选择版本
     *
     * @return 目标版本；返回 null 表示保持主版本
     */
    String chooseVersion(String key) {
        int bucket = Math.floorMod(Hashing.murmur3_32_fixed()
                .hashString(key, StandardCharsets.UTF_8).asInt(), 100);
        int cursor = 0;
        for (GrayRoutingProperties.Rule rule : properties.getRules()) {
            cursor += rule.getPercent();
            if (bucket < cursor) {
                return rule.getVersion();
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        // 必须在 TokenAuthenticationFilter(-100) 之后（要拿 userId），在路由转发(0) 之前
        return -20;
    }

}
