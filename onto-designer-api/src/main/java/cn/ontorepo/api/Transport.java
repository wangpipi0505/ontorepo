package cn.ontorepo.api;

import cn.ontorepo.common.domain.Input;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DTO 转换集中在传输边界，应用层不依赖 Web 请求。 */
@Component
public class Transport {
    private final ObjectMapper mapper;

    public Transport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public UUID user(HttpServletRequest request) {
        return Input.uuid(request.getAttribute("userId"));
    }

    public Map<String, Object> body(Object dto) {
        return mapper.convertValue(dto, new TypeReference<Map<String, Object>>() {});
    }

    public <T> T as(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    public <T> List<T> list(List<Map<String, Object>> rows, Class<T> type) {
        return rows.stream().map(r -> as(r, type)).toList();
    }
}
