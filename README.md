# DSH RegexLab（正则实验室）— dsh-java-regex

> DSH Java Native Plugin 独立 AI 产品系列 #13。面向开发者的正则工作台：解析结构、批量测试、ReDoS 危险预警、样本泛化、常用模式库（中国场景优先）。

## 产品定位

独立 AI 产品（非"业务系统 + 右侧助手"形态）：整个应用就是一个正则实验室，AI 与右侧工作台共享同一套工具能力，可解析/测试/评估任意正则。

## 功能

| 能力 | 说明 |
|---|---|
| 正则解析 | token 拆解（字符类/量词/分组/锚点/转义）、捕获分组数、编译检查、危险度初评 |
| 批量测试 | 每用例返回 matched/捕获内容/命名分组/耗时；支持 `=true/=false` 期望比对 |
| ReDoS 预警 | 嵌套量词/重叠 alternation/相邻量词/无上限贪婪/字符类量词堆叠 5 类启发式；高危模式拒绝执行（422） |
| 样本泛化 | 从样本字符串推导正则：公共字面量保留，数字→`\d+`、字母→`[A-Za-z]+` |
| 常用模式库 | 手机号/邮箱/IPv4/URL/身份证/日期/统一社会信用代码/颜色/金额/强密码 10 类 |

## AI 工具（5 个）

工具前缀：`plugin__dsh-java-regex-plugin__regex_`

- `regex_parse(pattern, flags)` — 解析正则结构
- `regex_test_batch(pattern, flags, cases)` — 批量测试（限时守卫，高危拒绝）
- `regex_danger_check(pattern)` — ReDoS 风险扫描 + 改写建议
- `regex_from_samples(samples)` — 样本泛化
- `regex_common_library(category)` — 常用模式库

## 安全红线

1. 全部工具只读；测试在隔离环境逐条限时执行（500ms 上限标记）。
2. 高危 ReDoS 模式（如 `(a+)+$`）`regex_test_batch` 直接拒绝（HTTP 422），不执行。
3. systemPrompt + PRE_TOOL_USE hook 双重约束：AI 必须先调用工具查证，不凭记忆臆断正则行为；主动给出安全改写建议（限定量词上限/占有量词/原子分组）。

## 架构

```
dsh-java-regex/
├── dsh-java-regex-app/       # Spring Boot 应用 (port 8099)
│   ├── controller/           # REST: /api/regex/* + /api/assistant/stream (SSE)
│   ├── service/              # RegexLabService（解析/测试/危险检查/泛化/模式库）
│   └── static/               # 前端（解析/测试/模式库/样本 四工作台 + AI 对话）
└── dsh-java-regex-plugin/    # Java Native Plugin（5 个 regex_* 工具）
```

SSE 链路：app `/api/assistant/stream` → harness `http://127.0.0.1:8090/api/agent/stream`，前端 agentId 即会话 UUID。

## 运行

```bash
# 构建
mvn package -DskipTests

# 启动 app（注意清掉 SERVER__PORT 环境变量污染）
SERVER__PORT= java -jar dsh-java-regex-app/target/dsh-java-regex-app-0.1.0-SNAPSHOT.jar --server.port=8099

# 安装插件到 harness
cp dsh-java-regex-plugin/target/dsh-java-regex-plugin-0.1.0-SNAPSHOT.jar ~/.dsh/standalone/plugins/dsh-java-regex-plugin.jar
curl -X POST http://127.0.0.1:8090/api/harness/plugins/install -H 'Content-Type: application/json' \
  -d '{"pluginId":"dsh-java-regex-plugin","jarName":"dsh-java-regex-plugin.jar","displayName":"DSH RegexLab Plugin","pluginVersion":"0.1.0","runtimeType":"JAVA_NATIVE","sourcePath":"'"$HOME"'/.dsh/standalone/plugins/dsh-java-regex-plugin.jar","entrypoint":"cn.xiaofuge.dsh.regex.plugin.DshRegexLabPlugin","installMode":"REPLACE"}'
curl -X POST http://127.0.0.1:8090/api/harness/plugins/activate -H 'Content-Type: application/json' \
  -d '{"pluginId":"dsh-java-regex-plugin"}'

# E2E
python3 regex_lab_e2e.py   # 8/8 PASS
```

## E2E 用例（8 组 30 断言）

- T0 解析：valid/分组数/token 数/危险度
- T1 批量测试：期望比对 + 全部通过
- T1b 命名分组提取（year/month）
- T2 危险检查：`(a+)+$` → high/blockExecution
- T3 样本泛化：日期样本 → `^\d+-\d+-\d+$`
- T4 模式库：10 类齐全 + 手机号模式实测通过
- T5 高危模式测试被拒（422 + 中文错误信息）
- T6 AI 走 SSE 调用 `regex_parse` 工具回答
- T7 安全红线：ReDoS 请求下 AI 先 danger_check、拒绝盲目执行并给改写建议

## 截图

- `screenshots/01-home.png` — 主界面（解析工作台）
- `screenshots/02-ai-drawer.png` — AI 解析手机号正则并评估 ReDoS
- `screenshots/03-safety.png` — 危险扫描：嵌套量词高危预警
