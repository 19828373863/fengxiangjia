# 风向家 Android（暂定，见 #51）

原生 Kotlin + Jetpack Compose。与 iOS 共用 JSON Schema 与 `shared/fixtures/` 黄金样例。

| 模块 | 说明 |
|---|---|
| `:core` | 纯 Kotlin/JVM，FXJCore 的移植（模型、几何、气流、哈希） |
| `:app` | Compose UI（我家 / 试摆 / 方案） |

## 构建
```bash
cd android
# 需要 JDK 17 与 Android SDK（platform 35）；box 上可 `. ./env.sh`
./gradlew test assembleDebug
```
CI：`.github/workflows/android.yml`（PR 与 push main 触发，不上传产物）。

## 状态（如实区分）
见 `docs/ANDROID_STATUS.md`。
