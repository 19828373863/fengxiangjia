# 跨平台黄金样例（#51 / #55）

由 iOS `Packages/FXJCore`（Swift，当前取自分支 `feat/25-surroundings-eval`）生成，Android `:core` 单测逐项比对，保证两端对同一输入得到相同结果。

| 文件 | 内容 | 比对规则 |
|---|---|---|
| `document-v2.json` | Swift `DocumentCodec.encode` 输出的完整文档（含方案、反馈、位置） | Android 解码→再编码，与原 JSON 语义相等 |
| `document-v1.json` / `document-v0.json` | 旧版本文件 | 迁移到 v2 后字段符合预期 |
| `solver-golden.json` | 10 个求解用例：输入文档、环境、scenarioHash、状态、指标、解释项、完整流场 u/v/mask、可视化计数 | 哈希/状态/解释/mask **完全一致**；u/v/指标绝对误差 ≤ 1e-9 |
| `comparison.json` | A/B 比较用例 | 哈希、维度、建议一致 |

## 重新生成（模型或哈希规则有意变更时，同时提升 modelVersion）
```bash
# 需要 Swift 6（Linux 或 macOS）
mkdir -p /tmp/fxjgen && cd /tmp/fxjgen
cp -r <repo>/Packages/FXJCore/Sources . && mkdir -p Sources/Gen out
cp <repo>/shared/fixtures/generator/main.swift Sources/Gen/
cp <repo>/shared/fixtures/generator/Package.swift .
swift run -c release Gen && cp out/*.json <repo>/shared/fixtures/
```
