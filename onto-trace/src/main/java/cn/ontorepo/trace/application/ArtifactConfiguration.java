package cn.ontorepo.trace.application;

import cn.ontorepo.artifact.ArtifactStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 全应用只建立一个独立对象存储客户端，禁止临时凭据或逐请求替换存储目的地。 */
@Configuration
public class ArtifactConfiguration {
    @Bean
    public ArtifactStore artifactStore(
            @Value("${ONTO_STORAGE_ENDPOINT}") String endpoint,
            @Value("${ONTO_STORAGE_ACCESS_KEY}") String access,
            @Value("${ONTO_STORAGE_SECRET_KEY}") String secret,
            @Value("${ONTO_STORAGE_BUCKET}") String bucket) {
        var store = new ArtifactStore(endpoint, access, secret, bucket);
        store.initialize();
        return store;
    }
}
