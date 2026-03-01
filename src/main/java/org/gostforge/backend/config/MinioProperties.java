package org.gostforge.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "minio")
@Getter @Setter
public class MinioProperties {
    private String endpoint = "http://localhost:9000";
    private String accessKey = "gostforge";
    private String secretKey = "gostforge_minio";
    private String bucket = "gostforge";
}
