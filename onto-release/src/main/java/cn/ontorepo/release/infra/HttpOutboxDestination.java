package cn.ontorepo.release.infra;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 显式配置才启用的HTTP目的地，默认应用接线使用PG收件箱。 固定地址、不跟随重定向；只有安全有界的原eventId确认才算接收，响应内容不会写lastError。 */
public final class HttpOutboxDestination implements OutboxDestination, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient client;

    public HttpOutboxDestination(URI endpoint, Duration timeout) {
        if (endpoint == null
                || !java.util.Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(20)) > 0)
            throw new IllegalArgumentException("事件HTTP目的地或超时配置无效");
        this.endpoint = endpoint;
        this.timeout = timeout;
        client =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public void deliver(JdbcOutboxStore.Delivery delivery) throws Exception {
        var body = JSON.createObjectNode();
        body.put("eventId", delivery.id());
        body.put("spaceId", delivery.spaceId().toString());
        body.put("releaseId", delivery.releaseId().toString());
        body.put("type", delivery.type().name());
        body.put("boundStatus", delivery.boundStatus());
        body.set("payload", JSON.readTree(delivery.payload()));
        var request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", Long.toString(delivery.id()))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                        .build();
        // InputStream BodyHandler只等到响应头，request.timeout不限制随后阻塞的read。
        // 完整body future另设总deadline；订阅者在入内存前限制4096字节，避免大响应耗尽堆。
        var pending = client.sendAsync(request, ignored -> receiptBody());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new OutboxProblem("DESTINATION_TIMEOUT");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new OutboxProblem("DESTINATION_UNAVAILABLE");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new OutboxProblem("DESTINATION_REJECTED");
        var receipt = JSON.readTree(response.body());
        if (receipt == null
                || !receipt.path("accepted").isBoolean()
                || !receipt.path("accepted").booleanValue()
                || !receipt.path("eventId").isIntegralNumber()
                || !receipt.path("eventId").canConvertToLong()
                || receipt.path("eventId").longValue() != delivery.id())
            throw new OutboxProblem("INVALID_DESTINATION_RECEIPT");
    }

    private static HttpResponse.BodySubscriber<byte[]> receiptBody() {
        var delegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long size;

            @Override
            public CompletionStage<byte[]> getBody() {
                return delegate.getBody();
            }

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                delegate.onSubscribe(value);
            }

            @Override
            public void onNext(List<ByteBuffer> chunks) {
                for (var chunk : chunks) size += chunk.remaining();
                if (size > 4096) {
                    subscription.cancel();
                    delegate.onError(new OutboxProblem("INVALID_DESTINATION_RECEIPT"));
                } else delegate.onNext(chunks);
            }

            @Override
            public void onError(Throwable failure) {
                delegate.onError(failure);
            }

            @Override
            public void onComplete() {
                delegate.onComplete();
            }
        };
    }

    /** 应用关闭时中止未完成网络任务，不让已取消的目的地请求阻止进程退出。 */
    @Override
    public void close() {
        client.shutdownNow();
    }
}
