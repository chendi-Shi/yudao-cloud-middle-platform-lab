package cn.iocoder.yudao.gateway.filter.grey;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GrayRoutingFilter} 的分流逻辑单测
 *
 * 重点验证三件事：
 * 1. 同一个用户每次都被分到同一个版本（稳定性，灰度最核心的要求）；
 * 2. 大样本下分流比例接近配置值（比例准确性）；
 * 3. 100% 时全量命中，0% 时不命中。
 *
 * @author campus-project
 */
class GrayRoutingFilterTest {

    private GrayRoutingProperties buildProperties(int percent) {
        GrayRoutingProperties properties = new GrayRoutingProperties();
        properties.setEnabled(true);
        GrayRoutingProperties.Rule rule = new GrayRoutingProperties.Rule();
        rule.setVersion("v2");
        rule.setPercent(percent);
        properties.setRules(new ArrayList<>(List.of(rule)));
        return properties;
    }

    @Test
    void chooseVersion_shouldBeStableForSameKey() {
        GrayRoutingFilter filter = new GrayRoutingFilter(buildProperties(20));
        String first = filter.chooseVersion("u:10086");
        for (int i = 0; i < 100; i++) {
            assertEquals(first, filter.chooseVersion("u:10086"), "同一用户的灰度结果必须稳定");
        }
    }

    @Test
    void chooseVersion_shouldMatchConfiguredPercent() {
        int percent = 20;
        GrayRoutingFilter filter = new GrayRoutingFilter(buildProperties(percent));
        int hit = 0;
        int total = 20000;
        for (int i = 0; i < total; i++) {
            if ("v2".equals(filter.chooseVersion("u:" + i))) {
                hit++;
            }
        }
        double actual = hit * 100.0 / total;
        // 2 万样本下允许 ±2 个百分点的统计误差
        assertTrue(Math.abs(actual - percent) <= 2,
                "期望约 " + percent + "%，实际 " + actual + "%");
    }

    @Test
    void chooseVersion_shouldHitAllWhenPercentIs100() {
        GrayRoutingFilter filter = new GrayRoutingFilter(buildProperties(100));
        for (int i = 0; i < 1000; i++) {
            assertEquals("v2", filter.chooseVersion("u:" + i));
        }
    }

    @Test
    void chooseVersion_shouldKeepMainVersionWhenPercentIsZero() {
        GrayRoutingFilter filter = new GrayRoutingFilter(buildProperties(0));
        for (int i = 0; i < 1000; i++) {
            assertNull(filter.chooseVersion("u:" + i));
        }
    }

}
