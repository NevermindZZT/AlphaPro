# AlphaPro (索尼 A6700 适配版) UI 交互原型设计规范

## 1. 设计语言与风格总览 (Design Language & Styling)

### 1.1 Material You (Material 3 Expressive) 设计准则
- **动态色彩 (Dynamic Color)**：优先接入 Android 12+ Monet 动态取色引擎，根据用户系统壁纸自适应配色；未开启动态色彩时使用索尼 Alpha 专业影像主题（钛金黑基底 + 索尼标志性琥珀橙 Accent #E85A19 + 微单科技灰 #2B2B2E）。
- **容器与层级 (Elevation & Surfaces)**：彻底摒弃传统阴影，采用 Material 3 标准的色调表面层级 (Tonal Elevation)：surface -> surfaceContainerLowest -> surfaceContainerLow -> surfaceContainer -> surfaceContainerHigh -> surfaceContainerHighest。
- **现代化便当盒布局 (Bento Grid)**：主仪表盘采用紧凑、高密度的便当盒卡片布局，信息主次分明，视觉节奏现代利落。
- **专业暗色先导 (Dark Theme First)**：作为摄影类伴生工具，默认采用沉浸式暗色主题（深空灰黑背景 #121316），户外强光下支持高对比浅色模式，夜拍场景避免刺眼影响暗视力。
- **规范的矢量图标 (Material Symbols)**：严格遵循设计规范，绝不使用 Emoji 替代图标，统一采用 Google Material Symbols Rounded 矢量图标。
- **相机参数等宽字体 (Monospace Telemetry)**：快门速度、光圈、ISO、经纬度坐标、传输速率使用等宽数字字体，防止数值跳动时界面抖动。

## 2. 信息架构与导航层级 (Information Architecture)
App 采用 Material 3 Navigation Bar (底部导视栏) 作为全局顶层导航，大屏设备自适应为 Navigation Rail。包含 4 个核心一级页面：
1. 仪表盘 (Hub)：A6700 状态便当盒、快捷动作、定位监控、时钟对时
2. 相册传输 (Media)：SD卡照片瀑布流、10-bit HEIF 转码、批量导出
3. 边拍边传 (Tether)：本地 FTP 极速回传、无线联机全屏检视
4. 遥控快门 (Remote)：纯蓝牙低功耗快门、B门倒计时、延时定时器

## 3. 核心页面交互原型设计
完整原型包含四大核心页面及 HEIF 导出策略 BottomSheet，详见本文档。