package cn.ontorepo.common.domain;

/** 业务失败仅包含可公开中文信息；底层异常统一在 API 边界脱敏。 */
public class Problem extends RuntimeException {
    public final int status;
    public final String code;

    public Problem(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static Problem bad(String message) {
        return new Problem(400, "INVALID_ARGUMENT", message);
    }

    public static Problem missing() {
        return new Problem(404, "NOT_FOUND", "记录不存在");
    }
}
