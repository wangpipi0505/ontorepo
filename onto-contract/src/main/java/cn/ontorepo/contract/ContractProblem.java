package cn.ontorepo.contract;

/** 契约校验的稳定错误信息；调用层负责映射 HTTP，不把库内部异常暴露给用户。 */
public final class ContractProblem extends RuntimeException {
    private final String code;
    private final String field;
    private final String reason;

    public ContractProblem(String code, String field, String reason) {
        super("契约校验失败：" + field + "（" + reason + "）");
        this.code = code;
        this.field = field;
        this.reason = reason;
    }

    public String code() { return code; }
    public String field() { return field; }
    public String reason() { return reason; }
}
