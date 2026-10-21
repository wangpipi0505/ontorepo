package cn.ontorepo.space.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 服务端可执行权限的唯一目录；自定义角色可组合权限，不能注册没有执行语义的任意代码。 */
public final class PermissionCatalog {
    public record Permission(String code, String group, String name, String description) {}

    private static final List<Permission> ITEMS = build();
    private static final Set<String> CODES =
            ITEMS.stream().map(Permission::code).collect(Collectors.toUnmodifiableSet());

    private PermissionCatalog() {}

    public static List<Permission> all() {
        return ITEMS;
    }

    public static boolean known(String code) {
        return CODES.contains(code);
    }

    public static List<String> codes(boolean system) {
        return ITEMS.stream()
                .map(Permission::code)
                .filter(c -> system || !c.startsWith("system."))
                .sorted()
                .toList();
    }

    private static List<Permission> build() {
        var out = new ArrayList<Permission>();
        for (var group :
                List.of(
                        new String[] {"space", "空间"}, new String[] {"model", "模型"},
                        new String[] {"rule", "规则与策略"}, new String[] {"operation", "操作"},
                        new String[] {"knowledge", "知识"}, new String[] {"datasource", "数据源"},
                        new String[] {"mapping", "语义映射"}, new String[] {"ingest", "建模抽取"},
                        new String[] {"candidate", "冻结候选"}, new String[] {"test", "测试用例"},
                        new String[] {"release", "发布版本"}, new String[] {"grant", "空间及端点授权"},
                        new String[] {"system.user", "系统用户"},
                                new String[] {"system.role", "系统角色"})) {
            out.add(
                    new Permission(
                            group[0] + ".read",
                            group[1],
                            "查看" + group[1],
                            "读取" + group[1] + "；业务资源仍需空间授权"));
            out.add(
                    new Permission(
                            group[0] + ".write",
                            group[1],
                            "管理" + group[1],
                            group[0].startsWith("system.")
                                    ? "可修改账号或授权配置，属于受信管理权限"
                                    : "修改" + group[1] + "；保留原空间角色、修订与审计约束"));
        }
        out.add(new Permission("space.create", "空间", "创建空间", "创建空间后成为该空间 OWNER"));
        out.add(new Permission("candidate.execute", "冻结候选", "试执行候选", "仅执行冻结操作，仍须 EDITOR 及守卫授权"));
        out.add(new Permission("test.execute", "测试用例", "运行冻结用例", "执行冻结候选内启用的用例"));
        out.add(new Permission("service.execute", "服务消费", "调用服务", "仍须空间成员、端点授予与数据范围校验"));
        out.add(new Permission("trace.read", "溯源审计", "查看溯源与审计", "只能查看获授权空间的事实记录"));
        out.add(new Permission("system.permission.read", "权限目录", "查看权限目录", "读取角色编辑可用的服务端权限目录"));
        out.add(new Permission("system.audit.read", "管理审计", "查看管理审计", "读取用户角色配置的安全变更快照"));
        return List.copyOf(out);
    }
}
