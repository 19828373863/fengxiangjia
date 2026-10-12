# fastlane（草稿，未运行）
- 依赖：`gem install bundler && bundle add fastlane`（未入库 Gemfile.lock）
- 密钥：App Store Connect API Key `.p8` 仅放本机/CI Secret，**不得提交**（已加入 .gitignore）
- `fastlane beta`：生成工程 → 构建 → 上传 TestFlight（阻塞：开发者账号）
- `fastlane metadata`：上传 `fastlane/metadata/` 文案，不提交审核（阻塞：App Store Connect 中创建 App）
