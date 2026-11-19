package cn.iocoder.yudao.gateway.filter.security;

import cn.iocoder.yudao.framework.common.util.sign.InternalSignUtils;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关侧内部签名的集成测试
 *
 * 与 {@code InternalSignUtilsTest}（只测算法）互补：这里验证过滤器「什么时候签、签哪些头、会不会被伪造头覆盖」。
 *
 * @author campus-project
 */
class InternalSignFilterTest {

    private static final String SECRET = "integration-test-secret";

    private InternalSignFilter buildFilter() {
        InternalSignFilter.Properties properties = new InternalSignFilter.Properties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        return new InternalSignFilter(properties);
    }

    private ServerWebExchange run(InternalSignFilter filter, MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertNotNull(captured.get(), "过滤器必须继续调用 chain");
        return captured.get();
    }

    @Test
    void shouldSignAdminApiRequest() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/admin-api/system/auth/login").build());

        ServerWebExchange routed = run(buildFilter(), exchange);

        String timestamp = routed.getRequest().getHeaders().getFirst(InternalSignUtils.HEADER_TIMESTAMP);
        String nonce = routed.getRequest().getHeaders().getFirst(InternalSignUtils.HEADER_NONCE);
        String sign = routed.getRequest().getHeaders().getFirst(InternalSignUtils.HEADER_SIGN);
        assertNotNull(timestamp);
        assertNotNull(nonce);
        assertNotNull(sign);

        String expected = InternalSignUtils.sign(SECRET,
                InternalSignUtils.buildPayload(Long.parseLong(timestamp), nonce, "POST", "/admin-api/system/auth/login"));
        assertEquals(expected, sign, "签名必须能被服务端用同一套规则复算出来");
    }

    @Test
    void shouldOverwriteForgedHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin-api/system/user/page")
                        .header(InternalSignUtils.HEADER_SIGN, "forged-sign")
                        .header(InternalSignUtils.HEADER_NONCE, "forged-nonce")
                        .build());

        ServerWebExchange routed = run(buildFilter(), exchange);

        assertEquals(1, routed.getRequest().getHeaders().get(InternalSignUtils.HEADER_SIGN).size(),
                "客户端伪造的签名头必须被移除，只保留网关生成的");
        assertNotEquals("forged-sign", routed.getRequest().getHeaders().getFirst(InternalSignUtils.HEADER_SIGN));
        assertNotEquals("forged-nonce", routed.getRequest().getHeaders().getFirst(InternalSignUtils.HEADER_NONCE));
    }

    @Test
    void shouldNotSignRpcApi() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/rpc-api/system/oauth2-token/check").build());

        ServerWebExchange routed = run(buildFilter(), exchange);

        // 服务间 Feign 调用不经过网关，签了也没人校验，反而会污染请求头
        assertNull(routed.getRequest().getHeaders().getFirst(InternalSignUtils.HEADER_SIGN));
    }

    @Test
    void shouldNotSignWhenDisabled() {
        InternalSignFilter.Properties properties = new InternalSignFilter.Properties();
        properties.setEnabled(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin-api/system/user/page").build());

        ServerWebExchange routed = run(new InternalSignFilter(properties), exchange);

        assertNull(routed.getRequest().getHeaders().getFirst(InternalSignUtils.HEADER_SIGN));
    }

}
