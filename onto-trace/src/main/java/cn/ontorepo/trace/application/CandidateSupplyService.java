package cn.ontorepo.trace.application;

import cn.ontorepo.knowledge.application.FrozenKnowledge;

import org.springframework.stereotype.Service;

import java.util.*;

/** 所有候选预览统一校验访问权和原始摘要，然后仅消费冻结载荷。 */
@Service
public class CandidateSupplyService {
    private final CandidateService candidates;
    private final SupplyService supply;
    private final FrozenKnowledge knowledge;

    public CandidateSupplyService(
            CandidateService candidates, SupplyService supply, FrozenKnowledge knowledge) {
        this.candidates = candidates;
        this.supply = supply;
        this.knowledge = knowledge;
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
}
