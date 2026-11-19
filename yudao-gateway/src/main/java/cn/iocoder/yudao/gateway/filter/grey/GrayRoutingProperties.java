package cn.iocoder.yudao.gateway.filter.grey;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 灰度分流配置
 *
 * 与上游 {@link GrayLoadBalancer} 的关系：
 * - 上游 LB 只会「按 header[version] 匹配 metadata[version] 选实例」，但不会替你做「谁该走新版本」的决策；
 * - 本配置负责决策：按用户/租户哈希做百分比分流，然后把结果写进 header[version]，交给上游 LB 选实例。
 * 这样职责分离：分流比例可随时在配置中心调整，不用改负载均衡器。
 *
 * @author campus-project
 */
@Data
@ConfigurationProperties(prefix = "yudao.gateway.gray")
public class GrayRoutingProperties {

    /**
     * 是否开启按比例分流
     */
    private boolean enabled = false;

    /**
     * 承载灰度版本号的 header 名，需与 GrayLoadBalancer 读取的 header 一致
     */
    private String headerName = "version";

    /**
     * 分流规则，按顺序累加 percent，形成 [0, 100) 的区间
     * 例如：v2 占 20%，则 bucket < 20 的流量走 v2，其余保持主版本
     */
    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Rule {

        /**
         * 目标版本，需要与 Nacos 实例 metadata.version 对应
         */
        private String version;

        /**
         * 百分比（0-100）
         */
        private int percent;

    }

}
