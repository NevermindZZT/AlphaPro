# 索尼相机 Android 伴生应用 (AlphaPro) 核心需求技术调研与架构设计方案

## 1. 项目背景与官方 Creators' App 痛点深度剖析

索尼为其微单相机（包括新一代采用全新菜单与系统的机型如 A7M4, A7R5, A6700, A7C2, A7CR, ZV-E1, A1, A9M3 等）推出了官方移动端应用 **Creators' App**。然而在 Android 平台上，用户长期面临两个极其严重影响使用体验的问题：

### 痛点一：定位总是掉（蓝牙后台 GPS 地理标记频繁断连、漏标、失效）

#### 根因剖析：
1. **Android 系统后台治理与厂商激进保活策略（Doze 机制与 OOM 杀进程）**：
   - 现代 Android（Android 10 至 Android 15）对后台应用的定位频率、蓝牙后台扫描、网络访问和 CPU 运行有极为严苛的限制。
   - 国内主流手机品牌（小米 HyperOS/MIUI、vivo OriginOS、OPPO ColorOS、华为 HarmonyOS、三星 OneUI 等）拥有激进的“后台电量管理”策略。一旦手机锁屏或放入口袋，Creators' App 的普通后台服务极易被系统挂起（Freeze）甚至直接被杀死。
2. **相机使用特性的“休眠-唤醒”蓝牙重连难题**：
   - 摄影师在实际外拍中，相机通常是“拍几张 -> 自动关机/休眠省电 -> 走一段路 -> 开机再拍”。
   - 当相机休眠关机时，BLE（低功耗蓝牙）链路必然断开；当相机再次开机时，相机开始广播 BLE 广播包。
   - Android 系统在锁屏和省电状态下，**禁止或大幅节流（Throttle）未处于前台的应用进行未过滤的 BLE 扫描**。Creators' App 没有适配 Android 系统级的设备唤醒机制，导致手机在黑屏时根本无法发现相机重新开机，或者扫描延迟长达数分钟，造成后续拍摄的照片全部缺失 GPS 信息。
3. **缺乏对 Android 系统级伴生设备管理器（CompanionDeviceManager）的适配**：
   - Google 早在 Android 8.0 引入并在 Android 12+ 大幅强化了 `CompanionDeviceManager` (CDM) API。
   - CDM 允许操作系统本身在底层代为监听已绑定的蓝牙设备信标。当检测到设备开机出现（`EVENT_BLE_APPEARED`）时，由系统直接拉起绑定的应用服务，且豁免后台前台服务启动限制。Creators' App 未深度利用该系统特性，仅依靠常规 Service，一旦断连便彻底失去重连能力。

---

### 痛点二：不支持 HEIF 格式图片导出到手机

#### 根因剖析：
1. **索尼 HEIF 采样的特殊性：10-bit 4:2:2 色彩采样**：
   - 索尼从 A7S3、A1、A7M4 等机型开始引入 HEIF 格式（文件后缀为 `.HIF`），支持 10-bit 采样，动态范围和色彩渐变更平滑，且体积仅为无损 RAW 的 1/5 ~ 1/3，优于普通 8-bit JPEG。
   - 关键在于：索尼相机拍摄的高质量 HEIF 采用的是 **HEVC Main 4:2:2 10 Profile（10-bit 4:2:2 色度抽样）**。
2. **Android 平台对 10-bit 4:2:2 HEIF 的系统级支持缺失**：
   - Android 系统从 Android 10 开始通过 `HeifDecoderProvider` 提供 HEIF 解码支持，但底层依赖设备的硬件 MediaCodec 或内置的软解库。
   - 绝大多数 Android 手机的硬件解码器芯片（高通骁龙、联发科天玑）和 Android 自带的 `ImageDecoder`/`BitmapFactory`，**仅支持 HEVC Main10（即 10-bit 4:2:0），完全无法硬件解码 10-bit 4:2:2 图像**！直接调用系统 API 解码索尼 .HIF 图像会导致解码失败、崩溃或返回黑色画面。
3. **官方 Creators' App 的“一刀切”封禁策略**：
   - 索尼官方并未在移动端 App 中内置通用的软件解码器（如基于 CPU 多核的 `libde265`/`libheif`）。
   - 由于担心导出到手机后，用户在系统相册中打不开图片而产生大量客诉，索尼官方在 Creators' App 的 Android 客户端中采取了**强制禁用/置灰 HEIF 导出**的做法，并弹窗提示用户：“Android 暂不支持 10-bit HEIF，请在电脑端使用 Imaging Edge Desktop 转换为 JPEG 或 TIFF 后再查看”。
   - 这对于希望在移动端快速出片、发社交媒体或用 Lightroom Mobile 修图的 Android 摄影师来说，造成了致命的断层。

---

## 2. 现有开源项目与索尼协议技术调研

### 2.1 调研项目一：AlphaGPS (Saschl/alpha-gps)
- **定位机制解密**：
  - AlphaGPS 成功解决了“定位总是掉”的问题。其核心就在于采用了 **Android 伴生设备管理器 (`CompanionDeviceManager` 与 `CompanionDeviceService`)**。
  - 在 Android 12+ 上注册 `CompanionDeviceService`，监听 `DevicePresenceEvent.EVENT_BLE_APPEARED` 和 `EVENT_BLE_DISAPPEARED`。相机开机广播出现时，由 Android 系统直接启动 `LocationSenderService`。
  - 配置 `foregroundServiceType="location|connectedDevice"` 前台服务，并引导用户配置“忽略电池优化”（Battery Optimization Exemption）。
  - 使用 `LocationPacketBuilder` 严格组装索尼 BLE GPS 95 字节特征包，并向 `0000dd11` 特征周期性写入定位。
- **Wi-Fi 与照片导出模块最新进展（代码反查发现）**：
  - AlphaGPS 最初仅支持 BLE GPS，近期代码中已开始尝试接入 Wi-Fi 遥控与传输，并创建了 `heif` NDK 模块。
  - 其 `heif` 模块采用 C++ 封装了 **`libheif` + `libde265`**，并由 JNI 提供 `HeifDecoder.decodeFile()`，能够软解索尼 10-bit 4:2:2 HEIF 并转码为 JPEG。
  - 但其整体定位偏向小巧的 GPS 伴侣工具，Wi-Fi 交互和相册浏览器仍处于初期探索阶段，缺少完善的 Android Jetpack Compose 现代化交互相册、批处理传输流控、RAW 预览引擎以及现代图片库集成。

### 2.2 调研项目二：AlphaSync (mlapaglia/AlphaSync)
- **协议握手细节**：
  - 针对 A7R5、A7M4 等采用新系统的机型，揭示了完整的 BLE 鉴权流程：
    - 连接 GATT 服务 `8000dd00-dd00-ffff-ffff-ffffffffffff`
    - 向 `0000dd30-0000-1000-8000-00805f9b34fb` 写入 `0x01`（解锁/激活指令）
    - 监听写入回调后，向 `0000dd31-0000-1000-8000-00805f9b34fb` 写入 `0x01`
    - 激活成功后，相机开始接收 `0000dd11-0000-1000-8000-00805f9b34fb` 的坐标数据。

### 2.3 调研项目三：SonyPhotoTransfer (Morning-Sea) & libptp / PTP-IP
- 索尼相机在开启传输模式后，会开放标准 **PTP/IP (Picture Transfer Protocol over IP)** 服务，固定监听 TCP 端口 `15740`。
- 通信机制基于两个 TCP 链接：**Command Socket**（处理命令、响应和二进制数据）与 **Event Socket**（处理相机事件通知）。
- 索尼 PTP 扩展指令集：
  - `0x9210`（OpenSession，模式 0 为遥控拍摄，模式 1 为图片传输，模式 2 为最新机型支持的遥控拍摄+传输混合模式）。
  - `0x1004` (`GetStorageIDs`) -> `0x1007` (`GetHandles`) -> `0x1008` (`GetObjectInfo`) -> `0x9201` (`GetPartialLargeObject`)。
  - PTP 格式代码：`0x3801` (JPEG), `0xB101` (Sony ARW RAW), `0xB110` (Sony HEIF/HIF), `0x300D`/`0xB981` (MP4 Video)。

### 2.4 调研项目四：索尼专业机型原生 FTP 边拍边传机制
- 索尼专业及中高端机型（A1, A9 系列, A7R5, A7M4, A7S3, A7C2, A7CR, FX3 等）内置了“FTP 传输功能”（FTP Transfer Func）。
- 相机在连接手机热点或相同局域网后，只要手机端运行一个轻量级的 FTP 服务器，相机在按下快门后即可在后台自动将原图（JPEG/HEIF/RAW）通过 FTP 协议推送到手机，实现**零延迟无线即拍即存**，这是解决传输体验的一大杀手锏。

---

## 3. 两大核心需求详细技术实现方案

### 3.1 核心需求一：永不断连的后台 GPS 与时区同步方案

#### 3.1.1 总体架构流程
```
[ 用户初次绑定 ]
       │
       ▼
[ Android CompanionDeviceManager 配对请求 ]
       │
       ▼
[ 系统底层注册 DevicePresence 监听 ] ────┐
       │                                  │ (当相机开机/进入信号范围)
       ▼                                  ▼
[ CameraCompanionDeviceService.onDeviceAppeared / onDevicePresenceEvent ]
       │
       ▼ (系统豁免后台限制直接启动)
[ LocationSenderService (前台服务: location | connectedDevice) ]
       │
       ├─► [ BluetoothGatt 连接与重试状态机 (MTU=512, Priority=HIGH) ]
       │         │
       │         ├─► [ 握手特征: 0000dd30 (0x01) -> 0000dd31 (0x01) ]
       │         ├─► [ 时间同步特征: 0000cc13 (写入当前系统UTC与时区) ]
       │         └─► [ 定位写入特征: 0000dd11 (周期性写入 95 字节定位包) ]
       │
       └─► [ 高精度自适应定位引擎 (FusedLocationProviderClient) ]
                 │ (静止时节流，移动时高频，精度 < 200m 时上报)
```

#### 3.1.2 关键实现细节与代码规范
1. **系统级绑定与唤醒 (`CompanionDeviceManager`)**：
   - 在应用内发起 `AssociationRequest`，过滤包含索尼服务 UUID (`8000dd00-dd00-ffff-ffff-ffffffffffff`) 的 BLE 广播。
   - 继承 `CompanionDeviceService`，实现：
     - Android 12~14: `onDeviceAppeared(AssociationInfo)`
     - Android 15+: `onDevicePresenceEvent(DevicePresenceEvent.EVENT_BLE_APPEARED)`
   - 当事件触发时，立即调用 `context.startForegroundService()` 启动定位服务。
2. **前台服务保活配置 (`AndroidManifest.xml`)**：
   ```xml
   <service
       android:name=".service.LocationSenderService"
       android:foregroundServiceType="location|connectedDevice"
       android:exported="false" />
   <service
       android:name=".service.CameraCompanionService"
       android:permission="android.permission.BIND_COMPANION_DEVICE_SERVICE"
       android:exported="true">
       <intent-filter>
           <action android:name="android.companion.CompanionDeviceService" />
       </intent-filter>
   </service>
   ```
3. **定位数据包 (95 Bytes) 构造协议规范**：
   - `Fixed Header` (11 字节): `[0x00, 0x5D, 0x08, 0x02, 0xFC, 0x03, 0x00, 0x00, 0x10, 0x10, 0x10]`
   - `Latitude` (4 字节, 大端序): `(latitude * 10,000,000).toInt()`
   - `Longitude` (4 字节, 大端序): `(longitude * 10,000,000).toInt()`
   - `UTC Date/Time` (7 字节): `[Year_High, Year_Low, Month, Day, Hour, Minute, Second]`
   - `Padding` (65 字节): 全 `0x00`
   - `Standard Timezone Offset` (2 字节, Short 大端序): 标准时区分钟偏移量（如东八区为 480）
   - `DST Offset` (2 字节, Short 大端序): 夏令时分钟偏移量（非夏令时为 0）
4. **时间同步包 (CC13 特征值)**：
   - 13 字节协议：`[12, 0, 0, Year_H, Year_L, Month, Day, Hour, Min, Sec, isDst(1/0), TimezoneHour, TimezoneMinute]`。
   - 每次连接成功后自动执行一次，保证相机内时间与手机毫秒级同步，彻底消除时钟漂移。

---

### 3.2 核心需求二：完整的 HEIF 导出、预览与全保真转换方案

#### 3.2.1 索尼 Wi-Fi 建立与图片拉取架构
```
[ 索尼相机 ] ◄──────── BLE 协商 (读取 SSID / 密码, 写入 0000cc08 启动 Wi-Fi) ────────► [ Android 应用 ]
     │                                                                                       │
(SoftAP 广播)                                                                         (NetworkSpecifier)
     │                                                                                       │
     └────────────────────────── Wi-Fi 直连成功 (分配 IP 网段) ──────────────────────────────┘
                                             │
                                    [ TCP 端口 15740 ]
                                             │
                              PTP/IP 握手 (Command & Event Sockets)
                                             │
                           读取对象元数据 (0x1008 / 0x9805)
                        过滤识别格式：0xB110 (HEIF), 0xB101 (ARW)
                                             │
                         分块高效拉取 (0x9201 GetPartialLargeObject)
                                             │
                                             ▼
                               ┌─────────────────────────────┐
                               │     用户导出模式策略选择     │
                               └──────────────┬──────────────┘
                                              │
                      ┌───────────────────────┴───────────────────────┐
                      ▼                                               ▼
         【方案 A：原始 HEIF 无损导出】                  【方案 B：内置高性能转码 JPEG/HDR】
         - 保持 10-bit .HIF 原文件                       - JNI 调用 libheif + libde265 软解
         - 直接存入 MediaStore 媒体库                    - 色彩空间转换 (10-bit -> 8-bit sRGB / HDR Gainmap)
         - 供 Lightroom Mobile 专业修图                   - 完美拷贝所有 EXIF 元数据 (镜头/参数/GPS)
                                                         - 生成高质量 JPEG (95% 质量) 存入系统相册
```

#### 3.2.2 核心模块详细设计

1. **BLE 引导零感 Wi-Fi 直连**：
   - 用户点击连接 Wi-Fi 时，通过已建立的 BLE 连接读取特征 `0000cc06` (SSID) 与 `0000cc07` (密码)，然后向 `0000cc08` 写入 `0x01`（启动相机 Wi-Fi）。
   - 在 Android 10+ 上利用 `ConnectivityManager.requestNetwork` 配合 `WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()`。
   - 实现**完全免手动输入密码、免跳转系统 Wi-Fi 设置**的应用内一键自动直连。
2. **PTP/IP 高性能流式传输**：
   - 支持 2MB 分块传输缓冲区，利用 Kotlin 协程与 `Dispatchers.IO`，在下载大文件时实时更新进度 Flow。
   - 使用 Android `MediaStore` 的 `IS_PENDING = 1` 机制：在下载和处理过程中临时标记为 pending，完全写入并刷新文件后将 `IS_PENDING` 置 0，杜绝系统相册读取到破损临时文件。
3. **针对 10-bit 4:2:2 索尼 HEIF 的 NDK C++ 解码引擎**：
   - 集成源码子模块：`libheif` (HEIF 容器解析) + `libde265` (HEVC 软件解码器)。
   - **关键 CMake 配置与 C++ 源码优化**：
     - 在 `heif_decoding_options` 中明确配置：
       - `options->convert_hdr_to_8bit = 1`（针对 SDR 导出）；
       - `options->autocorrect_broken_input = 1`（解决索尼相机 HIF 文件中常见的 NCLX / VUI 范围元数据不规范问题）；
       - `options->num_codec_threads = 4`（多线程并行解码，大幅提升导出速度）。
     - 解码输出为 `heif_colorspace_RGB` 与 `heif_chroma_interleaved_RGBA`，直接交由 Android `Bitmap`。
4. **EXIF 元数据完整迁移机制**：
   - 解码过程会剥离原始图像容器，因此必须使用 C++ 从 HEIF 容器中提取原始 `Exif` 数据块（Tag ID 检索）。
   - 导出为 JPEG 后，利用 `androidx.exifinterface.media.ExifInterface`，将原始机身型号、镜头信息、焦距、光圈、曝光时间、ISO、白平衡以及最重要的 **GPS 经纬度/海拔** 全部写回新生成的 JPEG 中，确保转码后的照片不丢失任何拍摄信息。
5. **Jetpack Compose 现代化 UI 预览适配**：
   - 自定义 Coil `Decoder`：拦截 `.hif` 和 `image/heif` 类型的请求，通过 JNI `HeifDecoder.decodeThumbnail` 获取缩略图，在 Compose 的 `AsyncImage` 或相册网格瀑布流中提供丝滑的快速缩略图预览。

---

## 4. 扩展杀手级功能设计 (Killer Features)

为了让 AlphaPro 全面超越索尼官方 Creators' App 并优于现有单一工具，规划以下亮点功能：

### 功能 1：内置本地 FTP 边拍边传服务 (Wireless Tethering)
- **原理**：索尼相机菜单自带“FTP 传输”设置。在 App 内启动一个超轻量级、无依赖的纯 Kotlin 本地 FTP Server（监听端口 2121）。
- **体验**：拍摄前手机开启热点，相机连上热点并配置手机 FTP。摄影师拍摄完一张照片，机身立刻通过 FTP 后台自动推送原图到手机，手机端 App 即时蜂鸣/振动提示并弹出预览大图。适合商业拍摄、人像外拍、活动会议等快速审片场景。

### 功能 2：离线 GPS 轨迹记录与后期批量打卡 (GPX Geotagging)
- **痛点**：有时候用户忘记带手机、手机没电、或者相机未开启蓝牙，导致拍摄的所有照片都没有 GPS。
- **解法**：App 内置轻量级后台 GPX 运动轨迹记录器。事后用户通过 Wi-Fi 导出照片到手机时，App 支持“一键根据拍摄时间匹配 GPX 轨迹”，自动将准确的 GPS 坐标批量写入已下载照片的 EXIF 中。

### 功能 3：多机身智能切换与设备云配置
- 支持同时配对管理摄影师的多台机身（如主机 A7M4 + 备机 A6700，或双机位）。
- 伴生设备管理器自动感应哪个机身开机，自适应切换连接；每个机身独立保存历史连接记录、配置偏好与传输相册。

### 功能 4：无线蓝牙遥控快门与高级延时定时器 (Intervalometer / B门遥控)
- 利用索尼 BLE Remote 特征值（`0000ff01` 与 `0000ff02`），实现无需开 Wi-Fi（纯蓝牙毫安级超低功耗）的无线遥控器。
- 支持半按对焦、全按快门、视频录制启停、B 门长曝光精确计时、自定义间隔定时拍摄（如每隔 5 秒拍一张，共拍 300 张），替代昂贵的第三方遥控手柄。

### 功能 5：创意 LUT / 调色预设实时套色预览 (Picture Profile LUT Viewer)
- 许多用户在机身上使用 S-Log3 或 HLG 拍摄照片或视频截图，在手机相册预览时画面发灰、缺乏对比度。
- App 内置 LUT 预览引擎，内置索尼常用还原 LUT（如 709, Cine EI 常用还原 LUT），在 Compose 预览大图时可一键开关“套 LUT 预览”，直观感受成片色彩。

---

## 5. 项目技术架构与模块划分规划

### 5.1 架构分层图 (Clean Architecture)

```
┌─────────────────────────────────────────────────────────────┐
│                       UI Layer (Jetpack Compose)            │
│  - Presentation: M3 Components, Navigation, ViewModels      │
│  - Features: Geotag, Gallery/Browser, Transfer, Remote, FTP │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                      Domain / Business Layer                │
│  - UseCases: ConnectCamera, SyncLocation, DownloadPhotos... │
│  - Repositories & StateFlow Orchestrators                   │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                       Data / Core Layer                     │
│  ┌───────────────────────┐       ┌───────────────────────┐  │
│  │    core:bluetooth     │       │       core:ptp        │  │
│  │  - BleGattManager     │       │  - PtpIpConnection    │  │
│  │  - CompanionService   │       │  - SonyContentCatalog │  │
│  │  - SonyPacketBuilder  │       │  - ImageTransferEngine│  │
│  └───────────────────────┘       └───────────────────────┘  │
│  ┌───────────────────────┐       ┌───────────────────────┐  │
│  │       core:heif       │       │       core:ftp        │  │
│  │  - NDK JNI Bridge     │       │  - EmbeddedFTPServer  │  │
│  │  - libheif + libde265 │       │  - BackgroundReceiver │  │
│  │  - ExifTagPreserver   │       │                       │  │
│  └───────────────────────┘       └───────────────────────┘  │
│  ┌───────────────────────┐       ┌───────────────────────┐  │
│  │     core:location     │       │     core:storage      │  │
│  │  - FusedLocationClient│       │  - MediaStoreAdapter  │  │
│  │  - TrackLogger (GPX)  │       │  - SAF FileExporter   │  │
│  └───────────────────────┘       └───────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 核心模块职责说明
1. **`app`**：应用入口、Hilt/Koin 依赖注入图配置、全局 Application 生命周期管理。
2. **`core:bluetooth`**：索尼 BLE 握手协议、伴生设备管理器 (`CompanionDeviceManager`) 绑定、前台服务保活、蓝牙状态监听。
3. **`core:location`**：`FusedLocationProviderClient` 高精度定位与静止节能策略、索尼 95 字节坐标转换器、GPX 轨迹记录。
4. **`core:ptp`**：PTP/IP 协议栈客户端（双 Socket 管理、事务队列、大对象流式分块拉取、Sony Content Catalog 树遍历）。
5. **`core:heif`**：C++ NDK 原生模块，集成 `libheif` 与 `libde265`，负责 10-bit 4:2:2 HIF 软解、预览生成、JPEG 转码与 EXIF 注入。
6. **`core:ftp`**：嵌入式轻量级 FTP 服务器，支持局域网/手机热点模式下的相机边拍边传与即时图片接收。
7. **`feature:home`**：相机连接状态卡片、一键重连、定位状态仪表盘。
8. **`feature:gallery`**：Jetpack Compose 现代化瀑布流相册、缩略图虚拟化滚动、多选批量下载、原图与 JPEG 转码选择导出。
9. **`feature:remote`**：纯蓝牙/Wi-Fi 快门遥控、定时拍摄、B 门长曝光控制器。

---

## 6. 实施路线图与验证里程碑 (Roadmap)

- **阶段一：蓝牙底层与永续定位服务验证 (Milestone 1)**
  - 搭建 Gradle 构建体系，配置 Compose 与前台服务权限；
  - 实现 `CompanionDeviceManager` 绑定与 `CompanionDeviceService` 唤醒；
  - 跑通 95 字节定位包与 CC13 时间校准包与真机握手；
  - 验证在锁屏 30 分钟、相机多次开关机休眠场景下的定位成功率达到 99%+。
- **阶段二：Wi-Fi 连接与 PTP/IP 照片浏览/下载 (Milestone 2)**
  - 实现基于 BLE 的自动 Wi-Fi 握手与 NetworkSpecifier 免密直连；
  - 实现 PTP/IP 客户端（端口 15740），完成相机文件列表与缩略图抓取；
  - 实现 RAW、JPEG、HEIF 原文件的流式下载并保存至 MediaStore。
- **阶段三：NDK libheif/libde265 软解与全保真导出 (Milestone 3)**
  - 交叉编译 `libde265` 与 `libheif` 动态库并配置 CMakeLists.txt；
  - 编写 JNI 解码方法，验证 Sony A7M4/A7R5 的 10-bit 4:2:2 .HIF 图像成功解码为 Bitmap；
  - 跑通完整 EXIF 信息从 HIF 拷贝回导出的 JPEG；
  - 将解码器作为 Coil 插件集成至 Compose 相册组件。
- **阶段四：UI 界面打磨与进阶特色功能 (Milestone 4)**
  - 使用 Material 3 构建现代化流畅的深色/浅色主题 UI；
  - 集成内置 FTP 边拍边传服务器；
  - 集成蓝牙无线快门与定时器功能；
  - 全流程端到端测试与打包发布。
