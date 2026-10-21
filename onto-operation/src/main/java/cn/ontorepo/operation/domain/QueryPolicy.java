package cn.ontorepo.operation.domain;

import cn.ontorepo.common.domain.Problem;

import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprFunction;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementBind;
import org.apache.jena.sparql.syntax.ElementData;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementOptional;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementSubQuery;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.syntax.ElementUnion;

import java.util.Set;

/** 以解析后的语法树和表达式白名单隔离查询能力，避免正则遗漏转义 IRI、嵌套 SERVICE 等情况。 首批只接受三元组、可选/联合、基础过滤/绑定与受检子查询；未实现语法默认拒绝。 */
public final class QueryPolicy {
    private QueryPolicy() {}

    private static final Set<String> PREDICATES =
            Set.of(
                    "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                    "http://www.w3.org/2000/01/rdf-schema#label",
                    "http://www.w3.org/2000/01/rdf-schema#comment",
                    "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                    "http://www.w3.org/2000/01/rdf-schema#domain",
                    "http://www.w3.org/2000/01/rdf-schema#range");
    private static final Set<String> FUNCTIONS =
            Set.of(
                    "E_Equals",
                    "E_NotEquals",
                    "E_LessThan",
                    "E_LessThanOrEqual",
                    "E_GreaterThan",
                    "E_GreaterThanOrEqual",
                    "E_LogicalAnd",
                    "E_LogicalOr",
                    "E_LogicalNot",
                    "E_Bound",
                    "E_Str",
                    "E_Lang",
                    "E_Datatype",
                    "E_IsIRI",
                    "E_IsURI",
                    "E_IsLiteral",
                    "E_IsBlank",
                    "E_StrContains",
                    "E_StrStartsWith",
                    "E_StrEndsWith",
                    "E_StrLowerCase",
                    "E_StrUpperCase",
                    "E_StrLength",
                    "E_Coalesce",
                    "E_Conditional",
                    "E_Add",
                    "E_Subtract",
                    "E_Multiply",
                    "E_Divide",
                    "E_SameTerm");

    private static Problem rejected() {
        return new Problem(400, "UNSAFE_QUERY", "查询包含本批不支持的语法、数据集或扩展能力");
    }

    public static Query validate(String text, int maxRows, int timeout) {
        if (text == null
                || text.length() > 10000
                || maxRows < 1
                || maxRows > 500
                || timeout < 100
                || timeout > 10000) throw Problem.bad("查询长度、结果上限或超时范围错误");
        final Query query;
        try {
            query = QueryFactory.create(text, Syntax.syntaxSPARQL_11);
        } catch (Exception e) {
            throw new Problem(400, "INVALID_QUERY", "SELECT查询语法错误");
        }
        inspect(query);
        query.setLimit(query.hasLimit() ? Math.min(query.getLimit(), maxRows) : maxRows);
        return query;
    }

    private static void inspect(Query q) {
        if (!q.isSelectType()
                || q.hasDatasetDescription()
                || q.hasAggregators()
                || q.hasGroupBy()
                || q.hasHaving()
                || q.getOffset() > 10000) throw rejected();
        q.getProject().getExprs().values().forEach(QueryPolicy::expression);
        if (q.hasOrderBy()) q.getOrderBy().forEach(o -> expression(o.getExpression()));
        element(q.getQueryPattern());
    }

    private static void expression(Expr e) {
        if (e == null || e.isVariable() || e.isConstant()) return;
        if (!e.isFunction()) throw rejected();
        ExprFunction f = e.getFunction();
        if (f.getFunctionIRI() != null || !FUNCTIONS.contains(f.getClass().getSimpleName()))
            throw rejected();
        f.getArgs().forEach(QueryPolicy::expression);
    }

    private static void predicate(Node p) {
        if (!p.isVariable() && (!p.isURI() || !PREDICATES.contains(p.getURI()))) throw rejected();
    }

    private static void element(Element e) {
        if (e == null) return;
        if (e instanceof ElementGroup x) {
            x.getElements().forEach(QueryPolicy::element);
            return;
        }
        if (e instanceof ElementPathBlock x) {
            x.patternElts()
                    .forEachRemaining(
                            t -> {
                                if (!t.isTriple()) throw rejected();
                                predicate(t.getPredicate());
                            });
            return;
        }
        if (e instanceof ElementTriplesBlock x) {
            x.patternElts().forEachRemaining(t -> predicate(t.getPredicate()));
            return;
        }
        if (e instanceof ElementOptional x) {
            element(x.getOptionalElement());
            return;
        }
        if (e instanceof ElementUnion x) {
            x.getElements().forEach(QueryPolicy::element);
            return;
        }
        if (e instanceof ElementFilter x) {
            expression(x.getExpr());
            return;
        }
        if (e instanceof ElementBind x) {
            expression(x.getExpr());
            return;
        }
        if (e instanceof ElementSubQuery x) {
            inspect(x.getQuery());
            return;
        }
        if (e instanceof ElementData) return;
        throw rejected();
    }
}
