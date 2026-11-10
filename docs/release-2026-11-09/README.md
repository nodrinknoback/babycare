# 上线前修复记录

本次处理 ABI 兼容、隐私政策、上次喂养时间和错误处理，并按方案 B 收拢编辑任务与统计计算。应用代码、政策源文件与交付文档一并纳入版本管理；线上隐私政策由独立的 `codex/privacy-pages` 分支发布。

## 已落地

- ABI 限定为 arm64-v8a、x86_64，避免 32 位设备安装后缺少 MMKV 启动库。
- 设置页提供离线政策全文；中、英、繁中、日、韩文统一来自多语言 Excel。公开页面：https://nodrinknoback.github.io/babycare/ 。联系方式为开发者邮箱 nodrinknoback@gmail.com。
- `RecordReferenceTime` 统一展示、排序、预测与提醒的时间语义，未计时喂养使用发生时刻，避免显示零时间戳。
- 读取使用有归属键的状态流，区分失败与不存在，错误可重试，保留未提交输入；ViewModel 不再持有记录加载的 View 回调。
- 正式写入和提醒刷新隔离。通知或调度失败不会把已成功提交的保存、删除误报为失败；生命周期异常保留不含敏感数据的诊断。
- `SessionCommitCoordinator` 持有冻结输入、停止、校验、确认和提交。页面只采集输入、显示结果与执行导航，旧确认不能提交切换后的宝宝或草稿。
- 统计页 ViewModel 从 1015 行收拢至 289 行；`StatisticsContentLoader` 封装查询和区块计算，Repository 在一个事务中取得整页快照，WHO 解析与图表计算在事务外执行。

## 已执行检查

- `bash ./gradlew updateAllLanguages`：成功。
- `bash ./gradlew assembleDebug :app:bundleRelease`：成功。
- `git diff --check`：通过。
- Release AAB 中只有两个 64 位 ABI，各自包含 MMKV；4 个原生库的 ELF LOAD 段对齐均为 16384 字节。这是产物静态检查，不代表完成设备运行验证。
- GitHub Pages 已部署并在 Chrome 查看公开页面；发布凭证见同目录 `privacy-published.jpg`。
- 所有测试相关工作按要求跳过，未创建、修改或运行测试。未进行真机、16 KB 页大小设备、四套主题组合的运行与视觉复核。

## 交付产物与剩余发布步骤

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- Release AAB：`app/build/outputs/bundle/release/app-release.aab`，21.45 MiB，当前未签名。
- AAB SHA-256：`21e3bab6b87217c3fdc1cba64341357f856ac659d88ae53546e7c5dad0c7a2af`。
- 正式上传前需要配置安全保存的上传密钥、生成签名 AAB，并完成 Play 商店资料、数据安全、健康应用声明等表单。未创建 Play 应用或提交版本。
- 用户已确认个人开发者账号注册于 2023-11-13 之后。申请正式发布前仍需按 [Google Play 新个人账号要求](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en) 完成至少 12 名测试者连续 14 天的封闭测试；本次“跳过测试”指本次开发工作范围，不免除平台发布条件。
- 健康相关功能的政策及商店声明要求见 [Google Play Health Content and Services](https://support.google.com/googleplay/android-developer/answer/16679511?hl=en)。隐私政策维护流程见 `../privacy/README.md`。
