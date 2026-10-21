package cn.ontorepo.data.infra;

import cn.ontorepo.data.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 仅精确白名单端点，禁止所有重定向；分页声明由受信配置提供。 */
public final class HttpSourceAdapter extends AdapterSupport {
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Override
  public Set<String> capabilities() {
    return Set.of(
        "GET",
        "POST_JSON_TEMPLATE",
        "EXACT_ENDPOINT",
        "DECLARED_PAGE_NUMBER",
        "FILTER_BEFORE_LIMIT",
        "NO_REDIRECT",
        "MAX_16_MIB_RESPONSE");
  }

  URI endpoint(SourceSpec s, String r, int page) {
    exact(s, r);
    boolean declared = false;
    for (JsonNode node : s.connection().path("endpoints"))
      if (node.isTextual() && node.textValue().equals(r)) declared = true;
    if (!declared || !r.matches("/[A-Za-z0-9_/-]*") || r.contains("..") || r.startsWith("//"))
      throw QueryPlanner.problem("HTTP_ENDPOINT_REJECTED");
    URI base = URI.create(text(s, "baseUrl"));
    if (!Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null
        || !(base.getPath().isEmpty() || base.getPath().equals("/")))
      throw QueryPlanner.problem("INVALID_HTTP_BASE");
    String pageParam = s.connection().path("pageParam").asText("");
    if (!pageParam.isEmpty()) QueryPlanner.identifier(pageParam);
    URI resolved = base.resolve(r + (pageParam.isEmpty() ? "" : "?" + pageParam + "=" + page));
    // 禁重定向不足以保护首次请求；最终URI仍须保持批准的scheme/host/有效port。
    if (!base.getScheme().equalsIgnoreCase(resolved.getScheme())
        || !base.getHost().equalsIgnoreCase(resolved.getHost())
        || effectivePort(base) != effectivePort(resolved)
        || resolved.getUserInfo() != null) throw QueryPlanner.problem("HTTP_ENDPOINT_REJECTED");
    return resolved;
  }

  private int effectivePort(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  JsonNode page(SourceSpec s, String r, int page, int timeout) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(endpoint(s, r, page))
            .timeout(Duration.ofMillis(Math.min(timeout, 10000)));
    var headers = s.connection().path("headers");
    if (!headers.isMissingNode() && !headers.isObject())
      throw QueryPlanner.problem("INVALID_HTTP_HEADERS");
    headers
        .fields()
        .forEachRemaining(
            e -> {
              if (!e.getValue().isTextual()
                  || Set.of("host", "connection", "content-length")
                      .contains(e.getKey().toLowerCase(Locale.ROOT)))
                throw QueryPlanner.problem("INVALID_HTTP_HEADERS");
              request.header(e.getKey(), e.getValue().textValue());
            });
    String method = s.connection().path("method").asText("GET");
    if (method.equals("GET")) request.GET();
    else if (method.equals("POST")) {
      JsonNode body = s.connection().path("body");
      if (!body.isObject()) throw QueryPlanner.problem("INVALID_HTTP_TEMPLATE");
      var payload = body.deepCopy();
      if (!s.connection().path("pageParam").asText("").isEmpty())
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload)
            .put(s.connection().path("pageParam").asText(), page);
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
    } else throw QueryPlanner.problem("HTTP_METHOD_REJECTED");
    // sendAsync 的完整 body future 同时受截止时间限制，不能仅对响应头计时。
    var future = client.sendAsync(request.build(), info -> new BoundedBody());
    HttpResponse<byte[]> response;
    try {
      response = future.get(Math.min(timeout, 10000), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      future.cancel(true);
      throw QueryPlanner.problem("QUERY_TIMEOUT");
    }
    if (response.statusCode() == 401 || response.statusCode() == 403)
      throw QueryPlanner.problem("AUTHENTICATION_FAILED");
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw QueryPlanner.problem("HTTP_RESPONSE_REJECTED");
    JsonNode data = JSON.readTree(response.body());
    if (!data.isArray()) throw QueryPlanner.problem("INVALID_HTTP_RESPONSE");
    return data;
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final java.util.concurrent.CompletableFuture<byte[]> result =
        new java.util.concurrent.CompletableFuture<>();
    private java.util.concurrent.Flow.Subscription subscription;

    @Override
    public java.util.concurrent.CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<java.nio.ByteBuffer> chunks) {
      for (var chunk : chunks) {
        if (chunk.remaining() > MAX_BYTES - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(QueryPlanner.problem("RESPONSE_TOO_LARGE"));
          return;
        }
        byte[] data = new byte[chunk.remaining()];
        chunk.get(data);
        bytes.writeBytes(data);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      result.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }

  @Override
  void connectivity(SourceSpec s) throws Exception {
    uriSocket(text(s, "baseUrl"), text(s, "baseUrl").startsWith("https:") ? 443 : 80);
  }

  @Override
  void authenticate(SourceSpec s) throws Exception {
    if (s.accessScope().isEmpty()) throw QueryPlanner.problem("SCOPE_EXCEEDED");
    page(s, s.accessScope().iterator().next(), 1, 3000);
  }

  @Override
  SourceMetadata readMetadata(SourceSpec s) throws Exception {
    List<SourceMetadata.Resource> out = new ArrayList<>();
    for (String r : s.accessScope()) {
      var data = page(s, r, 1, 3000);
      List<Map<String, Object>> rows = new ArrayList<>();
      for (var item : data) {
        if (!item.isObject()) throw QueryPlanner.problem("INVALID_HTTP_RESPONSE");
        rows.add(
            JSON.convertValue(
                item, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
      }
      out.add(inferred(r, rows));
    }
    return new SourceMetadata(out);
  }

  @Override
  QueryResult execute(SourceSpec s, QuerySpec q) throws Exception {
    flat(q);
    boolean transformed = QueryPlanner.transformed(q);
    endpoint(s, q.resource(), 1);
    long started = System.nanoTime();
    int pageSize = number(s, "pageSize", 100), maxPages = number(s, "maxPages", 1);
    if (pageSize < 1 || pageSize > 1000 || maxPages < 1 || maxPages > 100)
      throw QueryPlanner.problem("INVALID_HTTP_PAGINATION");
    List<Map<String, Object>> out = new ArrayList<>();
    List<QueryResult.SourceRecord> evidence = new ArrayList<>();
    SourceMetadata.Resource meta = null;
    for (int page = 1; page <= maxPages; page++) {
      deadline(started, q);
      var data =
          page(
              s,
              q.resource(),
              page,
              Math.max(1, q.timeoutMs() - (int) ((System.nanoTime() - started) / 1_000_000)));
      if (data.size() > pageSize) throw QueryPlanner.problem("HTTP_PAGE_SIZE_EXCEEDED");
      int index = 0;
      for (var item : data) {
        deadline(started, q);
        if (!item.isObject()) throw QueryPlanner.problem("INVALID_HTTP_RESPONSE");
        Map<String, Object> row =
            JSON.convertValue(
                item, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        if (meta == null) {
          meta = inferred(q.resource(), List.of(row));
          fields(q, row.keySet());
        }
        if (matches(q, row) && out.size() < q.limit()) {
          out.add(project(q, row));
          evidence.add(
              new QueryResult.SourceRecord(
                  s.id(), q.resource(), Map.of("page", page, "index", index)));
          // 转换查询继续到受控分页结束；触及分页预算仍未结束时明确拒绝而非伪造校验完成。
          if (out.size() >= q.limit() && !transformed)
            return new QueryResult(meta.fields(), out, evidence);
        }
        index++;
      }
      if (data.size() < pageSize || s.connection().path("pageParam").asText("").isEmpty())
        return new QueryResult(meta == null ? List.of() : meta.fields(), out, evidence);
    }
    throw QueryPlanner.problem("HTTP_PAGINATION_LIMIT");
  }
}
