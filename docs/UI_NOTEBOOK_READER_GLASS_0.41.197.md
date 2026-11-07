# 随手本正文玻璃 · 0.41.197

2026-11-06，版本码 274。用户要求正文阅读卡片与外层玻璃一致。

仅改 NotebookScrollReader.kt 的容器材质及版本号：去掉正文卡片局部强制的 0.86 白底、1dp 折射上限和色散压低，恢复主题 p.card，并沿用外层轻阴影。保留原有对比度下限、文字颜色、字号、行距、滚动和字符锚点逻辑。数据库 66、备份 29 和全部原文不变。

正式构建及 lintVital 通过。样式小改未新增或重跑完整 JVM 套件，未将上一轮 1,472 项结果冒充本轮重跑。5 组断网原生检查通过：完整中文/emoji/空行、滚动后返回续读、360dp 字体 1.4 重排、深色玻璃可读性及最终清理/安装 APK 读回。reader-glass-normal.png、reader-glass-scrolled.png、reader-glass-large.png、reader-glass-dark.png 已目视检查。

自有模拟器原 45 表 133 行、实际设置字节、图片哈希、序列和原锚点状态保持，7 篇合成写作与 1 本合成连载已清理，原系统夜间模式已恢复；模拟器已关闭。没有真实模型请求，没有 FPS 测量。

已核对原手机身份、原签名、无编辑/活动任务，按已有授权 install -r 更新至 0.41.197 / 274。首次安装时间保持 2026-09-23 10:52:18，安装 APK 读回匹配。没有代为打开 App 或查看私人正文。

APK SHA-256：63D088009DDC20178CA0A1970EDB0DBA74CF2DCB67A62EBF4928E7A7F728A9DC。

证书 SHA-256：19017bc504cabc4a40b407dba4d1239edd9854cdb7ace2fb451e06f9a0bd95be。

包与证据：C:/Users/777i/Documents/Codex/2026-10-05/ke/outputs/Cleos-notebook-reader-glass。RELEASE.json、SOURCE-STAMPS.json、NATIVE-CHECKS.json、PRESERVATION-RESULTS.json、LOCAL-ACCEPTANCE.json、PHONE-INSTALL-RESULTS.json。
