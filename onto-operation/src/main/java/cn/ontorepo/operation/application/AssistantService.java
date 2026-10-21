package cn.ontorepo.operation.application;

import cn.ontorepo.common.domain.*;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.contract.JsonSchemaAssertions;
import cn.ontorepo.space.application.SpaceService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.util.*;

/** 独立维护助手三段声明，允许不完整草稿，供给完整性由候选编译门禁判断。 */
@Service
public class AssistantService {
    private final Db db;
    private final SpaceService spaces;
    private static final ObjectMapper JSON = new ObjectMapper();

    public AssistantService(Db db, SpaceService spaces) {
        this.db = db;
        this.spaces = spaces;
    }

    public Map<String, Object> get(UUID u, UUID id, UUID s) {
        spaces.role(u, s);
        var row = OperationRefs.owned(db, "ont_operation", id, s);
        var out = new LinkedHashMap<String, Object>();
        out.put("operationId", id.toString());
        for (String k : List.of("inputSchema", "outputSchema", "assistantTool"))
            out.put(k, row.get(k));
        return out;
    }

    public Map<String, Object> save(UUID u, UUID id, Map<String, Object> b) {
        UUID s = Input.uuid(b.get("spaceId"));
        return spaces.write(
                u,
                s,
                Input.number(b, "expectedDraftRev"),
                "ASSISTANT_SAVE",
                () -> {
                    OperationRefs.owned(db, "ont_operation", id, s);
                    for (String key : List.of("inputSchema", "outputSchema"))
                        if (b.get(key) != null)
                            JsonSchemaAssertions.validateSchema(JSON.valueToTree(b.get(key)));
                    Object tool = b.get("assistantTool");
                    if (tool != null && !(tool instanceof Map<?, ?>))
                        throw Problem.bad("assistantTool必须为对象");
                    if (tool instanceof Map<?, ?> m) {
                        for (String key :
                                List.of(
                                        "guard_attrs",
                                        "info_attrs",
                                        "param_examples",
                                        "error_semantics"))
                            if (m.containsKey(key) && !(m.get(key) instanceof List<?>))
                                throw Problem.bad(key + "必须为数组");
                        for (String key : List.of("exec", "return_spec"))
                            if (m.containsKey(key) && !(m.get(key) instanceof Map<?, ?>))
                                throw Problem.bad(key + "必须为对象");
                    }
                    db.update(
                            "UPDATE ont_operation SET"
                                + " input_schema=?::jsonb,output_schema=?::jsonb,assistant_tool=?::jsonb"
                                + " WHERE id=?",
                            b.get("inputSchema") == null ? null : db.json(b.get("inputSchema")),
                            b.get("outputSchema") == null ? null : db.json(b.get("outputSchema")),
                            tool == null ? null : db.json(tool),
                            id);
                });
    }
}
