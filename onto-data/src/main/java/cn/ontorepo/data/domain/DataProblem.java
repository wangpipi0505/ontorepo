package cn.ontorepo.data.domain;

/** 数据源及范围边界的稳定错误码；消息不携带连接配置、密钥或源响应体。 */
public final class DataProblem extends RuntimeException {
  private final String code;

  public DataProblem(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
