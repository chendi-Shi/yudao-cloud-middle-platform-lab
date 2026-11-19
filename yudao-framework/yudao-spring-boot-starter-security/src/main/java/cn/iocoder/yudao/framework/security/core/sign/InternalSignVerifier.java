package cn.iocoder.yudao.framework.security.core.sign;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.sign.InternalSignUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;

/**
 * 内部调用签名校验过滤器（服务端）
 *
 * 校验顺序：签名存在性 → 时间窗口 → HMAC 签名 → nonce 防重放。
 * 顺序不能反：先验签名再查 nonce，否则攻击者可以用随机 nonce 打爆 Redis。
 *
 * 只对「外部入口」（/admin-api/**、/app-api/**）校验：
 * 服务间 Feign 调用走 /rpc-api/**，不经过网关，因此不在校验范围内。
 *
 * @author campus-project
 */
@RequiredArgsConstructor
@Slf4j
public class InternalSignVerifier extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final InternalSignProperties properties;

    private final NonceStore nonceStore;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || !needVerify(request)) {
            chain.doFilter(request, response);
            return;
        }

        // 1. 取出签名头
        String timestampHeader = request.getHeader(InternalSignUtils.HEADER_TIMESTAMP);
        String nonce = request.getHeader(InternalSignUtils.HEADER_NONCE);
        String sign = request.getHeader(InternalSignUtils.HEADER_SIGN);
        if (StrUtil.isBlank(timestampHeader) || StrUtil.isBlank(nonce) || StrUtil.isBlank(sign)) {
            reject(request, response, "缺少内部调用签名头，疑似绕过网关直接访问服务");
            return;
        }

        // 2. 校验时间窗口，防止历史签名被长期重放
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException ex) {
            reject(request, response, "签名时间戳格式非法");
            return;
        }
        long offset = Math.abs(System.currentTimeMillis() - timestamp);
        if (offset > properties.getToleranceSeconds() * 1000L) {
            reject(request, response, "签名已过期，时钟偏移 " + offset + "ms");
            return;
        }

        // 3. 校验 HMAC 签名（覆盖 method + path，防止签名被挪用到其它接口）
        String path = resolvePath(request);
        String payload = InternalSignUtils.buildPayload(timestamp, nonce, request.getMethod(), path);
        String expected = InternalSignUtils.sign(properties.getSecret(), payload);
        if (!InternalSignUtils.verify(properties.getSecret(), expected, sign)) {
            reject(request, response, "签名不正确");
            return;
        }

        // 4. 校验 nonce，防止同一签名在时间窗口内被重复使用
        if (!nonceStore.firstUse(nonce)) {
            reject(request, response, "nonce 已被使用，疑似重放攻击");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean needVerify(HttpServletRequest request) {
        String path = resolvePath(request);
        return properties.getPaths().stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    /**
     * 去掉 context-path，保证与网关侧的 path 一致（网关看到的是 /admin-api/xxx）
     */
    private String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotEmpty(contextPath) && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        log.warn("[reject][uri({}/{}) 内部调用签名校验失败：{}，来源 IP({})]",
                request.getMethod(), request.getRequestURI(), reason, request.getRemoteAddr());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        ServletUtils.writeJSON(response, CommonResult.error(UNAUTHORIZED.getCode(), "内部调用签名校验失败：" + reason));
    }

}
