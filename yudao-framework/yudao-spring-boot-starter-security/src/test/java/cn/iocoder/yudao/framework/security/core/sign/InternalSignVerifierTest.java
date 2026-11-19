package cn.iocoder.yudao.framework.security.core.sign;

import cn.iocoder.yudao.framework.common.util.sign.InternalSignUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务端内部签名校验的集成测试
 *
 * 用 MockHttpServletRequest 模拟「绕过网关直连服务端口」的各种攻击，验证都会被 401 挡住。
 *
 * @author campus-project
 */
class InternalSignVerifierTest {

    private static final String SECRET = "verifier-test-secret";

    private static final String PATH = "/admin-api/system/auth/login";

    private InternalSignProperties properties;

    private InternalSignVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new InternalSignProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        properties.setToleranceSeconds(60);
        verifier = new InternalSignVerifier(properties, new InMemoryNonceStore());
    }

    /**
     * 极简的内存 nonce 存储，行为与 Redis SETNX 一致（用于测试，生产用非 Redis 实现见 NonceStore）
     */
    private static class InMemoryNonceStore implements NonceStore {

        private final Set<String> used = ConcurrentHashMap.newKeySet();

        @Override
        public boolean firstUse(String nonce) {
            return used.add(nonce);
        }
    }

    private MockHttpServletRequest buildRequest(long timestamp, String nonce, String sign) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType("application/json");
        if (timestamp > 0) {
            request.addHeader(InternalSignUtils.HEADER_TIMESTAMP, String.valueOf(timestamp));
        }
        if (nonce != null) {
            request.addHeader(InternalSignUtils.HEADER_NONCE, nonce);
        }
        if (sign != null) {
            request.addHeader(InternalSignUtils.HEADER_SIGN, sign);
        }
        return request;
    }

    private boolean invoke(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> invoked.set(true);
        verifier.doFilter(request, response, chain);
        return invoked.get();
    }

    @Test
    void shouldRejectWhenHeadersMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(invoke(buildRequest(0, null, null), response));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("缺少内部调用签名头"));
    }

    @Test
    void shouldRejectExpiredTimestamp() throws Exception {
        long expired = System.currentTimeMillis() - 5 * 60 * 1000L;
        String nonce = "expired-nonce";
        String sign = InternalSignUtils.sign(SECRET,
                InternalSignUtils.buildPayload(expired, nonce, "POST", PATH));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(invoke(buildRequest(expired, nonce, sign), response));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("签名已过期"));
    }

    @Test
    void shouldRejectTamperedPath() throws Exception {
        long now = System.currentTimeMillis();
        String nonce = "tampered-nonce";
        // 用「登录」路径生成的合法签名，拿去调另一个接口
        String signForLogin = InternalSignUtils.sign(SECRET,
                InternalSignUtils.buildPayload(now, nonce, "POST", PATH));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/system/user/delete");
        request.addHeader(InternalSignUtils.HEADER_TIMESTAMP, String.valueOf(now));
        request.addHeader(InternalSignUtils.HEADER_NONCE, nonce);
        request.addHeader(InternalSignUtils.HEADER_SIGN, signForLogin);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(invoke(request, response), "签名与路径绑定，换路径必须失效");
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldRejectReplay() throws Exception {
        long now = System.currentTimeMillis();
        String nonce = "replay-nonce";
        String sign = InternalSignUtils.sign(SECRET,
                InternalSignUtils.buildPayload(now, nonce, "POST", PATH));

        MockHttpServletResponse first = new MockHttpServletResponse();
        assertTrue(invoke(buildRequest(now, nonce, sign), first), "第一次合法请求应放行");

        MockHttpServletResponse second = new MockHttpServletResponse();
        assertFalse(invoke(buildRequest(now, nonce, sign), second), "同一 nonce 重放必须被拒绝");
        assertEquals(401, second.getStatus());
        assertTrue(second.getContentAsString().contains("nonce 已被使用"));
    }

    @Test
    void shouldPassWithValidSignature() throws Exception {
        long now = System.currentTimeMillis();
        String nonce = "valid-nonce";
        String sign = InternalSignUtils.sign(SECRET,
                InternalSignUtils.buildPayload(now, nonce, "POST", PATH));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(invoke(buildRequest(now, nonce, sign), response));
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldIgnoreRpcApiPath() throws Exception {
        // /rpc-api 是服务间调用，不经过网关，不应被签名校验拦截
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rpc-api/system/oauth2-token/check");

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(invoke(request, response));
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldSkipWhenDisabled() throws Exception {
        properties.setEnabled(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(invoke(buildRequest(0, null, null), response));
    }

}
