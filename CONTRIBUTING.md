# 贡献 Cleos

感谢你提交问题、改进和代码。

## 许可与版权

本项目有权授权的原创代码采用 Apache License 2.0，见根目录 LICENSE。
依其第 5 节，除非你明确说明其他安排，主动提交并拟并入本项目的原创贡献按 Apache-2.0 提供，不附加额外条款；已有的单独许可协议不因此被取代。
这不是版权转让，贡献者仍保留自己的版权，也没有要求贡献者授予自己不拥有的权利。

第三方代码或素材必须注明来源、改动情况和适用许可，并保留必要的版权 / LICENSE / NOTICE。不能通过勾选 PR 模板把第三方作品改为自己的 Apache 作品；兼容性有疑问时先在 PR 中说明。
本次迁移前的贡献按其原授权及明确取得的再授权确认处理，不把新模板追溯视为旧贡献者已经同意。

## 提交说明

说明问题、变化后的行为以及相关验证；涉及 UI 时可提供截图。
提交中不要放 API Key、密钥文件、私人备份或授权聊天截图。
有关依赖、素材和许可范围，见 THIRD_PARTY_NOTICES.md 和 docs/DEPENDENCY_LICENSES.md。

## 验证

根据改动运行相关检查；代码变更通常可用 `gradlew :app:testDebugUnitTest` 和 `gradlew :app:assembleRelease` 验证。
只改文档时检查内容与链接；改许可声明时运行 `python tools/sync_license_assets.py --check`，确保 App 内副本同步。

欢迎在二创介绍中注明「基于 Cleos 开发」，这是自愿社区倡议，具体见 README，不是 Apache-2.0 的新增条件。
