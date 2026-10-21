package cn.ontorepo.release.application;

import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.SpaceService;
import cn.ontorepo.space.infra.SpaceAuthorizationGate;

import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.DriverManager;
import java.util.UUID;

/** 随机隔离schema只供历史服务测试使用，禁止操作public和开发库。 */
final class HistoryDatabase implements AutoCloseable {
    private final String schema = "p4_history_t_" + UUID.randomUUID().toString().replace("-", "");
    final PGSimpleDataSource source = new PGSimpleDataSource();
    final Db db;
    final SpaceService spaces;
    final DataSourceTransactionManager transactions;
    final UUID user = UUID.randomUUID(), space = UUID.randomUUID(), other = UUID.randomUUID();

    HistoryDatabase() throws Exception {
        this(false);
    }

    HistoryDatabase(boolean fullSchema) throws Exception {
        String url = required("ONTO_TEST_DB_URL");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:15472/onto_test_p4(?:\\?.*)?"))
            throw new IllegalStateException("History tests require dedicated onto_test_p4");
        source.setURL(url);
        source.setUser(required("ONTO_TEST_DB_USER"));
        source.setPassword(required("ONTO_TEST_DB_PASSWORD"));
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        source.setCurrentSchema(schema);
        db = new Db(new JdbcTemplate(source));
        spaces = new SpaceService(db, new SpaceAuthorizationGate(source));
        transactions = new DataSourceTransactionManager(source);
        try {
            if (fullSchema) {
                var migration =
                        java.nio.file.Path.of("onto-designer-api/src/main/resources/db/migration");
                if (!java.nio.file.Files.isDirectory(migration))
                    migration =
                            java.nio.file.Path.of(
                                    "../onto-designer-api/src/main/resources/db/migration");
                try (var paths = java.nio.file.Files.list(migration)) {
                    for (var path :
                            paths.filter(
                                            p ->
                                                    p.getFileName()
                                                            .toString()
                                                            .matches("V[0-9]+__.*\\.sql"))
                                    .sorted(
                                            java.util.Comparator.comparingInt(
                                                    p ->
                                                            Integer.parseInt(
                                                                    p.getFileName()
                                                                            .toString()
                                                                            .split("__")[0]
                                                                            .substring(1))))
                                    .toList())
                        db.jdbc.execute(java.nio.file.Files.readString(path));
                }
                db.update("INSERT INTO local_user VALUES(?,'history-owner','历史测试','unused')", user);
                db.update(
                        "INSERT INTO ontology_space(id,code,name,namespace_uri,domain)"
                            + " VALUES(?,'history','历史','https://example.test/history#','test')",
                        space);
                db.update(
                        "INSERT INTO space_grant(space_id,user_id,role) VALUES(?,?,'OWNER')",
                        space,
                        user);
                return;
            }
            db.jdbc.execute(
                    """
                      CREATE TABLE ontology_space(id uuid PRIMARY KEY,status text,draft_rev bigint);
                      CREATE TABLE local_user(id uuid PRIMARY KEY,username text,display_name text);
                      CREATE TABLE space_grant(space_id uuid,user_id uuid,role text);
                      CREATE TABLE op_execution(id uuid PRIMARY KEY,space_id uuid,trace_id uuid,subject_type text,
                        subject_id uuid,op_code text,status text,error_code text,elapsed_ms bigint,
                        candidate_sha256 text,result_json jsonb,principal_id uuid,caller text,input_digest text,
                    output_digest text,"authorization" jsonb DEFAULT '[]',created_at timestamptz DEFAULT now());
                      CREATE TABLE op_execution_step(id bigserial PRIMARY KEY,execution_id uuid,step_order int,
                        kind text,status text,detail text,created_at timestamptz);
                      CREATE TABLE evidence_ref(id bigserial PRIMARY KEY,execution_id uuid,kind text,ref jsonb,created_at timestamptz);
                      CREATE TABLE audit_log(id bigserial PRIMARY KEY,space_id uuid,actor_id uuid,action text,
                        subject_id text,detail jsonb DEFAULT '{}',draft_rev bigint,created_at timestamptz DEFAULT now(),
                        subject_type text,version_refs jsonb,input_digest text,output_digest text,result text);
                    """);
            db.update("INSERT INTO ontology_space VALUES(?,'ACTIVE',0)", space);
            db.update("INSERT INTO space_grant VALUES(?,?,'OWNER')", space, user);
        } catch (Exception failure) {
            try {
                close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    UUID execution() {
        UUID execution = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " op_execution(id,space_id,trace_id,subject_type,subject_id,op_code,status,elapsed_ms,caller,result_json)"
                    + " VALUES(?,?,?,'CANDIDATE',?,'read','SUCCEEDED',1,'LEGACY','{\"columns\":[],\"rows\":[]}')",
                execution,
                space,
                UUID.randomUUID(),
                UUID.randomUUID());
        return execution;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Missing test environment: " + name);
        return value;
    }

    @Override
    public void close() throws Exception {
        if (!schema.matches("p4_history_t_[0-9a-f]{32}"))
            throw new IllegalStateException("Unsafe schema");
        try (var connection =
                        DriverManager.getConnection(
                                source.getURL(), source.getUser(), source.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
