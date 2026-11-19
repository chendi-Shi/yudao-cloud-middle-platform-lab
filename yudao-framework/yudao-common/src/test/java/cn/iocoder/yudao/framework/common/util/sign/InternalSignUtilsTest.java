package cn.iocoder.yudao.framework.common.util.sign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InternalSignUtils} 单测
 *
 * 覆盖：签名可复现、验签正确、路径/方法被篡改时验签失败、空签名失败
 *
 * @author campus-project
 */
class InternalSignUtilsTest {

    private static final String SECRET = "unit-test-secret";

    @Test
    void sign_shouldBeDeterministic() {
        String payload = InternalSignUtils.buildPayload(1700000000000L, "abc", "POST", "/admin-api/system/auth/login");
        assertEquals(InternalSignUtils.sign(SECRET, payload), InternalSignUtils.sign(SECRET, payload));
    }

    @Test
    void sign_shouldChangeWhenPathChanged() {
        long ts = 1700000000000L;
        String loginPayload = InternalSignUtils.buildPayload(ts, "abc", "POST", "/admin-api/system/auth/login");
        String deletePayload = InternalSignUtils.buildPayload(ts, "abc", "DELETE", "/admin-api/system/user/delete");
        // 同一个时间戳和 nonce，路径不同 → 签名必须不同，否则签名可被「挪用到其它接口」
        assertNotEquals(InternalSignUtils.sign(SECRET, loginPayload), InternalSignUtils.sign(SECRET, deletePayload));
    }

    @Test
    void sign_shouldChangeWhenSecretChanged() {
        String payload = InternalSignUtils.buildPayload(1700000000000L, "abc", "GET", "/admin-api/system/user/page");
        assertNotEquals(InternalSignUtils.sign("secret-a", payload), InternalSignUtils.sign("secret-b", payload));
    }

    @Test
    void buildPayload_shouldNotBeAmbiguous() {
        // ts=1,nonce=23 与 ts=12,nonce=3 直接字符串拼接会得到相同结果，用 \n 分隔后必须不同
        String first = InternalSignUtils.buildPayload(1L, "23", "GET", "/a");
        String second = InternalSignUtils.buildPayload(12L, "3", "GET", "/a");
        assertNotEquals(first, second);
    }

    @Test
    void verify_shouldPassOnlyWithExactSign() {
        String payload = InternalSignUtils.buildPayload(1700000000000L, "abc", "GET", "/admin-api/system/user/page");
        String expected = InternalSignUtils.sign(SECRET, payload);
        assertTrue(InternalSignUtils.verify(SECRET, expected, expected));
        assertFalse(InternalSignUtils.verify(SECRET, expected, expected.toUpperCase()));
        assertFalse(InternalSignUtils.verify(SECRET, expected, null));
        assertFalse(InternalSignUtils.verify(SECRET, expected, expected.substring(0, expected.length() - 1)));
    }

}
