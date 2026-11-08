package interview.guide.common.ai;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 用户级隔离的有界 TTL 缓存。
 *
 * <p>用于存放「每个用户各自持有的模型客户端」这类不允许跨用户共享的实例。</p>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li><b>并发</b>：{@link ConcurrentHashMap#computeIfAbsent} 保证同一 Key 只有一个线程执行加载，
 *       不同 Key 之间不互相阻塞；加载抛异常时不会写入缓存。</li>
 *   <li><b>容量</b>：超过上限后按写入顺序淘汰最旧的约 10%，避免用户增长导致内存无界增长。</li>
 *   <li><b>过期</b>：每个条目带 TTL，读取时惰性剔除；淘汰时同时清掉已过期条目。</li>
 *   <li><b>失效</b>：支持按 Key 前缀批量剔除（用户改 Key / 删 Key 时调用）。</li>
 * </ul>
 *
 * <p>Key 由调用方保证不含任何凭证明文。</p>
 */
@Slf4j
public final class UserScopedCache<V> {

    private final String name;
    private final int maxEntries;
    private final long ttlNanos;
    private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public UserScopedCache(String name, int maxEntries, Duration ttl) {
        this.name = name;
        this.maxEntries = Math.max(8, maxEntries);
        this.ttlNanos = Math.max(Duration.ofSeconds(1).toNanos(), ttl.toNanos());
    }

    public V get(String key, Function<String, V> loader) {
        Entry<V> existing = entries.get(key);
        if (existing != null && !existing.expired()) {
            return existing.value();
        }
        if (existing != null) {
            entries.remove(key, existing);
        }
        Entry<V> entry = entries.computeIfAbsent(key, k -> new Entry<>(loader.apply(k), sequence.incrementAndGet()));
        trimIfOversized();
        return entry.value();
    }

    public void invalidate(Predicate<String> keyMatcher) {
        List<String> stale = entries.keySet().stream().filter(keyMatcher).toList();
        stale.forEach(entries::remove);
        if (!stale.isEmpty()) {
            log.info("[{}] 已失效 {} 个用户级缓存条目", name, stale.size());
        }
    }

    public void clear() {
        int size = entries.size();
        entries.clear();
        if (size > 0) {
            log.info("[{}] 已清空全部缓存条目: {}", name, size);
        }
    }

    public int size() {
        return entries.size();
    }

    public boolean containsKey(String key) {
        Entry<V> entry = entries.get(key);
        return entry != null && !entry.expired();
    }

    /** 只读 Key 视图，用于诊断与安全测试（缓存 Key 不允许包含凭证明文）。 */
    public java.util.Set<String> keys() {
        return java.util.Set.copyOf(entries.keySet());
    }

    private void trimIfOversized() {
        if (entries.size() <= maxEntries) {
            return;
        }
        // 先清过期条目，仍超限再按写入顺序淘汰约 10%
        entries.entrySet().removeIf(e -> e.getValue().expired());
        int overflow = entries.size() - maxEntries;
        if (overflow <= 0) {
            return;
        }
        int evictCount = Math.max(overflow, maxEntries / 10);
        Map<String, Entry<V>> snapshot = Map.copyOf(entries);
        List<String> victims = snapshot.entrySet().stream()
            .sorted(Comparator.comparingLong(e -> e.getValue().sequence()))
            .limit(evictCount)
            .map(Map.Entry::getKey)
            .toList();
        victims.forEach(entries::remove);
        log.debug("[{}] 缓存容量达到上限，已淘汰 {} 个条目", name, victims.size());
    }

    private final class Entry<T> {

        private final T value;
        private final long bornNanos;
        private final long order;

        private Entry(T value, long order) {
            this.value = value;
            this.bornNanos = System.nanoTime();
            this.order = order;
        }

        T value() {
            return value;
        }

        long sequence() {
            return order;
        }

        boolean expired() {
            return System.nanoTime() - bornNanos > ttlNanos;
        }
    }
}
