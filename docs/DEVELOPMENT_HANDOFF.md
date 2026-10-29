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

协议细节/设备清单见 `REMOTE_CONTROL_REVIEW.md`；公共UI尺寸和配色见 `UI_COMPONENTS.md`；本地化见 `LOCALIZATION.md`。
