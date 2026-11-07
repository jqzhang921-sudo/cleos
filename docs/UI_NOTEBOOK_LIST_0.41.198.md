# 随手本回到卡片列表 · 0.41.198

2026-11-06，版本码 275。

用户明确选择：外层恢复之前的单列卡片列表，点击后用上下滚动阅读；等有更好的设计以后再考虑其他形态。本轮不继续扩展叠卡或书架变体。

从已保存的 .194 WorldNotebook.kt 恢复目录卡片、日期分段和按顺序的章节列表，保留当前 .197 NotebookScrollReader 的主题玻璃、可选择文字、字符续读及上一章/下一章。移除目录叠卡、两列平铺和模式切换入口；保留搜索、分类、连载归册与整理、来源和删除确认。原型源码留作后续参考，不再从当前目录调用；旧展示状态不影响列表。数据库 66、备份 29、原文及所有者过滤保持。

完整 JVM 1,472 项通过，失败、错误和跳过为 0。正式构建及 lintVital、原签名、版本和生产 Manifest 无 QA 入口核验通过。

8 组断网原生检查：主目录/章节目录单列且无叠卡切换；列表点入上下阅读并返回续读；章节顺序；360dp 字体 1.4 的玻璃正文与锚点；整理入口及取消；搜索；TA 隔离；清理、保留和安装 APK 读回。list-main.png、list-chapters.png、list-reader.png、list-reader-large.png 和 list-search.png 留有界面证据。

自有 AVD 原 45 表 133 行逐行保持，真实设置文件、图片、序列和锚点状态恢复；7 篇合成写作与 1 本合成连载已清理，自有模拟器已关闭。没有真实模型请求，没有 FPS 基准。

已按已有授权核对原 PKT110、同签名和无活动编辑/任务后 install -r 更新到 0.41.198 / 275。首次安装时间保持 2026-09-23 10:52:18，安装 APK 读回一致；没有代为打开 App 或操作私人内容。

SHA-256：D34CF4A264DB4653620915EA94A4457A8FFE6F0ABDFE99FA26EFEE24E0E66DF4。

证书 SHA-256：19017bc504cabc4a40b407dba4d1239edd9854cdb7ace2fb451e06f9a0bd95be。

包与证据：C:/Users/777i/Documents/Codex/2026-10-05/ke/outputs/Cleos-notebook-list。RELEASE.json、JVM-RESULTS.json、NATIVE-CHECKS.json、SOURCE-STAMPS.json、LOCAL-ACCEPTANCE.json、PRESERVATION-RESULTS.json、PHONE-INSTALL-RESULTS.json。
