package cn.ontorepo.release;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.release.domain.ChangeImpact;
import cn.ontorepo.release.domain.ChangeImpact.Level;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** R10：影响分级纯函数——删除 CRITICAL、收紧 HIGH、层级/端点 MEDIUM、其余 SAFE。 */
class ChangeImpactTest {

    @Test
    void deletionsOfReferencedKindsAreCritical() {
        for (String kind : new String[] {"CLASS", "PROPERTY", "OPERATION", "RULE"}) {
            var result =
                    ChangeImpact.classify(
                            Map.of("kind", kind, "key", "k1", "before", Map.of("name", "x")));
            assertEquals(Level.CRITICAL.name(), result.get("severity"));
        }
        // SPACE 等无下游标识引用的删除不算 breaking
        var space =
                ChangeImpact.classify(
                        Map.of("kind", "SPACE", "key", "s1", "before", Map.of("name", "x")));
        assertEquals(Level.SAFE.name(), space.get("severity"));
    }

    @Test
    void tighteningPropertyRangeIsHigh() {
        var result =
                ChangeImpact.classify(
                        Map.of(
                                "kind", "PROPERTY",
                                "key", "p1",
                                "before", Map.of("rangeType", "STRING", "required", false),
                                "after", Map.of("rangeType", "INTEGER", "required", true)));
        assertEquals(Level.HIGH.name(), result.get("severity"));
        assertTrue(String.valueOf(result.get("reason")).contains("收紧"));
    }

    @Test
    void relaxConstraintIsSafeButParentMoveIsMedium() {
        // required true→false 是放宽
        var relax =
                ChangeImpact.classify(
                        Map.of(
                                "kind", "PROPERTY",
                                "key", "p1",
                                "before", Map.of("rangeType", "STRING", "required", true),
                                "after", Map.of("rangeType", "STRING", "required", false)));
        assertEquals(Level.SAFE.name(), relax.get("severity"));
        // 类移动父级
        var move =
                ChangeImpact.classify(
                        Map.of(
                                "kind", "CLASS",
                                "key", "c1",
                                "before", Map.of("name", "x", "parentId", "a"),
                                "after", Map.of("name", "x", "parentId", "b")));
        assertEquals(Level.MEDIUM.name(), move.get("severity"));
        // 关系基数
        var card =
                ChangeImpact.classify(
                        Map.of(
                                "kind", "RELATION",
                                "key", "r1",
                                "before", Map.of("cardinality", "ONE_TO_MANY"),
                                "after", Map.of("cardinality", "MANY_TO_MANY")));
        assertEquals(Level.MEDIUM.name(), card.get("severity"));
    }

    @Test
    void additionsAndDescriptionEditsAreSafe() {
        var added =
                ChangeImpact.classify(Map.of("kind", "CLASS", "key", "c2", "after", Map.of("name", "新")));
        assertEquals(Level.SAFE.name(), added.get("severity"));
        var edit =
                ChangeImpact.classify(
                        Map.of(
                                "kind", "CLASS",
                                "key", "c1",
                                "before", Map.of("name", "x", "description", "旧"),
                                "after", Map.of("name", "x", "description", "新")));
        assertEquals(Level.SAFE.name(), edit.get("severity"));
    }
}
