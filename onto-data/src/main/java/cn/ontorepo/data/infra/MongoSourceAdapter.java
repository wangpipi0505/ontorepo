package cn.ontorepo.data.infra;

import cn.ontorepo.data.domain.*;
import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.*;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

/** Mongo 只生成受限 find；不接收聚合管道、操作符或服务端代码。 */
public final class MongoSourceAdapter extends AdapterSupport {
  @Override
  public Set<String> capabilities() {
    return Set.of(
        "FIND", "SERVER_FILTER_PROJECTION_LIMIT", "SAMPLED_SCHEMA", "INDEX_METADATA", "NO_JOIN");
  }

  MongoClient open(SourceSpec s) {
    return MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(text(s, "uri")))
            .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
            .applyToSocketSettings(
                b -> b.connectTimeout(2, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS))
            .build());
  }

  String collection(SourceSpec s, String resource) {
    exact(s, resource);
    QueryPlanner.identifier(resource);
    String db = text(s, "database");
    QueryPlanner.identifier(db);
    if (!resource.startsWith(db + ".") || resource.split("\\.").length != 2)
      throw QueryPlanner.problem("SCOPE_EXCEEDED");
    return resource.substring(db.length() + 1);
  }

  @Override
  void connectivity(SourceSpec s) throws Exception {
    var cs = new ConnectionString(text(s, "uri"));
    String h = cs.getHosts().getFirst();
    uriSocket("http://" + h, 27017);
  }

  @Override
  void authenticate(SourceSpec s) {
    try (MongoClient c = open(s)) {
      c.getDatabase(text(s, "database")).runCommand(new Document("ping", 1));
    }
  }

  @Override
  SourceMetadata readMetadata(SourceSpec s) {
    try (MongoClient c = open(s)) {
      List<SourceMetadata.Resource> out = new ArrayList<>();
      for (String r : s.accessScope()) {
        var coll = c.getDatabase(text(s, "database")).getCollection(collection(s, r));
        Document doc = coll.find().maxTime(3, TimeUnit.SECONDS).first();
        List<SourceMetadata.Index> indexes = new ArrayList<>();
        for (Document idx : coll.listIndexes().maxTime(3, TimeUnit.SECONDS)) {
          Document keys = idx.get("key", Document.class);
          indexes.add(
              new SourceMetadata.Index(
                  idx.getString("name"),
                  new ArrayList<>(keys.keySet()),
                  Boolean.TRUE.equals(idx.getBoolean("unique"))));
        }
        var sampled = inferred(r, doc == null ? List.of() : List.of(doc));
        out.add(
            new SourceMetadata.Resource(r, sampled.fields(), List.of("_id"), List.of(), indexes));
      }
      return new SourceMetadata(out);
    }
  }

  static Object value(com.fasterxml.jackson.databind.JsonNode n) {
    Object v = QueryPlanner.scalar(n);
    return v instanceof java.math.BigDecimal d ? new Decimal128(d) : v;
  }

  @Override
  QueryResult execute(SourceSpec s, QuerySpec q) {
    flat(q);
    String name = collection(s, q.resource());
    List<Bson> predicates = new ArrayList<>();
    for (var b : q.guardBindings().entrySet()) {
      String field = b.getValue();
      predicates.add(Filters.exists(field, true));
      predicates.add(
          Filters.expr(
              new Document(
                  "$in",
                  List.of(
                      new Document("$type", "$" + field),
                      List.of("string", "int", "long", "double", "decimal", "bool")))));
      predicates.add(Filters.nin(field, Arrays.asList(null, "")));
      predicates.add(
          Filters.expr(
              new Document(
                  "$ne",
                  List.of(
                      new Document(
                          "$trim",
                          new Document(
                              "input",
                              new Document(
                                  "$convert",
                                  new Document("input", "$" + field)
                                      .append("to", "string")
                                      .append("onError", "")
                                      .append("onNull", "")))),
                      ""))));
      var allowed = q.scope().parameters().get(b.getKey());
      if (allowed != null)
        predicates.add(Filters.in(field, allowed.stream().map(MongoSourceAdapter::value).toList()));
    }
    for (var f : q.filters()) {
      List<Object> v = f.values().stream().map(MongoSourceAdapter::value).toList();
      predicates.add(
          switch (f.operator()) {
            case EQ -> Filters.eq(f.column(), v.getFirst());
            case IN -> Filters.in(f.column(), v);
            case GT -> Filters.gt(f.column(), v.getFirst());
            case GE -> Filters.gte(f.column(), v.getFirst());
            case LT -> Filters.lt(f.column(), v.getFirst());
            case LE -> Filters.lte(f.column(), v.getFirst());
          });
    }
    try (MongoClient c = open(s)) {
      var coll = c.getDatabase(text(s, "database")).getCollection(name);
      List<Map<String, Object>> out = new ArrayList<>();
      List<QueryResult.SourceRecord> evidence = new ArrayList<>();
      Set<String> projection = new LinkedHashSet<>(q.projection());
      projection.add("_id");
      try (MongoCursor<Document> cursor =
          coll.find(Filters.and(predicates))
              .projection(Projections.include(projection.toArray(String[]::new)))
              .limit(q.limit())
              .maxTime(q.timeoutMs(), TimeUnit.MILLISECONDS)
              .iterator()) {
        while (out.size() < q.limit() && cursor.hasNext()) {
          Document d = cursor.next();
          out.add(project(q, d));
          evidence.add(
              new QueryResult.SourceRecord(s.id(), q.resource(), Map.of("_id", d.get("_id"))));
        }
      }
      return new QueryResult(inferred(q.resource(), out).fields(), out, evidence);
    }
  }
}
