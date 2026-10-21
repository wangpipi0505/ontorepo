package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.common.domain.Problem;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 统一错误信封禁止泄露 SQL、堆栈与凭据。 */
@RestControllerAdvice
public class Errors {
    @ExceptionHandler(cn.ontorepo.release.infra.OutboxProblem.class)
    public ResponseEntity<Envelope<Void>> outbox(cn.ontorepo.release.infra.OutboxProblem e) {
        return ResponseEntity.status(e.code().endsWith("UNAVAILABLE") ? 503 : 409)
                .body(new Envelope<>(e.code(), "事件处理未完成，请查看状态后重试", null));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Envelope<Void>> missing(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(404).body(new Envelope<>("NOT_FOUND", "接口不存在", null));
    }

    /** 契约失败必须保留可定位的三元组，不能落入通用400或500。 */
    @ExceptionHandler(cn.ontorepo.contract.ContractProblem.class)
    public ResponseEntity<java.util.Map<String, Object>> contract(
            cn.ontorepo.contract.ContractProblem e) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        body.put("data", null);
        body.put("field", e.field());
        body.put("reason", e.reason());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(cn.ontorepo.data.domain.DataProblem.class)
    public ResponseEntity<Envelope<Void>> data(cn.ontorepo.data.domain.DataProblem e) {
        return ResponseEntity.badRequest().body(new Envelope<>(e.code(), e.getMessage(), null));
    }

    @ExceptionHandler(Problem.class)
    public ResponseEntity<Envelope<Void>> problem(Problem e) {
        return ResponseEntity.status(e.status).body(new Envelope<>(e.code, e.getMessage(), null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Envelope<Void>> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(409)
                .body(new Envelope<>("RESOURCE_CONFLICT", "编码、命名空间或同级名称重复，或资源仍被引用", null));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<Envelope<Void>> invalid(Exception e) {
        return ResponseEntity.badRequest()
                .body(new Envelope<>("INVALID_ARGUMENT", "请求参数格式错误", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Envelope<Void>> failure(Exception e) {
        org.slf4j.LoggerFactory.getLogger(Errors.class).error("API执行失败", e);
        return ResponseEntity.status(500)
                .body(new Envelope<>("INTERNAL_ERROR", "服务暂时无法处理请求，请稍后重试", null));
    }
}
