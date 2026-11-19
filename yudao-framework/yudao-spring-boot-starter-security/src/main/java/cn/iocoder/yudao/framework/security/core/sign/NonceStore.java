package cn.iocoder.yudao.framework.security.core.sign;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * nonce 存储：用于防重放
 *
 * 两种实现：
 * 1. {@link RedisNonceStore}：多实例部署时唯一正确的选择，SETNX + TTL 天然去重；
 * 2. {@link LocalNonceStore}：没有 Redis 时的单机兜底（例如本地起单个服务调试），
 *    多实例下会失效，所以只在拿不到 Redis 时启用，并打 WARN 日志。
 *
 * @author campus-project
 */
public interface NonceStore {

    /**
     * 记录 nonce，返回是否是首次使用
     *
     * @param nonce 随机数
     * @return true 表示首次使用（允许通过），false 表示重放（拒绝）
     */
    boolean firstUse(String nonce);

    /**
     * 基于 Redis SETNX 的实现
     */
    @Slf4j
    class RedisNonceStore implements NonceStore {

        private final StringRedisTemplate redisTemplate;

        private final Duration ttl;

        public RedisNonceStore(StringRedisTemplate redisTemplate, Duration ttl) {
            this.redisTemplate = redisTemplate;
            this.ttl = ttl;
        }

        @Override
        public boolean firstUse(String nonce) {
            Boolean result = redisTemplate.opsForValue()
                    .setIfAbsent("yudao:internal-sign:nonce:" + nonce, "1", ttl);
            return Boolean.TRUE.equals(result);
        }

    }

    /**
     * 基于 Guava Cache 的单机实现
     */
    @Slf4j
    class LocalNonceStore implements NonceStore {

        private final Cache<String, Boolean> cache;

        public LocalNonceStore(Duration ttl) {
            this.cache = CacheBuilder.newBuilder()
                    .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
                    .maximumSize(100_000)
                    .build();
            log.warn("[LocalNonceStore][未检测到 Redis，降级为单机 nonce 存储，多实例部署下防重放会失效]");
        }

        @Override
        public boolean firstUse(String nonce) {
            if (cache.getIfPresent(nonce) != null) {
                return false;
            }
            cache.put(nonce, Boolean.TRUE);
            return true;
        }

    }

}
