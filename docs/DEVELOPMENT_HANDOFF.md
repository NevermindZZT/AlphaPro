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
- O5验收包为 `1.0.0.13`，已本地提交为第14次 `7d60153`。B1后台设置引导验收包为 `1.0.0.14`，2026-10-29用户确认OK并授权将其与前一轮调试清理一并本地提交；该提交为第15次，下次构建自动 `1.0.0.15` / `100000015`。本次不推送、不改标签/Release，历史验收APK不因提交而替换。
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

## 引导调试清理（2026-10-29，随 B1 获验收并本地提交）

- 用户在验收并本地提交O5（`7d60153`）后，要求移除临时“引导启动记录”。历史O1～O5段落保留排查经过，但其中的诊断入口/日志采集不再属于当前产品。
- 删除设置记录入口、复制报告对话框和3个双语诊断资源；删除 `OnboardingTrace` 存储/Logcat/调用栈实现及所有调用。
- 删除引导Activity的触摸、键盘、焦点和无障碍状态观察器，以及只为记录而覆盖的生命周期方法；对话框恢复为默认Material输入，不再挂诊断pointer/key修饰器。删除Screen和生产状态机中的追踪参数，测试改以真实状态和写入断言验证。
- 保留独立引导Activity、四页动画、设置回看、首次/回看返回栈、确认防护、耐久完成写入、“稍后了解/Later”文案和全部业务分支。完成规则版本仍为3，清理版不会要求已完成用户重新看引导。
- 新代码不再访问旧诊断偏好，也未清空设备的配对/应用数据；不以额外的永久调试兼容钩子保留已删除实现。
- 验证完成：离线Release构建、正式签名v2/v3、213项默认JVM回归（app43/Bluetooth122/PTP48）、389键本地化和72处/14文件公共组件契约均通过。13项可选Android运行时用例未运行。
- 直接检查成品APK的DEX/资源，确认无引导日志类、记录存储键、日志标识、输入埋点及“引导启动记录”资源，且独立引导、确认组件仍在。
- 清理版安装包：`app/build/outputs/apk/release/AlphaPro-1.0.0.14-onboarding-clean.apk`，SHA256 `405366cbbb6d53b759a7858eaf9b3a85e27575fbb10e4b2385ba19ba2f1bf090`。版本号来自已有第14次提交，不是新增提交或发布。
- 最初只清理并交付验证；用户现确认B1“OK，提交改动”，授权本轮清理与B1一并本地提交，不推送、不改标签/Release。

## 后台持续定位设置引导 B1（2026-10-29，已获用户验收）

- 用户要求增加“始终允许定位”和“关闭电池优化”两项设置引导。已在首次/回看引导GPS页和设置 → 定位与轨迹加入同一可复用卡片；完成规则版本3和四页结构不变，已完成用户不强制重看。
- 实际读取前台/后台定位权限与PowerManager豁免；返回授权/系统页面、页面恢复时重查，不以返回码或点击过按钮作为已完成，不持久化状态、不恢复引导日志。
- 先申请前台精确+近似定位；API26～28无单独后台权限，API29可单独请求后台权限，API30+打开公开应用信息并指导权限 → 位置 → 系统本地化“始终允许”。保留应用权限设置按钮处理永久拒绝。
- 电池优化使用公开系统列表，提示必要时选择所有应用并将AlphaPro设为不优化；不直接请求受政策限制的豁免弹窗，不关闭全局省电模式，缺失入口降级到应用信息。厂商附加后台限制仍需用户检查，不承诺绝对保活。
- Main尊重已获近似定位的选择，不自动重复精确授权；缺少前台定位时不强行启动定位前台服务，提示授权后重开GPS或重连。未改BLE/GPS心跳协议和调度。
- 离线Release构建、正式签名v2/v3、219项默认JVM结果通过（app49/Bluetooth122/PTP48），其中新增6项纯政策测试覆盖API26/28/29/30/36/37等77组状态；414键本地化、75处/15文件组件契约及差异检查通过。用户随后概括确认OK，未声明逐个版本/厂商/系统设置路径覆盖；13项可选Android运行时用例未运行。
- 已检查APK含新卡片/系统状态读取和优化列表动作，且引导调试实现/资源仍已移除。包为 `app/build/outputs/apk/release/AlphaPro-1.0.0.14-background-setup-B1.apk`，SHA256 `b32196477c155705da213e585c3f82bb1e20640a616d640f0af9760b9d0f5932`。
- 用户于2026-10-29回复“OK，提交改动”，授权B1与前轮调试清理一并本地提交；未授权推送或修改标签/正式Release。详细规则、官方依据和真机检查见 `BACKGROUND_SYNC_SETUP.md`。

## 无线快门交互简化 R4（方案一：标准遥控 + 定时拍摄，2026-10-29）

- 用户指出原有单张/连拍/B门/间隔四分段中，单张与连拍差异本质在相机机身驱动设置，App 层面除 B 门定时与间隔拍摄有参数控制外，均为统一的快门、对焦和录像操作，要求进行界面与操作简化。
- 实施方案一（双模式收敛：标准遥控 + 定时拍摄）：
  - 顶部分段器收敛为 2 选 1：`标准遥控` 与 `定时拍摄`。
  - **标准遥控**：默认即开即用模式。
    - 统一快门键（160dp `AlphaHoldShutterButton` + `SonyRemoteShutterController.captureUnified`）：
      - 纯图标居中展示（64dp 相机图标）：移除了快门按钮内部的文本标签，彻底消除文字挤压导致的图标偏心问题；
      - 状态提示外置：长按连拍时的“松手停止”与“正在停止”提示移至快门下方独立展示，不干扰快门圆盘几何形心；
      - 短按（< 300ms 抬手）：发送 07（半按），等待 150ms，发送 09（全按），保持至少 150ms 脉冲宽度，发送 08/06 释放。序列与已验证的 `takePhoto()` 字节和时序 100% 一致，保底拍出 1 张。
      - 长按（>= 300ms 持续按住）：发送 07/150ms/09，保持全按状态直到手指离开屏幕，抬手发送 08/06 优雅释放。若相机处于连拍驱动模式，实现按住持续连拍、松手即止。
      - 无障碍优化：TalkBack 双击或辅助点击通过 `onTap` 直接触发单张拍照。
    - 大尺寸对称辅助控制栏：对焦锁定（AF-ON）与录像切换（REC）按钮由 Compact(32dp) 加大为 Large(48dp 最小高度、20dp 图标、各占 50% 等宽排布），单手握持时大拇指极易点击。
    - 居中紧凑布局：卡片自适应视口高度居中，消除过去单张/连拍下的空旷留白。
  - **定时拍摄**：按需自动化任务模式。
    - 快门同样采用 64dp 纯图标居中展示，启动提示与运行计时钟位于按钮下方。
    - 二级子模式：`[ B 门长曝光 | 间隔拍摄 ]`。
    - B 门长曝光：单对数滑块配置曝光时长（1s..3600s，带精准输入对话框），点击快门开始/停止倒计时，等宽大字号时钟显示已曝光与剩余时间。
    - 间隔拍摄：双滑块配置拍摄间隔（1s..3600s 对数）与触发次数（1..999 线性），显示总进度与下次触发倒计时。
    - 辅助控制、前台指导卡片、异常警告卡片完整保留。
  - 保持底层安全规范：会话锁定（Session Pinning）、意外断开/退出非取消安全释放（NonCancellable cleanup）、固定底部 Stop 停止栏、返回键防误触、前台保持屏幕常亮（Keep Screen On）完全保留。
- 质量与回归验证：
  - 离线 Release 构建与正式签名通过（v2/v3 签名有效，无警告性报错）。
  - 全套 222 项默认 JVM 单元测试全部通过（app 50，core:bluetooth 124，core:ptp 48）。
  - 新增 `SonyRemoteHoldInteractionTest` 中关于 `captureUnified` 快速短按（保底 7,9,8,6 单张时序）与持续长按（保持 7,9 直至释放）的独立时序测试。
  - 新增 `RemoteShootingUiStateTest` 关于单张与连拍状态机运行态的回归测试。
  - 双语本地化检查通过（420 键对齐，`python3 scripts/check-localization.py`）。
  - 公共组件契约通过（74 处调用，15 个文件，`node scripts/check-ui-components.mjs`）。
  - Git diff 格式与代码空白检查无误（`git diff --check`）。
- 测试包产出：
  - 包路径：`app/build/outputs/apk/release/AlphaPro-1.0.0.15-simplified-remote-R4.apk`
  - 大小：12,784,630 字节
  - SHA256：`a39d9e73db04f5cdb9d515c0a61b3c2495a19abce64576e22c0a0626dcfc9a3f`
- 本地未提交、未推送，保持原有 Release 和标签不变，等待用户体验与进一步指示。

## 使用引导预测性返回手势适配（2026-10-29）

- 用户在 Android 16 设备实测发现，主界面（MainActivity）与设置界面（SettingsActivity）已原生生效预测性返回手势，但使用引导界面（OnboardingActivity）此前未生效。
- 根因分析：
  1. 清单文件 `<application>` 未显式声明 `android:enableOnBackInvokedCallback="true"`；
  2. `OnboardingScreen.kt` 此前无条件常驻启用 `BackHandler`（`enabled = true`），且在设置回看（Replay）模式及第 0 页退出时强行拦截系统返回并在松手后异步调用 `finish()`，导致系统手势框架直接抑制系统的 Cross-Activity 与 Back-to-Home 预测性返回动画。
- 适配改造：
  1. 在 `AndroidManifest.xml` 的 `<application>` 节点显式配置 `android:enableOnBackInvokedCallback="true"`，全局开启 Android 13/14+ 系统预测性返回手势；
  2. 重构 `OnboardingScreen.kt` 的返回手势处理：
     - 将无条件 `BackHandler` 升级为 `PredictiveBackHandler`，且仅在 `actionsEnabled && !isReplay && pagerState.currentPage > 0`（即首次启动且非第一页）时启用；
     - **首次引导多页翻页**：当处于 Page 1..3 时，接入 `PredictiveBackHandler` 的进度流，手势滑动过程中通过 `graphicsLayer` 呈现微幅缩放（~8%）与透明度渐变，手势完成时滚动至上一页，手势取消时平滑复原；
     - **设置回看（Replay 退出）**：`PredictiveBackHandler` 处于禁用状态，由系统原生接管返回手势，完整展现系统级 **Cross-Activity 预测性返回动画**（随侧滑手指逐渐将当前引导界面缩小移出，底层平滑露出 `SettingsActivity`）；
     - **首次引导第 0 页（Page 0 退出）**：`PredictiveBackHandler` 处于禁用状态，由系统原生接管返回手势，完整展现系统级 **Back-to-Home 预测性返回动画**（随侧滑手指平滑缩小至桌面壁纸）。
- 验证情况：
  - 离线 Release 构建通过，`apksigner` 验证 v2/v3 签名有效；
  - 222 项 JVM 单元测试全部通过（Bluetooth 124, App 50, PTP 48）；
  - 420 键本地化契约通过；74 处公共组件契约通过；代码空白检查通过；
  - 测试包产出：`app/build/outputs/apk/release/AlphaPro-1.0.0.16-predictive-back.apk`（大小 12,786,521 字节，SHA256 `743172246728d67ab313b195bcc343b526003c9612d6258e33f60724fbb4cfd3`）。
- 改动保持本地工作区未提交状态，等待用户体验与进一步确认。

## GPX 公共目录权限崩溃修复 G1（2026-10-29，已获用户验收）

- 用户提供 Android 运行日志，连续四次 `FileNotFoundException / EACCES` 均来自 `GpxTrackLogger.exportToGpx → flush → LocationSenderForegroundService`，目标为公共 Documents 下当日 GPX。该堆栈不是预测性返回崩溃；不据此归咎引导 Activity。
- 原 `publicDocs.exists() || publicDocs.mkdirs()` 仅能说明目录存在/创建成功，不能证明 Android 分区存储允许打开该文件。心跳子协程未捕获异常；根部 SupervisorJob 不能隔离普通 activeJob 内的子协程失败。onDestroy 和设置分享也存在未保护的保存调用。
- 自动记录改为 `filesDir/tracks`，不新增存储权限，不申请所有文件访问；旧 Documents 与旧 app-external 轨迹不删除、不自动强行迁移。私有轨迹随卸载删除，请先分享导出。
- 周期保存从 5 秒 BLE 心跳中分离，每 60 秒尝试保存；保存边界只捕获 IOException/SecurityException，以 Result 和 saveFailed 状态反映失败，保留内存点、允许后续重试，不抛到服务协程。通知与设置显示失败。GPS 包协议和心跳周期未改。
- 停服先取消/等待采集任务，再在 IO 协程尽力保存；不在主线程直接写文件。进程被杀仍可能丢失上次保存后的点（约一分钟），不宣称绝对耐久或自动恢复全部未保存内容。
- Logger 使用独立写锁与点快照，同目录 UTF-8 临时文件写完、flush/fsync 后原子替换；失败不截断上一份成功快照。会话 UUID 和固定会话起始日期避免同日重启覆写旧轨迹，以及跨午夜重复生成全会话快照；这是每进程会话轨迹，不是合并的整日轨迹。运行期间点仍留在内存，长期内存/历史管理不是本轮改造范围。
- 分享前保存改为 suspend/IO + Result；失败不静默退回旧轨迹、不吞异常；无轨迹/分享失败有双语提示。FileProvider 新增仅 `tracks/` 的 files-path，使用 GPX MIME、只读 URI grant 和 ClipData；设置文案改为私有存储及“当前／最近轨迹”，不再误称公共目录可见或整日导出。接收方稍后打开的仍是当前会话最近原子快照，不承诺点击时不可变副本。
- 同时修复服务无条件记录的问题：GPX 开关关闭后不新增轨迹点，但不影响向相机发送 GPS；已记录点不删除，仍可保存。
- 新增 core:location 的 13 项纯 JVM 回归：GPX XML/UTC、会话快照/重启保护、目录失败、SecurityException、写到中途失败保护旧文件、内存保留/恢复、分享不回退旧文件及并发采集/保存。它们不代表 Android FileProvider/设备分区存储路径实测。
- 最终离线 Release 构建成功，正式 v2/v3 签名校验通过；235 项默认 JVM 结果全部通过（app50/Bluetooth124/PTP48/location13），423 键本地化、74处/15文件公共组件和 diff 空白检查通过。直接检查优化后 APK 的 FileProvider XML，含窄范围 `files-path/tracks/`，DEX 含安全保存方法，无旧 `AlphaPro/Tracks` 自动存储路径，不含所有文件访问权限。
- G1 测试包：`app/build/outputs/apk/release/AlphaPro-1.0.0.17-gpx-storage-G1.apk`，12,793,960 字节；SHA256 `0a3da9f0e28d7dc89c3c5444db68a2b58eecbc56ea7d58d2fdc2a22ae834989e`。
- 未连接 Android 真机进行本轮验证，13 项可选 Android 运行时测试未启用。请在原崩溃设备连续记录至少3分钟（跨多次60秒保存）、前后台切换、停止/重启GPS服务，并从设置分享GPX给接收应用检查可读；关闭GPX应不再新增点但相机GPS继续同步。无需清除应用数据，不要求重新授予公共存储权限。
- 用户随后回复“OK, 提交改动”，概括验收 G1 并明确授权本轮本地提交；不视为上述设备、系统分享或失败路径已逐项覆盖。未授权推送、修改标签或更新正式 Release。

## HIF 导出 JNI 崩溃修复 H1（2026-10-29，已获用户提交确认）

- 用户日志为 `UnsatisfiedLinkError`：默认构建此前关闭 NDK，APK 不含 alpha_heif.so；加载失败被忽略，随后无条件调用 external decodeFile。ViewModel 的 Exception 捕获不覆盖 LinkageError。不能诊断为用户照片损坏，也不能将系统 HEIF 解码当作 Sony 10-bit 4:2:2 的可靠替代。
- 已补齐 NDK26.1.10909125/CMake3.22.1；最初磁盘不足且 NDK 仅有旧安装残留，后续可用空间回升后安装、真实交叉编译成功。默认启用原生构建，只有明确 `-PnativeHeif=false` 才省略解码器；修复 JNI 旧包名转发到未声明函数的问题，native* 实现直接导出当前包名方法，并补 consumer keep 规则。
- 所有原生解码/EXIF入口经 NativeHeifRuntime，缺库/符号缺失转可恢复 IOException，已知解码分配OOM也转错误而不自动重试；不广泛吞掉其他VM错误/取消。不可用时下载前拦截，导出面板默认原始HIF并禁用JPEG，提供双语提示；不偷偷将用户请求的JPEG替换成HIF。
- JPEG转换串行，传入协程取消检查，缓存文件取消交接安全清理。补上原先缺失的相册发布：只有JPEG实际写入并发布MediaStore后才提示成功；失败删除未完成条目。不再把仅生成缓存文件当作导出完成。
- 三ABI arm64-v8a/armeabi-v7a/x86_64 全部原生编译成功，最终默认构建APK含9个.so（alpha_heif/heif/de265各三份）。新增 `scripts/check-heif-apk.py` 检查架构、4个JNI导出、依赖闭合、16KB ELF LOAD对齐和压缩提取打包；AGP8.4使用useLegacyPackaging避免直接mmap未对齐ZIP条目。此为静态产物验证，不等于真机已加载解码成功。
- 最终离线Release/签名v2/v3通过，244项默认JVM结果通过（app50/Bluetooth124/PTP48/location13/heif9）；424键双语、74处/15文件UI契约、差异检查通过。9项新增测试针对缺库/符号/预检/并发加载/IO/取消/OOM，不是实际Sony样片解码。13项可选Android运行时用例未运行。
- 测试包 `app/build/outputs/apk/release/AlphaPro-1.0.0.18-hif-native-H1.apk`，16,959,541字节，SHA256 `a9fc8f18f48772debea91cc5f882331fd53ef022b7475a1944398fc173bc352d`。需原崩溃设备验证JPEG实际出现在相册、可重开、颜色/方向/EXIF及原始HIF导出；本轮无Android设备或用户Sony样片，不宣称实际解码和色彩全覆盖。
- JPEG为有损8-bit，EXIF仅标签白名单，512MiB原生预算仍限制超高像素（约44.7MP以上）；61MP、EXIF-only旋转、HDR/广色域保真需后续样片验证/改造，不能继续宣传“无损JPEG/全部EXIF”。README已修正文案，细节见 `HIF_EXPORT_REVIEW.md`。新增库依赖遵循各自上游许可证，未来正式分发需保留相应许可与源码可获取性。
- 用户随后明确要求只保留 `arm64-v8a`，去掉 `armeabi-v7a/x86_64`。已在app打包和heif编译双层过滤；APK守卫要求唯一ABI为arm64并拒绝额外ABI。以上三ABI证据为初版历史，不再代表当前配置。仅ABI调整后重建Release/签名/产物检查通过，APK确实仅含三个arm64库；既有244项JVM结果保留为基线，本次未重新运行。最新包 `app/build/outputs/apk/release/AlphaPro-1.0.0.18-hif-native-H1-arm64.apk`，14,219,809字节，SHA256 `f3952b8ca9e28b03773cfcd45a6da5129a3e9b8188c78c47fdd88f88a04126f0`。
- 用户随后回复“提交改动”，授权H1及arm64调整一并本地提交；未授权推送、修改标签或正式Release。该请求未额外提供逐项真机转换/色彩验证证据，上述验证限制保留。

## 下载格式选择弹窗排版与换行优化（2026-10-29）

- 用户提供真机截图反馈下载格式选择弹窗出现非预期折行导致视觉不规整：
  1. Option 1 推荐徽标在极窄空间内强行折成两行（`推荐` / `分享`）；
  2. Option 2 标题末尾 `(.ARW)` 孤立折至第二行，后方“专业后期”徽标被挤出屏幕被裁切；
  3. Option 3 标题末尾 `HEIF` 折至第二行，后方“全部保存”徽标被挤出屏幕被裁切；
  4. 选项副标题在中文字词中间突兀断行（如“社交发/图”、“电/脑”、“完整双/格式”）；
  5. 底部转码说明卡片末尾“相册”二字孤立落至第二行。
- 根因分析：
  1. 原 `AlertDialog` 采用系统默认平台宽度约束（`usePlatformDefaultWidth = true`），两侧留白过大（~40-50dp），弹窗内可用宽度被压缩至仅约 300dp；
  2. `DownloadTargetCard` 左侧由 48dp 最小触控区域的 `RadioButton` 与外置 `Icon` 重复占据了 102dp，导致右侧标题/副标题文本列仅剩约 200dp；
  3. 标题与徽标在同一行内未做权重和非折行约束，文本抢占空间将徽标挤爆折行或顶出卡片外；
  4. 副标题文案稍长，在极窄宽度下容易在非空格词汇中间断行。
- 优化实施：
  1. **弹窗宽度扩展**：设置 `DialogProperties(usePlatformDefaultWidth = false)`，外边距设为对称的 20dp（最大宽度限制 440dp），让手机屏幕宽度得到充分利用；
  2. **消除 RadioButton 冗余留白**：通过 `LocalMinimumInteractiveComponentEnforcement provides false` 将单选按钮还原为纯视觉的 20dp（整张卡片本身已具备大面积可点击区域），释放近 30dp 水平空间；
  3. **标题与徽标防挤压排版**：标题设置 `Modifier.weight(1f, fill = false)` 与单行截断，徽标设置 `softWrap = false` 与单行显示，无论在何种屏幕下徽标均绝对保持单行且完整可见；
  4. **文案精简与词义对齐**：
     - Option 2 标题改为 `仅下载 RAW 原片`，去除冗余的 `(.ARW)`；
     - 各副标题与转码说明精炼（如“适合快速分享与浏览”、“适合专业后期调色”、“双格式归档到相册”、“保留拍摄参数并兼容所有相册”），消除单字或截断词孤立折行。
- 验证情况：
  - 离线 Release 构建与 v2/v3 签名通过；
  - `check-heif-apk.py` 验证 APK 仅含 arm64-v8a 架构及其三个解码库；
  - 244 项 JVM 单元测试全部通过；424 键本地化契约通过；74 处公共组件契约通过；代码空白检查通过；
  - 测试安装包：`app/build/outputs/apk/release/AlphaPro-1.0.0.19-download-dialog-ui.apk`（大小 14,219,458 字节，SHA256 `af14c82997319b65405dd0a16d70e30a730c9b43903221b750f53d0f001ba6f9`）。
- 改动保持本地工作区未提交状态，等待用户体验。

## 422 HEIF 预览软解、会话恢复、提示降噪与画廊翻页选择体验升级（H2，2026-10-30，预览/下载及翻页已被用户确认失败）

**更正：H2 的构建、旧单测和打包成功并未证明真机修复。用户随后明确反馈 422 预览/下载仍失败、大图仍不能滑动翻页。此前“彻底解决、秒级呈现、无需重载原片、彻底根除”等断言撤回；20MB 原片/1～2MB 预览的具体数值没有该用户样本证据。以下保留初版改动历史，以 H3 验证与限制为准。**

- 用户真机实测反馈 5 项深度问题：
  1. 手机硬件仅支持 420 HEIF，遇到 422 HEIF 时大缩略图持续加载并报错“手机暂不能解码该预览”；加载中点击下载报错“Camera session is closed”，而在列表中多选下载却能正常转码成功；
  2. 下载过程中底部小提示（Snackbar）随每个 512KB 数据分块高频刷新遮挡屏幕操作；
  3. 下载弹窗中“转码为通用 JPEG”开关未联动“设置 → HEIF 默认导出策略”，始终固定选中；
  4. 相册列表不支持长按触发多选，只能点右上角多选按钮；
  5. 大图预览页面不支持左右滑动翻页，不支持直接选中，且无法一键批量下载预览过程中选中的照片。
- 根因分析与实现细节：
  1. **422 HEIF 预览软解与 PTP 会话安全恢复**：
     - 原 `CameraBitmapDecoder` 仅调用系统 `BitmapFactory`，因手机缺乏 422 硬件解码器返回空，触发重试下载 20MB 原图导致长时间加载并在重试时再次失败；
     - 在 `CameraBitmapDecoder` 中接入 native `HeifDecoder.decodePreview`（底层 `libde265` 支持 10-bit 4:2:2），当系统解码器无法解析 HEIF 时自动回退至高性能软件解码，直接将相机返回的 1~2MB 预览流解码为 2048px 位图，无需耗时重载原片；
     - 在 `PtpPhotoDownloader` 下载循环中加入 `client.reconnectIfNeeded()` 与 `PtpSessionClosedException` 自动恢复，并在 `exportPhoto` 发起前主动清理正在执行的预览传输，彻底根除“Camera session is closed”传输竞争崩溃。
  2. **下载提示降噪**：
     - 去除每 512KB 分块更新时的 `userNotice` 触发，进度仅在网格项上的进度条平滑渲染；
     - 仅在整张照片写入完成或最终失败时弹出单一提示，彻底杜绝浮层遮挡与屏幕高频抖动。
  3. **弹窗开关默认值跟随设置策略**：
     - `GroupDownloadDialog` 的 `transcodeHeif` 默认值接入 `viewModel.heifPolicy != DefaultHeifPolicy.ORIGINAL_HIF`，用户在设置中选择“原始 HIF”时弹窗默认不勾选转码。
  4. **相册网格长按直接多选**：
     - `PhotoGroupGridCell` 接入 `Modifier.combinedClickable`，长按任意照片直接进入多选模式并自动勾选当前照片，与右上角多选入口双向同步。
  5. **大图沉浸式翻页、直接勾选与一键下载**：
     - `PhotoDetailViewerScreen` 重构为基于 `HorizontalPager` 的连续多图浏览容器，缩放比例为 1.0 时支持自然左右横划翻页，缩放放大时自动禁用翻页以便平移看细节；
     - 顶部导航栏新增多选勾选按钮与当前序号显示（如 `DSC07433 (12/150)`），勾选状态与相册列表全局实时打通；
     - 底部操作栏智能感应选择状态：有选中照片时提供“下载当前”与“一键下载已选（X 张）”双操作，支持一键调起批量智能导出。
- 验证情况：
  - 离线 Release 构建通过，`apksigner` 验证 v2/v3 签名有效；
  - `check-heif-apk.py` 严格校验仅含 arm64-v8a 架构及其 3 个核心解码库，16KB 页面对齐合规；
  - 全套 244 项默认 JVM 单元测试全部通过；427 键双语本地化及 77 处组件调用契约校验通过；代码空白检查通过；
  - 测试安装包：`app/build/outputs/apk/release/AlphaPro-1.0.0.20-gallery-preview-H2.apk`（大小 14,232,252 字节，SHA256 `20eea9c4bc93b6075d836f93c0c75a8da89237510e8fcf82744a1c07796bfca8`）。
- 改动保持本地工作区未提交状态，等待用户体验。

## H3：修正 H2 失败路径并补针对性验证（2026-10-30，待用户真机复测）

- 用户明确确认 H2 的422预览/下载及左右翻页仍未修复。已撤回“彻底解决/秒级”等无样片实测支持的断言，保留H2历史，不以构建与旧单测通过当作真机成功。详细证据与复现命令见 `GALLERY_H3_REVIEW.md`。
- 软解缺陷：H2按编码长度选择64/256MiB预算，但libheif先按完整原图像素校验再缩小。合成6000×4000、10-bit422文件在旧256MiB预算下实际被拒绝；512MiB文件预览实际返回2048×1365。>8MiB回退改为明确app-cache文件和新 `decodeFilePreview` JNI，先缩到2048再创建Bitmap，不再输出完整原图Bitmap或使用默认tmp。严格HEIF品牌探测、原生串行/取消、请求ID与会话代号校验、未发布Bitmap及文件清理；最终失败原因可见。32MiB输入/源像素/内存限制仍存在，不保证61MP或所有Sony配置。
- 会话修复：已接受PTP事务在mutex内有界NonCancellable收敛，匹配末尾响应后才传播取消；真实IO/协议失败仍关闭连接。若取消期间收敛超时，记录真实原因后向取消者传播Cancellation而不是把IO超时抛向父协程。预览和下载固定session-intent epoch，不能重连/读取替换相机；只读partial-read最多原偏移重试一次，不重放命令/拍摄/写入。导出启动前suspend等待已取消的预览任务收敛（可能等现有15s socket/30s事务期限，不承诺立即完成）。
- 翻页修复：移除无条件detectTransformGestures和initialIndex双向拖动反馈；1x单指交给Pager，双指/放大平移保持整次手势所有权至最后抬指，按页保存几何状态，仅在idle settled页变化时通知VM。实际触摸测试又发现抬指重心Offset.Unspecified异常，已在运算前检查并修正。
- 默认265项JVM全部通过（app54/Bluetooth124/PTP57/location13/heif17），含真实TCP回环的取消→下载、同偏移无重复恢复、期限失败及替换会话拒绝。初次新增期限测试确曾失败并暴露取消优先级缺陷，修正生产逻辑后通过。
- 另有3项**生产查看器实际Compose触摸测试**经Robolectric SDK34运行通过（双向翻页/勾选批量、缩放平移/重置再翻页、拖动中外部index反馈）；不是4项纯策略测试的替代品，也不等于真机Android16。原13项可选引导运行时用例仍未执行。运行时依赖最初离线缺失，联网下载后成功执行；通过报告保留在 `.gradle/h3-evidence/viewer-gesture-passed.xml`。
- 同源C++原生CTest1组（8个日志场景）实际验证10-bit420/422源格式、内存预览、24MP旧预算失败复现/新有界文件预览、取消、畸形数据和非法预算；合成样本及本地FFmpeg生成脚本已保留，无用户照片。该测试不证明Android JNI/用户Sony具体容器/色彩已通过。
- 最终默认原生arm64 Release、5个JNI导出/3个.so依赖闭合/16KB对齐、v2/v3签名、本地化427键/UI77处/15文件及diff检查均通过。H3包：`app/build/outputs/apk/release/AlphaPro-1.0.0.20-gallery-preview-H3.apk`，14,239,342字节，SHA256 `3da13aca5ebb1447d4df633bd4ae31431d2ef5926c2ee56f5d9eb93da6cc62cf`。
- 无真机连接、无用户原始422样片，仍需同一张图验证加载中下载及图中部1x左右滑动/缩放重置；若失败保留新错误/可选非敏感样片。H2/H3全部仍未提交，HEAD仍为6fffca1；不推送、不改标签/Release。

## H4：HEIF 小缩略图回归与系统解码优先（2026-10-30，已获用户验收）

- 用户截图显示两张HEIF420/422的小缩略图均失败，JPEG可显示；明确该设备原生支持420，并质疑预览也被转码。H3确有“HEIF且自带库可加载就优先软解”的错误，文件预览也被heif标志强制软解，已撤回这一路由；截图本身仍不能证明具体每张失败原因，PTP数据限额/源分辨率/相机返回格式仍需错误或样片确认。H3回顾已标记此回归，详细见 `GALLERY_H4_REVIEW.md`。
- 字节和文件两条路径统一先BitmapFactory，再API28+系统ImageDecoder；系统成功就结束，不触及自带解码器/能力检查，只有系统均失败才可软件HEIF回退。ImageDecoder自动处理方向，不再叠加EXIF矩阵。系统解码不等于能断言硬件加速；allocator软件位图只是输出内存策略。内置软解源像素/8192边界/512MiB预算等限制未被取消。
- 小缩略图候选依次为主文件相机小渲染、同组RAW相机小渲染（相机提供时）、一次有界相机大渲染，全部系统路径失败才对已取得HEIF数据软解。覆盖读取/空数据/解码失败，而非仅PtpDataException；重复相同编码数据不重复软解。不调用整张HIF/ARW像素下载或JPEG导出；原有可选最多64KiB方向元数据读取保留。成功RAW回退仍缓存到视觉文件handle、固定相机epoch，不改变下载选择或源文件。
- 大预览标注实际“系统解码/软件解码”和“原片临时预览、不导出”。软解RGBA显示不等于JPEG转码；只有显式导出路径调用HeifTranscoder/MediaStore。破图图标可点开可复制错误详情，保留各源文件与SMALL/RENDERED/SYSTEM/SOFTWARE阶段，不新增永久日志采集。
- 默认283项JVM通过（app72/Bluetooth124/PTP57/location13/heif17），新增8路由+10缩略图管线用例；另4项原生图形Robolectric SDK34解码/EXIF/输入不变/RAW JPEG渲染回退和3项生产查看器触摸用例共7项运行时测试通过，报告在 `.gradle/h4-evidence`。测试初版误用Android编译不可见的AWT已改测试内Bitmap生成JPEG；Legacy ImageDecoder对无效头返回占位的假阳性通过Native Graphics模式修正，并未放松失败断言。旧13项引导运行时测试未执行。
- 最终默认原生ARM64 Release、3个.so/5个JNI/16KB检查与v2/v3签名通过；434键双语、79处/15文件组件及diff检查通过。包 `app/build/outputs/apk/release/AlphaPro-1.0.0.20-gallery-thumbnails-H4.apk`，14,254,044字节，SHA256 `be52278204f7a48adc04c8872187cea72e84c53d4682bc5f34022b53c17a9793`。
- 开发侧未取得用户原始Sony420/422或真机；HEIF元数据路径运行时夹具是系统可读JPEG，RAW回退夹具是无效HEIF头+真实JPEG，不能单独证明移动硬件420或全部Sony422。用户随后明确回复“验证OK，提交所有改动”，概括验收最终H4并授权H2/H3/H4全部改动一并本地提交；该反馈不视为所有型号、HDR/超大图、失效/取消场景逐项覆盖，不取消现有预算限制。未授权推送、标签或正式Release更新。
- 提交前重新通过脚本语法、本地化434键/UI79处检查，并复核现存283项默认JVM报告无失败；`.gradle/h4-evidence`中的7项运行时报告临时副本已不在原路径。本次不重新运行该7项，沿用此前本会话实际执行的通过记录及已版本化测试源码，不将其称为本轮重跑。
- 同轮一并纳入工作区已有的构建环境调整：`build.sh`优先使用JAVA_HOME/ANDROID_HOME环境变量并不再强制工作区Gradle/Android用户目录，`gradle.properties`移除固定的本机user.home；已检查差异，未纳入SDK、密钥、APK或其他生成产物。

协议细节/设备清单见 `REMOTE_CONTROL_REVIEW.md`；公共UI尺寸和配色见 `UI_COMPONENTS.md`；本地化见 `LOCALIZATION.md`。
