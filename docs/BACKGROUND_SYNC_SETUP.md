# 后台定位与电池优化设置引导

## 产品入口

- 首次引导或设置回看：GPS/相机对时第一页，功能说明下方的“后台持续定位同步”卡片。
- 设置中心：定位与轨迹分区，GPS开关和参数前的同一卡片。
- 不增加引导页数，不修改完成版本3，不强制授权，也不重新触发已完成用户的首次引导。
- 系统页面只由用户点击进入；从授权/系统设置返回或页面恢复前台时，重新查询真实系统状态。

## 两项设置

### 定位

1. 未允许前台定位：一起申请精确/近似定位，由用户选择；没有授权时不会同时申请后台定位。
2. Android 8～9（API26～28）：没有单独后台定位权限，前台定位授权满足此项权限检查。
3. Android10（API29）：在前台授权后单独申请后台定位，系统弹窗可提供“始终允许”。也保留应用权限设置备用入口。
4. Android11及以上（API30+）：打开公开的应用信息页面，说明权限 → 位置 → “始终允许”路径，并使用系统返回的本地化选项名称。不会依赖未公开的权限深链或伪装成直达定位开关。
5. 前台/后台权限分阶段请求，近似定位仍保持近似精度。启动页不重复要求已选择近似定位的用户升级到精确授权。
6. 未授予前台定位时，不强行启动定位类型前台服务；其他不依赖定位的功能可继续使用。授权后若服务此前未启动，可重新开启GPS同步或重连相机。

### 电池优化

- 状态只使用 `PowerManager.isIgnoringBatteryOptimizations(packageName)`，不以系统页面返回码或用户点击过按钮判断为已完成。
- 使用公开的 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 列表（不附带package URI）。提示必要时切换到所有应用，找到AlphaPro，选择“不优化”。
- 页面不存在或设备策略阻止时，降级到本应用信息；如果所有入口都不可用，提示用户手动检查，不自动宣称已豁免。
- 不直接使用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：官方限制其适用范围，本轮没有确认Play分发资格，故采用普通设置列表。
- 不关闭手机全局省电模式。部分厂商还需要在应用耗电管理中选择不受限制/允许后台活动；豁免是部分放宽，不保证所有手机后台不被中断。

## 状态和交互边界

- 定位就绪：有前台定位权限，且API29+还需后台定位权限。
- 电池就绪：系统明确返回已豁免；未知状态不视为完成。
- 拒绝、撤销、返回设置时均读取系统实际值；不写入新的“已完成设置”偏好，不采集/恢复已移除的引导调试记录。
- 没有自动弹窗/反复申请循环。被永久拒绝时仍提供公开的应用权限设置入口。
- 公共按钮采用紧凑规格并保留Material交互范围；窄屏、大字体可自然换行和滚动。

## 自动化与真机检查

纯Kotlin政策测试覆盖API26/28/29/30/36/37、前台优先、后台单独申请、授权撤销、电池豁免false/unknown等组合；它们不等同真实系统页面/权限弹窗验证。

真机需检查：

1. 先允许前台定位，拒绝/近似/精确不同选择后状态是否准确。
2. API29单独后台授权；API30+进入应用信息后完成“始终允许”，返回自动刷新。
3. 电池列表中选择AlphaPro为不优化，再返回检查状态；撤销豁免也应刷新。
4. 入口缺失、系统策略限制、永久拒绝；不会误显示已完成或产生请求循环。
5. 引导/设置两处，深浅主题、中文/英文、大字体和窄屏；返回系统设置不完成或关闭引导。
6. 既有定位同步、BLE连接、相册与无线快门不因权限卡片查询而重启；本轮不改BLE/GPS协议和心跳调度。

## 验收记录

2026-10-29用户对B1回复“OK，提交改动”，授权后台设置引导和前轮引导调试清理一并本地提交，不推送或更新正式Release。验收包为 `1.0.0.14` B1；219项默认JVM回归、Release构建和正式签名校验通过。用户的概括验收不等同以上全部版本、厂商和故障场景逐项覆盖，13项可选Android运行时用例未执行。

## 官方依据

本轮先按agent-reach网页读取路由核对；Jina不可用后，官方页面由只读文档桥接/云端Firecrawl及限定官方域名摘录读取。未使用用户凭据或设备数据。

- [后台定位权限与系统版本差异](https://developer.android.com/develop/sensors-and-location/location/permissions/background)
- [Android11前台/后台权限分开请求](https://developer.android.com/about/versions/11/privacy/location#request-background-location-separately)
- [API30新增后台权限选项名称接口](https://developer.android.com/sdk/api_diff/30/changes/android.content.pm.PackageManager)
- [公开应用信息设置入口](https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS)
- [电池优化设置列表](https://developer.android.com/reference/android/provider/Settings#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
- [Doze豁免限制与适用情况](https://developer.android.com/training/monitoring-device-state/doze-standby#exemption-cases)

系统/厂商页面名称、入口可用性及实际后台表现仍需设备验证。
