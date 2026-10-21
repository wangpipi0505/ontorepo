package cn.ontorepo.trace.infra;

import cn.ontorepo.common.domain.Problem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** JSON 对象键确定性排序；列表保持调用方已确定的顺序，冻结查询须显式稳定排序。摘要取实际保存的 UTF-8 字节。 */
@Component
public class CanonicalPayload {
    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("载荷序列化失败", e);
        }
    }

    public Map<String, Object> decode(byte[] bytes) {
        try {
            return mapper.readValue(bytes, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new Problem(409, "CANDIDATE_CORRUPT", "候选载荷无法读取");
        }
    }

    public String json(Object value) {
        return new String(encode(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rows(Map<String, Object> payload, String key) {
        return (List<Map<String, Object>>) payload.getOrDefault(key, java.util.List.of());
    }
}
