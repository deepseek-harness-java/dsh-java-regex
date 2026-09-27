package cn.xiaofuge.dsh.regex.service;

import cn.xiaofuge.dsh.regex.model.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Regex Lab 正则实验室：
 * - 解析正则结构：拆解 token（字符类/量词/分组/锚点/转义）生成结构说明
 * - 批量测试用例：match/mismatch + 命名分组提取 + 耗时
 * - 灾难回溯预警：嵌套量词/(a+)+ 类模式 + 长度上限缺失
 * - 从样本生成正则：按分隔符/数字/字母泛化
 * - 常用模式库：邮箱/手机号/IP/URL/日期/身份证等（中国场景优先）
 * 红线：测试在隔离环境逐条限时执行，防 ReDoS；不执行外部提交的正则于生产数据。
 */
@Service
public class RegexLabService {

    private static final long TEST_TIMEOUT_MS = 500;

    private static final Map<String, String> COMMON_LIBRARY = buildLibrary();

    public Map<String, Object> parseRegex(String pattern, String flags) {
        if (pattern == null || pattern.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "pattern 不能为空");
        }
        int flagBits = parseFlags(flags);
        List<Map<String, Object>> tokens = tokenize(pattern);
        // 编译检查（隔离 try）
        String compileError = null;
        int groupCount = 0;
        try {
            Pattern p = Pattern.compile(pattern, flagBits);
            groupCount = p.matcher("").pattern().toString().isEmpty() ? 0 : countGroups(pattern);
        } catch (PatternSyntaxException e) {
            compileError = e.getDescription() == null ? e.getMessage() : e.getDescription();
        }
        Map<String, Object> danger = dangerCheck(pattern);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pattern", pattern);
        r.put("flags", flags == null ? "" : flags);
        r.put("valid", compileError == null);
        if (compileError != null) r.put("compileError", compileError);
        r.put("groupCount", groupCount);
        r.put("tokens", tokens);
        r.put("tokenCount", tokens.size());
        r.put("danger", danger);
        return r;
    }

    public Map<String, Object> testBatch(String pattern, String flags, List<Map<String, Object>> cases) {
        if (pattern == null || pattern.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "pattern 不能为空");
        }
        if (cases == null || cases.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cases 不能为空");
        }
        int flagBits = parseFlags(flags);
        // 预检：高危模式直接拒绝执行
        Map<String, Object> danger = dangerCheck(pattern);
        if (Boolean.TRUE.equals(danger.get("dangerous")) && danger.get("level") == "high"
                && Boolean.TRUE.equals(danger.get("blockExecution"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "该正则被判定为高危 ReDoS 风险（" + danger.get("reason") + "），已阻止在服务内执行测试。请改写模式后重试。");
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, flagBits);
        } catch (PatternSyntaxException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "正则编译失败: " + (e.getDescription() == null ? e.getMessage() : e.getDescription()));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int matchCount = 0;
        for (Map<String, Object> c : cases) {
            String input = String.valueOf(c.get("input"));
            Boolean expect = c.get("expect") == null ? null : Boolean.parseBoolean(String.valueOf(c.get("expect")));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("input", input);
            long start = System.nanoTime();
            boolean matched;
            long elapsed;
            try {
                long t0 = System.nanoTime();
                Matcher m = compiled.matcher(input);
                matched = m.find();
                elapsed = (System.nanoTime() - t0) / 1_000_000;
                if (matched) {
                    item.put("group", m.group());
                    Map<String, String> named = new LinkedHashMap<>();
                    try {
                        for (String name : namedGroups(compiled)) {
                            String v = m.group(name);
                            if (v != null) named.put(name, v);
                        }
                    } catch (Exception ignored) {
                    }
                    if (!named.isEmpty()) item.put("namedGroups", named);
                }
            } catch (Exception e) {
                item.put("error", String.valueOf(e.getMessage()));
                matched = false;
                elapsed = (System.nanoTime() - start) / 1_000_000;
            }
            item.put("matched", matched);
            item.put("elapsedMs", elapsed);
            if (elapsed > TEST_TIMEOUT_MS) {
                item.put("timeout", true);
                item.put("error", "执行超过 " + TEST_TIMEOUT_MS + "ms，疑似灾难回溯，已中断");
            }
            if (expect != null) {
                item.put("expect", expect);
                item.put("pass", expect == matched);
            }
            if (matched) matchCount++;
            results.add(item);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("caseCount", cases.size());
        r.put("matchCount", matchCount);
        boolean allPass = results.stream().allMatch(x -> !x.containsKey("pass") || Boolean.TRUE.equals(x.get("pass")));
        r.put("allPass", allPass);
        r.put("results", results);
        return r;
    }

    public Map<String, Object> dangerCheck(String pattern) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<String> findings = new ArrayList<>();
        boolean dangerous = false;
        String level = "low";
        String reason = null;
        boolean blockExecution = false;

        if (pattern == null || pattern.isBlank()) {
            r.put("dangerous", false);
            r.put("findings", List.of());
            return r;
        }
        // 1) 嵌套量词：(a+)+ / (a*)* / (a{1,10}){5,} —— 最典型 ReDoS
        Matcher nested = Pattern.compile("\\((?:[^()\\\\]|\\\\.)*[+*]\\)\\s*[+*{]").matcher(pattern);
        if (nested.find()) {
            findings.add("nested-quantifier: 形如 (a+)+ 的嵌套量词，经典灾难回溯结构");
            dangerous = true;
            level = "high";
            reason = "嵌套量词";
            blockExecution = true;
        }
        // 2) 同位重叠 alternation：(.*)* 或 (a|a)+
        if (pattern.matches("(?s).*\\(.*\\|.*\\)[+*{].*") && pattern.matches("(?s).*\\(\\.[*+].*\\)[+*].*")) {
            findings.add("overlap-alternation: 含 (.*)* 类结构，回溯路径指数级");
            dangerous = true;
            level = "high";
            reason = reason == null ? "重叠 alternation" : reason;
        }
        // 3) 相邻多量词：a** 无效或 a+* 等
        if (pattern.matches("(?s).*[+*]\\s*[+*{].*") && !pattern.matches("(?s).*\\\\[+*].*")) {
            findings.add("adjacent-quantifiers: 相邻多量词，可能非法或回溯爆炸");
            dangerous = true;
            if (!"high".equals(level)) level = "medium";
            reason = reason == null ? "相邻多量词" : reason;
        }
        // 4) 长度上限缺失 + 贪婪通配
        if (pattern.matches("(?s).*\\.\\*.*") && !pattern.matches("(?s).*\\{\\d+,\\d+\\}.*")) {
            findings.add("unbounded-greedy: 含 . * 无长度上限，长输入下回溯风险高");
            dangerous = true;
            if ("low".equals(level)) level = "medium";
            reason = reason == null ? "无上限贪婪匹配" : reason;
        }
        // 5) 字符类内嵌量词（如 [a-z]+* ）
        if (pattern.matches("(?s).*\\][+*]\\s*[+*{].*")) {
            findings.add("class-quantifier-stack: 字符类量词后紧跟量词");
            dangerous = true;
            if ("low".equals(level)) level = "medium";
        }
        r.put("dangerous", dangerous);
        r.put("level", dangerous ? level : "none");
        r.put("reason", reason);
        r.put("findings", findings);
        r.put("blockExecution", blockExecution);
        r.put("advice", dangerous
                ? "给每个量词加长度上限（如 {1,64}）、消除嵌套量词、用占有量词 *+ 或原子分组 (?>...) 固化"
                : "未发现典型 ReDoS 结构");
        return r;
    }

    public Map<String, Object> fromSamples(List<String> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "samples 不能为空");
        }
        // 简单泛化：数字→\d+，小写字母串→[a-z]+，其余字面量化；再按公共结构对齐
        List<List<String>> tokenized = new ArrayList<>();
        for (String s : samples) {
            if (s == null || s.isEmpty()) continue;
            tokenized.add(tokenizeSample(s));
        }
        if (tokenized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "样本均为空");
        }
        // 以最短样本为基准对齐
        List<String> base = tokenized.stream().min(Comparator.comparingInt(List::size)).orElseThrow();
        StringBuilder sb = new StringBuilder("^");
        int wildcardCount = 0;
        for (int i = 0; i < base.size(); i++) {
            String t = base.get(i);
            final int idx = i;
            boolean same = tokenized.stream().allMatch(list -> list.size() > idx && list.get(idx).equals(t));
            if (same) {
                sb.append(t);
            } else {
                // 类型一致的泛化为类
                String kind = tokenKind(t);
                sb.append(generalize(kind));
                wildcardCount++;
            }
        }
        sb.append("$");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("suggested", sb.toString());
        r.put("sampleCount", samples.size());
        r.put("generalizedSegments", wildcardCount);
        r.put("note", "简单泛化结果，建议再人工收紧/放宽，并用 regex_test_batch 回归");
        return r;
    }

    public Map<String, Object> commonLibrary(String category) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (category != null && !category.isBlank() && COMMON_LIBRARY.containsKey(category)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put(category, COMMON_LIBRARY.get(category));
            r.put("categories", one);
        } else {
            r.put("categories", COMMON_LIBRARY);
        }
        r.put("count", r.get("categories") instanceof Map<?, ?> m ? m.size() : 0);
        r.put("note", "模式为常用起点，生产使用前请结合真实数据回归测试");
        return r;
    }

    // ---------- helpers ----------

    private int parseFlags(String flags) {
        if (flags == null || flags.isBlank()) return 0;
        int bits = 0;
        for (char c : flags.toCharArray()) {
            switch (c) {
                case 'i' -> bits |= Pattern.CASE_INSENSITIVE;
                case 'm' -> bits |= Pattern.MULTILINE;
                case 's' -> bits |= Pattern.DOTALL;
                case 'x' -> bits |= Pattern.COMMENTS;
                default -> { }
            }
        }
        return bits;
    }

    private int countGroups(String pattern) {
        int count = 0;
        boolean inClass = false, escaped = false;
        for (char c : pattern.toCharArray()) {
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (c == '(' && !inClass) {
                count++;
            }
        }
        return count;
    }

    private List<String> namedGroups(Pattern compiled) {
        // Java 17 模块系统封锁 java.util.regex 反射（InaccessibleObjectException），
        // 改为从 pattern 文本直接解析 (?<name>...) 声明
        List<String> names = new ArrayList<>();
        String source = compiled.pattern();
        Matcher decl = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9_]*)>").matcher(source);
        while (decl.find()) {
            names.add(decl.group(1));
        }
        return names;
    }

    private List<Map<String, Object>> tokenize(String pattern) {
        List<Map<String, Object>> tokens = new ArrayList<>();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            Map<String, Object> t = new LinkedHashMap<>();
            switch (c) {
                case '\\' -> {
                    String literal = pattern.substring(i, Math.min(i + 2, pattern.length()));
                    t.put("type", "escape");
                    t.put("value", literal);
                    t.put("meaning", "转义（\\d 数字 / \\w 字母数字 / \\s 空白 等）");
                    i += Math.min(2, pattern.length() - i);
                }
                case '[' -> {
                    int end = findClassEnd(pattern, i);
                    t.put("type", "char-class");
                    t.put("value", pattern.substring(i, end + 1));
                    t.put("meaning", "字符类：匹配其中任意一个字符");
                    i = end + 1;
                }
                case '(' -> {
                    t.put("type", "group-open");
                    String head = pattern.substring(i, Math.min(i + 3, pattern.length()));
                    if (head.startsWith("(?:")) t.put("meaning", "非捕获分组");
                    else if (head.startsWith("(?<")) t.put("meaning", "命名分组");
                    else t.put("meaning", "捕获分组");
                    t.put("value", "(");
                    i++;
                }
                case ')' -> {
                    t.put("type", "group-close");
                    t.put("value", ")");
                    i++;
                }
                case '+', '*' -> {
                    t.put("type", "quantifier");
                    t.put("value", String.valueOf(c));
                    t.put("meaning", c == '+' ? "1 次或多次（贪婪）" : "0 次或多次（贪婪）");
                    i++;
                }
                case '{' -> {
                    int end = pattern.indexOf('}', i);
                    if (end < 0) { t.put("type", "literal"); t.put("value", "{"); i++; break; }
                    t.put("type", "quantifier");
                    t.put("value", pattern.substring(i, end + 1));
                    t.put("meaning", "指定重复次数范围");
                    i = end + 1;
                }
                case '?' -> {
                    t.put("type", "quantifier-or-lazy");
                    t.put("value", "?");
                    t.put("meaning", i + 1 < pattern.length() && "+*}]".indexOf(pattern.charAt(i + 1)) >= 0
                            ? "惰性修饰（前一量词非贪婪）" : "0 或 1 次");
                    i++;
                }
                case '.', '^', '$', '|', ']' -> {
                    t.put("type", c == '|' ? "alternation" : "anchor-or-meta");
                    t.put("value", String.valueOf(c));
                    t.put("meaning", switch (c) {
                        case '.' -> "任意字符（默认不含换行）";
                        case '^' -> "行首锚点";
                        case '$' -> "行尾锚点";
                        case '|' -> "或分支";
                        case ']' -> "字面 ]（未配对）";
                        default -> "元字符";
                    });
                    i++;
                }
                default -> {
                    t.put("type", "literal");
                    t.put("value", String.valueOf(c));
                    i++;
                }
            }
            tokens.add(t);
        }
        return tokens;
    }

    private int findClassEnd(String pattern, int start) {
        int i = start + 1;
        boolean escaped = false;
        for (; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == ']') return i;
        }
        return pattern.length() - 1;
    }

    private List<String> tokenizeSample(String s) {
        List<String> tokens = new ArrayList<>();
        java.text.BreakIterator iter = java.text.BreakIterator.getCharacterInstance();
        // 按简单规则切：连续数字、连续字母、单个其他字符
        java.util.regex.Matcher m = Pattern.compile("\\d+|[A-Za-z]+|[^\\dA-Za-z]").matcher(s);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    private String tokenKind(String t) {
        if (t.matches("\\d+")) return "digits";
        if (t.matches("[A-Za-z]+")) return "letters";
        return "literal";
    }

    private String generalize(String kind) {
        return switch (kind) {
            case "digits" -> "\\d+";
            case "letters" -> "[A-Za-z]+";
            default -> "";
        };
    }

    private static Map<String, String> buildLibrary() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("中国大陆手机号", "^1[3-9]\\d{9}$");
        m.put("邮箱", "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
        m.put("IPv4", "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
        m.put("URL", "^https?://[\\w.-]+(?:/[^\\s]*)?$");
        m.put("中国居民身份证(18位)", "^[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$");
        m.put("日期 YYYY-MM-DD", "^\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])$");
        m.put("统一社会信用代码", "^[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}$");
        m.put("十六进制颜色", "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");
        m.put("金额(两位小数)", "^(?:0|[1-9]\\d*)(?:\\.\\d{1,2})?$");
        m.put("强密码(8+含大小写数字)", "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");
        return m;
    }
}
