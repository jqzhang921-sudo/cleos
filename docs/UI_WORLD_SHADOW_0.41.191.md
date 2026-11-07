# 世界空间卡片柔和阴影 · 0.41.191

2026-11-06。用户反馈：滑动书房卡片时，深色区域突然出现，希望自然、柔和。

本次仅修改 WorldScreen.kt 的 SpaceDeck 及正式版本号（268 / 0.41.191）：

- 去掉空间卡片 clickable 的默认 indication，保留按钮语义、点击进入和拖动。自有离线 AVD 复现了按下即覆盖卡片的矩形深色反馈；在同场景/同坐标按住对比，原按压层造成卡片区域 RGB 平均绝对差约19.88，新版为0，证明突变按压覆盖层已移除，不代表所有动态帧零变化。
- 使用 CompositingStrategy.ModulateAlpha，避免卡片从alpha=1进入略小于1的滑动态时，自动创建有边界的合成缓冲，裁切原本延伸到卡片外的阴影。玻璃内层仍使用原有着色器，只调整空间卡片的外层合成策略。官方说明：https://developer.android.com/develop/ui/compose/graphics/draw/modifiers
- 空间卡片沿用用户的卡片样式，阴影透明度乘0.8、扩散半径加6dp；暗色无阴影仍保持0。保留原有旋转、缩放、布局、文字和点击入口，不给阴影添加开关式触发。

验证：既有WorldDepthTest五项通过，正式构建及原签名/生产入口核验通过；自有AVD显式DOWN→MOVE40/100/180→UP持按、拖动、松手截图已实际查看；四个空间页签、立体效果开关、点击书房及返回通过；原资料库、设置字节及图片哈希保持，测试AVD已关闭。没有新增测试资料或调用真实模型。

手机：按已有授权，在确认无未保存编辑/活动任务后install -r保留数据更新到192.168.5.17:43657；原签名与首次安装时间保持，安装APK读回匹配。未抢占用户其他应用，也未把模拟器截图当作手机观感确认。用户可正常使用时体验阴影强度。

最终SHA-256：935CA505A8947020AF47287800E4CF415873FD6A0E85F818B2708254AE4630E9。

证据：outputs/Cleos-0.41.191-world-shadow 中 RELEASE.json、JVM-RESULTS.json、PIXEL-RESULTS.json、DECK-FLOW-RESULTS.json、PRESERVATION-RESULTS.json、PHONE-INSTALL-RESULTS.json 与截图。
