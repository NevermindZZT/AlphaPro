# AlphaPro (索尼微单全系伴侣)

面向索尼 Alpha 全系微单（兼容 A7M4, A7R5, A6700, A7C2, A7CR, ZV-E1, A1, A9M3 等）打造的 Android 现代化伴生应用。彻底解决官方 Creators' App 在 Android 端**“定位频繁断连”**与**“无法导出/预览 10-bit HEIF 图像”**的两大核心痛点。

## 核心特性

1. **永不断连的后台 GPS 守护**
   - 适配 Android **CompanionDeviceManager (CDM)**，相机开机时由系统底层直接唤醒应用。
   - 双属性前台保活服务 (`location | connectedDevice`)，结合索尼 95 字节坐标封包协议。
   - 自动毫秒级机身时钟校准（写入 CC13），彻底消除打卡时钟漂移。
2. **完整的 10-bit 4:2:2 HEIF 支持**
   - **双轨导出**：支持直接无损导出原始 `.HIF` 文件（供 Lightroom Mobile 调色），或通过内置 NDK `libde265` 多核软解转码为 95% 高画质 JPEG，完整保留所有 EXIF 元数据（机身、镜头、曝光参数与 GPS 坐标）。
   - **Compose 流畅渲染**：集成自定义 Coil `CoilHeifDecoder`，支持直接在 Jetpack Compose 相册中秒级展示 HIF 缩略图与大图。
3. **高速免密 Wi-Fi 直连**
   - 通过蓝牙通道读取 SSID/密码，利用 `WifiNetworkSpecifier` 应用内自动直连相机 5GHz Wi-Fi，无需手动输密或跳转系统设置。
4. **无线边拍边传工作台 (FTP Wireless Tethering)**
   - 内置纯 Kotlin 轻量级 FTP 服务器（监听端口 2121），索尼 A6700 按下快门数秒内静默回传照片至手机，支持 100% 像素检焦与 S-Cinetone 还原 LUT 实时套色。
5. **纯 BLE 低功耗无线遥控**
   - 毫安级极低功耗，支持半按 AF 锁定、全按拍摄、视频录制开关、B 门长曝光计时与延时连拍。

---

## 模块结构

- `:app`：主工程入口、CompanionDeviceService、前台服务与 Jetpack Compose Material You UI
- `:core:bluetooth`：索尼 BLE 握手协议、95 字节定位数据打包、CC13 时钟校准、BLE 快门遥控
- `:core:location`：高精度定位引擎、自适应节能调度、离线 GPX 轨迹记录
- `:core:ptp`：PTP/IP 端口 15740 双套接字客户端、A6700 目录遍历、分块并发下载
- `:core:heif`：10-bit 4:2:2 HEIF 软解引擎、EXIF 完整迁移、Coil 图片加载器插件
- `:core:ftp`：本地嵌入式 FTP 边拍边传服务器

---

## 快速构建

项目内已配置完整的隔离编译环境（独立 JDK 17 与 Android SDK 34）。直接运行：

```bash
# 编译并生成 Debug 安装包
./build.sh assembleDebug
```

编译生成的 APK 路径为：
`app/build/outputs/apk/debug/app-debug.apk`
