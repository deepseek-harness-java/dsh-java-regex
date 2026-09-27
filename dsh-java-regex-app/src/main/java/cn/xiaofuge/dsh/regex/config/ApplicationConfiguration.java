package cn.xiaofuge.dsh.regex.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RegexLabProperties.class)
public class ApplicationConfiguration {
}
