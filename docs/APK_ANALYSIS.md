# 国行一代 APK 静态分析记录

分析日期：2026-11-08（北京时间）。本记录区分上传文件中的静态证据、推断和未验证的设备行为。

## 输入身份

| 项目 | 观察值 |
| --- | --- |
| 包名 | `com.heytap.health` |
| 显示名称 | 欢太健康 |
| 版本 | `4.22.11_8c76bdd_250530` / `4221100` |
| 最低／目标 Android | API 29 / API 34 |
| 包大小 | 122,211,419 字节 |
| DEX 数量 | 18 |
| 原生 ABI | 仅 `arm64-v8a` |
| SHA-256 | `5b9ce81310236a3c02a603f4032ab9bc9ad482e95abcc1a654aeef4c31933ce1` |
| 内部 APK 签名检查 | Google `apksigner verify` 通过，v3=true |
| 签名证书 SHA-256 | `38bd2bbfa86127bd03c11195b507321358fe2ba116c54a16c713b91e39fa393e` |

内部签名检查确认文件签名自洽；本次没有与官方分发渠道独立比对证书，因此不据此认定安装包来源。

## 普通安卓手机适配线索

Manifest 没有 `sharedUserId`，四个 `uses-library` 都声明 `required=false`。请求 OPPO/OPlus 私有权限不能单独证明整包仅能用于 OPPO 手机。

资源 `health_sleep_phone_measure_description_tip16`（`0x7f151485`）和 `health_sleep_phone_measure_setting_tip3`（`0x7f1514bc`）包含对非 OPPO 手机后台清理策略的说明，表明开发者预期存在非 OPPO 手机使用场景。是否能在具体手机上成功配对，还需运行证据。

## 一代仍有代码和资源

- `classes15.dex` 的 `DeviceManagerUtils.<clinit>` 直接包含 `W301CN`、`W301CN-Demo`、`W501CN`、`W501CN-Demo`。
- 仍存在 `ONE_PLUS_WATCH` 分支、`W301CN&01000100/01000200/01000300` 型号组合。
- `settings_oneplus_watch`（`0x7f152235`）为 `OnePlus Watch`。
- `health_blood_oxygen_support_devices`（`0x7f150ad4`）仍列 OnePlus Watch。
- `DeviceParams` 的蓝牙名称匹配使用前缀、非单词分隔符与十六进制后缀。新 App 保留全部设备展示入口，名称命中仅标注“候选”。

型号表部分字符由 `manager.c` 读取 `DM_CHARACTERS` 配置再拼接。按其字符索引还原时 W301CN/W501CN 落入 OnePlus group 4；直接型号字符串和 OnePlus 分类提供独立旁证。配置来源的完整复核与实际广播名称仍需结合运行环境，不把名称推断等同型号认证。

## 经典蓝牙调用链

关键分支同时由局部 JADX 输出与 DEX 引用定位：

1. `DeviceModel.onePlus / g7()`，`y7()` 合并 OnePlus／realme 分支，`C7()` 包含 `y7()`。
2. `ao.g$a.a()`（元信息 `NodeAbility.kt`）在 `C7()` 时调用 `dt0.i.a(4, mac)`。
3. `dt0.i.a()` 把 `MainModule.connectionType` 设为 4。
4. `ns0.a.c(4)` 为真，BLE 分支 `ns0.a.b(4)` 为假。
5. `ht0.b.u()` 创建 `jt0.d`，日志名 `BRClientDevice`。
6. `jt0.d.a0()` 分别用 `kt0.e.m` 和 `kt0.e.n` 创建主、数据连接。
7. `kt0.e` 中这两个 UUID 分别为 `db764ac8-4b08-7f25-aafe-59d03c27bae3`、`db764ac8-4b08-7f25-aafe-59d03c27bae4`。
8. `jt0.b.Y()` 使用 secure `BluetoothDevice.createRfcommSocketToServiceRecord()`；`e0()` 调用 `socket.connect()`。
9. 主通道成功后约 1 秒再连接数据通道；原实现等待两个通道连接后才上报底层 transport connected。

Socket 层成功回调不依赖先发送一个固定私有首包。上层业务会继续初始化与绑定。因此新 App 可以验证双通道，但不能凭 Socket 成功宣称原厂绑定完成。

上述 UUID 是 APK 内配置，本次尚未取得实体手表 SDP 回包，不能保证每个固件均实际公开相同服务。

## BLE 与上层业务的界限

APK 另有 `00002760-08c2-11e1-9073-0e8ac72e1011` 一类 OPPO BLE 服务。已追踪的一代路径进入经典蓝牙工厂；这些 BLE UUID 不作为本项目的一代主通道。BLE/GATT 只作备用诊断。

官方配对任务序列中存在登录、账户、连接、校时、设备信息、SSOID、BindKey、BindDevice 和上报。`TaskBindDevice` 包含网络调用；但是 `TaskBindKey` 会按设备能力跳过包括一代所属 `y7()` 的部分分支。不能据此笼统宣称“一代必须云端密钥才能建立蓝牙连接”，也不能宣称已恢复完整离线绑定。

## 本次没有验证的事项

- 上传包在用户手机上的具体故障原因。
- 手表实际固件、广播和 SDP 内容。
- 双通道在实体手表上能否持续连接。
- 设备业务协议、账号所有权验证、健康同步、消息通知、表盘与升级。

本次只静态读取 APK，没有安装或执行原 APK/native 库，没有调用原厂账号接口。项目中只包含原创诊断实现与分析事实，不分发原包或反编译源码。

## Android 原始参考

- [蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [经典蓝牙连接](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)
- [BLE 扫描](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices)
- [GATT 连接](https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server)
