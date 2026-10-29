# AlphaPro (索尼微单开源全能伴侣)

<p align="center">
  <a href="https://github.com/NevermindZZT/AlphaPro"><img src="https://img.shields.io/badge/GitHub-NevermindZZT%2FAlphaPro-181717.svg?style=flat-square&logo=github" alt="GitHub Repo" /></a>
  <a href="https://github.com/NevermindZZT/AlphaPro/releases"><img src="https://img.shields.io/badge/version-1.0.0-orange.svg?style=flat-square" alt="Version" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg?style=flat-square&logo=android" alt="Platform" />
  <img src="https://img.shields.io/badge/Target%20SDK-37-blue.svg?style=flat-square" alt="Target SDK" />
  <img src="https://img.shields.io/badge/Kotlin-1.9.23-7F52FF.svg?style=flat-square&logo=kotlin" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20(Material%20You)-4285F4.svg?style=flat-square&logo=jetpackcompose" alt="Compose" />
  <img src="https://img.shields.io/badge/Build-Passing-brightgreen.svg?style=flat-square" alt="Build Status" />
</p>

面向索尼 Alpha 全系微单（全面兼容 A7M4, A7R5, A6700, A7C2, A7CR, ZV-E1, A1, A9M3, FX3 等）打造的 Android 现代化伴生应用。彻底解决官方 Creators' App 在 Android 端**“定位频繁断连亮禁止符号”**与**“无法导出/预览 10-bit HEIF 图像”**的两大核心痛点。

---

## 核心特性

1. **永不断连的后台 GPS 守护**
   - **5 秒周期性心跳机制**：向机身持续喂饱看门狗（Watchdog），彻底杜绝静止或锁屏时相机定位图标变红/禁止符号。
   - **系统级开机自动唤醒**：接入 Android **CompanionDeviceManager (CDM)** 在场感知，相机开机发射信标时由系统底层直接唤醒应用服务并弹出连接通知。
   - **毫秒级对时**：连接瞬间自动校准机身微秒时钟（写入 CC13），拍摄即刻完成精准时间对齐。
2. **专业相册浏览与 10-bit HEIF 导出**
   - **RAW + JPG / HIF 智能归并**：机内双格式拍摄同名文件自动聚合成单张照片卡片，直观显示 `RAW + 10b HIF` 醒目角标。
   - **全屏沉浸式检焦看图**：点击相片仅打开全屏高清黑色画廊，支持双指平滑放大、双击放大检焦、平移细节，绝不误触下载。
   - **下载格式决策**：可选择渲染图、RAW 原片或双格式归档，也可直接下载未转换的原始 HIF。默认构建启用 `libheif` + `libde265` 软件解码器，仅编译和打包 `arm64-v8a`，需要 NDK 26.1.10909125 和 CMake 3.22.1。`-PnativeHeif=false` 仅用于显式无原生库构建，此时 JPEG 转换不可用并提示使用原始 HIF。仍需实际验证机型、ABI、颜色和导出流程。JPEG 是有损 8-bit 输出，代码只复制部分 EXIF 标签，不承诺保留全部 EXIF、HDR 或 10-bit 信息。
3. **免密高速 Wi-Fi 直连**
   - 蓝牙自动协商提取凭据，通过 Android `WifiNetworkSpecifier` 在应用内秒级直连相机 5GHz/2.4GHz Wi-Fi 热点。
   - 套接字强制绑定相机局域网，彻底不受手机 5G/4G 蜂窝数据劫持干扰。
4. **纯 BLE 低功耗无线遥控快门**
   - 严格还原两段式物理按压时序：半按对焦锁定 → 保持锁定下压全按快门 → 阶梯释放，对焦曝光一气呵成。
   - 完整支持 **录像启停开关 (Movie REC Down/Up)**、B 门长曝光计时器与延时拍摄序列。
5. **统一设置中心与 Material You 动态主题**
   - 独立的 `SettingsActivity` 二级页面，保持主底栏 4 项大图标开阔舒适。
   - 深度集成 **Material You 动态莫奈取色**（随壁纸变色）与深浅色模式即时切换。
   - 快捷访问官方 GitHub 仓库：`https://github.com/NevermindZZT/AlphaPro`。

---

## 自动化版本号规则

项目在 `app/build.gradle.kts` 中集成了**基于 Git 提交次数的自动化版本号计算机制**：

- **版本名称 (Version Name)**：`1.0.0.<commit_count>`
  - 例如仓库提交了 7 次，则生成的版本名为：`1.0.0.7`
- **版本代号 (Version Code)**：`1 00 00 <commit_count>`（9 位数字）
  - 例如提交第 7 次，对应的 Version Code 为：`100000007`

每次执行编译时，Gradle 会自动通过 `git rev-list --count HEAD` 读取最新提交并动态写入 `BuildConfig`，无需手动维护版本递增。

---

## 通用正式签名密钥 (Universal Release Keystore)

项目内置了正式的 Android 签名凭据，采用 **RSA 4096 位** 高强度算法（有效期 30 年至 2056 年）。该签名不仅用于 AlphaPro 的 Release 与 Debug 构建，亦可作为个人/团队全套 Android 项目的通用签名：

- **密钥文件**：`keystore/release.jks`（受 `.gitignore` 保护不入库）
- **配置属性**：`keystore/keystore.properties`（受 `.gitignore` 保护不入库）
- **配置模板**：`keystore/keystore.properties.example`
- **密钥别名 (Alias)**：`letter`
- **证书 DName**：`CN=Letter, OU=Developer, O=Letter Studio, L=Hangzhou, ST=Zhejiang, C=CN`

在其他 Android 项目中使用方法详见：[keystore/README.md](keystore/README.md)。

---

## 模块架构

```text
AlphaPro/
├── app/                  # 主模块：Navigation, ViewModel, Material You Compose UI, Settings
│   ├── ui/gallery/       # 相册卡片、RAW+HIF归并、PhotoDetailViewerDialog、GroupDownloadDialog
│   ├── ui/hub/           # 仪表盘、Hero 状态卡片、一键直连、外设扫描
│   ├── ui/remote/        # 蓝牙无线快门、两段式拍摄、Movie REC 开关、B门延时
│   └── ui/settings/      # 独立设置 Activity、主题莫奈色彩、版本与 GitHub 仓库入口
├── core/bluetooth/       # 索尼 BLE 握手协议、95/91 字节坐标封包、CC13 对时、快门/录像指令
├── core/ptp/             # PTP/IP 端口 15740 双套接字、Sony Content Catalog 0x923c 目录、断点分块流
├── core/heif/            # HEIF 软件解码、部分 EXIF 复制、JNI 可用性保护、Coil 加载器
├── core/location/        # 自适应高精度定位、防漂移滤波、离线 GPX 轨迹记录
├── core/ftp/             # 本地轻量级 FTP 边拍边传服务器 (即拍即显)
└── keystore/             # 4096 位通用正式签名密钥库与使用说明
```

---

## 快速构建

项目内已配置完整的隔离编译环境（独立 JDK 17 与 Android SDK 37）。直接运行：

```bash
# 编译并生成带正式签名的 Debug 安装包
./gradlew assembleDebug --no-daemon

# 编译带正式签名的 Release 安装包
./gradlew assembleRelease --no-daemon
```

构建产物路径：
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk` (或 `app-release.apk`)

---

## 开源许可证 (License)

本项目采用 **Apache License 2.0** 许可证开放源代码。详情参见 [LICENSE](LICENSE) 文件。
