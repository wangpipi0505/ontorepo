package cn.ontorepo.release.infra;

/** 对外只传播稳定错误码；JDBC/HTTP原文可能含连接信息或目的地秘密，不进入安全事件目录。 */
public final class OutboxProblem extends RuntimeException {
    private final String code;

    public OutboxProblem(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
