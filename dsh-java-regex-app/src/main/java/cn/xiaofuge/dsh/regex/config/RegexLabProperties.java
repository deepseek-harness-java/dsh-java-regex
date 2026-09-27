package cn.xiaofuge.dsh.regex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dsh.regexlab")
public record RegexLabProperties(
        String dataDirectory,
        Harness harness
) {
    public record Harness(String baseUrl) {
    }
}
