package cn.ontorepo.contract;

/** 守卫、主体属性与授权范围共用名称边界；保留既有首下划线和128字符冻结语义。 */
public final class GuardAttributeName {
    private GuardAttributeName() {}

    public static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}");
    }
}
