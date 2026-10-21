package cn.ontorepo;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.knowledge.application.FrozenKnowledge;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 索引是可丢弃缓存，单候选也必须有明确编译上限与重建语义。 */
class KnowledgeIndexTest {
    @Test
    void oversizedCandidateIndexIsRejectedBeforeAllocatingLuceneDocuments() throws Exception {
        Map<String, Object> entry =
                Map.of(
                        "id",
                        "k1",
                        "term",
                        "设备",
                        "aliases",
                        List.of(),
                        "definition",
                        "设备定义",
                        "sourceRef",
                        "来源");
        var index = new FrozenKnowledge();
        try {
            var e =
                    assertThrows(
                            Problem.class,
                            () ->
                                    index.search(
                                            "large",
                                            Map.of("knowledge", Collections.nCopies(10001, entry)),
                                            "设备",
                                            1));
            assertEquals("KNOWLEDGE_INDEX_LIMIT", e.code);
        } finally {
            index.close();
        }
    }

    @Test
    void newIndexInstanceRebuildsTheSameFrozenSearchResults() throws Exception {
        Map<String, Object> p =
                Map.of(
                        "knowledge",
                        List.of(
                                Map.of(
                                        "id",
                                        "entry-1",
                                        "term",
                                        "设备",
                                        "aliases",
                                        List.of("装备"),
                                        "definition",
                                        "机械设备",
                                        "sourceRef",
                                        "设计说明")));
        var first = new FrozenKnowledge();
        var second = new FrozenKnowledge();
        try {
            assertEquals(
                    first.search("same-sha", p, "装备", 1), second.search("same-sha", p, "装备", 1));
        } finally {
            first.close();
            second.close();
        }
    }
}
