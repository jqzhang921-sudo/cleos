# Cleos 0.41.71

修复「从别的 app 搬过来」无法识别 Operit 原生 JSON 聊天归档的问题。

- 识别 `operit_chat_archive` v2 容器里的 `chats` 会话列表及 `baseMessage` 消息包装；兼容旧版直接导出的 `ChatHistory` 数组。
- 从 `sender` 区分用户与 AI，保留会话标题、正文及消息时间。按 `selectedVariantIndex` 保留用户当前选中的回复，其余备用回复 `variants` 不会被追加成实际聊天；系统与工具角色消息不导入为双方发言。
- 一个文件里的多段对话分别写入，不再只取第一段。全部归导入时选中的当前 TA；归档中的角色绑定名称不会自动生成角色卡或恢复模型、工具配置。
- 多会话聊天写入放在一个事务里，成功提示显示对话总数与消息总数；空归档、损坏结构及暂不支持的版本给出明确提示。
- 保留原有限制：文件最多 32 MB，每段对话最多保留最近 2,000 条，过长时提示截断。

依据 Operit 官方 `OperitChatArchive.kt` 和 `ChatMessage.kt` 的字段结构补齐。用户目前提供的是错误截图，尚未拿到该用户实际 JSON；回归样本均为不含个人信息的合成数据。

本版只做兼容修复，不增加 OCR、数据库迁移或依赖。

验证：529 项单元测试通过，0 失败、0 错误、0 跳过。新增 9 项回归覆盖多会话、旧版数组、双方角色与时间、已选回复、损坏结构、空归档、版本拒绝及导入结果提示。

Release 构建与 vital lint 通过。安装包 8,790,307 字节，沿用原 Release 签名，尚未在用户手机或实际反馈文件上复测。

APK SHA-256：`AB06D14A6E1C7435B0EAFE0661D3D7A5D0BD48EA262A251475BE105F0E1E9BB7`

官方结构依据：
- https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/data/model/OperitChatArchive.kt
- https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/data/model/ChatMessage.kt
- https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/data/repository/ChatHistoryManager.kt
