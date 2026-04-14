package org.gostforge.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    private Rule conversion = new Rule();
    private Rule registration = new Rule();
    private Rule login = new Rule();
    private Rule api = new Rule();

    @Getter
    @Setter
    public static class Rule {
        private long capacity = 1;
        private long refill = 1;
        private long durationMinutes = 1;

        public boolean isDisabled() {
            return capacity <= 0 || refill <= 0 || durationMinutes <= 0;
        }
    }
}
