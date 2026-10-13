# 第三方许可、素材与服务说明

本文件说明当前源码和资源的许可范围，不把第三方作品改为 Cleos 原创作品。
根目录 [LICENSE](LICENSE) 是标准 Apache License 2.0；本文和 [NOTICE](NOTICE) 不增加禁止商用、禁止分发或强制宣传等附加条件。

## 随项目提供的代码与数据

### binaural-voice（MIT）

- 来源：[Saekisui/binaural-voice](https://github.com/Saekisui/binaural-voice)，本次核对的上游提交为 `1ea18165226b041d7dd04b818415f37c57fd2574`。
- 版权：Copyright (c) 2026 Saekisui。
- 涉及 `app/src/main/java/com/cleo/cleos/ai/Binaural.kt` 中从上游移植的语音移动行为及 `ai/EarVoice.kt` 的接入；这是代码移植，不仅是功能思路参考。
- Cleos 将相关行为改写为 Kotlin/Android 实现，并接入自身播放、缓存及资源格式。上游移植部分保留 MIT；Cleos 有权授权的原创修改按项目 Apache-2.0 提供。
- [MIT 许可全文](app/src/main/assets/licenses/binaural-voice.txt) 与版权声明继续随源码及安装包提供。MIT 允许商用和修改，但要求保留其版权与许可声明。

### Neumann KU100 HRIR（CC BY 4.0）

- 作者：J. M. Arend、A. Neidhardt、C. Pörschmann。
- 作品：*Spherical Near-Field (NF) HRIR Compilation of the Neumann KU100*，Zenodo，2020。
- 原始来源：[doi:10.5281/zenodo.4297951](https://doi.org/10.5281/zenodo.4297951)。
- 资源：`app/src/main/assets/hrir/ku100_near.bin`。它经 binaural-voice 的 `ku100_nearfield_circ360.npz` 转换而来；数据仍为 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)，不采用源码的 Apache 许可。
- 改动：保留水平面 0.25 / 0.5 / 0.75 米三档，分别应用 1.0 / 0.33 / 0.25 的距离增益，转换成 16 位整数；运行时按语音采样率重采样。
- 转换工具：`tools/convert_hrir.py`；详细来源及修改说明见 [HRIR README](app/src/main/assets/hrir/README.txt)。
- CC BY 4.0 允许商业使用，分发时保留作者、来源、许可链接及修改说明。语音算法的 MIT 与测量数据的 CC BY 是两项不同的授权。

### 通过包管理器引入的依赖

版本和许可见 [依赖清单](docs/DEPENDENCY_LICENSES.md)。当前 release compile/package 元数据的并集为 116 个 Maven 坐标：115 个声明 Apache-2.0，1 个声明 BSD-3-Clause。这是构建元数据清单，不等于 R8 后 APK 中实际保留的类库数量。

`androidx.datastore:datastore-preferences-external-protobuf:1.2.1` 是 BSD-3-Clause 的 Protocol Buffers 重打包依赖；随实际制品提供的 [许可原文](app/src/main/assets/licenses/datastore-protobuf.txt) 原样保留，也保留制品的 META-INF 声明。该原文未列具体版权年份和姓名，本文没有替上游猜写年份；发布时还应保留实际制品随附的其他版权声明。

AndroidX、Kotlin、Compose、Room、WorkManager、Coil、OkHttp、Okio 等各自版权继续归其权利人。Gradle Wrapper 保留原 Apache 版权头；测试依赖 JUnit 的 EPL-1.0 和 Hamcrest 的 BSD 许可与发布包依赖分开说明，不能把整个 Gradle 缓存都认定为 App 内容。

## 图片和其他素材

- `bubble_sleep.png`、`bubble_bunny.png`：2026-10-07 使用 OpenAI 图像生成工具为本项目生成的装饰；来源见 [BUBBLE_ASSETS.md](docs/BUBBLE_ASSETS.md)。在维护者有权授权的范围内按 Apache-2.0 提供，不声明为 CC0，不主张排他版权，也不保证已经取得一切可能涉及的第三方权利。
- `app/src/main/res/drawable-nodpi/tip_qr.png`：维护者提供的微信收款展示图。收款账户身份、微信标识及其他第三方权利不在 Apache 源码授权范围内。衍生应用建议换成自己的收款渠道或移除该入口，避免向用户误示收款人或官方关系。
- 原创几何绘制和界面代码按项目许可提供；系统字体、系统 Emoji、依赖提供的图标保持各自的来源和权利。
- 用户导入的头像、壁纸、表情、气泡图片以及备份中的私人内容不是项目源码授权的对象，使用和再分发需有相应权利。

## 运行时获取的内容和服务

源代码许可不授予第三方服务账户、商用套餐、数据或内容的权利。

- **公开图库**：Openverse 等来源返回的图片各自采用 CC0、PDM、CC BY、CC BY-SA 等标记；按具体图片的作者、来源、许可及更改情况处理。保留工具返回的归属信息，不能把搜到的图片统一改成 Apache。
- **天气**：[Open-Meteo 数据](https://open-meteo.com/en/license)采用 CC BY 4.0；其[免费接口条款](https://open-meteo.com/en/terms)与商业套餐另行适用。Apache 允许代码商用，不等于获得免费商业 API 服务资格。
- **一起听音乐**：Cleos 自己实现 Android 媒体会话联动、播放器歌词解析、LRCLIB 匹配及播放状态 UI，没有随 App 打包歌曲录音。歌曲、歌词、封面仍归各自权利人；[LRCLIB 软件](https://github.com/tranxuanthang/lrclib)的 MIT 不是对每首歌词的再发行许可。
- **RSS / Atom 资讯**：文章、照片和其他出版内容遵守具体发布者的许可；订阅地址可公开访问不代表可以任意转载。
- **模型、语音、定位、MCP 等接口**：各服务商的账户及使用条款独立适用。

## 功能思路和格式兼容

表情管理曾参考 [cute-chat-stickers](https://github.com/Anko3o/cute-chat-stickers) 的功能思路，没有纳入该项目的代码或数据；不能据此把其 CC BY-NC-SA 作品视为本项目素材。
Claude 的记忆功能提供过思路启发，Cleos 的分类、动作、上下文组织和 Room 持久层为自己的实现，没有引入 Anthropic SDK 或其 memory API 示例代码。
对 Operit 等导出格式以及播放器歌词字段的兼容，是自己的解析实现；格式互通不意味着把对方项目的代码并入 Cleos。今后若实际复制其代码或素材，应重新核对许可证并补充对应声明。

## 更新声明

新增或更新依赖、移植代码和素材时重新核对来源、版权与许可；保留必要的上游 LICENSE / NOTICE，不以本文件替代上游要求。
安装包内的 Apache 原文、NOTICE、第三方说明和依赖清单由 `tools/sync_license_assets.py` 同步，可在“关于 → 许可与版权 → 看许可全文”查看。
