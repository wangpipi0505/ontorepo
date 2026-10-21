package cn.ontorepo.artifact;

/** 稳定的存储失败类型；不给客户端传播 S3 凭据、远程响应体或内部路径。 */
public final class ArtifactProblem extends RuntimeException {
    private final String code;

    public ArtifactProblem(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
