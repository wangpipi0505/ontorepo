package cn.ontorepo.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 解析同一文档中每个资源的引用，包括未执行分支和未使用定义；不加载任何外部文档。 */
final class SchemaReferences {
    private static final Set<String> IN_PLACE_APPLICATORS =
            Set.of("allOf", "anyOf", "oneOf", "not", "if", "then", "else", "dependentSchemas", "dependencies");
    private final Map<String, JsonNode> schemas = new LinkedHashMap<>();
    private final Map<String, String> resources = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> anchors = new LinkedHashMap<>();
    private final Map<String, Set<String>> dynamicAnchors = new LinkedHashMap<>();

    private SchemaReferences() {}

    /** 统一 URI 片段写法，保留 JSON Pointer 的 ~0/~1；避免把编码后的 / 误认成命名锚点。 */
    static String fragmentReference(String fragment) {
        try {
            return new URI(null, null, fragment).toASCIIString();
        } catch (URISyntaxException e) {
            throw ContractFields.problem("INVALID_SCHEMA", "$ref");
        }
    }

    static void validate(JsonNode root) {
        SchemaReferences index = new SchemaReferences();
        index.index(root, "", "");
        index.rejectNonAdvancingCycles();
    }

    private void index(JsonNode schema, String pointer, String resource) {
        String scope = schema.has("$id") ? pointer : resource;
        schemas.put(pointer, schema);
        resources.put(pointer, scope);
        Map<String, String> localAnchors = anchors.computeIfAbsent(scope, ignored -> new LinkedHashMap<>());
        for (String keyword : new String[]{"$anchor", "$dynamicAnchor"}) {
            if (schema.has(keyword)) {
                String previous = localAnchors.putIfAbsent(schema.path(keyword).textValue(), pointer);
                if (previous != null && !previous.equals(pointer)) {
                    throw ContractFields.problem("INVALID_SCHEMA", keyword);
                }
            }
        }
        if (schema.has("$dynamicAnchor")) {
            dynamicAnchors.computeIfAbsent(schema.path("$dynamicAnchor").textValue(),
                    ignored -> new LinkedHashSet<>()).add(pointer);
        }
        SchemaTraversal.children(schema, (child, suffix) -> index(child, pointer + suffix, scope));
    }

    private String resolve(String pointer, String ref) {
        String fragment = URI.create(ref).getFragment();
        String scope = resources.get(pointer);
        String target;
        if (fragment.isEmpty() || fragment.startsWith("/")) {
            // URI 解码与 JSON Pointer 的 ~0/~1 解码不同，不能使用表单 URLDecoder。
            if (fragment.matches(".*~(?![01]).*")) {
                throw ContractFields.problem("INVALID_SCHEMA", "$ref");
            }
            target = scope + fragment;
        } else {
            target = anchors.get(scope).get(fragment);
        }
        if (target == null || !schemas.containsKey(target)) {
            throw ContractFields.problem("INVALID_SCHEMA", "$ref");
        }
        return target;
    }

    /**
     * 只把引用和同位置 applicator 连入图；properties/items 等下降到子实例的边不入图。
     * 因此对象树、数组树及交替的正常递归仍然合法，而在同一实例上原地循环会在编译前失败。
     */
    private void rejectNonAdvancingCycles() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        schemas.keySet().forEach(pointer -> edges.put(pointer, new LinkedHashSet<>()));
        schemas.forEach((pointer, schema) -> {
            Set<String> targets = edges.get(pointer);
            for (String keyword : new String[]{"$ref", "$dynamicRef"}) {
                if (!schema.has(keyword)) {
                    continue;
                }
                String ref = schema.path(keyword).textValue();
                String target = resolve(pointer, ref);
                targets.add(target);
                String fragment = URI.create(ref).getFragment();
                if (keyword.equals("$dynamicRef") && !fragment.isEmpty() && !fragment.startsWith("/")
                        && fragment.equals(schemas.get(target).path("$dynamicAnchor").textValue())) {
                    /*
                     * 动态锚点可能重绑定到调用链中的同名资源。用虚拟分派节点表示文档内的候选，
                     * 避免为每个引用与每个候选建立笛卡尔积，也覆盖跨资源重绑定形成的原地循环。
                     */
                    String dispatch = "@dynamic:" + fragment;
                    targets.add(dispatch);
                    edges.computeIfAbsent(dispatch, ignored -> new LinkedHashSet<>(dynamicAnchors.get(fragment)));
                }
            }
            SchemaTraversal.children(schema, (child, suffix) -> {
                int separator = suffix.indexOf('/', 1);
                String keyword = separator < 0 ? suffix.substring(1) : suffix.substring(1, separator);
                if (IN_PLACE_APPLICATORS.contains(keyword)) {
                    targets.add(pointer + suffix);
                }
            });
        });
        rejectCycles(edges);
    }

    /** 使用迭代拓扑消除法，检测器自身也不依赖引用链长度消耗 Java 调用栈。 */
    private static void rejectCycles(Map<String, Set<String>> edges) {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        edges.keySet().forEach(pointer -> incoming.put(pointer, 0));
        edges.values().forEach(targets -> targets.forEach(target -> incoming.merge(target, 1, Integer::sum)));
        ArrayDeque<String> ready = new ArrayDeque<>();
        incoming.forEach((pointer, count) -> {
            if (count == 0) {
                ready.addLast(pointer);
            }
        });
        int resolved = 0;
        while (!ready.isEmpty()) {
            String pointer = ready.removeFirst();
            resolved++;
            for (String target : edges.get(pointer)) {
                if (incoming.merge(target, -1, Integer::sum) == 0) {
                    ready.addLast(target);
                }
            }
        }
        if (resolved != edges.size()) {
            throw new ContractProblem("INVALID_SCHEMA", "schema", "NON_ADVANCING_SCHEMA_CYCLE");
        }
    }
}
