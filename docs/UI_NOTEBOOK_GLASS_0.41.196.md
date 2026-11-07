# 随手本叠卡：收束切换与主题玻璃

2026-11-06，0.41.196 / 273。

用户反馈拖动时卡片像飞出窗口，左侧出现矩形截边，材质偏白。本轮仅修改 NotebookDeck.kt 及版本号，正文阅读、归册关系和资料格式保持。

移除绘制卡片的横向分页视口，改用有界横向拖动状态与原位层叠过渡。完整手势只切换一张，短拖回弹；首尾不循环，可反向回来。出卡位移最多 12dp、倾斜最多 1.4°，卡片渐退后露出下一张；为圆角和阴影保留空间。只有停稳的前卡可以点开，后卡隐藏语义与文字，避免透过玻璃出现双层正文。保留横向无障碍滚动动作及目录位置回调。

卡片恢复当前主题 p.card 的透明度、模糊、折射和色散，去掉局部强制 0.88 白底及折射/色散压低。仅轻化阴影，平铺卡片同步恢复玻璃样式。系统减少动态效果时取消倾斜，仍可切换。章节正文保持上一版的上下阅读。

## 验证

完整 JVM 1,472 项通过，失败、错误和跳过为 0。正式构建/lintVital、原签名、版本及生产 Manifest 无 QA 入口核验通过。

11 组断网原生检查：短拖回弹、完整手势一张、反向、末端不循环、点入连载/竖向章节、拖动中四角/阴影目视检查、360dp 字体 1.4 平铺、减少动态效果、正文和章节顺序保留、清理及 APK 读回、原资料与设置在自有模拟器重启后保留。glass-rest.png、glass-drag-half.png 与 glass-overview-large.png 留有真实像素证据。没有 FPS 基准或真人手机动效观感验收。

本轮自有 AVD 开机发现 settings.preferences_pb 为 0 字节；在种入测试资料前，确认数据库与上一轮已验收的 45 表 133 行完全一致，将设置从上一轮已验证的 1,294 字节快照恢复，SHA 为 ecc58870a8b99f07025006865dabd96b6c51438d8c00070c622b2d120cafd5ca。没有修改用户手机设置。清理后增加 sync，并重启自有 AVD，复验设置字节和全部原表一致；未对根因作推断。见 AVD-SETTINGS-RECOVERY.json、AVD-REBOOT-PRESERVATION.json。7 篇合成写作与 1 本合成连载已清理，自有模拟器已关闭。

## 手机与包

按已有授权核对原 PKT110、同签名及无活动编辑/任务后 install -r 更新到 0.41.196 / 273。首次安装时间仍为 2026-09-23 10:52:18，安装 APK 读回匹配。未代为启动 App、发消息、请求模型或开启权限。

SHA-256：04B1F55442B87FD7DC308C3C34B2501534DAB94C5C9DEAE4338806F2F6C94525。

原签名 SHA-256：19017bc504cabc4a40b407dba4d1239edd9854cdb7ace2fb451e06f9a0bd95be。

证据和正式 APK：C:/Users/777i/Documents/Codex/2026-10-05/ke/outputs/Cleos-notebook-glass。RELEASE.json、JVM-RESULTS.json、NATIVE-CHECKS.json、SOURCE-STAMPS.json、LOCAL-ACCEPTANCE.json、PRESERVATION-RESULTS.json、PHONE-INSTALL-RESULTS.json 及截图。
