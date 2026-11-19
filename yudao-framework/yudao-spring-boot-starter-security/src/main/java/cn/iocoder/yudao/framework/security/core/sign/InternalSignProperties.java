package cn.iocoder.yudao.framework.security.core.sign;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 内部调用签名校验配置（服务端）
 *
 * 必须与网关侧 yudao.gateway.internal-sign 的 secret 保持一致。
 *
 * @author campus-project
 */
@Data
@ConfigurationProperties(prefix = "yudao.security.internal-sign")
public class InternalSignProperties {

    /**
     * 是否开启校验（默认关闭，避免影响已有部署）
     */
    private boolean enabled = false;

    /**
     * 与网关共享的密钥
     */
    private String secret = "yudao-internal-sign-secret-change-me";

    /**
     * 允许的时钟偏移（秒），超过这个窗口的请求直接拒绝
     */
    private long toleranceSeconds = 60;

    /**
     * 需要校验签名的路径（Ant 风格）
     */
    private List<String> paths = new ArrayList<>(List.of("/admin-api/**", "/app-api/**"));

}
