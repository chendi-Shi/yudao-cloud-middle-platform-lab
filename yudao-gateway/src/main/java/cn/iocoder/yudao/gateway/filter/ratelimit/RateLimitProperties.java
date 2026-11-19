package cn.iocoder.yudao.gateway.filter.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关限流配置
 *
 * 设计说明：
 * 1. 采用「令牌桶」而不是「固定窗口」，原因是固定窗口在窗口切换的临界点会有 2 倍突刺，
 *    令牌桶用「桶容量」显式表达可接受的突发量，更贴近真实业务（例如登录接口允许 10 个突发）。
 * 2. 规则按 path 匹配（Ant 风格），未命中则使用 defaultQps / defaultCapacity。
 * 3. 默认关闭，避免影响已有部署；通过 yudao.gateway.rate-limit.enabled=true 开启。
 *
 * @author campus-project
 */
@Data
@ConfigurationProperties(prefix = "yudao.gateway.rate-limit")
public class RateLimitProperties {

    /**
     * 是否开启网关限流
     */
    private boolean enabled = false;

    /**
     * Redis 故障时是否放行（fail-open）
     *
     * true：Redis 不可用时放行，保证可用性，但此时等于没有限流（默认，中台网关可用性优先）
     * false：Redis 不可用时拒绝，保证安全性，但 Redis 抖动会放大为全站故障
     */
    private boolean failOpen = true;

    /**
     * 默认 QPS（每秒补充令牌数）
     */
    private int defaultQps = 100;

    /**
     * 默认桶容量（允许的突发请求数）
     */
    private int defaultCapacity = 200;

    /**
     * Redis key 前缀
     */
    private String keyPrefix = "yudao:gateway:ratelimit:";

    /**
     * 限流维度：TENANT_USER（租户 + 用户，未登录退化为 IP）/ IP
     */
    private String dimension = "TENANT_USER";

    /**
     * 不做限流的路径（Ant 风格），默认放过健康检查
     */
    private List<String> ignorePaths = new ArrayList<>(List.of("/actuator/**"));

    /**
     * 规则列表，按顺序匹配，命中第一条即返回
     */
    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Rule {

        /**
         * Ant 风格路径，例如 /admin-api/system/auth/login
         */
        private String path;

        /**
         * 每秒补充令牌数
         */
        private int qps;

        /**
         * 桶容量
         */
        private int capacity;
    }

}
