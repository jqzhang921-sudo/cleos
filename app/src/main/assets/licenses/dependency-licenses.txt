# 发布依赖许可清单（0.41.88 审计快照）

2026-10-13 根据本地 release lint 元数据及对应缓存 POM 核对。清单覆盖 compile 与 package 的并集，不声明所有依赖代码都被 R8 保留在 APK 中。未来更新依赖时需要重新解析、核对并更新此表。
许可名称及项目地址取自实际制品的 POM；有一项许可从父 POM 继承。它们是来源证据，不表示 Cleos 拥有这些依赖的版权。

| Maven 坐标 | 许可 | compile | package | POM 声明的项目来源 |
|---|---|---|---|---|
| androidx.activity:activity-compose:1.13.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.activity:activity-ktx:1.13.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.activity:activity:1.13.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.annotation:annotation-experimental:1.4.1 | Apache-2.0 | 是 | 否 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.annotation:annotation-experimental:1.5.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.annotation:annotation-jvm:1.10.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.appcompat:appcompat-resources:1.7.1 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.arch.core:core-common:2.2.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.arch.core:core-runtime:2.2.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.autofill:autofill:1.0.0 | Apache-2.0 | 否 | 是 | http://source.android.com |
| androidx.collection:collection-jvm:1.5.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.collection:collection-ktx:1.5.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.animation:animation-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.animation:animation-core-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.foundation:foundation-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.foundation:foundation-layout-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.material3:material3-android:1.4.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.material:material-icons-core-android:1.7.8 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.material:material-icons-extended-android:1.7.8 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.material:material-ripple-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.runtime:runtime-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.runtime:runtime-annotation-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.runtime:runtime-retain-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.runtime:runtime-saveable-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.ui:ui-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.ui:ui-geometry-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.ui:ui-graphics-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.ui:ui-text-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.ui:ui-tooling-preview-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.ui:ui-unit-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.compose.ui:ui-util-android:1.12.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.concurrent:concurrent-futures-ktx:1.1.0 | Apache-2.0 | 否 | 是 | http://source.android.com |
| androidx.concurrent:concurrent-futures:1.1.0 | Apache-2.0 | 否 | 是 | http://source.android.com |
| androidx.core:core-ktx:1.19.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.core:core-viewtree:1.0.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.core:core:1.19.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.customview:customview-poolingcontainer:1.0.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.datastore:datastore-android:1.2.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.datastore:datastore-core-android:1.2.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.datastore:datastore-core-okio-jvm:1.2.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.datastore:datastore-preferences-android:1.2.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.datastore:datastore-preferences-core-android:1.2.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.datastore:datastore-preferences-external-protobuf:1.2.1 | BSD-3-Clause | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.datastore:datastore-preferences-proto:1.2.1 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.emoji2:emoji2:1.4.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.exifinterface:exifinterface:1.4.2 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.graphics:graphics-path:1.0.1 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.interpolator:interpolator:1.0.0 | Apache-2.0 | 否 | 是 | http://source.android.com |
| androidx.lifecycle:lifecycle-common-java8:2.11.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-common-jvm:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-livedata-core:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-livedata:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-process:2.11.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-runtime-android:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-runtime-ktx-android:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-service:2.11.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-viewmodel-android:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-viewmodel-compose-android:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.11.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.navigation:navigation-common-android:2.10.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.navigation:navigation-compose-android:2.10.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.navigation:navigation-runtime-android:2.10.1 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.navigationevent:navigationevent-android:1.0.0 | Apache-2.0 | 是 | 否 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.navigationevent:navigationevent-android:1.1.2 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.navigationevent:navigationevent-compose-android:1.0.0 | Apache-2.0 | 是 | 否 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.navigationevent:navigationevent-compose-android:1.1.2 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.profileinstaller:profileinstaller:1.4.1 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.room:room-common-jvm:2.8.5 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.room:room-ktx:2.8.5 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.room:room-runtime-android:2.8.5 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.savedstate:savedstate-android:1.5.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.savedstate:savedstate-compose-android:1.5.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.savedstate:savedstate-ktx:1.5.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.sqlite:sqlite-android:2.6.2 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.sqlite:sqlite-framework-android:2.6.2 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.startup:startup-runtime:1.1.1 | Apache-2.0 | 是 | 否 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.startup:startup-runtime:1.2.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.tracing:tracing-ktx:1.2.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.tracing:tracing:1.2.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.vectordrawable:vectordrawable-animated:1.1.0 | Apache-2.0 | 否 | 是 | http://source.android.com |
| androidx.vectordrawable:vectordrawable:1.1.0 | Apache-2.0 | 否 | 是 | http://source.android.com |
| androidx.versionedparcelable:versionedparcelable:1.1.1 | Apache-2.0 | 是 | 是 | http://source.android.com |
| androidx.window:window-core-android:1.5.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.window:window:1.5.0 | Apache-2.0 | 否 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.work:work-runtime-ktx:2.12.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.work:work-runtime:2.12.0 | Apache-2.0 | 是 | 是 | https://cs.android.com/androidx/platform/frameworks/support |
| com.google.accompanist:accompanist-drawablepainter:0.37.3 | Apache-2.0 | 否 | 是 | https://github.com/google/accompanist/ |
| com.google.guava:listenablefuture:1.0 | Apache-2.0 | 是 | 是 | 未提供 SCM；可按坐标查询 Google Maven / Maven Central POM |
| com.squareup.okhttp3:okhttp-android:5.5.0 | Apache-2.0 | 是 | 是 | https://github.com/lysine-dev/okhttp |
| com.squareup.okio:okio-jvm:3.18.1 | Apache-2.0 | 是 | 是 | https://github.com/lysine-dev/okio/ |
| io.coil-kt.coil3:coil-android:3.6.3 | Apache-2.0 | 是 | 是 | https://github.com/coil-kt/coil |
| io.coil-kt.coil3:coil-compose-android:3.6.3 | Apache-2.0 | 是 | 是 | https://github.com/coil-kt/coil |
| io.coil-kt.coil3:coil-compose-core-android:3.6.3 | Apache-2.0 | 是 | 是 | https://github.com/coil-kt/coil |
| io.coil-kt.coil3:coil-core-android:3.6.3 | Apache-2.0 | 是 | 是 | https://github.com/coil-kt/coil |
| org.jetbrains.compose.animation:animation-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.animation:animation-core-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.foundation:foundation-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.foundation:foundation-layout-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.runtime:runtime-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.runtime:runtime-saveable-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.ui:ui-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.ui:ui-geometry-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.ui:ui-graphics-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.ui:ui-text-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.ui:ui-unit-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.compose.ui:ui-util-android:1.12.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/compose-multiplatform |
| org.jetbrains.kotlin:kotlin-stdlib:2.4.20 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/kotlin |
| org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0 | Apache-2.0 | 是 | 是 | https://github.com/Kotlin/kotlinx.coroutines |
| org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 | Apache-2.0 | 是 | 是 | https://github.com/Kotlin/kotlinx.coroutines |
| org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0 | Apache-2.0 | 是 | 是 | https://github.com/Kotlin/kotlinx.serialization |
| org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0 | Apache-2.0 | 是 | 是 | https://github.com/Kotlin/kotlinx.serialization |
| org.jetbrains:annotations:23.0.0 | Apache-2.0 | 是 | 是 | https://github.com/JetBrains/java-annotations |
| org.jspecify:jspecify:1.0.0 | Apache-2.0 | 是 | 是 | https://github.com/jspecify/jspecify/ |

## 许可原文与版权

- Apache-2.0 的标准原文见根目录 [LICENSE](../LICENSE)（安装包副本为 `assets/licenses/cleos-apache-2.0.txt`）。依赖自身的版权、专利、归属及适用 NOTICE 仍应保留，不能用 Cleos 署名替换。
- DataStore 重打包的 Protocol Buffers 为 BSD-3-Clause；实际制品的许可原文见 [datastore-protobuf.txt](../app/src/main/assets/licenses/datastore-protobuf.txt)，制品原有 META-INF 许可也应保留。
- `com.google.guava:listenablefuture:1.0` 的 Apache 许可从 `com.google.guava:guava-parent:26.0-android` 继承。
- `libandroidx.graphics.path.so` 与 `libdatastore_shared_counter.so` 来自 AndroidX 的原生组件；本次核对的制品 POM 与相关 AOSP 实现为 Apache-2.0，不能仅凭图形功能将其误标为其他项目的 BSD 代码。

## 测试与构建工具（不是发布包依赖清单的一部分）

| 内容 | 许可 | 范围 / 来源 |
|---|---|---|
| JUnit 4.13.2 | EPL-1.0 | 单元测试；https://www.eclipse.org/legal/epl-v10.html |
| Hamcrest Core 1.3 | BSD（New BSD / BSD-3-Clause） | 单元测试的传递依赖；父 POM 和制品内 LICENSE 已核对 |
| Gradle Wrapper | Apache-2.0 | 构建工具；保留现有脚本版权头及上游声明 |

AGP、Kotlin、KSP 等构建工具不因为在开发缓存中存在就成为安装包内容。若单独再发行这些工具或测试依赖，应一并核对并保留其发行包声明。
