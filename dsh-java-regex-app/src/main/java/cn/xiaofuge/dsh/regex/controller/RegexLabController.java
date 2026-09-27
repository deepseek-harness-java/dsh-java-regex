package cn.xiaofuge.dsh.regex.controller;

import cn.xiaofuge.dsh.regex.config.RegexLabProperties;
import cn.xiaofuge.dsh.regex.model.ApiException;
import cn.xiaofuge.dsh.regex.service.HarnessAiStreamService;
import cn.xiaofuge.dsh.regex.service.RegexLabService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class RegexLabController {
    private final RegexLabService regexLabService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final RegexLabProperties properties;

    public RegexLabController(RegexLabService regexLabService,
                              HarnessAiStreamService harnessAiStreamService,
                              RegexLabProperties properties) {
        this.regexLabService = regexLabService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    public record TestBatchRequest(String pattern, String flags, List<Map<String, Object>> cases) {
    }

    public record FromSamplesRequest(List<String> samples) {
    }

    @GetMapping("/api/regex/parse")
    public Map<String, Object> parse(@RequestParam String pattern,
                                     @RequestParam(required = false, defaultValue = "") String flags) {
        return regexLabService.parseRegex(pattern, flags);
    }

    @PostMapping("/api/regex/test")
    public Map<String, Object> test(@RequestBody TestBatchRequest request) {
        return regexLabService.testBatch(request.pattern(), request.flags(), request.cases());
    }

    @PostMapping("/api/regex/danger")
    public Map<String, Object> danger(@RequestBody Map<String, String> request) {
        return regexLabService.dangerCheck(request.getOrDefault("pattern", ""));
    }

    @PostMapping("/api/regex/from-samples")
    public Map<String, Object> fromSamples(@RequestBody FromSamplesRequest request) {
        return regexLabService.fromSamples(request.samples());
    }

    @GetMapping("/api/regex/library")
    public Map<String, Object> library(@RequestParam(required = false) String category) {
        return regexLabService.commonLibrary(category);
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- RegexLab（正则实验室）：正则解析/批量测试/危险预警/样本泛化/常用模式库\n");
        prompt.append("- 你可以使用以下正则工具获取真实数据（解析、测试、评估风险类问题必须先调用工具，禁止凭记忆臆断正则行为）：\n");
        prompt.append("  - plugin__dsh-java-regex-plugin__regex_parse(pattern, flags)：解析正则结构（token 拆解/分组数/编译检查/危险度）\n");
        prompt.append("  - plugin__dsh-java-regex-plugin__regex_test_batch(pattern, flags, cases)：批量测试用例（matched/命名分组/耗时，高危模式会被阻止执行）\n");
        prompt.append("  - plugin__dsh-java-regex-plugin__regex_danger_check(pattern)：灾难回溯（ReDoS）风险扫描与改写建议\n");
        prompt.append("  - plugin__dsh-java-regex-plugin__regex_from_samples(samples)：从样本字符串泛化生成正则\n");
        prompt.append("  - plugin__dsh-java-regex-plugin__regex_common_library(category)：常用模式库（手机号/邮箱/IP/URL/身份证/日期等）\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 正则解析/测试结论必须来自工具返回的真实数据，引用关键数字（分组数/匹配数/危险级别/耗时）。\n");
        prompt.append("- 给出可复制的正则时标注适用场景与局限。\n");
        prompt.append("- 红线：高危 ReDoS 模式不执行批量测试（工具会直接拒绝），主动给出安全的改写建议；不执行/不推荐用户提交的正则于生产数据，先在隔离测试中回归。\n");
        return prompt.toString();
    }
}
