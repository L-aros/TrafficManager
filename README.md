# TrafficSIM

TrafficSIM 是一个 LSPosed 模块，用于根据 Wi-Fi SSID/BSSID 规则自动切换默认数据卡（SIM1/SIM2）

> 本项目由 Codex + GPT-5.3-Codex 全程协助开发
>
> 本项目不包含任何联网能力，可放心使用
>
> 如遇更新请优先前往[主仓库](https://github.com/L-aros/TrafficSIM)进行更新



## 应用界面

![7d27abe0445bfcabf96c265b43c106ba](https://github.com/user-attachments/assets/e9dd109b-c720-4300-98c3-ff6c83db88bd)

## 功能特性

- 支持按 `SSID` / `BSSID` 匹配规则
- 支持规则优先级（`priority`）
- 支持冷却时间防抖（`cooldownSec`）
- 支持离开 Wi-Fi 策略（`leaveDelaySec`、`leaveMissThreshold`、`revertOnLeave`）
- 切卡链路支持：
  - LSPosed 广播桥接（优先）
  - Root 命令兜底
- 支持实时状态显示与“一键学习当前 Wi-Fi 规则”
- 支持日志导出与切卡事件通知
- 支持省电模式 / 常驻模式切换

## 当前适配范围

- 当前版本**仅适配小米澎湃 OS 3（HyperOS 3）**。
- 其他 ROM 或系统版本暂未做完整兼容验证，可能出现作用域、权限或系统接口差异导致的不生效问题。

## 环境要求

- Android 10+
- Root（建议开启，以提高兼容性）
- LSPosed（建议 Zygisk 版本）
- 建议双卡设备

## 构建

1. 将 LSPosed API jar 放到：`app/libs/api-82.jar`
2. 构建 Debug 包：

```powershell
.\gradlew.bat :app:assembleDebug
```

输出 APK：

- `app/build/outputs/apk/debug/app-debug.apk`


## 安装与启用

1. 安装 APK
2. 在 LSPosed 中启用模块
3. 勾选推荐作用域（内置）：
   - `com.android.phone`
   - `com.xiaomi.phone`
   - `com.qti.phone`
   - `com.qualcomm.qti.telephonyservice`
   - `com.android.services.telephony`
   - `com.android.telephony`
4. 如果仍不生效，可手动额外勾选 `android` 作用域尝试兼容
5. 重启设备
6. 打开 App，配置规则并开启自动切卡

## 省电/常驻模式说明

- 省电模式：不启动前台常驻服务，不显示常驻通知；依赖系统广播触发，可能延迟或漏触发
- 常驻模式：前台服务常驻更稳定、实时性更好；需要常驻通知且耗电更高

## 基础配置示例

```json
{
  "enabled": true,
  "powerSaveMode": true,
  "screenOnIntervalSec": 20,
  "screenOffIntervalSec": 90,
  "cooldownSec": 90,
  "leaveDelaySec": 180,
  "leaveMissThreshold": 3,
  "revertOnLeave": true,
  "fixedLeaveSlot": null,
  "noWifiSlot": 0,
  "noWifiImmediate": true,
  "logRetentionDays": 7,
  "logMaxMb": 10,
  "appendDefaultRootTemplates": true,
  "rootCommandTemplates": [
    "cmd phone set-default-data-subscription {subId}",
    "cmd phone set-data-subscription {subId}",
    "settings put global multi_sim_data_call {slot}"
  ],
  "rules": [
    {
      "id": "home_wifi",
      "priority": 100,
      "ssid": "MyHomeWiFi",
      "bssid": null,
      "targetSlot": 0
    }
  ]
}
```

- `enabled`: 是否开启自动切卡。
- `powerSaveMode`: true 表示更依赖系统广播触发的省电策略，false 则常驻服务更稳定。
- `screenOnIntervalSec` / `screenOffIntervalSec`: 屏幕亮/灭时触发检查的频率。
- `cooldownSec`: 同一规则命中后的冷却时长，防止抖动。
- `leaveDelaySec` / `leaveMissThreshold`: 离开 Wi-Fi 后经过多少时间、漏报次数才认为脱离。
- `revertOnLeave`: 离开 Wi-Fi 后是否还原原先使用的数据卡。
- `fixedLeaveSlot`: 离开 Wi-Fi 时强制切到的卡（null 表示按逻辑决定）。
- `noWifiSlot` / `noWifiImmediate`: 无 Wi-Fi 时的目标卡与是否立即切换。
- `logRetentionDays` / `logMaxMb`: 日志保留天数与单日志大小上限。
- `appendDefaultRootTemplates`: 是否在自定义 root 指令后自动补齐内置默认模板。
- `rootCommandTemplates`: root 兜底时使用的系统命令模板（可保留多条）。
- `rules`: Wi-Fi 规则列表，`priority` 越大优先级越高，`targetSlot` 0/1 表示 SIM1/SIM2。

## 常见排查

- 确认定位权限、附近设备（Wi-Fi）权限已授予
- 确认系统“定位总开关”已开启
- 查看日志目录：`Android/data/com.laros.lsp.traffics/files/logs/`
- 若 LSPosed 链路失败，检查 root 兜底链路是否可用
- 若自定义 `rootCommandTemplates` 包含 `accelerometer_rotation` / `user_rotation`，会被安全拦截以避免误改方向锁定

## 无 Wi-Fi 切卡说明

- 设置 `noWifiSlot` 可在 **未连接任何 Wi‑Fi** 时自动切换到指定卡槽
- 触发条件受 `leaveDelaySec` 与 `leaveMissThreshold` 影响（避免频繁抖动）
- 若希望无 Wi‑Fi 立即切换，可设置 `noWifiImmediate: true`（不受 `cooldownSec` 限制）
- App「高级」页提供“无 Wi‑Fi 立即切换”开关
- App「高级」页提供目标卡槽选择（关闭 / SIM1 / SIM2）

## 下一步开发计划（TODO）

- [ ] 增加更多 ROM 兼容适配（AOSP / OneUI / ColorOS 等）
- [ ] 增加作用域与链路自检页（自动提示缺失项）
- [ ] 增加规则调试模式（展示命中过程与未命中原因）
- [ ] 增加配置导入导出与版本迁移能力
- [ ] 增加更细粒度的通知与日志筛选
- [ ] 补齐自动化测试与兼容性回归用例

## 开源协议

MIT，详见 [LICENSE](LICENSE)
