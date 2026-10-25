package cn.ontorepo.release;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.release.domain.CompetencyQuestions;
import cn.ontorepo.release.domain.CompetencyQuestions.Question;
import cn.ontorepo.release.domain.CompetencyQuestions.Question.Status;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** R14：CQ 管理器——登记/裁决/报告；PASS 必须有依据；缺口含 OPEN 与 FAIL。 */
class CompetencyQuestionsTest {

    @Test
    void passRequiresEvidenceAndGapsIncludeOpenAndFail() {
        var cq = new CompetencyQuestions();
        cq.register("q1", "设备", "列出所有在轨卫星", "listSatellites");
        cq.register("q2", "设备", "某卫星的历史轨道参数", "orbitHistory");
        cq.register("q3", "溯源", "这次结论用了哪些数据源", null);

        // PASS 无依据 → 拒绝
        assertThrows(
                IllegalArgumentException.class, () -> cq.decide("q1", Status.PASS, null));
        cq.decide("q1", Status.PASS, "trial 通过：返回 3 行，与样例一致");
        cq.decide("q2", Status.FAIL, "操作缺失，暂无法回答历史参数");
        // q3 保持 OPEN

        var report = cq.report();
        assertEquals(3, report.get("total"));
        assertEquals(1, report.get("passed"));
        @SuppressWarnings("unchecked")
        var gaps = (java.util.List<Map<String, Object>>) report.get("gaps");
        assertEquals(2, gaps.size());
        assertTrue(gaps.stream().anyMatch(g -> "q2".equals(g.get("id"))));
        assertTrue(gaps.stream().anyMatch(g -> "q3".equals(g.get("id"))));
        assertEquals(0.333, (double) report.get("coverage"), 0.001);
    }

    @Test
    void blankTextAndUnknownDecideAreRejected() {
        var cq = new CompetencyQuestions();
        assertThrows(IllegalArgumentException.class, () -> cq.register("q1", "设备", "  ", null));
        assertThrows(
                NullPointerException.class, () -> cq.decide("missing", Status.PASS, "证据"));
        // FAIL 无需依据（失败本身就是结论）
        cq.register("q1", "设备", "列出所有在轨卫星", "listSatellites");
        var q = cq.decide("q1", Status.FAIL, null);
        assertEquals(Status.FAIL, q.status());
    }
}
