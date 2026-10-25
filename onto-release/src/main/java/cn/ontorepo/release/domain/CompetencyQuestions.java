package cn.ontorepo.release.domain;

import java.util.*;

/**
 * R14：能力问题（Competency Questions）管理器——纯内存领域逻辑。
 *
 * <p>CQ 是本体工程的验收锚点："这批能力问题，本体（模型+操作）能否回答"。
 * 管理器只做三件事：登记（问题+归属域+关联操作）、裁决（能否回答+依据）、
 * 汇总报告（覆盖率与缺口清单）。持久化与权限由上层调用方处理，本类不触存储。
 *
 * <p>判定语义：一个 CQ 视为"可回答"当且仅当它绑定了至少一个操作（operationCode）
 * 且裁决为 PASS；否则进入缺口清单。裁决依据（evidence）是必填的——禁止无依据
 * 的"我认为可以回答"。
 */
public final class CompetencyQuestions {
    /** 一条能力问题。id 由调用方生成（UUID），域用于分组展示（如"设备""溯源"）。 */
    public record Question(
            String id, String domain, String text, String operationCode, Status status, String evidence) {
        public enum Status {
            OPEN,
            PASS,
            FAIL
        }

        public Question {
            Objects.requireNonNull(text, "问题文本必填");
            if (text.isBlank()) throw new IllegalArgumentException("问题文本必填");
        }
    }

    private final Map<String, Question> questions = new LinkedHashMap<>();

    /** 登记或更新问题；同 id 覆盖（幂等），不同 id 同文本也允许（不同域可能问同一句）。 */
    public Question register(String id, String domain, String text, String operationCode) {
        var q =
                new Question(
                        id,
                        Objects.requireNonNullElse(domain, "默认"),
                        text,
                        operationCode,
                        Question.Status.OPEN,
                        null);
        questions.put(id, q);
        return q;
    }

    /** 裁决：PASS 必须给出依据（操作试执行通过的证据摘要或人工核查结论）。 */
    public Question decide(String id, Question.Status status, String evidence) {
        var q = Objects.requireNonNull(questions.get(id), "问题不存在");
        if (status == Question.Status.PASS && (evidence == null || evidence.isBlank()))
            throw new IllegalArgumentException("判定通过必须给出依据（操作试执行证据或核查结论）");
        var updated =
                new Question(q.id(), q.domain(), q.text(), q.operationCode(), status, evidence);
        questions.put(id, updated);
        return updated;
    }

    public List<Question> all() {
        return List.copyOf(questions.values());
    }

    /** 汇总报告：总数/通过/缺口 + 缺口明细（OPEN 或 FAIL），供发布评审材料引用。 */
    public Map<String, Object> report() {
        var passed = new ArrayList<Question>();
        var gaps = new ArrayList<Question>();
        for (var q : questions.values()) {
            boolean hasOperation = q.operationCode() != null && !q.operationCode().isBlank();
            if (q.status() == Question.Status.PASS && hasOperation) passed.add(q);
            else gaps.add(q);
        }
        int total = questions.size();
        // 覆盖率=通过数/总数；显式 double 参与运算避免整数除法（1/3→0）。
        double coverage = total == 0 ? 0.0 : Math.round(passed.size() * 1000.0 / total) / 1000.0;
        return Map.of(
                "total", total,
                "passed", passed.size(),
                "gaps", gaps.stream().map(q -> Map.of("id", q.id(), "domain", q.domain(), "text", q.text(), "status", q.status().name())).toList(),
                "coverage", coverage);
    }
}
