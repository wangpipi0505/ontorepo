package cn.ontorepo.release.infra;

import cn.ontorepo.release.application.OutboxRelay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.*;

import javax.sql.DataSource;

/** 默认真实PG收件箱；只有服务器显式配置才启用HTTP目的地，不接受客户端提供通知地址。 */
@Configuration
@EnableScheduling
public class ReleaseConfiguration {
    @Bean
    public JdbcOutboxStore jdbcOutboxStore(DataSource source) {
        return new JdbcOutboxStore(source, Duration.ofSeconds(30), 3);
    }

    @Bean
    public OutboxDestination outboxDestination(
            DataSource source, @Value("${ONTO_OUTBOX_HTTP_URL:}") String endpoint) {
        return endpoint.isBlank()
                ? new PgInboxDestination(source, Clock.systemUTC())
                : new HttpOutboxDestination(java.net.URI.create(endpoint), Duration.ofSeconds(10));
    }

    @Bean
    public OutboxRelay outboxRelay(JdbcOutboxStore store, OutboxDestination destination) {
        return new OutboxRelay(store, destination);
    }
}
