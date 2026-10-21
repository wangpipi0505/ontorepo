package cn.ontorepo.trace.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.model.domain.PropertyFacets;
import cn.ontorepo.model.infra.RdfCodec;
import cn.ontorepo.operation.domain.*;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** 执行仅接收冻结包；策略上限取交集，调用参数不能扩大资源边界。 */
@Component
public class FrozenExecutor {
    private final cn.ontorepo.mapping.application.FrozenDataExecutor data;

    public FrozenExecutor(cn.ontorepo.mapping.application.FrozenDataExecutor data) {
        this.data = data;
    }

    public record Result(
            List<String> columns,
            List<Map<String, Object>> rows,
            List<Map<String, String>> steps,
            List<Map<String, Object>> evidence) {
        public Result(
                List<String> columns,
                List<Map<String, Object>> rows,
                List<Map<String, String>> steps) {
            this(columns, rows, steps, List.of());
        }
    }

    private Map<String, String> step(String k, String d) {
        return Map.of("kind", k, "status", "SUCCEEDED", "detail", d);
    }

    @SuppressWarnings("unchecked")
    public Result execute(Map<String, Object> p, Map<String, Object> op, Object input) {
        return execute(p, op, input, cn.ontorepo.data.domain.LabelScope.unrestricted());
    }

    public Result execute(
            Map<String, Object> p,
            Map<String, Object> op,
            Object input,
            cn.ontorepo.data.domain.LabelScope scope) {
        if (op.get("dataQuery") != null) {
            var result = data.execute(p, op, input, scope);
            return new Result(
                    result.columns(),
                    result.rows(),
                    List.of(step("QUERY_EXEC", "冻结映射参数化查询完成，返回 " + result.rows().size() + " 行")),
                    result.evidence());
        }
        var effective = FrozenPolicy.of(p, op);
        int max = effective.maxRows(), timeout = effective.timeoutMs();
        List<Map<String, String>> steps = new ArrayList<>();
        if (op.get("assistantTool") == null && !effective.scopes().isEmpty())
            throw new Problem(409, "READ_SCOPE_UNSUPPORTED", "当前MODEL_ONLY模型查询不能证明该读取范围");
        String kind = op.getOrDefault("kind", "OBJECT_QUERY").toString();
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long started = System.nanoTime();
        if (kind.equals("OBJECT_QUERY") || kind.equals("RELATION_QUERY")) {
            Query query =
                    QueryPolicy.validate(
                            op.get("queryTemplate").toString(), max, Math.max(100, timeout));
            steps.add(step("VALIDATE_QUERY", "受限SELECT安全校验通过"));
            Model model = RdfCodec.model(p);
            try (QueryExecution qe =
                    QueryExecution.model(model)
                            .query(query)
                            .timeout(timeout, TimeUnit.MILLISECONDS)
                            .build()) {
                qe.getContext().set(ARQ.enablePropertyFunctions, false);
                ResultSet result = qe.execSelect();
                columns.addAll(result.getResultVars());
                while (result.hasNext() && rows.size() < max) {
                    QuerySolution solution = result.next();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String col : columns) {
                        RDFNode value = solution.get(col);
                        row.put(
                                col,
                                value == null
                                        ? null
                                        : value.isLiteral()
                                                ? value.asLiteral().getLexicalForm()
                                                : value.toString());
                    }
                    rows.add(row);
                }
            } finally {
                model.close();
            }
            steps.add(step("MODEL_SELECT", "本地冻结模型查询完成，返回 " + rows.size() + " 行"));
        } else {
            if (!(input instanceof Map<?, ?> attributes))
                throw new Problem(400, "INPUT_ROOT_NOT_APPLICABLE", "规则和评分操作需要属性对象输入");
            Map<String, String> types = new HashMap<>();
            for (var prop : RdfCodec.rows(p, "properties"))
                types.put(prop.get("id").toString(), prop.get("rangeType").toString());
            Map<String, Object> canonicalInput = new LinkedHashMap<>();
            for (var entry : attributes.entrySet()) {
                String key = Input.uuid(entry.getKey()).toString();
                if (canonicalInput.containsKey(key)) throw Problem.bad("输入属性UUID重复");
                canonicalInput.put(key, entry.getValue());
            }
            for (var entry : canonicalInput.entrySet()) {
                if (!types.containsKey(entry.getKey())) throw Problem.bad("输入引用未知属性");
                if (entry.getValue() != null)
                    PropertyFacets.scalar(types.get(entry.getKey()), entry.getValue());
            }
            columns.addAll(
                    kind.equals("SCORING")
                            ? List.of("ruleId", "score", "explanation")
                            : List.of("ruleId", "passed", "message"));
            List<?> ids = (List<?>) op.getOrDefault("ruleIds", List.of());
            for (Object id : ids) {
                var rule =
                        RdfCodec.rows(p, "rules").stream()
                                .filter(r -> Input.sameUuid(r.get("id"), id))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new Problem(
                                                        409,
                                                        "FROZEN_REFERENCE_INVALID",
                                                        "冻结操作规则引用缺失"));
                if (RuleExpression.evaluate(rule.get("condition").toString(), canonicalInput)) {
                    // 只记录实际命中的规则；未满足条件不能伪造 RULE_FIRE 或后续评分步骤。
                    String ruleId = rule.get("id").toString();
                    steps.add(step("RULE_FIRE", "规则 " + ruleId + " 的冻结条件命中"));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ruleId", ruleId);
                    if (kind.equals("SCORING")) {
                        Map<String, Object> scoring = (Map<String, Object>) rule.get("scoring");
                        String score =
                                ScoringPolicy.score(scoring, canonicalInput)
                                        .stripTrailingZeros()
                                        .toPlainString();
                        row.put("score", score);
                        steps.add(step("SCORING", "规则 " + ruleId + " 按冻结权重及分段计算得分 " + score));
                        row.put(
                                "explanation",
                                Boolean.TRUE.equals(scoring.get("explain")) ? "按冻结指标、权重与分段计算" : "");
                        if (Boolean.TRUE.equals(scoring.get("explain")))
                            steps.add(
                                    step("EXPLAIN", "规则 " + ruleId + "：" + row.get("explanation")));
                    } else {
                        Map<String, Object> action = (Map<String, Object>) rule.get("action");
                        row.put("passed", Boolean.toString("ACCEPT".equals(action.get("type"))));
                        row.put("message", Objects.toString(action.get("message"), ""));
                    }
                    rows.add(row);
                }
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > timeout)
                    throw new QueryCancelledException();
            }
            if (kind.equals("SCORING"))
                rows.sort(
                        Comparator.<Map<String, Object>, java.math.BigDecimal>comparing(
                                        r -> new java.math.BigDecimal(r.get("score").toString()))
                                .reversed()
                                .thenComparing(r -> r.get("ruleId").toString()));
            if (rows.size() > max) rows = new ArrayList<>(rows.subList(0, max));
            steps.add(step("RULE_EXECUTION", "冻结规则计算完成，返回 " + rows.size() + " 行"));
        }
        return new Result(columns, rows, steps);
    }
}
