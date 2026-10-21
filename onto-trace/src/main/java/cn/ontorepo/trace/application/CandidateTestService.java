package cn.ontorepo.trace.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.contract.ContractProblem;
import cn.ontorepo.model.domain.PropertyFacets;
import cn.ontorepo.model.infra.RdfCodec;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 用例只使用候选冻结的 enabled 集合，确定性比较结构并持久化 LOCAL_TEST 执行事实。 */
@Service
public class CandidateTestService {
    private final CandidateService candidates;
    private final SpaceService spaces;
    private final Db db;

    public CandidateTestService(CandidateService candidates, SpaceService spaces, Db db) {
        this.candidates = candidates;
        this.spaces = spaces;
        this.db = db;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public Map<String, Object> tests(UUID user, UUID id) {
        var candidate = candidates.candidate(user, id);
        UUID space = Input.uuid(candidate.get("spaceId"));
        spaces.editor(user, space);
        Map<String, Object> payload;
        try {
            payload = candidates.payload(candidate);
        } catch (Problem e) {
            if (!e.code.equals("CANDIDATE_CORRUPT")) throw e;
            // 不能从损坏载荷读取或伪造用例；登记一次LOCAL_TEST完整性失败并关联实际执行事实。
            var failed = candidates.run(user, id, null, Map.of(), "LOCAL_TEST");
            return Map.of(
                    "candidateSha256",
                    candidate.get("sha256"),
                    "passed",
                    false,
                    "results",
                    List.of(
                            Map.of(
                                    "caseId",
                                    "",
                                    "name",
                                    "候选完整性检查",
                                    "passed",
                                    false,
                                    "executionId",
                                    failed.get("id"),
                                    "message",
                                    e.getMessage())));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        boolean all = true;
        for (var test : RdfCodec.rows(payload, "testCases")) {
            if (!Boolean.TRUE.equals(test.get("enabled"))) continue;
            String executionId = "";
            boolean passed = false;
            String message = "";
            try {
                var execution =
                        candidates.run(
                                user,
                                id,
                                "NL".equals(test.get("mode"))
                                        ? null
                                        : Input.uuid(test.get("operationId")),
                                test.get("input"),
                                "LOCAL_TEST",
                                "NL".equals(test.get("mode"))
                                        ? test.get("question").toString()
                                        : null);
                executionId = execution.get("id").toString();
                Map<String, Object> expected = (Map<String, Object>) test.get("expected");
                Map<String, Object> actual = new LinkedHashMap<>();
                for (String key : List.of("status", "errorCode", "opCode", "columns", "rows"))
                    if (expected.containsKey(key)) actual.put(key, execution.get(key));
                Map<String, Object> assertion = new LinkedHashMap<>(expected);
                assertion.remove("explanationKeywords");
                boolean explanationOk = true;
                if (expected.get("explanationKeywords") instanceof List<?> keywords) {
                    String explanation = explanation(execution);
                    actual.put("explanation", explanation);
                    explanationOk =
                            keywords.stream()
                                    .allMatch(
                                            keyword ->
                                                    explanation.contains(
                                                            Objects.toString(keyword, "")));
                }
                boolean allowed =
                        Set.of(
                                        "status",
                                        "errorCode",
                                        "opCode",
                                        "columns",
                                        "rows",
                                        "explanationKeywords")
                                .containsAll(expected.keySet());
                passed =
                        allowed
                                && !expected.isEmpty()
                                && explanationOk
                                && RdfCodec.rows(payload, "operations").stream()
                                        .anyMatch(
                                                op ->
                                                        Input.sameUuid(
                                                                        op.get("id"),
                                                                        test.get("operationId"))
                                                                && Objects.equals(
                                                                        op.get("code"),
                                                                        execution.get("opCode")))
                                && Objects.equals(
                                        expected.getOrDefault("status", "SUCCEEDED"),
                                        execution.get("status"))
                                && ("FAILED".equals(expected.get("status"))
                                        || "SUCCEEDED".equals(execution.get("status")))
                                && matches(
                                        assertion, actual, "CONTAINS".equals(test.get("matchMode")));
                message =
                        !explanationOk
                                ? "结果解释未包含冻结关键词"
                                : passed ? "操作命中且结果断言匹配" : "操作结果不符合冻结断言";
            } catch (ContractProblem e) {
                message = e.getMessage();
            } catch (Problem e) {
                message = e.getMessage();
            }
            all &= passed;
            results.add(
                    Map.of(
                            "caseId",
                            test.get("id"),
                            "name",
                            test.get("name"),
                            "passed",
                            passed,
                            "executionId",
                            executionId,
                            "message",
                            message));
        }
        return Map.of(
                "candidateSha256", candidate.get("sha256"), "passed", all, "results", results);
    }

    /** 从执行事实的步骤中取 EXPLAIN 解释文本；缺失时返回空串。 */
    private String explanation(Map<String, Object> execution) {
        if (execution.get("steps") instanceof List<?> steps)
            for (Object item : steps)
                if (item instanceof Map<?, ?> step
                        && "EXPLAIN".equals(Objects.toString(step.get("kind"), "")))
                    return Objects.toString(step.get("detail"), "");
        return "";
    }

    /** EXACT 逐层相等；CONTAINS 对象按字段子集、数组按元素包含，数值按数学值比较。 */
    private boolean matches(Object expected, Object actual, boolean contains) {
        if (expected instanceof Map<?, ?> em) {
            if (!(actual instanceof Map<?, ?> am) || !contains && em.size() != am.size())
                return false;
            for (var e : em.entrySet())
                if (!am.containsKey(e.getKey())
                        || !matches(e.getValue(), am.get(e.getKey()), contains)) return false;
            return true;
        }
        if (expected instanceof List<?> el) {
            if (!(actual instanceof List<?> al)) return false;
            if (contains) {
                List<Object> left = new ArrayList<>(al);
                for (Object e : el) {
                    int found = -1;
                    for (int i = 0; i < left.size(); i++)
                        if (matches(e, left.get(i), true)) {
                            found = i;
                            break;
                        }
                    if (found < 0) return false;
                    left.remove(found);
                }
                return true;
            }
            if (el.size() != al.size()) return false;
            for (int i = 0; i < el.size(); i++)
                if (!matches(el.get(i), al.get(i), false)) return false;
            return true;
        }
        return PropertyFacets.equal(expected, actual);
    }
}
