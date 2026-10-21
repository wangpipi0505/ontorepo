package cn.ontorepo.operation.domain;

import cn.ontorepo.common.domain.Problem;

import java.math.BigDecimal;
import java.util.*;

/** 评分权重与闭区间分段校验；缺值策略与冻结策略保持一致。 */
public final class ScoringPolicy {
    private ScoringPolicy() {}

    private static Problem invalid(String message) {
        return new Problem(400, "INVALID_SCORING", message);
    }

    private record Band(BigDecimal min, BigDecimal max, BigDecimal score) {}

    private record Metric(String propertyId, BigDecimal weight, List<Band> bands) {}

    private record Policy(List<Metric> metrics, boolean zero, String tieBreak) {}

    /** 指标必须引用适用对象的数值属性，禁算字段不得参与评分。 */
    public static void validate(Map<String, Object> policy, Map<String, String> propertyTypes) {
        Policy parsed = parse(policy);
        for (Metric metric : parsed.metrics)
            if (propertyTypes == null
                    || !RuleExpression.numericType(propertyTypes.get(metric.propertyId)))
                throw invalid("评分指标必须引用适用对象的数值属性");
        for (Object forbidden : list(policy.get("forbiddenProperties")))
            if (!propertyTypes.containsKey(id(forbidden))) throw invalid("禁算属性不存在或不属于适用对象");
    }

    /** 每个指标命中唯一闭区间后累加加权分；未覆盖值按缺值策略处理。 */
    public static BigDecimal score(Map<String, Object> policy, Map<String, Object> input) {
        Policy parsed = parse(policy);
        BigDecimal score = BigDecimal.ZERO;
        for (Metric metric : parsed.metrics) {
            Object raw = input == null ? null : input.get(metric.propertyId);
            if (raw == null) {
                if (parsed.zero) continue;
                throw invalid("评分指标缺失");
            }
            BigDecimal value = numeric(raw), contribution = null;
            for (Band band : metric.bands)
                if (value.compareTo(band.min) >= 0 && value.compareTo(band.max) <= 0) {
                    contribution = band.score.multiply(metric.weight);
                    break;
                }
            if (contribution == null) {
                if (parsed.zero) continue;
                throw invalid("评分指标值未命中任何分段");
            }
            score = score.add(contribution);
        }
        return score;
    }

    /** 保存规范引用；运行旧策略仍由parse规范化，不修改原策略或候选。 */
    public static Map<String, Object> normalized(Map<String, Object> raw) {
        parse(raw);
        Map<String, Object> out = new LinkedHashMap<>(raw);
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (Object item : list(raw.get("metrics"))) {
            Map<String, Object> metric = new LinkedHashMap<>();
            map(item).forEach((k, v) -> metric.put(k.toString(), v));
            metric.put("propertyId", id(metric.get("propertyId")));
            metrics.add(metric);
        }
        out.put("metrics", metrics);
        out.put(
                "forbiddenProperties",
                list(raw.get("forbiddenProperties")).stream().map(ScoringPolicy::id).toList());
        return out;
    }

    private static Policy parse(Map<String, Object> policy) {
        if (policy == null) throw invalid("评分策略不能为空");
        String tieBreak = Objects.toString(policy.get("tieBreak"), "STABLE_ID");
        if (!("REJECT".equals(policy.get("missingValue"))
                        || "ZERO".equals(policy.get("missingValue")))
                || !Set.of("STABLE_ID", "INPUT_ORDER").contains(tieBreak))
            throw invalid("评分缺值与同分策略无效");
        if (!(policy.get("explain") instanceof Boolean)) throw invalid("评分解释标志必须为布尔值");
        List<?> forbiddenRaw = list(policy.get("forbiddenProperties"));
        Set<String> forbidden = new HashSet<>();
        for (Object id : forbiddenRaw) forbidden.add(id(id));
        List<?> metricsRaw = list(policy.get("metrics"));
        if (metricsRaw.isEmpty() || metricsRaw.size() > 100) throw invalid("评分指标数量须在1至100之间");
        Set<String> ids = new HashSet<>();
        List<Metric> metrics = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Object raw : metricsRaw) {
            Map<?, ?> m = map(raw);
            String propertyId = id(m.get("propertyId"));
            if (!ids.add(propertyId) || forbidden.contains(propertyId))
                throw invalid("评分指标重复或引用了禁算属性");
            BigDecimal weight = numeric(m.get("weight"));
            if (weight.signum() < 0) throw invalid("评分权重不得为负");
            total = total.add(weight);
            List<?> bandsRaw = list(m.get("bands"));
            if (bandsRaw.isEmpty() || bandsRaw.size() > 1000) throw invalid("评分分段数量须在1至1000之间");
            List<Band> bands = new ArrayList<>();
            for (Object bandRaw : bandsRaw) {
                Map<?, ?> b = map(bandRaw);
                Band band =
                        new Band(
                                numeric(b.get("min")),
                                numeric(b.get("max")),
                                numeric(b.get("score")));
                if (band.min.compareTo(band.max) > 0) throw invalid("评分分段上下界颠倒");
                bands.add(band);
            }
            bands.sort(Comparator.comparing(Band::min));
            for (int i = 1; i < bands.size(); i++)
                if (bands.get(i - 1).max.compareTo(bands.get(i).min) >= 0)
                    throw invalid("评分闭区间分段不得重叠");
            metrics.add(new Metric(propertyId, weight, List.copyOf(bands)));
        }
        if (total.compareTo(BigDecimal.ONE) != 0) throw invalid("评分权重总和必须为1");
        return new Policy(List.copyOf(metrics), "ZERO".equals(policy.get("missingValue")), tieBreak);
    }

    private static String id(Object raw) {
        if (!(raw instanceof String value)) throw invalid("评分属性引用必须为UUID");
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value))
                throw invalid("评分属性引用必须为UUID");
        } catch (IllegalArgumentException e) {
            throw invalid("评分属性引用必须为UUID");
        }
        return UUID.fromString(value).toString();
    }

    private static List<?> list(Object raw) {
        if (!(raw instanceof List<?> value)) throw invalid("评分集合字段格式错误");
        return value;
    }

    private static Map<?, ?> map(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) throw invalid("评分条目格式错误");
        return value;
    }

    private static BigDecimal numeric(Object raw) {
        if (!(raw instanceof Number)) throw invalid("评分数值字段必须为真实数值");
        try {
            BigDecimal value = new BigDecimal(raw.toString());
            if (value.precision() > 1000 || Math.abs((long) value.scale()) > 1000)
                throw invalid("评分数值超出限制");
            return value;
        } catch (NumberFormatException e) {
            throw invalid("评分数值必须为有限数");
        }
    }
}
