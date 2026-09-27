package cn.xiaofuge.dsh.regex.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DshRegexLabPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-regex-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshRegexLabPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ParseTool(),
                new TestBatchTool(),
                new DangerCheckTool(),
                new FromSamplesTool(),
                new CommonLibraryTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("regex-regexlab-capabilities", 20, """
                ## DSH RegexLab Plugin
                - 全部工具只读，不修改任何数据；regex_test_batch 在隔离环境逐条限时执行。
                - regex_danger_check 判定为高危（blockExecution=true）时，regex_test_batch 会直接拒绝执行——不要重试同一模式，先按建议改写。
                - 红线：不推荐把用户提交的正则直接用于生产数据；高危 ReDoS 模式主动给出安全改写建议（限定量词上限、消除嵌套、占有量词）。
                - 正则解析/测试结论必须来自工具返回结果，禁止凭记忆臆断。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__regex_")) {
                return PluginHookResult.context("DSH RegexLab audit: regex tests run isolated with timeout guard.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String get(String path, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .GET()
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_REGEX_ADMIN_URL", "http://127.0.0.1:8099")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** AI 可能传 JSON 数组字符串而不是 List —— 容错还原为 JSON 数组文本。 */
    private String listFieldJson(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                if (item instanceof Map<?, ?> || item instanceof List<?>) {
                    sb.append(String.valueOf(item));
                } else {
                    sb.append("\"").append(json(String.valueOf(item))).append("\"");
                }
                first = false;
            }
            return sb.append("]").toString();
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("[")) {
                return trimmed; // 已经是 JSON 数组文本
            }
            // 逗号分隔的纯文本样本
            String[] parts = trimmed.split("[,，\\n]");
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String p : parts) {
                if (!first) sb.append(",");
                sb.append("\"").append(json(p.trim())).append("\"");
                first = false;
            }
            return sb.append("]").toString();
        }
        return "[]";
    }

    /** cases 可能是 List<Map>，也可能是 JSON 字符串 —— 容错处理。 */
    private String casesFieldJson(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                if (item instanceof Map<?, ?> map) {
                    sb.append(mapFieldJson(map));
                } else if (item instanceof String s && s.trim().startsWith("{")) {
                    sb.append(s.trim());
                } else {
                    sb.append("{\"input\":\"").append(json(String.valueOf(item))).append("\"}");
                }
                first = false;
            }
            return sb.append("]").toString();
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            return trimmed.startsWith("[") ? trimmed : "[]";
        }
        return "[]";
    }

    private String mapFieldJson(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(json(String.valueOf(e.getKey()))).append("\":");
            Object v = e.getValue();
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                sb.append(String.valueOf(v));
            } else if (v instanceof Boolean b) {
                sb.append(b);
            } else if (v instanceof Number n) {
                sb.append(n);
            } else {
                sb.append("\"").append(json(String.valueOf(v))).append("\"");
            }
            first = false;
        }
        return sb.append("}").toString();
    }

    private class ParseTool extends AbstractTool {
        @Override public String name() { return "regex_parse"; }
        @Override public String description() { return "解析正则表达式结构：token 拆解（字符类/量词/分组/锚点/转义）、捕获分组数、编译检查、危险度初评。解释正则含义或排查语法错误时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("pattern", stringSchema("正则表达式原文"))
                    .prop("flags", stringSchema("可选，标志字母：i 忽略大小写 / m 多行 / s 点号匹配换行 / x 忽略空白注释"))
                    .prop("adminBaseUrl", stringSchema("可选，RegexLab 后台地址，默认 http://127.0.0.1:8099"))
                    .required("pattern")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String qs = "?pattern=" + java.net.URLEncoder.encode(str(args, "pattern"), java.nio.charset.StandardCharsets.UTF_8)
                    + "&flags=" + java.net.URLEncoder.encode(str(args, "flags"), java.nio.charset.StandardCharsets.UTF_8);
            return ok(get("/api/regex/parse" + qs, args));
        }
    }

    private class TestBatchTool extends AbstractTool {
        @Override public String name() { return "regex_test_batch"; }
        @Override public String description() { return "批量测试正则用例：每个用例返回 matched/捕获内容/命名分组/耗时；高危 ReDoS 模式会被拒绝执行并给出改写建议。验证正则是否符合预期时调用。"; }
        @Override public Map<String, Object> parameters() {
            Map<String, Object> caseItem = objectSchema()
                    .prop("input", stringSchema("测试输入字符串"))
                    .prop("expect", stringSchema("可选，期望是否匹配：true/false"))
                    .build();
            Map<String, Object> casesSchema = new java.util.HashMap<>();
            casesSchema.put("type", "array");
            casesSchema.put("description", "测试用例数组，每项含 input（必填）与 expect（可选 true/false）");
            casesSchema.put("items", caseItem);
            return objectSchema()
                    .prop("pattern", stringSchema("正则表达式原文"))
                    .prop("flags", stringSchema("可选，标志字母 imsx"))
                    .prop("cases", casesSchema)
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("pattern", "cases")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            StringBuilder body = new StringBuilder("{");
            body.append("\"pattern\":\"").append(json(str(args, "pattern"))).append("\"");
            body.append(",\"flags\":\"").append(json(str(args, "flags"))).append("\"");
            body.append(",\"cases\":").append(casesFieldJson(args.get("cases")));
            body.append("}");
            return ok(post("/api/regex/test", body.toString(), args));
        }
    }

    private class DangerCheckTool extends AbstractTool {
        @Override public String name() { return "regex_danger_check"; }
        @Override public String description() { return "扫描正则的灾难回溯（ReDoS）风险：嵌套量词/重叠 alternation/相邻量词/无上限贪婪等，返回危险级别与改写建议。上线前评估或用户要求检查正则安全时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("pattern", stringSchema("正则表达式原文"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("pattern")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/regex/danger", "{\"pattern\":\"" + json(str(args, "pattern")) + "\"}", args));
        }
    }

    private class FromSamplesTool extends AbstractTool {
        @Override public String name() { return "regex_from_samples"; }
        @Override public String description() { return "从若干样本字符串泛化生成正则：公共字面量保留，数字泛化为 \\d+、字母泛化为 [A-Za-z]+，返回建议正则与泛化段数。用户给出一组样例要求推导正则时调用。"; }
        @Override public Map<String, Object> parameters() {
            Map<String, Object> sampleItem = stringSchema("样本字符串");
            Map<String, Object> samplesSchema = new java.util.HashMap<>();
            samplesSchema.put("type", "array");
            samplesSchema.put("description", "样本字符串数组，至少 1 条，越多越准");
            samplesSchema.put("items", sampleItem);
            return objectSchema()
                    .prop("samples", samplesSchema)
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("samples")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/regex/from-samples",
                    "{\"samples\":" + listFieldJson(args.get("samples")) + "}", args));
        }
    }

    private class CommonLibraryTool extends AbstractTool {
        @Override public String name() { return "regex_common_library"; }
        @Override public String description() { return "常用正则模式库（中国场景优先）：手机号/邮箱/IPv4/URL/身份证/日期/统一社会信用代码/十六进制颜色/金额/强密码。查找现成模式时调用，category 不传返回全部。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("category", stringSchema("可选，分类名（如 中国大陆手机号 / 邮箱 / IPv4），不传返回全部"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String category = str(args, "category");
            String qs = category.isBlank() ? "" : "?category=" + java.net.URLEncoder.encode(category, java.nio.charset.StandardCharsets.UTF_8);
            return ok(get("/api/regex/library" + qs, args));
        }
    }
}
