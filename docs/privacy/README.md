# 隐私政策维护

公开地址：https://nodrinknoback.github.io/babycare/ 。开发者为 copydog，联系邮箱为 nodrinknoback@gmail.com。

应用内“设置 → 关于 → 隐私政策”离线展示全文。应用资源和网页都以 `tools/language/多语言对照表.xlsx` 的 `common` sheet 为唯一正文来源，使用 `privacy_policy_title` 与 `privacy_policy_body` 两个 key。

更新步骤：

1. 在 Excel 中同步修改简体中文、English、繁体中文、日文、韩文，并更新正文中的日期。
2. 执行 `bash ./gradlew updateAllLanguages`（Python 环境需要 pandas、openpyxl）。
3. 执行 `python3 tools/privacy/generate_policy.py`，生成此目录的五个 HTML 文件。
4. 将这些 HTML 文件及 `.nojekyll` 同步到 GitHub `nodrinknoback/babycare` 的 `codex/privacy-pages` 分支的 `docs/` 根目录。只发布政策生成结果，避免把本地审查截图或其他未提交内容带入该分支。
5. GitHub Pages 已配置为该分支的 `/docs` 目录，HTTPS 强制启用。确认部署成功后再使用公开地址。

2026-11-09 发布提交：`8f19b798e560e9f9a93457b49069105fbb010b4a`。Pages 网站默认显示英文，页面顶部可切换五种语言。

当前声明对应本地离线版本：无账号、无广告、无远程分析或崩溃上报，手动备份文件不加密。引入云同步、第三方 SDK、网络或新的敏感权限前，必须同步更新政策及 Play 数据安全表。

Play 商店完整说明还需写明：BabyCare 不是医疗器械，不用于诊断、治疗、治愈或预防任何疾病；医疗建议、诊断或治疗应咨询医疗专业人员。应用内容页需如实完成健康应用声明。
