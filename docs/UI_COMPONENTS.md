# AlphaPro UI 按钮规范

## 原则：统一样式，不强制同一大小

用户于 2026-10-28 明确：统一的是圆角、颜色层级、边框、文字/图标关系、加载与禁用行为；实际尺寸必须根据页面协调性选择，不能把卡片内按钮都做成大型主操作。

所有操作按钮使用公共组件，不在页面各自定义一套外观。尺寸是独立的场景参数，允许普通按钮、卡片按钮与主操作采用不同高度和内边距。

## 视觉尺寸档位

| size | 最小可见高度 | 水平/垂直内边距 | 图标/间距 | 建议场景 |
| --- | --- | --- | --- | --- |
| Compact | 32dp | 12dp / 4dp | 16dp / 6dp | 仪表盘卡片中的重连、断开、Wi-Fi 操作 |
| Regular（默认） | 40dp | 20dp / 8dp | 20dp / 8dp | 普通页面、弹窗、设置和辅助操作 |
| Large | 48dp | 24dp / 12dp | 20dp / 8dp | 查看器下载等需要强调的主操作 |

这些是视觉最小值，不是固定高度；大字体/换行允许自然撑高。文字按钮采用同档垂直内边距，水平内边距为 Compact12dp、其余16dp。

可见按钮大小与触摸目标是两件事：保留 Material 的最小交互区域机制，不通过关闭触摸扩展来制造小按钮。不要用任意固定高度裁切文字。图标按钮默认48dp触摸区，但其中图标不必与文字按钮同大；拍照快门保留专用160dp圆形操作区。

## 样式与语义层级

- AlphaButton：实心主操作。
- AlphaTonalButton：浅色辅助操作，例如仪表盘 Wi-Fi。
- AlphaOutlinedButton：描边次操作。
- AlphaTextButton：低强调、取消及重试。
- AlphaIconButton / AlphaFilledIconButton：圆形图标操作。
- AlphaFilterChip：胶囊筛选。
- AlphaSingleChoiceSegmentedButtonRow / AlphaSegmentedButton：同一圆角体系的互斥选择。
- AlphaShutterButton：保留相机快门辨识度的圆形专用按钮，只发出普通点击，不实现 AF/曝光协议。
- AlphaButtonProgress：随场景尺寸缩放的行内加载指示。

颜色来自当前 Material You 主题。删除/结束等危险语义使用 AlphaButtonDefaults 的 error/onError 颜色方法，不用任意品牌色代替语义色。深色查看器需要内容色覆盖时，必须同时处理禁用内容色，不单独给 Text 着色绕过状态。

卡片、列表导航、底部导航、Switch 和 RadioButton 仍属于各自语义组件，不强制变为胶囊操作按钮。

## 使用方式

普通按钮组件支持 onClick、modifier、enabled、colors 和 size，保留 RowScope 内容槽。不暴露页面级 shape/contentPadding；尺寸通过 size 选择，不影响共享外观。


dashboard example:

```kotlin
AlphaTonalButton(
    onClick = onConnect,
    size = AlphaButtonSize.Compact,
    modifier = Modifier.fillMaxWidth()
) {
    Text("一键秒连")
}

AlphaButton(onClick = onExport, size = AlphaButtonSize.Large) {
    Icon(Icons.Rounded.Download, null, Modifier.size(AlphaButtonSize.Large.iconSize))
    Spacer(Modifier.width(AlphaButtonSize.Large.iconSpacing))
    Text("下载 / 导出")
}
```

避免给 Compact 的子 Text 强行指定 Large 文本样式；让公共组件按档位提供标签样式。按钮忙碌时，调用方保留原有 enabled 状态及业务逻辑，只使用 AlphaButtonProgress(size = 同一档位) 表示进度。

## 一致性检查

运行 `node scripts/check-ui-components.mjs`，检查所有 UI 页面是否绕过公共组件直接创建 Material 按钮，或覆写公共圆角/内边距。**检查不再禁止低于48dp的视觉尺寸**；统一样式不是统一高度。

脚本只检查源码契约，不能替代窄屏、大字体、深浅色、禁用态和页面协调性的真机检查。业务回调、BLE/PTP/Wi-Fi、导出及偏好逻辑保持不变。

## 验收记录

2026-10-28，用户确认 U2 版本验证 OK 并授权提交。Debug/Release 构建、正式签名校验和全部53处公共按钮调用的一致性检查通过；此记录不代表所有设备尺寸或字体档位均已逐一覆盖。
