package cn.ontorepo.operation.domain;

import cn.ontorepo.common.domain.*;

import java.util.*;

/** 同一冻结策略交集同时驱动契约资源声明和执行器上限，避免预览与运行漂移。 */
public record FrozenPolicy(int maxRows, int timeoutMs, List<Object> scopes) {
    @SuppressWarnings("unchecked")
    public static FrozenPolicy of(Map<String, Object> p, Map<String, Object> op) {
        int max = ((Number) op.get("maxRows")).intValue(),
                timeout = ((Number) op.get("timeoutMs")).intValue();
        List<Object> scopes = new ArrayList<>();
        for (var strategy : (List<Map<String, Object>>) p.getOrDefault("strategies", List.of())) {
            List<?> bound = (List<?>) strategy.getOrDefault("boundOps", List.of());
            if (!Input.sameUuid(strategy.get("id"), op.get("strategyRef"))
                    && bound.stream().noneMatch(ref -> Input.sameUuid(ref, op.get("id")))) continue;
            Map<String, Object> policy = (Map<String, Object>) strategy.get("policy");
            max =
                    Math.min(
                            max,
                            Math.min(
                                    ((Number) policy.get("maxRows")).intValue(),
                                    ((Number) policy.get("resultLimit")).intValue()));
            timeout = Math.min(timeout, ((Number) policy.get("timeoutMs")).intValue());
            if (policy.get("readScope") instanceof Map<?, ?> scope && !scope.isEmpty())
                scopes.add(scope);
        }
        return new FrozenPolicy(max, timeout, List.copyOf(scopes));
    }
}
