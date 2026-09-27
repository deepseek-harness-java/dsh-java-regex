package cn.xiaofuge.dsh.regex;

import cn.xiaofuge.dsh.regex.config.RegexLabProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RegexLabProperties.class)
public class DshJavaRegexLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaRegexLabApplication.class, args);
    }
}
