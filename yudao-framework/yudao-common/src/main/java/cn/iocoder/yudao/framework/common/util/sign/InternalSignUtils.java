package cn.iocoder.yudao.framework.common.util.sign;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 内部调用签名工具
 *
 * 背景：网关做统一鉴权后，仍然存在「绕过网关直连服务端口」的风险（服务端口在内网可达时尤其明显）。
 * 这里用一种轻量的 HMAC 签名解决：网关在转发前对「时间戳 + 随机数 + 方法 + 路径」签名，
 * 服务端用同一个密钥验签，验不过直接 401。
 *
 * 为什么不用 JWT？
 * 1. JWT 体积大（几百字节），每个内部请求都带一遍，纯属浪费；
 * 2. 这里只需要「证明请求来自网关」，不需要承载用户身份（用户身份已经由 login-user header 承载）；
 * 3. HMAC 是 O(1) 计算，不需要解析/校验签名结构，网关这个性能敏感位置更合适。
 *
 * 为什么签名里要带 path 和 method？
 * 防止签名被「重放到另一个接口」：只签时间戳的话，攻击者可以拿一个订单查询的签名去打删除接口。
 *
 * 为什么还要 nonce？
 * 防止同一个签名在时间窗口内（默认 60 秒）被重复使用。服务端用 Redis SETNX 记录 nonce。
 *
 * @author campus-project
 */
public class InternalSignUtils {

    /**
     * 时间戳请求头，单位毫秒
     */
    public static final String HEADER_TIMESTAMP = "X-Yudao-Ts";

    /**
     * 随机数请求头，防重放
     */
    public static final String HEADER_NONCE = "X-Yudao-Nonce";

    /**
     * 签名请求头，HmacSHA256 的十六进制小写
     */
    public static final String HEADER_SIGN = "X-Yudao-Sign";

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * 构造待签名的规范字符串
     *
     * 用 \n 分隔而不是拼接，是为了避免「歧义拼接」攻击：
     * 例如 ts=1, nonce=23 与 ts=12, nonce=3 直接拼接都是 "123"，加分隔符后不会冲突。
     */
    public static String buildPayload(long timestamp, String nonce, String method, String path) {
        return timestamp + "\n" + nonce + "\n" + method.toUpperCase() + "\n" + path;
    }

    /**
     * 计算 HmacSHA256 签名，返回十六进制小写字符串
     */
    public static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("[sign][HmacSHA256 签名失败]", ex);
        }
    }

    /**
     * 校验签名
     *
     * 使用 {@link MessageDigest#isEqual} 做常量时间比较，避免时序攻击（逐字节比较会泄漏「已匹配多少位」）。
     */
    public static boolean verify(String secret, String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

}
