package cn.ontorepo.trace.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.knowledge.application.FrozenKnowledge;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.stereotype.Service;

import java.util.*;

/** 所有候选预览统一校验访问权和原始摘要，然后仅消费冻结载荷。 */
@Service
public class CandidateSupplyService {
    private final CandidateService candidates;
    private final SupplyService supply;
    private final FrozenKnowledge knowledge;
    private final Db db;
    private final SpaceService spaces;

    public CandidateSupplyService(
            CandidateService candidates,
            SupplyService supply,
            FrozenKnowledge knowledge,
            Db db,
            SpaceService spaces) {
        this.candidates = candidates;
        this.supply = supply;
        this.knowledge = knowledge;
        this.db = db;
        this.spaces = spaces;
    }

    public Map<String, Object> contracts(UUID u, UUID id) {
        var c = candidates.candidate(u, id);
        return Map.of(
                "candidateId",
                id.toString(),
                "candidateSha256",
                c.get("sha256"),
                "manifests",
                supply.manifests(candidates.payload(c), id.toString()));
    }

    public Map<String, Object> context(UUID u, UUID id) {
        var c = candidates.candidate(u, id);
        var out = new LinkedHashMap<>(knowledge.context(candidates.payload(c)));
        out.put("candidateId", id.toString());
        out.put("candidateSha256", c.get("sha256"));
        return out;
    }

    public Map<String, Object> search(UUID u, UUID id, String q, int limit) {
        var c = candidates.candidate(u, id);
        return Map.of(
                "candidateId",
                id.toString(),
                "candidateSha256",
                c.get("sha256"),
                "hits",
                knowledge.search(c.get("sha256").toString(), candidates.payload(c), q, limit));
    }

    /** 正式版本复用其冻结候选的知识索引，不读取当前知识草稿。 */
    public Map<String, Object> releaseSearch(UUID u, UUID release, String q, int limit) {
        var r = db.one("SELECT space_id,candidate_id FROM ontology_release WHERE id=?", release);
        if (r == null) throw Problem.missing();
        spaces.role(u, Input.uuid(r.get("spaceId")));
        var c = candidates.candidate(u, Input.uuid(r.get("candidateId")));
        return Map.of(
                "releaseId",
                release.toString(),
                "candidateSha256",
                c.get("sha256"),
                "hits",
                knowledge.search(c.get("sha256").toString(), candidates.payload(c), q, limit));
    }
}
