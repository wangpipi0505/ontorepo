package cn.ontorepo.operation.domain;

import cn.ontorepo.common.domain.Problem;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

/** 规则专用语法树；不接入脚本引擎、反射或任意 Java 方法。 */
public final class RuleExpression {
    private RuleExpression() {}

    private static final Set<String> FUNCTIONS =
            Set.of("isEmpty", "size", "toNumber", "toString", "contains");
    private static final Set<String> COMPARISONS = Set.of("==", "!=", ">", ">=", "<", "<=");

    private enum Type {
        NUMBER,
        STRING,
        BOOLEAN,
        NULL,
        ANY
    }

    private record Node(String op, Object value, List<Node> args) {}

    private static Node node(String op, Node... args) {
        return new Node(op, null, List.of(args));
    }

    private static Node literal(Object value) {
        return new Node("literal", value, List.of());
    }

    private static Problem invalid(String message) {
        return new Problem(400, "INVALID_RULE", message);
    }

    private static Problem unsupported() {
        return new Problem(400, "RULE_ANALYSIS_UNSUPPORTED", "条件超出可证明的单属性区间范围，请拆分为属性与常量比较");
    }

    private static long deadline() {
        return System.nanoTime() + 50_000_000L;
    }

    private static void check(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
            throw new Problem(400, "RULE_TIMEOUT", "规则处理超过50毫秒限制");
    }

    /** 校验所有分支的语法、属性引用和类型，顶层条件必须为布尔值。 */
    public static void validate(String expression, Map<String, String> propertyTypes) {
        Node n = parse(expression);
        if (type(n, propertyTypes) != Type.BOOLEAN) throw invalid("规则条件必须为布尔表达式");
    }

    /** 读取真实语法树中的引用；字符串字面量中的占位符不构成引用。 */
    public static Set<String> references(String expression) {
        Set<String> result = new LinkedHashSet<>();
        references(parse(expression), result);
        return Collections.unmodifiableSet(result);
    }

    private static void references(Node n, Set<String> result) {
        if (n.op.equals("ref")) result.add((String) n.value);
        n.args.forEach(a -> references(a, result));
    }

    /** 缺失属性作为 null；比较 null 与非 null 返回不匹配，算术拒绝缺失值。 */
    public static boolean evaluate(String expression, Map<String, Object> input) {
        long deadline = deadline();
        Node n = parse(expression, deadline);
        // 先完整解析，再短路求值，短路分支不能隐藏非法语法。
        Object value = eval(n, input == null ? Map.of() : input, deadline);
        check(deadline);
        return bool(value);
    }

    /** 判定两个条件是否存在共同输入；超出支持范围时明确拒绝作出结论。 */
    public static boolean overlap(String first, String second) {
        return satisfiable(node("&&", parse(first), parse(second)));
    }

    /** A 蕴含 B 等价于 A 与非 B 没有共同输入。 */
    public static boolean implies(String first, String second) {
        return !satisfiable(node("&&", parse(first), node("!", parse(second))));
    }

    static boolean numericType(String type) {
        return Set.of("INTEGER", "DECIMAL", "NUMBER").contains(type == null ? "" : type);
    }

    private static Type propertyType(String id, Map<String, String> types) {
        if (types == null || !types.containsKey(id)) throw invalid("规则引用了不存在或不适用的属性");
        String type = types.get(id);
        if (numericType(type)) return Type.NUMBER;
        return switch (type == null ? "" : type) {
            case "STRING", "DATE" -> Type.STRING;
            case "BOOLEAN" -> Type.BOOLEAN;
            default -> throw invalid("规则属性类型不受支持");
        };
    }

    private static Type type(Node n, Map<String, String> types) {
        if (n.op.equals("literal")) {
            if (n.value == null) return Type.NULL;
            if (n.value instanceof BigDecimal) return Type.NUMBER;
            return n.value instanceof Boolean ? Type.BOOLEAN : Type.STRING;
        }
        if (n.op.equals("ref")) return propertyType((String) n.value, types);
        List<Type> args = n.args.stream().map(a -> type(a, types)).toList();
        if (n.op.equals("!")) {
            require(args.getFirst() == Type.BOOLEAN);
            return Type.BOOLEAN;
        }
        if (n.op.equals("neg")) {
            require(args.getFirst() == Type.NUMBER);
            return Type.NUMBER;
        }
        if (Set.of("&&", "||").contains(n.op)) {
            require(args.stream().allMatch(t -> t == Type.BOOLEAN));
            return Type.BOOLEAN;
        }
        if (COMPARISONS.contains(n.op)) {
            require(args.get(0) == args.get(1) || args.contains(Type.NULL));
            if (!Set.of("==", "!=").contains(n.op))
                require(args.get(0) != Type.BOOLEAN && !args.contains(Type.NULL));
            return Type.BOOLEAN;
        }
        if (Set.of("+", "-", "*", "/", "%").contains(n.op)) {
            require(args.stream().allMatch(t -> t == Type.NUMBER));
            return Type.NUMBER;
        }
        return switch (n.op) {
            case "isEmpty" -> Type.BOOLEAN;
            case "size" -> {
                require(args.getFirst() == Type.STRING || args.getFirst() == Type.NULL);
                yield Type.NUMBER;
            }
            case "contains" -> {
                require(args.stream().allMatch(t -> t == Type.STRING));
                yield Type.BOOLEAN;
            }
            case "toNumber" -> {
                require(args.getFirst() == Type.STRING || args.getFirst() == Type.NUMBER);
                yield Type.NUMBER;
            }
            case "toString" -> Type.STRING;
            default -> throw invalid("不支持的规则函数");
        };
    }

    private static void require(boolean valid) {
        if (!valid) throw invalid("规则表达式类型不兼容");
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean b)) throw invalid("逻辑运算需要布尔值");
        return b;
    }

    static BigDecimal number(Object value) {
        if (!(value instanceof Number)) throw invalid("数值运算需要真实数值");
        try {
            BigDecimal result = new BigDecimal(value.toString());
            if (result.precision() > 1000 || Math.abs((long) result.scale()) > 1000)
                throw invalid("规则数值超出限制");
            return result;
        } catch (NumberFormatException e) {
            throw invalid("数值必须为有限数");
        }
    }

    private static Object eval(Node n, Map<String, Object> input, long deadline) {
        check(deadline);
        if (n.op.equals("literal")) return n.value;
        if (n.op.equals("ref")) {
            Object value = input.get((String) n.value);
            runtimeValue(value);
            return value;
        }
        Object left = eval(n.args.getFirst(), input, deadline);
        if (n.op.equals("!")) return !bool(left);
        if (n.op.equals("neg")) return number(left).negate();
        if (n.op.equals("&&")) return bool(left) && bool(eval(n.args.get(1), input, deadline));
        if (n.op.equals("||")) return bool(left) || bool(eval(n.args.get(1), input, deadline));
        if (FUNCTIONS.contains(n.op)) {
            return switch (n.op) {
                case "isEmpty" ->
                        left == null
                                || left instanceof String s && s.isEmpty()
                                || left instanceof Collection<?> c && c.isEmpty()
                                || left instanceof Map<?, ?> m && m.isEmpty();
                case "size" -> {
                    if (left == null) yield BigDecimal.ZERO;
                    if (left instanceof String s) yield BigDecimal.valueOf(s.length());
                    if (left instanceof Collection<?> c) yield BigDecimal.valueOf(c.size());
                    if (left instanceof Map<?, ?> m) yield BigDecimal.valueOf(m.size());
                    throw invalid("size仅支持文本或集合");
                }
                case "toNumber" -> {
                    if (left instanceof Number) yield number(left);
                    if (!(left instanceof String s) || s.length() > 1000)
                        throw invalid("toNumber需要数值或数值文本");
                    try {
                        yield number(new BigDecimal(s));
                    } catch (NumberFormatException e) {
                        throw invalid("toNumber无法转换文本");
                    }
                }
                case "toString" -> left == null ? "" : scalarString(left);
                case "contains" -> {
                    Object right = eval(n.args.get(1), input, deadline);
                    if (left instanceof String s && right instanceof String r) yield s.contains(r);
                    if (left instanceof Collection<?> c) yield c.contains(right);
                    throw invalid("contains需要文本或集合");
                }
                default -> throw invalid("不支持的规则函数");
            };
        }
        Object right = eval(n.args.get(1), input, deadline);
        if (COMPARISONS.contains(n.op)) {
            if (left == null || right == null)
                return switch (n.op) {
                    case "==" -> left == right;
                    case "!=" -> left != right;
                    default -> false;
                };
            int c = compare(left, right);
            return switch (n.op) {
                case "==" -> c == 0;
                case "!=" -> c != 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                case "<" -> c < 0;
                default -> c <= 0;
            };
        }
        BigDecimal a = number(left), b = number(right);
        try {
            return switch (n.op) {
                case "+" -> a.add(b);
                case "-" -> a.subtract(b);
                case "*" -> a.multiply(b);
                case "/" -> a.divide(b, MathContext.DECIMAL128);
                case "%" -> a.remainder(b);
                default -> throw invalid("不支持的规则运算");
            };
        } catch (ArithmeticException e) {
            throw invalid("规则算术运算失败");
        }
    }

    private static String scalarString(Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
            throw invalid("toString仅支持标量值");
        if (value instanceof Number) return number(value).stripTrailingZeros().toPlainString();
        return value.toString();
    }

    private static void runtimeValue(Object value) {
        if (value instanceof String s && s.length() > 10000) throw invalid("规则输入文本过长");
        if (value instanceof Number) number(value);
        if (value instanceof Collection<?> c) {
            if (c.size() > 1000) throw invalid("规则输入集合过大");
            for (Object item : c) {
                if (item instanceof Collection<?> || item instanceof Map<?, ?>)
                    throw invalid("规则输入集合只能包含标量");
                runtimeValue(item);
            }
        }
        if (value instanceof Map<?, ?> m && m.size() > 1000) throw invalid("规则输入集合过大");
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return number(a).compareTo(number(b));
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return x.compareTo(y);
        throw invalid("比较的输入类型不兼容");
    }

    private static Node parse(String expression) {
        return parse(expression, deadline());
    }

    private static Node parse(String expression, long deadline) {
        check(deadline);
        if (expression == null || expression.isBlank()) return literal(true);
        if (expression.length() > 10000) throw invalid("规则表达式过长");
        Parser p = new Parser(expression, deadline);
        Node n = p.expression(0);
        if (!p.token.equals("EOF")) throw invalid("规则包含多余或非法语法");
        return n;
    }

    private static final class Parser {
        final String text;
        final long deadline;
        int pos, nodes, depth;
        String token;
        Object value;

        Parser(String text, long deadline) {
            this.text = text;
            this.deadline = deadline;
            next();
        }

        void next() {
            check(deadline);
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
            if (pos == text.length()) {
                token = "EOF";
                value = null;
                return;
            }
            char c = text.charAt(pos++);
            value = null;
            if (c == '$' && pos < text.length() && text.charAt(pos) == '{') {
                int start = ++pos, end = text.indexOf('}', start);
                if (end < 0) throw invalid("属性引用未闭合");
                String id = text.substring(start, end);
                try {
                    if (!UUID.fromString(id).toString().equalsIgnoreCase(id))
                        throw invalid("属性引用必须为UUID");
                } catch (IllegalArgumentException e) {
                    throw invalid("属性引用必须为UUID");
                }
                value = UUID.fromString(id).toString();
                token = "ref";
                pos = end + 1;
                return;
            }
            if (c == '\'' || c == '"') {
                StringBuilder s = new StringBuilder();
                while (pos < text.length()) {
                    char ch = text.charAt(pos++);
                    if (ch == c) {
                        token = "literal";
                        value = s.toString();
                        return;
                    }
                    if (ch == '\\') {
                        if (pos >= text.length()) throw invalid("字符串转义未完成");
                        ch = text.charAt(pos++);
                        ch =
                                switch (ch) {
                                    case 'n' -> '\n';
                                    case 'r' -> '\r';
                                    case 't' -> '\t';
                                    case '\\', '\'', '"' -> ch;
                                    default -> throw invalid("不支持的字符串转义");
                                };
                    }
                    s.append(ch);
                }
                throw invalid("字符串未闭合");
            }
            if (Character.isDigit(c)
                    || c == '.' && pos < text.length() && Character.isDigit(text.charAt(pos))) {
                int start = pos - 1;
                while (pos < text.length()
                        && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) pos++;
                if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                    pos++;
                    if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-'))
                        pos++;
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
                }
                try {
                    BigDecimal number = new BigDecimal(text.substring(start, pos));
                    if (number.precision() > 1000 || Math.abs((long) number.scale()) > 1000)
                        throw invalid("规则数值超出限制");
                    token = "literal";
                    value = number;
                    return;
                } catch (NumberFormatException e) {
                    throw invalid("规则数值格式错误");
                }
            }
            if (Character.isLetter(c)) {
                int start = pos - 1;
                while (pos < text.length() && Character.isLetterOrDigit(text.charAt(pos))) pos++;
                token = text.substring(start, pos);
                if (Set.of("true", "false", "null").contains(token)) {
                    value = token.equals("null") ? null : Boolean.valueOf(token);
                    token = "literal";
                } else if (!FUNCTIONS.contains(token)) throw invalid("规则包含非白名单标识符");
                return;
            }
            String two = pos < text.length() ? "" + c + text.charAt(pos) : "";
            if (Set.of("&&", "||", "==", "!=", ">=", "<=").contains(two)) {
                pos++;
                token = two;
                return;
            }
            if ("()+-*/%!<>,".indexOf(c) >= 0) {
                token = String.valueOf(c);
                return;
            }
            throw invalid("规则包含非法字符");
        }

        boolean take(String expected) {
            if (!token.equals(expected)) return false;
            next();
            return true;
        }

        void expect(String expected) {
            if (!take(expected)) throw invalid("规则缺少必要符号");
        }

        Node expression(int minimum) {
            if (++nodes > 1024 || ++depth > 64) throw invalid("规则表达式过于复杂");
            Node left;
            if (take("!")) left = node("!", expression(7));
            else if (take("-")) left = node("neg", expression(7));
            else if (take("+")) left = expression(7);
            else if (take("(")) {
                left = expression(0);
                expect(")");
            } else if (token.equals("literal") || token.equals("ref")) {
                left = new Node(token, value, List.of());
                next();
            } else if (FUNCTIONS.contains(token)) {
                String name = token;
                next();
                expect("(");
                List<Node> args = new ArrayList<>();
                if (!token.equals(")")) {
                    args.add(expression(0));
                    while (take(",")) args.add(expression(0));
                }
                expect(")");
                if (args.size() != (name.equals("contains") ? 2 : 1)) throw invalid("规则函数参数数量错误");
                left = new Node(name, null, List.copyOf(args));
            } else throw invalid("规则表达式缺少操作数");
            while (precedence(token) >= minimum) {
                String op = token;
                int precedence = precedence(op);
                next();
                left = node(op, left, expression(precedence + 1));
            }
            depth--;
            return left;
        }

        int precedence(String op) {
            return switch (op) {
                case "||" -> 1;
                case "&&" -> 2;
                case "==", "!=" -> 3;
                case ">", ">=", "<", "<=" -> 4;
                case "+", "-" -> 5;
                case "*", "/", "%" -> 6;
                default -> -1;
            };
        }
    }

    // 区间约束采用析取范式；有界展开防止恶意布尔表达式导致指数级资源消耗。
    private static boolean satisfiable(Node n) {
        long deadline = deadline();
        for (List<Node> conjunction : dnf(n, false, deadline)) {
            Map<String, List<Node>> constraints = new HashMap<>();
            boolean possible = true;
            for (Node atom : conjunction) {
                check(deadline);
                Set<String> refs = new HashSet<>();
                references(atom, refs);
                if (refs.isEmpty()) {
                    if (!bool(eval(atom, Map.of(), deadline))) {
                        possible = false;
                        break;
                    }
                } else {
                    if (!COMPARISONS.contains(atom.op)) {
                        if (atom.op.equals("ref")) atom = node("==", atom, literal(true));
                        else if (atom.op.equals("!") && atom.args.getFirst().op.equals("ref"))
                            atom = node("==", atom.args.getFirst(), literal(false));
                        else throw unsupported();
                    }
                    Predicate predicate = predicate(atom, deadline);
                    if (predicate.ref == null) {
                        if (!predicate.constant) {
                            possible = false;
                            break;
                        }
                    } else
                        constraints
                                .computeIfAbsent(predicate.ref, k -> new ArrayList<>())
                                .add(new Node(predicate.op, predicate.value, List.of()));
                }
            }
            if (possible && constraints.values().stream().allMatch(RuleExpression::consistent))
                return true;
        }
        return false;
    }

    private static List<List<Node>> dnf(Node n, boolean negated, long deadline) {
        check(deadline);
        if (n.op.equals("!")) return dnf(n.args.getFirst(), !negated, deadline);
        if (n.op.equals("isEmpty") && n.args.getFirst().op.equals("ref")) {
            Node ref = n.args.getFirst();
            return dnf(
                    node("||", node("==", ref, literal(null)), node("==", ref, literal(""))),
                    negated,
                    deadline);
        }
        if (n.op.equals("&&") || n.op.equals("||")) {
            List<List<Node>> a = dnf(n.args.get(0), negated, deadline),
                    b = dnf(n.args.get(1), negated, deadline);
            boolean and = n.op.equals("&&") != negated;
            List<List<Node>> result = new ArrayList<>();
            if (and) {
                if ((long) a.size() * b.size() > 256) throw unsupported();
                for (List<Node> x : a)
                    for (List<Node> y : b) {
                        List<Node> both = new ArrayList<>(x);
                        both.addAll(y);
                        result.add(both);
                    }
            } else {
                result.addAll(a);
                result.addAll(b);
            }
            if (result.size() > 256) throw unsupported();
            return result;
        }
        if (negated && Set.of(">", ">=", "<", "<=").contains(n.op)) {
            // null 与非 null 的有序比较为 false，取反后必须保留缺失值分支。
            Node inverted = new Node(invert(n.op), null, n.args);
            Node a = n.args.get(0), b = n.args.get(1);
            if (a.op.equals("ref")) inverted = node("||", inverted, node("==", a, literal(null)));
            if (b.op.equals("ref")) inverted = node("||", inverted, node("==", b, literal(null)));
            return dnf(inverted, false, deadline);
        }
        if (negated)
            n = COMPARISONS.contains(n.op) ? new Node(invert(n.op), null, n.args) : node("!", n);
        return List.of(List.of(n));
    }

    private static String invert(String op) {
        return switch (op) {
            case "==" -> "!=";
            case "!=" -> "==";
            case ">" -> "<=";
            case ">=" -> "<";
            case "<" -> ">=";
            default -> ">";
        };
    }

    private static String reverse(String op) {
        return switch (op) {
            case ">" -> "<";
            case ">=" -> "<=";
            case "<" -> ">";
            case "<=" -> ">=";
            default -> op;
        };
    }

    private record Affine(String ref, BigDecimal coefficient, BigDecimal constant) {}

    private record Fraction(BigDecimal numerator, BigDecimal denominator)
            implements Comparable<Fraction> {
        public int compareTo(Fraction other) {
            return numerator
                    .multiply(other.denominator)
                    .compareTo(other.numerator.multiply(denominator));
        }
    }

    private record Predicate(String ref, String op, Object value, boolean constant) {}

    private static Predicate predicate(Node n, long deadline) {
        Node a = n.args.get(0), b = n.args.get(1);
        Set<String> leftRefs = new HashSet<>(), rightRefs = new HashSet<>();
        references(a, leftRefs);
        references(b, rightRefs);
        if (a.op.equals("ref") && rightRefs.isEmpty()) {
            Object value = eval(b, Map.of(), deadline);
            if (!(value instanceof Number))
                return new Predicate((String) a.value, n.op, value, true);
        }
        if (b.op.equals("ref") && leftRefs.isEmpty()) {
            Object value = eval(a, Map.of(), deadline);
            if (!(value instanceof Number))
                return new Predicate((String) b.value, reverse(n.op), value, true);
        }
        Affine left = affine(a, deadline), right = affine(b, deadline);
        if (left.ref != null && right.ref != null && !left.ref.equals(right.ref))
            throw unsupported();
        String ref = left.ref == null ? right.ref : left.ref;
        BigDecimal coefficient = left.coefficient.subtract(right.coefficient),
                constant = left.constant.subtract(right.constant);
        if (coefficient.signum() == 0) {
            boolean result =
                    bool(
                            eval(
                                    node(n.op, literal(constant), literal(BigDecimal.ZERO)),
                                    Map.of(),
                                    deadline));
            return new Predicate(null, n.op, null, result);
        }
        String op = coefficient.signum() < 0 ? reverse(n.op) : n.op;
        return new Predicate(
                ref,
                op,
                new Fraction(
                        coefficient.signum() < 0 ? constant : constant.negate(), coefficient.abs()),
                true);
    }

    private static Affine affine(Node n, long deadline) {
        check(deadline);
        if (n.op.equals("ref"))
            return new Affine((String) n.value, BigDecimal.ONE, BigDecimal.ZERO);
        Set<String> refs = new HashSet<>();
        references(n, refs);
        if (refs.isEmpty())
            return new Affine(null, BigDecimal.ZERO, number(eval(n, Map.of(), deadline)));
        if (n.op.equals("neg")) {
            Affine a = affine(n.args.getFirst(), deadline);
            return new Affine(a.ref, a.coefficient.negate(), a.constant.negate());
        }
        if (!Set.of("+", "-", "*", "/").contains(n.op)) throw unsupported();
        Affine a = affine(n.args.get(0), deadline), b = affine(n.args.get(1), deadline);
        if (a.ref != null && b.ref != null && !a.ref.equals(b.ref)) throw unsupported();
        String ref = a.ref == null ? b.ref : a.ref;
        if (n.op.equals("+"))
            return new Affine(ref, a.coefficient.add(b.coefficient), a.constant.add(b.constant));
        if (n.op.equals("-"))
            return new Affine(
                    ref, a.coefficient.subtract(b.coefficient), a.constant.subtract(b.constant));
        if (n.op.equals("*")) {
            if (a.coefficient.signum() != 0 && b.coefficient.signum() != 0) throw unsupported();
            return new Affine(
                    ref,
                    a.coefficient.multiply(b.constant).add(b.coefficient.multiply(a.constant)),
                    a.constant.multiply(b.constant));
        }
        if (b.coefficient.signum() != 0 || b.constant.signum() == 0) throw unsupported();
        try {
            return new Affine(
                    a.ref, a.coefficient.divide(b.constant), a.constant.divide(b.constant));
        } catch (ArithmeticException e) {
            throw unsupported();
        }
    }

    private static int constraintCompare(Object a, Object b) {
        if (a instanceof Fraction f && b instanceof Fraction g) return f.compareTo(g);
        // 字符串字典序不是连续区间，不能套用数值区间的可满足性结论。
        throw unsupported();
    }

    private static boolean consistent(List<Node> atoms) {
        Object eq = null, lower = null, upper = null;
        boolean hasEq = false, lowerClosed = false, upperClosed = false;
        List<Object> excluded = new ArrayList<>();
        for (Node atom : atoms) {
            Object v = atom.value;
            if (atom.op.equals("==")) {
                if (hasEq && !same(eq, v)) return false;
                eq = v;
                hasEq = true;
            } else if (atom.op.equals("!=")) excluded.add(v);
            else if (v == null || v instanceof Boolean || v instanceof String) throw unsupported();
            else if (atom.op.startsWith(">")) {
                int c = lower == null ? 1 : constraintCompare(v, lower);
                if (c > 0) {
                    lower = v;
                    lowerClosed = atom.op.equals(">=");
                } else if (c == 0) lowerClosed &= atom.op.equals(">=");
            } else {
                int c = upper == null ? -1 : constraintCompare(v, upper);
                if (c < 0) {
                    upper = v;
                    upperClosed = atom.op.equals("<=");
                } else if (c == 0) upperClosed &= atom.op.equals("<=");
            }
        }
        if (hasEq) {
            for (Object value : excluded) if (same(eq, value)) return false;
            if (eq == null) return lower == null && upper == null;
            if (lower != null) {
                int c = constraintCompare(eq, lower);
                if (c < 0 || c == 0 && !lowerClosed) return false;
            }
            if (upper != null) {
                int c = constraintCompare(eq, upper);
                if (c > 0 || c == 0 && !upperClosed) return false;
            }
            return true;
        }
        if (lower != null && upper != null) {
            int c = constraintCompare(lower, upper);
            if (c > 0 || c == 0 && (!lowerClosed || !upperClosed)) return false;
            if (c == 0) for (Object value : excluded) if (same(lower, value)) return false;
        }
        return true;
    }

    private static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Fraction && b instanceof Fraction) return constraintCompare(a, b) == 0;
        if (a.getClass() != b.getClass()) throw unsupported();
        return compare(a, b) == 0;
    }
}
