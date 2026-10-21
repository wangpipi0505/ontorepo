package cn.ontorepo.release.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.ontorepo.artifact.ArtifactStore;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.trace.application.FrozenBundleLoader;
import cn.ontorepo.trace.infra.CanonicalPayload;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 实际迁移、PG事务及MinIO验证：恢复范围不限于页面可见的八类实体。 */
class ReleaseRestoreTest {
    private final CanonicalPayload codec = new CanonicalPayload();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DEPRECATED", "CORRUPTED"})
    void candidateDiffSkipsLatestUnavailableVersionAndUsesPreviousReady(String status)
            throws Exception {
        try (var f = new HistoryDatabase(true)) {
            var bundle = snapshot(f);
            UUID ready = release(f, bundle, 1, "READY");
            UUID unavailable = release(f, bundle, 2, status);
            if ("CORRUPTED".equals(status)) {
                String key =
                        ((Map<?, ?>)
                                        f.db.one(
                                                        "SELECT bundle_ref FROM ontology_release"
                                                                + " WHERE id=?",
                                                        unavailable)
                                                .get("bundleRef"))
                                .get("key")
                                .toString();
                var admin =
                        io.minio.MinioClient.builder()
                                .endpoint(required("ONTO_STORAGE_ENDPOINT"))
                                .credentials(
                                        required("ONTO_STORAGE_ACCESS_KEY"),
                                        required("ONTO_STORAGE_SECRET_KEY"))
                                .build();
                byte[] damaged =
                        "broken latest version".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                admin.putObject(
                        io.minio.PutObjectArgs.builder()
                                .bucket(required("ONTO_STORAGE_BUCKET"))
                                .object(key)
                                .stream(
                                        new java.io.ByteArrayInputStream(damaged),
                                        (long) damaged.length,
                                        -1L)
                                .build());
            }
            UUID candidate = UUID.randomUUID();
            byte[] bytes = codec.encode(bundle);
            var artifact =
                    store().storeImmutable(
                                    "test/history/" + candidate + "/candidate.json",
                                    bytes,
                                    "application/json");
            var ref =
                    Map.of(
                            "key",
                            artifact.key(),
                            "sha256",
                            artifact.sha256(),
                            "size",
                            artifact.size());
            f.db.update(
                    "INSERT INTO"
                        + " publish_candidate(id,space_id,draft_rev,sha256,payload,format_version,pre_release_id,pre_version_no,bundle_ref)"
                        + " VALUES(?,?,0,?,?,3,?,3,?::jsonb)",
                    candidate,
                    f.space,
                    artifact.sha256(),
                    bytes,
                    UUID.randomUUID(),
                    f.db.json(ref));
            var diff = history(f).candidateDiff(f.user, candidate);
            assertEquals(ready.toString(), diff.get("fromReleaseId"));
        }
    }

    @Test
    void restoreRebuildsDraftAndPreservesImmutableSnapshotAndExecutionHistory() throws Exception {
        try (var f = new HistoryDatabase(true)) {
            var source = UUID.randomUUID();
            var snapshot = UUID.randomUUID();
            var cls = UUID.randomUUID();
            f.db.update(
                    "INSERT INTO"
                        + " datasource(id,space_id,code,name,kind,dialect,conn_cfg,access_scope)"
                        + " VALUES(?,?,'source','原源','RELATIONAL','POSTGRES','cipher-original','{}')",
                    source,
                    f.space);
            f.db.update(
                    "INSERT INTO meta_snapshot(id,space_id,datasource_id,sha256,resources)"
                            + " VALUES(?,?,?,?, '[]')",
                    snapshot,
                    f.space,
                    source,
                    "b".repeat(64));
            f.db.update(
                    "INSERT INTO ont_class(id,space_id,name,description,deprecated)"
                            + " VALUES(?,?,'原类','说明',false)",
                    cls,
                    f.space);
            f.db.update(
                    "INSERT INTO ont_codebook VALUES(?,?,'unit','单位','UNIT','[]')",
                    UUID.randomUUID(),
                    f.space);
            f.db.update(
                    "INSERT INTO data_file(id,space_id,name,media_type,size,sha256,resource)"
                            + " VALUES(?,?,'数据','text/csv',10,?,'history-data.csv')",
                    UUID.randomUUID(),
                    f.space,
                    "c".repeat(64));
            seedModel(f, cls, source, snapshot);
            var bundle = snapshot(f);
            var release = release(f, bundle);
            var secondSource = UUID.randomUUID();
            var secondSnapshot = UUID.randomUUID();
            f.db.update(
                    "INSERT INTO"
                        + " datasource(id,space_id,code,name,kind,dialect,conn_cfg,access_scope)"
                        + " VALUES(?,?,'later-source','后新增源','RELATIONAL','POSTGRES','cipher-later','{}')",
                    secondSource,
                    f.space);
            f.db.update(
                    "INSERT INTO meta_snapshot(id,space_id,datasource_id,sha256,resources)"
                            + " VALUES(?,?,?,?, '[]')",
                    secondSnapshot,
                    f.space,
                    secondSource,
                    "d".repeat(64));
            UUID execution = UUID.randomUUID();
            f.db.update(
                    "INSERT INTO"
                        + " op_execution(id,space_id,trace_id,subject_type,subject_id,op_code,status,elapsed_ms,candidate_sha256,result_json,caller)"
                        + " VALUES(?,?,?,'RELEASE',?,'read','SUCCEEDED',1,?,'{\"columns\":[],\"rows\":[]}','LEGACY')",
                    execution,
                    f.space,
                    UUID.randomUUID(),
                    release,
                    "a".repeat(64));
            f.db.update(
                    "INSERT INTO evidence_ref(execution_id,kind,ref)"
                            + " VALUES(?,'META_SNAPSHOT',?::jsonb)",
                    execution,
                    f.db.json(Map.of("id", secondSnapshot.toString(), "sha256", "d".repeat(64))));
            f.db.update(
                    "UPDATE datasource SET conn_cfg='cipher-changed',name='新源' WHERE id=?", source);
            f.db.update(
                    "UPDATE ontology_space SET"
                        + " name='新名称',domain='new',guard_cfg='{\"x\":1}',draft_rev=1 WHERE id=?",
                    f.space);
            f.db.update("DELETE FROM ont_codebook WHERE space_id=?", f.space);
            f.db.update("DELETE FROM data_file WHERE space_id=?", f.space);
            var history = history(f);
            history.restore(f.user, release, 1, "从历史版本恢复");
            assertEquals(
                    "历史",
                    f.db.one("SELECT name FROM ontology_space WHERE id=?", f.space).get("name"));
            assertEquals(
                    2L,
                    ((Number)
                                    f.db.one(
                                                    "SELECT draft_rev FROM ontology_space WHERE"
                                                            + " id=?",
                                                    f.space)
                                            .get("draftRev"))
                            .longValue());
            assertEquals(
                    "cipher-original",
                    f.db.one("SELECT conn_cfg FROM datasource WHERE id=?", source).get("connCfg"));
            assertEquals(1, f.db.list("SELECT * FROM datasource WHERE space_id=?", f.space).size());
            assertEquals(
                    1, f.db.list("SELECT * FROM ont_codebook WHERE space_id=?", f.space).size());
            assertEquals(1, f.db.list("SELECT * FROM data_file WHERE space_id=?", f.space).size());
            for (var pair :
                    new String[][] {
                        {"classes", "ont_class"},
                        {"properties", "ont_property"},
                        {"relations", "ont_relation"},
                        {"constraints", "ont_constraint"},
                        {"rules", "ont_rule"},
                        {"strategies", "ont_strategy"},
                        {"operations", "ont_operation"},
                        {"testCases", "ont_test_case"},
                        {"rdfDocuments", "ont_rdf_document"},
                        {"knowledge", "ont_knowledge"}
                    })
                assertEquals(
                        codec.json(bundle.get(pair[0])),
                        codec.json(
                                f.db.list(
                                        "SELECT * FROM "
                                                + pair[1]
                                                + " WHERE space_id=? ORDER BY id",
                                        f.space)),
                        pair[0]);
            assertEquals(
                    3, f.db.list("SELECT * FROM data_mapping WHERE space_id=?", f.space).size());
            assertEquals(
                    1, f.db.list("SELECT * FROM meta_snapshot WHERE space_id=?", f.space).size());
            assertEquals(
                    "d".repeat(64),
                    f.db.one("SELECT sha256 FROM meta_snapshot_archive WHERE id=?", secondSnapshot)
                            .get("sha256"));
            assertEquals(1, f.db.list("SELECT * FROM ontology_release WHERE id=?", release).size());
            assertEquals(
                    1,
                    f.db.list("SELECT * FROM audit_log WHERE action='RELEASE_RESTORE_DRAFT'")
                            .size());
            var trace = new TraceAuditService(f.db, f.spaces).trace(f.user, execution);
            String traceJson = f.db.json(trace);
            assertTrue(traceJson.contains(secondSnapshot.toString()));
            assertTrue(traceJson.contains("snapshot\""));
            assertTrue(traceJson.contains("d".repeat(64)));
            assertThrows(Problem.class, () -> history.restore(f.user, release, 1, "旧修订不能覆盖"));
            assertEquals(
                    2L,
                    ((Number)
                                    f.db.one(
                                                    "SELECT draft_rev FROM ontology_space WHERE"
                                                            + " id=?",
                                                    f.space)
                                            .get("draftRev"))
                            .longValue());
            history.restore(f.user, release, 2, "再次恢复同一历史版本");
            assertEquals(
                    3L,
                    ((Number)
                                    f.db.one(
                                                    "SELECT draft_rev FROM ontology_space WHERE"
                                                            + " id=?",
                                                    f.space)
                                            .get("draftRev"))
                            .longValue());
            assertEquals(
                    2,
                    f.db.list("SELECT * FROM meta_snapshot_archive WHERE space_id=?", f.space)
                            .size());
        }
    }

    @Test
    void conflictingArchiveFactRollsBackWithoutDeletingCurrentSnapshot() throws Exception {
        try (var f = new HistoryDatabase(true)) {
            UUID source = UUID.randomUUID(), snap = UUID.randomUUID();
            f.db.update(
                    "INSERT INTO"
                        + " datasource(id,space_id,code,name,kind,dialect,conn_cfg,access_scope)"
                        + " VALUES(?,?,'source','source','RELATIONAL','POSTGRES','cipher','{}')",
                    source,
                    f.space);
            f.db.update(
                    "INSERT INTO meta_snapshot(id,space_id,datasource_id,sha256,resources)"
                            + " VALUES(?,?,?,?,'[]')",
                    snap,
                    f.space,
                    source,
                    "a".repeat(64));
            UUID release = release(f, snapshot(f));
            f.db.update(
                    "INSERT INTO meta_snapshot_archive SELECT"
                        + " id,space_id,datasource_id,?,resources,captured_at FROM meta_snapshot"
                        + " WHERE id=?",
                    "b".repeat(64),
                    snap);
            var failure =
                    assertThrows(
                            Problem.class, () -> history(f).restore(f.user, release, 0, "冲突应回滚"));
            assertEquals("SNAPSHOT_ARCHIVE_CONFLICT", failure.code);
            assertEquals(
                    "a".repeat(64),
                    f.db.one("SELECT sha256 FROM meta_snapshot WHERE id=?", snap).get("sha256"));
            assertEquals(
                    0L,
                    ((Number)
                                    f.db.one(
                                                    "SELECT draft_rev FROM ontology_space WHERE"
                                                            + " id=?",
                                                    f.space)
                                            .get("draftRev"))
                            .longValue());
            assertTrue(
                    f.db.list("SELECT * FROM audit_log WHERE action='RELEASE_RESTORE_DRAFT'")
                            .isEmpty());
        }
    }

    @Test
    void invalidFrozenReferencesRollbackEntireRestore() throws Exception {
        try (var f = new HistoryDatabase(true)) {
            var bundle = snapshot(f);
            var invalid = new LinkedHashMap<String, Object>();
            invalid.put("id", UUID.randomUUID().toString());
            invalid.put("spaceId", f.space.toString());
            invalid.put("name", "broken");
            invalid.put("domainClassId", UUID.randomUUID().toString());
            invalid.put("rangeType", "STRING");
            invalid.put("required", false);
            invalid.put("description", "");
            invalid.put("facets", Map.of());
            bundle.put("properties", List.of(invalid));
            var release = release(f, bundle);
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> history(f).restore(f.user, release, 0, "验证回滚"));
            assertEquals(
                    0L,
                    ((Number)
                                    f.db.one(
                                                    "SELECT draft_rev FROM ontology_space WHERE"
                                                            + " id=?",
                                                    f.space)
                                            .get("draftRev"))
                            .longValue());
            assertTrue(
                    f.db.list("SELECT * FROM audit_log WHERE action='RELEASE_RESTORE_DRAFT'")
                            .isEmpty());
        }
    }

    @Test
    void corruptActualBundlePreventsAnyDraftWrite() throws Exception {
        try (var f = new HistoryDatabase(true)) {
            UUID release = release(f, snapshot(f));
            byte[] wrong =
                    "corrupt-actual-object".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var admin =
                    io.minio.MinioClient.builder()
                            .endpoint(required("ONTO_STORAGE_ENDPOINT"))
                            .credentials(
                                    required("ONTO_STORAGE_ACCESS_KEY"),
                                    required("ONTO_STORAGE_SECRET_KEY"))
                            .build();
            admin.putObject(
                    io.minio.PutObjectArgs.builder()
                            .bucket(required("ONTO_STORAGE_BUCKET"))
                            .object("test/history/" + release + "/bundle.json")
                            .stream(
                                    new java.io.ByteArrayInputStream(wrong),
                                    (long) wrong.length,
                                    -1L)
                            .build());
            var problem =
                    assertThrows(
                            Problem.class,
                            () -> history(f).restore(f.user, release, 0, "不能恢复损坏制品"));
            assertEquals("ARTIFACT_CORRUPT", problem.code);
            assertEquals(
                    0L,
                    ((Number)
                                    f.db.one(
                                                    "SELECT draft_rev FROM ontology_space WHERE"
                                                            + " id=?",
                                                    f.space)
                                            .get("draftRev"))
                            .longValue());
            assertTrue(
                    f.db.list("SELECT * FROM audit_log WHERE action='RELEASE_RESTORE_DRAFT'")
                            .isEmpty());
        }
    }

    private Map<String, Object> snapshot(HistoryDatabase f) {
        var p = new LinkedHashMap<String, Object>();
        p.put("space", f.db.one("SELECT * FROM ontology_space WHERE id=?", f.space));
        for (var pair :
                new String[][] {
                    {"classes", "ont_class"},
                    {"properties", "ont_property"},
                    {"relations", "ont_relation"},
                    {"codebooks", "ont_codebook"},
                    {"constraints", "ont_constraint"},
                    {"rules", "ont_rule"},
                    {"strategies", "ont_strategy"},
                    {"operations", "ont_operation"},
                    {"testCases", "ont_test_case"},
                    {"rdfDocuments", "ont_rdf_document"},
                    {"knowledge", "ont_knowledge"},
                    {"datasources", "datasource"},
                    {"metaSnapshots", "meta_snapshot"},
                    {"dataFiles", "data_file"}
                })
            p.put(
                    pair[0],
                    f.db.list(
                            "SELECT * FROM " + pair[1] + " WHERE space_id=? ORDER BY id", f.space));
        for (var pair :
                new String[][] {
                    {"objectMappings", "OBJECT"},
                    {"propertyMappings", "PROPERTY"},
                    {"relationMappings", "RELATION"}
                }) {
            var definitions = new ArrayList<Map<String, Object>>();
            for (var row :
                    f.db.list(
                            "SELECT id,definition FROM data_mapping WHERE space_id=? AND kind=?"
                                    + " ORDER BY id",
                            f.space,
                            pair[1])) {
                var definition = new LinkedHashMap<>((Map<String, Object>) row.get("definition"));
                definition.put("id", row.get("id"));
                definitions.add(definition);
            }
            p.put(pair[0], definitions);
        }
        p.put("formatVersion", 3);
        p.put("capability", "CONTRACT");
        return p;
    }

    private void seedModel(HistoryDatabase f, UUID cls, UUID source, UUID snapshot) {
        UUID child = UUID.randomUUID(),
                property = UUID.randomUUID(),
                relation = UUID.randomUUID(),
                inverse = UUID.randomUUID(),
                rule = UUID.randomUUID(),
                strategy = UUID.randomUUID(),
                operation = UUID.randomUUID(),
                object = UUID.randomUUID();
        f.db.update(
                "INSERT INTO ont_class(id,space_id,parent_id,name,description,deprecated)"
                        + " VALUES(?,?,?,'子类','',false)",
                child,
                f.space,
                cls);
        f.db.update(
                "INSERT INTO"
                    + " ont_property(id,space_id,domain_class_id,name,range_type,required,description)"
                    + " VALUES(?,?,?,'名称','STRING',true,'')",
                property,
                f.space,
                cls);
        f.db.update(
                "INSERT INTO"
                    + " ont_relation(id,space_id,name,from_class_id,to_class_id,cardinality,transitive,\"symmetric\",description)"
                    + " VALUES(?,?,'关联',?,?,'ONE_TO_MANY',false,true,'')",
                relation,
                f.space,
                cls,
                child);
        f.db.update(
                "INSERT INTO"
                    + " ont_relation(id,space_id,name,from_class_id,to_class_id,cardinality,inverse_of,transitive,\"symmetric\",description)"
                    + " VALUES(?,?,'逆关联',?,?,'MANY_TO_ONE',?,false,false,'')",
                inverse,
                f.space,
                child,
                cls,
                relation);
        f.db.update("UPDATE ont_relation SET inverse_of=? WHERE id=?", inverse, relation);
        f.db.update(
                "INSERT INTO ont_constraint VALUES(?,?,'必填','REQUIRED','PROPERTY',?,'{}')",
                UUID.randomUUID(),
                f.space,
                property);
        f.db.update(
                "INSERT INTO ont_rule"
                    + " VALUES(?,?,'规则',?,'VALIDATION','true','{\"type\":\"ACCEPT\"}',null,'INFO')",
                rule,
                f.space,
                cls);
        f.db.update("INSERT INTO ont_strategy VALUES(?,?,'策略','{}','[]')", strategy, f.space);
        f.db.update(
                "INSERT INTO"
                    + " ont_operation(id,space_id,code,name,query_template,max_rows,timeout_ms,strategy_ref,rule_ids)"
                    + " VALUES(?,?,'read','读取','SELECT ?s WHERE {?s ?p ?o}',10,1000,?,?::jsonb)",
                operation,
                f.space,
                strategy,
                f.db.json(List.of(rule.toString())));
        f.db.update(
                "INSERT INTO"
                    + " ont_test_case(id,space_id,name,question,operation_id,input,expected,match_mode,enabled)"
                    + " VALUES(?,?,'用例','读取',?,'{}','{}','EXACT',true)",
                UUID.randomUUID(),
                f.space,
                operation);
        f.db.update(
                "INSERT INTO ont_rdf_document VALUES(?,?,'','TTL','[]','','TTL')",
                UUID.randomUUID(),
                f.space);
        f.db.update(
                "INSERT INTO ont_knowledge VALUES(?,?,'任务','[]','定义',?,'来源')",
                UUID.randomUUID(),
                f.space,
                cls);
        f.db.update(
                "INSERT INTO data_mapping VALUES(?,?,'OBJECT',?::jsonb)",
                object,
                f.space,
                f.db.json(
                        Map.of(
                                "datasourceId",
                                source.toString(),
                                "metaSnapshotId",
                                snapshot.toString(),
                                "targetClassId",
                                cls.toString(),
                                "objectKeyExpr",
                                "${id}",
                                "sourceResource",
                                "tasks")));
        f.db.update(
                "INSERT INTO data_mapping VALUES(?,?,'PROPERTY',?::jsonb)",
                UUID.randomUUID(),
                f.space,
                f.db.json(
                        Map.of(
                                "objectMappingId",
                                object.toString(),
                                "targetPropertyId",
                                property.toString(),
                                "srcField",
                                "name")));
        f.db.update(
                "INSERT INTO data_mapping VALUES(?,?,'RELATION',?::jsonb)",
                UUID.randomUUID(),
                f.space,
                f.db.json(
                        Map.of(
                                "relationId",
                                relation.toString(),
                                "fromObjectMappingId",
                                object.toString(),
                                "toObjectMappingId",
                                object.toString())));
    }

    private ArtifactStore store() {
        var s =
                new ArtifactStore(
                        required("ONTO_STORAGE_ENDPOINT"),
                        required("ONTO_STORAGE_ACCESS_KEY"),
                        required("ONTO_STORAGE_SECRET_KEY"),
                        required("ONTO_STORAGE_BUCKET"));
        s.initialize();
        return s;
    }

    private ReleaseHistoryService history(HistoryDatabase f) {
        return new ReleaseHistoryService(
                f.db,
                f.spaces,
                codec,
                new FrozenBundleLoader(f.db, store(), codec),
                f.transactions);
    }

    private UUID release(HistoryDatabase f, Map<String, Object> bundle) {
        return release(f, bundle, 1, "READY");
    }

    private UUID release(
            HistoryDatabase f, Map<String, Object> bundle, long version, String status) {
        UUID candidate = UUID.randomUUID(), release = UUID.randomUUID();
        byte[] bytes = codec.encode(bundle);
        var artifact =
                store().storeImmutable(
                                "test/history/" + release + "/bundle.json",
                                bytes,
                                "application/json");
        var ref =
                Map.of("key", artifact.key(), "sha256", artifact.sha256(), "size", artifact.size());
        f.db.update(
                "INSERT INTO publish_candidate(id,space_id,draft_rev,sha256,payload)"
                        + " VALUES(?,?,0,?,?)",
                candidate,
                f.space,
                artifact.sha256(),
                bytes);
        f.db.update(
                "INSERT INTO"
                    + " ontology_release(id,space_id,version_no,candidate_id,status,bundle_ref,manifest_ref,context_pack_ref,index_ref,bundle_sha256,manifest_sha256,published_at,published_by)"
                    + " VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,now(),?)",
                release,
                f.space,
                version,
                candidate,
                status,
                f.db.json(ref),
                f.db.json(ref),
                f.db.json(ref),
                f.db.json(ref),
                artifact.sha256(),
                artifact.sha256(),
                f.user);
        return release;
    }

    private static String required(String key) {
        return Objects.requireNonNull(System.getenv(key), key);
    }
}
