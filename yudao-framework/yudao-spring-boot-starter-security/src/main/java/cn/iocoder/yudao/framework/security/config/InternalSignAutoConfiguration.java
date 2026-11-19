package cn.iocoder.yudao.framework.security.config;

import cn.iocoder.yudao.framework.security.core.sign.InternalSignProperties;
import cn.iocoder.yudao.framework.security.core.sign.InternalSignVerifier;
import cn.iocoder.yudao.framework.security.core.sign.NonceStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 内部调用签名校验的自动配置
 *
 * 默认不开启（yudao.security.internal-sign.enabled=false），
 * 开启后会在 Spring Security 过滤器链之前执行，尽早拒绝「绕过网关」的直连请求。
 *
 * @author campus-project
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "yudao.security.internal-sign", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(InternalSignProperties.class)
@Slf4j
public class InternalSignAutoConfiguration {

    @Bean
    public NonceStore internalSignNonceStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                             InternalSignProperties properties) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        // nonce 至少要在时间窗口内存活，否则窗口内仍可重放；这里留 2 倍窗口
        Duration ttl = Duration.ofSeconds(properties.getToleranceSeconds() * 2);
        if (redisTemplate != null) {
            return new NonceStore.RedisNonceStore(redisTemplate, ttl);
        }
        log.warn("[internalSignNonceStore][未找到 StringRedisTemplate，降级为单机 nonce 存储]");
        return new NonceStore.LocalNonceStore(ttl);
    }

    @Bean
    public FilterRegistrationBean<InternalSignVerifier> internalSignVerifierFilter(
            InternalSignProperties properties, NonceStore nonceStore) {
        FilterRegistrationBean<InternalSignVerifier> registration =
                new FilterRegistrationBean<>(new InternalSignVerifier(properties, nonceStore));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setName("internalSignVerifier");
        return registration;
    }

}
