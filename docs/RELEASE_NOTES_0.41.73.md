# Cleos 0.41.73 候选修复

状态：本地候选，未发布。版本号 0.41.73，versionCode 150；包含 0.41.72 的设定多选删除和工具拒绝后恢复修复。

## 工具参数 schema 兼容

- 针对截图中的 HTTP 400：`Unknown name "dependentRequired" at '***.tools[0].function_declarations[48].parameters': Cannot find field.`。
- 只在 JSON 错误响应明确指出工具参数 schema 不接受这个字段时，转换本次请求的 schema 并重试一次。没有这个错误的接口继续使用原始 schema。
- 保留全部工具、参数、required 和原有描述；把 dependentRequired 的字段依赖写入对应 schema 的描述。递归只遍历 schema 位置，不改变同名参数、默认值或示例数据。
- 重试失败时保留错误，不去掉工具，也不把参数格式拒绝判断成模型不支持工具。鉴权失败不触发此兼容重试。
- 调用 MCP 前，使用原始 inputSchema 检查根对象的 dependentRequired；缺少依赖参数时不执行该工具。此检查不是完整 JSON Schema 校验器，嵌套、引用和分支约束仍需要 MCP 服务端校验。
- 兼容转换限于当前请求，没有按模型名设置白名单，也没有保存接口永久兼容标记。未改变工具开关、权限或请求内容授权。

## 诊断与证据边界

- 用户提供接口 `https://zzwsapi.top/v1`、模型 `Claude-opus-4-6`、应用版本 0.41.69。
- 截图二明确是工具参数字段被拒绝，不能据此认定 Claude 不支持工具。dependentRequired 是合法的 JSON Schema 关键字；不同工具接口支持的 schema 子集可能不同。
- Cleos 请求使用 OpenAI-compatible tools[].function.parameters；截图中的 function_declarations 是另一种接口形态，提示转发层可能做了转换，但尚无该接口原始请求和响应，不能确定其内部路由，也不能确认具体第 49 个工具名称。
- 旧版代码曾将一部分 HTTP 400 直接作为功能拒绝降级；当前版本已收窄判断，并取消工具拒绝的应用运行期间缓存。截图一与截图二是否来自同一次模型配置尚待确认；本说明不宣称已经复现用户的完整故障链路。
- 涉及代码：ChatClient.stream / httpFailure、ChatCompatibility.rejectedFeature / diagnostic、ToolSchemaCompatibility、McpHub.call。
- 本次没有使用用户密钥，没有向用户的真实接口发请求，没有读取或输出私人聊天内容。

参考：
- JSON Schema 条件依赖：https://json-schema.org/understanding-json-schema/reference/conditionals#dependentRequired
- Google 工具调用 schema 子集：https://ai.google.dev/gemini-api/docs/function-calling

## 验证

- `:app:testDebugUnitTest :app:assembleRelease --offline` 成功，541 项单元测试全部通过，0 失败、0 错误、0 跳过；release vital lint 通过。
- 合成接口测试覆盖明确 schema 拒绝后保留全部工具重试、正常解析 tool call、再次拒绝时仅重试一次，以及鉴权失败/没有可转换 schema 时不重试。
- 转换测试覆盖原始 schema 不变、参数名/默认值/示例保留、嵌套 schema 和根对象依赖检查；包含 13,382 条设定的多选/批量删除测试。
- APK 签名验证通过，证书 SHA256 `19017bc504cabc4a40b407dba4d1239edd9854cdb7ace2fb451e06f9a0bd95be`，与既有包一致。
- APK：8,806,691 字节；SHA256 `63AA88EA0C75265599DC370448FAD928276525B055E978155906DFD5568AA290`。
- 交付位置：桌面 `Cleos-0.41.73.apk`；前一候选包 0.41.72 归档于桌面 `旧版本` 文件夹。
- 未进行用户真实接口或手机实测；源码保留本地，未提交、未推送 GitHub、未发布 Release。

建议复测：安装候选包后保持原接口、模型和 MCP 配置，先用一个不产生外部写入的工具验证。若仍有错误，记录完整错误提示和 MCP 服务名称，无需提供 Key 或聊天原文。
