package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.runtime.store.ObjectStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import java.nio.file.Path;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.storage.mode", havingValue="mysql")
public class ObjectStorageConfiguration {
    @Bean @ConditionalOnMissingBean(ObjectStore.class)
    ObjectStore privateObjects(@Value("${agent.storage.object-store:minio}") String backend,
            @Value("${agent.storage.object-directory:./var/artifacts}") String directory,
            @Value("${agent.storage.minio.endpoint:http://127.0.0.1:9000}") String endpoint,
            @Value("${agent.storage.minio.access-key:}") String accessKey,
            @Value("${agent.storage.minio.secret-key:}") String secretKey,
            @Value("${agent.storage.minio.bucket:tianmu-agent-artifacts}") String bucket,
            @Value("${agent.storage.minio.prefix:agent-artifacts/}") String prefix) {
        return switch (backend) {
            case "minio" -> MinioObjectStore.connect(endpoint, accessKey, secretKey, bucket, prefix);
            case "file" -> new FileObjectStore(Path.of(directory));
            default -> throw new IllegalArgumentException("agent.storage.object-store must be minio or file");
        };
    }
}
