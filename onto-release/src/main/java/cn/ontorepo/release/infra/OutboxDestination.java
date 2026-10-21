package cn.ontorepo.release.infra;

/** 真实目的地边界：正常返回表示原eventId已确认接收，抛错表示必须保留重试资格。 */
@FunctionalInterface
public interface OutboxDestination {
    void deliver(JdbcOutboxStore.Delivery delivery) throws Exception;
}
