package cn.ontorepo.trace.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.knowledge.application.FrozenKnowledge;

import org.springframework.stereotype.Component;

import java.util.*;

/** 确定性NL选择：仅冻结声明、示例与词典可参与打分，不接收期望operationId。 */
@Component
public class TermMatcher {
    public UUID select(Map<String, Object> p, String question) {
        String q = normalize(question);
        if (q.isEmpty()) throw new Problem(400, "NL_OPERATION_NOT_FOUND", "问题未命中冻结操作声明");
        int best = 0;
        List<Map<String, Object>> found = new ArrayList<>();
        for (var op : FrozenKnowledge.rows(p, "operations")) {
            if (!(op.get("assistantTool") instanceof Map<?, ?> tool)) continue;
            int score = 0;
            for (Object text :
                    List.of(
                            op.get("name"),
                            op.get("code"),
                            Objects.toString(tool.get("name"), ""),
                            Objects.toString(tool.get("description"), ""))) {
                String t = normalize(text.toString());
                if (!t.isEmpty() && (q.contains(t) || t.contains(q)))
                    score = Math.max(score, t.equals(q) ? 100 : 20);
            }
            String declaration =
                    normalize(
                            op.get("name")
                                    + " "
                                    + tool.get("name")
                                    + " "
                                    + tool.get("description"));
            for (var term : FrozenKnowledge.rows(p, "knowledge")) {
                Set<String> names = new HashSet<>();
                names.add(normalize(Objects.toString(term.get("term"), "")));
                for (Object alias : (List<?>) term.getOrDefault("aliases", List.of()))
                    names.add(normalize(Objects.toString(alias, "")));
                // 已冻结的历史词条也可能只有标点；空token绝不能利用contains("")制造命中。
                names.remove("");
                boolean questionHit = names.stream().anyMatch(q::contains);
                boolean operationHit = names.stream().anyMatch(declaration::contains);
                if (questionHit && operationHit) score += 10;
            }
            if (tool.get("param_examples") instanceof List<?> samples)
                for (Object sample : samples) {
                    if (sample instanceof Map<?, ?> example
                            && example.get("question") instanceof String text
                            && q.equals(normalize(text))) score += 100;
                    Set<String> tokens = new HashSet<>();
                    exampleTokens(sample, tokens, 0);
                    if (tokens.stream().anyMatch(q::contains)) score += 5;
                }
            if (score > best) {
                best = score;
                found.clear();
                found.add(op);
            } else if (score > 0 && score == best) found.add(op);
        }
        if (found.isEmpty()) throw new Problem(400, "NL_OPERATION_NOT_FOUND", "问题未命中冻结操作声明");
        if (found.size() != 1)
            throw new Problem(400, "NL_OPERATION_AMBIGUOUS", "问题命中多个冻结操作，请补充限定条件");
        return Input.uuid(found.getFirst().get("id"));
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。？！]", "");
    }

    /** 普通参数例中的稳定字符串也参与匹配，不能只识别非标准question字段。 */
    private void exampleTokens(Object value, Set<String> tokens, int depth) {
        if (depth > 32) return;
        if (value instanceof String text) {
            String token = normalize(text);
            if (token.length() >= 2) tokens.add(token);
        } else if (value instanceof Map<?, ?> map)
            for (Object child : map.values()) exampleTokens(child, tokens, depth + 1);
        else if (value instanceof List<?> list)
            for (Object child : list) exampleTokens(child, tokens, depth + 1);
    }
}
