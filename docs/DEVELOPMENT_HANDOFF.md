# AlphaPro 开发接续记录

更新：2026-10-29。此文件用于新会话接续，避免依赖已删除或即将删除的旧聊天记录。
以当前源码、构建报告和用户实际反馈为准；历史说明不是设备验证证据。

## 协作约定

- 用户真机验证并明确批准后才提交；未明确要求不推送。2026-10-28用户报告 R3“验证OK”，明确授权提交无线快门 R1/R2/R3、创建 v1.0.0 标签并发布 Release；本次推送属于该发布流程。后续改动仍须遵守正常验收/授权约定。
- UI 统一的是样式、配色层级、圆角、状态和组件，不是强制同尺寸。卡片内辅助按钮按场景使用 Compact，圆形快门160dp。
- 产品界面不出现开发验证徽标、重复模式标题、机身设置前置勾选或正常拍摄的额外确认。
- 没有自动化测试可以代替相机协议、实际触摸和 Android 视觉验证。不要宣称全面兼容或彻底解决。
- 签名文件/真实密码配置保持忽略，不读取、复述或提交凭据。

## 仓库与版本

- 工作区：`/home/zander/code/AlphaPro`；应用包名 `com.letter.alphapro`。
- 已发布基线：`0bab081`，提交计数13，正式标签 `v1.0.0`；版本由提交次数自动生成。
- 本次引导验收包为 `1.0.0.13` O5。2026-10-29用户确认OK并授权提交；本次仅本地提交，不推送、不改标签/Release。该提交为第14次，下次构建自动为 `1.0.0.14` / `100000014`；历史验收APK和现有正式Release均不因提交而替换。
- 远程 origin 已配置，默认分支 main。后续推送或发布须有新的明确授权，不延用过去的 v1.0.0 发布许可。
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

## 首次启动引导（O1/O2/O3，2026-10-28～29）

- 引导包含 GPS/对时、相册/HEIF、无线快门、Wi-Fi/FTP 四页，使用 Compose 动画；设置中可重复查看。
- O1、O2 均未解决用户报告的“首次引导约两三秒后关闭”。用户进一步确认：完全不触摸也退出，但设置回看正常。
- **撤回旧结论**：此前把权限弹窗返回/BackHandler 判定为根因、声称“彻底修复”均无日志或设备复现证据。源码和缓存字节码没有证实权限返回、动画或 Compose 回调被提前执行导致关闭。不能再把这个猜测当事实。
- 旧 `OnboardingStateTest` 只给局部布尔值赋值/断言常量，没有调用生产代码。因此 O1/O2 的193/194项报告虽通过，不能证明引导持久化或自动关闭问题已覆盖。这些无效用例已替换。

### O3 的结构性隔离与可观测性

- 首次引导移到 MainActivity 根流程，以 `FirstLaunchOnboarding` 在引导和主界面之间互斥选择，不再叠加于正在运行的主导航上。
- 引导期间不创建 CameraViewModel/主界面，不自动重连、不弹权限窗；用户明确跳过或末页开始后才请求权限，再创建主界面。
- 使用生产 `OnboardingSession` 和显式 `OnboardingExit` 事件；仅 SKIP 或末页 FINISH 可持久化一次完成；BACK/CLOSE_REPLAY、观察状态、重组、生命周期不完成引导。
- 新完成版本键 `onboarding_completed_revision` 不采信 O1/O2 可能误写的布尔键。首次覆盖 O3 会再显示一次，不需清空配对、相册或其他应用数据；明确完成后不再显示。
- 本地保留最多60条引导启动事件，含 O3 构建标识、Activity生命周期、退出原因/页码/应用内调用路径，不含位置、相机标识、媒体或凭据。设置 → 使用指南 → 引导启动记录可查看/复制，以便未复现问题时采集实际证据。
- Robolectric/Compose 回归使用真实动画页面、实际公共按钮和生产根流程：无触摸12秒、外部重组保持页面、Next不完成、明确Skip/Finish各一次、回看关闭不持久化；另有真实 SharedPreferences 测试和生产状态机测试。执行结果需以本轮测试报告为准，不能将本地模拟验证等同手机实测。
- 本轮实际完成：Release 构建成功、正式签名v2/v3通过；197项非Android运行时回归通过（app27/Bluetooth122/PTP48），包括9项生产OnboardingSession测试。双语387键、公共组件73处/13文件、差异检查通过。
- 新增的4项Compose UI用例和3项真实SharedPreferences用例已编译，但Android模拟运行时下载因系统盘满/写入失败而未完成，**不得计作通过或宣称已经验证无输入12秒**。停止下载后仅清理本轮3个不完整临时目录和新下载且未使用的原生测试运行时缓存；未删除既有源码、签名、APK或旧缓存。继续跑这些用例前需先有足够磁盘空间（建议至少1GB），执行完整 `:app:testDebugUnitTest`。
- O3包：`app/build/outputs/apk/release/AlphaPro-1.0.0.13-onboarding-O3.apk`；SHA256 `970cdcb6798f23f21504c5b9560651044f947c24b829df00518c03e89bac7b31`。已核验APK内确实包含O3标识、独立引导根组件和新完成版本键，避免误交付旧O2产物。
- 本机 adb 可运行但未连接 Android 设备，故无法复现用户手机的原始O2现象。**O3是隔离改造与诊断候选，不是已证实根因的彻底修复。**
- 建议覆盖安装O3后首次打开完全不触摸15秒；若仍退到主界面，复制设置内“引导启动记录”反馈，查看SKIP/FINISH事件或Activity重建的实际来源。无需清除应用数据。
- 保持未提交、未推送；发布 v1.0.0 不变。

## O4：独立引导 Activity（历史阶段，用户报告未通过，2026-10-29）

- 用户明确反馈 O3 验证不通过，并要求使用独立 Activity。旧 O3 根组件已删除；不再在 MainActivity 或 SettingsScreen 内渲染引导。
- 新增内部 `OnboardingActivity`（exported=false），保留原 MainActivity launcher 组件以兼容已有入口。Main 在权限请求、setContent 和相机 ViewModel 访问前检查持久化版本；未完成则启动首次引导、结束路由 Main 并 return。
- 首次流程任务栈预期为 `[Guide] -> [Main]`：明确 Skip/末页Finish 经生产 `OnboardingActivityFlow` 校验，在 IO 线程等待 checked commit 成功后才启动 Main 并结束 Guide；保存失败提示重试，重复事件不重复写入/跳转。
- 设置回看为普通同任务 `REPLAY` 启动：关闭、任意页系统返回、末页完成均只 finish 自身，不保存首次完成、不启动 Main，返回已有设置 Activity。上一页按钮仍可翻页。恢复旧设置栈时也先检查首次标记。
- 普通 Activity（非 singleTask/noHistory/独立进程）；首次路由用 CLEAR_TOP/SINGLE_TOP 去重，同模式新 Intent 不重置页码。首次请求遇到旧回看记录会创建新首次实例替换，不静默改回看模式。模式随 savedInstanceState 恢复，完成权威始终是持久化标记；生命周期回调仅记录，不自动导航。
- 引导 Activity 不创建 CameraViewModel、不请求权限、不承载主导航。Application 全局组件/既有后台服务仍存在，不能声称是进程或后台协议隔离。
- 完成版本提升为2，用于独立 Activity 流程迁移；覆盖 O4 会再显示一次，无需清空数据。这是明确迁移策略，不是认定 O3 的版本1必然损坏。
- 默认离线构建及204项JVM回归通过（app34/Bluetooth122/PTP48），含7项实际生产 ActivityFlow 决策测试与9项 Session 测试；正式签名v2/v3、本地化388键、组件73处/13文件、差异检查通过。已核验 APK 内独立 Activity 声明、launcher未变、旧 O3 根组件不在DEX中。
- Android/Compose 运行时测试已移至 `app/src/androidRuntimeTest/java`，改为显式 `-PandroidUiRegression=true` 才加入源码/依赖，默认单测不会解析或下载其大运行时。新的6项UI集成用例及迁移的3项Preferences用例本轮未执行；也不将其视为实际 Activity 栈/恢复测试。启用前需准备足够空间及准确运行时缓存，Gradle offline 本身不阻止 Robolectric 的独立SDK下载。
- O4包：`app/build/outputs/apk/release/AlphaPro-1.0.0.13-onboarding-O4.apk`；SHA256 `0f170f20b2447395645c7a14ef7143649d02bca9489101965e54e4decc7f553f`。
- 需用户真机检查：首次无操作停留、旋转/后台恢复、明确跳过/开始、Main返回不露出Guide、设置回看返回原设置；若仍异常，复制设置内“引导启动记录”，O4日志会区分 Main/Guide 和 finish/新Intent/保存事件。**仍未取得原自动关闭现象的根因证据，不宣称彻底修复或全路径实测成功。**
- 保持未提交、未推送，正式 v1.0.0 Release 不变。

## O5：无输入 SKIP 日志与自动跳广告兼容（已获用户验收，2026-10-29）

- 用户反馈 O4 仍失败并提供真机记录：`176320389 guide_create FIRST_RUN revision=1`，`176322761 activity_exit_requested SKIP page=0`（相隔2372ms），随后保存revision2、handoff Main、正常finish。这已证实关闭由 **SKIP 请求**驱动，不是 Activity 自行退出；不能据此说用户手动点击。
- 用户进一步明确确认启用了自动跳开屏广告服务，具体工具未确定。首次右上角原有“跳过/Skip”，回看只有关闭图标；结合仅首次失败、无触摸SKIP，**高度指向自动广告规则误点击**。这是目前有环境支持的强推测，尚缺服务关闭/排除列表A/B或同步输入栈证明具体注入者；不要把未获取的具体工具/系统bug当作事实。
- O5 将入口文案改为“稍后了解 / Later”，确认标题“现在结束功能引导？ / Leave the walkthrough?”、确认按钮“进入应用 / Enter app”，避免通用“跳过/Skip”广告匹配词。保留独立 Activity，不修改/关闭用户无障碍服务，不破坏正常无障碍操作。
- 原始SKIP只返回 `CONFIRM_SKIP` 并记录待确认页码，绝不持久化或进入Main。只有匹配当前请求的 `CONFIRMED_SKIP` 才可保存；未经请求、页码不符、取消后确认、重复事件均拒绝；确认未决时FINISH等其他退出也不进入Main。取消/返回只留在引导。末页正常开始仍可完成。
- Activity中在任何协程/IO切换前记录 `ui_exit_sync`；Screen原始动作也同步记录，保留最多48帧含框架类的方法栈，避免旧日志只剩协程入口。额外观察本引导Activity/确认对话框的触摸阶段、控制键事件、焦点、无障碍启用布尔值；不记录字符、坐标、服务名称、相机/位置/凭据。日志上限80条/64K字符。
- 完成规则版本提升为3，覆盖O5重新验证一次；不会清空配对和其他应用数据。新确认窗口不恢复为已确认状态，任何渲染/关闭/动画都不确认。
- 默认JVM测试新增对原始SKIP连续400次不写入/不进Main、缺失/错误/取消确认、保存失败重试、确认待决时拒绝其他完成路径等生产逻辑回归。Android/Compose/Preferences可选用例仍仅在 `-PandroidUiRegression=true` 时启用，不把未执行的运行时测试计为通过。
- 请用户保留自动跳广告服务开启，覆盖O5后首次不触摸等待15秒；如果仍异常或自动出现确认框，复制“引导启动记录”，或在该工具里仅排除AlphaPro做对照。不要要求全局关闭正常无障碍功能作为唯一解决方案。
- O5最终离线Release构建和正式签名v2/v3通过，213项默认JVM回归通过（app43/Bluetooth122/PTP48），其中25项生产Session/ActivityFlow测试；13项Android/Compose/Preferences可选用例未运行，不计通过。双语392键、组件75处/14文件、差异检查通过。
- 已直接读取最终APK资源验证中英文入口为“稍后了解/Later”，确认动作为“进入应用/Enter app”，并检查包含O5标识、同步入口诊断及确认组件，避免交付旧文案包。
- O5包：`app/build/outputs/apk/release/AlphaPro-1.0.0.13-onboarding-O5.apk`；SHA256 `e62174eecac907147d1efe602e9b119adee50fe8ecc63cb43e84a8334ef42c03`。
- 用户于2026-10-29回复“OK，提交改动”，概括验收O5并明确授权本地提交。该反馈不等于全部Android版本、返回栈恢复或无障碍路径逐项覆盖；13项可选运行时用例仍未执行，具体注入服务身份仍未由同步调用栈单独证实。
- 本次不推送、不创建/修改标签、不更新正式Release；后续新改动继续按验证和授权流程处理。

协议细节/设备清单见 `REMOTE_CONTROL_REVIEW.md`；公共UI尺寸和配色见 `UI_COMPONENTS.md`；本地化见 `LOCALIZATION.md`。
