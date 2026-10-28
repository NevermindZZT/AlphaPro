# AlphaPro 开发接续记录

更新：2026-10-28。此文件用于新会话接续，避免依赖即将删除的旧聊天记录。
以当前源码、构建报告和用户实际反馈为准；历史说明不是设备验证证据。

## 协作约定

- 用户真机验证并明确批准后才提交；未明确要求不推送。2026-10-28用户报告 R3“验证OK”，明确授权提交无线快门 R1/R2/R3、创建 v1.0.0 标签并发布 Release；本次推送属于该发布流程。后续改动仍须遵守正常验收/授权约定。
- UI 统一的是样式、配色层级、圆角、状态和组件，不是强制同尺寸。卡片内辅助按钮按场景使用 Compact，圆形快门160dp。
- 产品界面不出现开发验证徽标、重复模式标题、机身设置前置勾选或正常拍摄的额外确认。
- 没有自动化测试可以代替相机协议、实际触摸和 Android 视觉验证。不要宣称全面兼容或彻底解决。
- 签名文件/真实密码配置保持忽略，不读取、复述或提交凭据。

## 仓库与版本

- 工作区：`/home/zander/code/AlphaPro`；应用包名 `com.letter.alphapro`。
- 提交前基线：`d145a2f`（本地化），提交计数12；版本由提交次数自动生成。
- 用户验收包为 `1.0.0.12` R3。本次发布在下一次提交构建，versionName `1.0.0.13` / versionCode `100000013`，发布标签 `v1.0.0`；标签号与内部构建号是不同层级。实际提交哈希以该标签解析结果为准。
- 远程 origin 已配置，默认分支 main。本次按明确授权推送代码、标签和发布；后续不要因新会话接续自动提交/推送。
- 栈：Android/Kotlin/Jetpack Compose/Material3，minSdk26、compile/targetSdk37、JDK17。
- 支持 System / 简体中文 zh-Hans / English en，默认资源为英文。技术诊断不强制翻译。

## 当前功能与验证边界

### 已有基线

- BLE GPS：共享 GATT 逐操作队列，GPS5秒心跳；Wi-Fi凭据协商、PTP相册、独立预览、EXIF方向、日期分组等既有实现。
- 旧会话记录用户已验证单张拍照、半按 AF、录像开关。新会话未重新连接相机复测。
- README 的宣传措辞不能作为每台机型/每个功能均真机成功的证据。

### 本次已获验收的无线快门工作

- R1/R2 包含 controller、GATT、ViewModel、手势组件、滑动条、状态、资源、测试及导航保护的广泛修改；本会话启动时这些改动已经存在，不能回滚或误当作本轮纯 UI 改动。
- 单张：既有序列07 →150ms→09 →150ms→08 →50ms→06（payload前缀01）。AF按下07、释放06；录像0F →150ms→0E。
- 连拍：机身设连续驱动，App按住触发、松手停止，没有固定持续时间设置。
- 蓝牙 BULB 是完整按键开始/再次完整按键结束，不能套用物理快门按住/抬起协议；UI可提供长按和定时两种操作。
- BULB/间隔使用共享滑动条+精确数值对话框。秒数1..3600对数映射；次数1..999线性映射。
- 手势独立令牌；会话身份固定；过早松手不允许排队指令迟到触发；已接受 GATT 写入等待有界结算后再清理。
- 已知机身 inactive 反馈抑制多余 BULB 结束按键；无线中断/状态不明不能保证曝光已结束，必须提示检查机身。
- 前台序列：页面离开/锁屏停止手机序列并尝试同会话清理；不会因为离开页面切换既有录像。Stop/真实异常确认固定在滚动区外。
- FF02反馈可空，不等于照片保存证据；计时是手机计划，触发次数不是照片数。

### R3：本会话 UI 改动

仅修改 `RemoteShutterScreen.kt`、两种语言的 `strings_remote_product.xml` 和开发文档，未修改拍摄协议/共享组件。

- 固定顶部模式选择；内容最大宽560dp，宽屏水平居中。
- 同一主卡片包含机身说明、模式参数、160dp快门、运行状态、Compact AF/录像按钮；分隔线区分辅助操作。
- 单张/连拍按视口增加有上限的16–80dp顶部留白，不拉伸卡片或放大按钮；参数多的模式自然滚动。
- 计时放快门下方；留白计算补偿 pinned Stop 占位，避免开始时快门移动。保持单个 key(mode,manual) 手势子树。
- 安全说明分段放入低强调卡片；错误/释放不明使用展开的错误卡片；圆内停止文案缩短。
- 代理未采集本轮 Android 实屏截图：adb无法初始化默认 home；项目内未安装可用模拟器。用户于2026-10-28随后报告 R3“验证OK”，未逐项声明机型、字体或故障路径覆盖范围。
- 原有 UI 限制：亚秒级成功长按后没有持续显示的明确“已结束”结果标签；与本轮分组无关，后续按需要设计。

## 最近构建与交付

- Debug/Release 构建成功；188项测试报告均通过（app18 / Bluetooth122 / PTP48，部分任务复用未变结果）。
- 本地化检查：app341键 / Bluetooth5键；组件契约65处调用/12文件；`git diff --check`通过。
- 正式签名v2/v3验证通过。
- R3安装包：`app/build/outputs/apk/release/AlphaPro-1.0.0.12-remote-R3.apk`。
- SHA256：`5f05e174cfb6526178af11007f949a1ff700aea8e38502f331ac42885100d86f`。
- Gradle仅有现存废弃API、Android metrics目录警告，未阻塞构建。

## 构建命令

```sh
export JAVA_HOME="$PWD/.sdk/jdk-17"
export GRADLE_USER_HOME="$PWD/.gradle"
export ANDROID_USER_HOME="$PWD/.android"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest :core:bluetooth:testDebugUnitTest \
  :core:ptp:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon
python3 scripts/check-localization.py
node scripts/check-ui-components.mjs
git diff --check
```

## 发布与后续

1. 用户已验收 R3，并授权本次提交、`v1.0.0` 标签及正式 Release；以该标签构建新的正式 APK，而不是上传旧验收包。
2. 正式附件命名 `AlphaPro-v1.0.0.apk`，随附 `SHA256SUMS.txt`。发布说明见 `releases/v1.0.0.md`；完成情况以实际远端标签/Release为准。
3. 继续按用户反馈改进；不要把本次概括验收解释成所有机型、字体、手势和故障路径全覆盖。
4. 新功能或修复重新走验证/批准流程，不延用本次发布授权自动提交或推送。

协议细节/设备清单见 `REMOTE_CONTROL_REVIEW.md`；公共UI尺寸和配色见 `UI_COMPONENTS.md`；本地化见 `LOCALIZATION.md`。
