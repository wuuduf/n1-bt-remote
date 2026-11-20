# 技术调查记录

这里记录做这个项目时，在斐讯 N1 上查到的东西。测试机的固件是 `N1_A0_V2.32_A00_0904_NS`（Android 7.1.2，内核 3.14.29，arm64），时间是 2026 年 11 月。下面写的是这台机器上实际看到的情况，别的固件版本可能不一样。

## 1. 网络 ADB

- 端口 5555。`service.adb.tcp.port=5555` 是固件开机时设的，断电重启后依然开着。`persist.adb.tcp.port` 是空的。
- `ro.adb.secure=0`，所以不需要密钥授权。连上以后 shell 的 `id` 是 `uid=0(root)`，SELinux 上下文是 `u:r:su:s0`。
- **adbd 同一时间只接受一个 TCP 客户端。** 电脑的 `adb connect` 挂着时，第二个客户端发出 `CNXN` 后收不到任何回复，只能等到超时。所以：
  - `tools/adb_tcp.py` 每执行一条命令就断开；
  - 待机助手连本机 adbd 时会重试；
  - 在盒子上测试助手时，测试进程要用 `setsid sh -c "…" </dev/null >/dev/null 2>&1 & sleep 1` 脱离会话，然后马上断开连接。如果没有那个 `sleep 1`，会话结束得太快，子进程还没脱离就会被一起杀掉。

## 2. 电源键与休眠

用反编译 `/system/framework/oat/arm64/services.odex` 的办法查。Android 7 的 OAT 文件里内嵌了完整的 dex（`dex\n037`），可以直接切出来，用 `dexdump -i -d` 反汇编：

- `PhoneWindowManager.whichPowerKeyDefinition()` 读的是 `Settings.System power_key_definition`。设了 `ro.platform.has.tvuimode` 时默认值是 1。对应的常量是：`POWER_KEY_SUSPEND = 0`、`POWER_KEY_SHUTDOWN = 1`、`POWER_KEY_RESTART = 2`。
- `powerPress()`：
  - 值为 1 时调用 `startPowerActivity()`。如果 `com.android.tv.settings.ShutdownActivity` 已经在前台，就调用 `PowerManager.shutdown()`；否则用 `android.intent.action.SHOW_SHUTDOWN` 打开这个界面。**这个界面带倒计时，不操作的话几秒后会真关机。**
  - 值为 0 时走 AOSP 原来的逻辑：`mShortPressOnPowerBehavior=1` → `goToSleep()`。
- **`PowerManagerService.goToSleepNoUpdateLocked()` 的方法体只有 `return false`。** 斐讯把休眠彻底删掉了：`input keyevent POWER` 不会灭屏，CEC 发来的 Standby 也不会让盒子休眠。
- `dumpsys window policy` 里还能看到：`mLongPressOnPowerBehavior=3`，也就是长按电源键会直接关机，不弹确认。

所以在这个固件上，原生电源键只有两种结果：关机（1），或者什么都不发生（0）。

## 3. 关机后为什么叫不醒

- **没有红外接收头**：`/system/etc/remote.conf` 和驱动的 `map_tables` 里倒是配了斐讯红外遥控（`factory_code 0xbd020001`，电源码 `0x45`），但红外中断（`/proc/interrupts` 里的 `keypad`，GIC 228）的计数从开机起一直是 0。用手机红外对着盒子发 NEC 码，计数也没有变化，内核调试日志里一条记录都没有。手机这边是确认发出去了的：`ConsumerIrService` 有对应的日志。
- **蓝牙不会作为唤醒源**：设备树的 `bt-dev` 节点只有 `gpio_reset`，没有 host-wake 引脚，也没有「掉电时保持供电」的配置。实测关机后，盒子 ping 不通，ADB 连不上，手机蓝牙也连不上，只能拔电重插。
- **网上说「遥控器能唤醒 N1」**，指的是基于官方 v2.19 的第三方固件（比如恩山论坛 rush 的极限精简固件），那一代固件还保留着休眠。这条没有在本项目里验证。

## 4. 假待机：权限从哪来

- SELinux 是 Enforcing。`/dev/cec` 的 DAC 权限是 666，但标签是 `cec_device`；HDMI 和 framebuffer 的 sysfs 文件属于 root 或 system。普通 App 都碰不到。
- Amlogic 的 `system_control` 服务可以代为写 sysfs，但它要求 `droidlogic.permission.SYSTEM_CONTROL`，这是 signature 级别的权限，第三方 App 拿不到。
- 最后的办法：助手（普通 App，只申请了 `INTERNET` 权限）连接 `127.0.0.1:5555`，用 root adbd 执行命令。用的是 `box-helper/src/app/n1standby/BoxShell.java` 里实现的最小 ADB 协议。

待机和恢复执行的命令（在 HDMI 没接时验证过，命令都能执行成功）：

| 动作 | 命令 |
|---|---|
| 回桌面 | `input keyevent HOME` |
| 画面 | `echo 1 > /sys/class/graphics/fb0/blank`（恢复时写 0） |
| HDMI 信号 | `echo 0 > /sys/class/amhdmitx/amhdmitx0/phy`（恢复时写 1） |
| CEC 让电视待机 | `printf '\100\066' > /dev/cec`，即 `0x40 0x36` |
| CEC 唤醒电视 | `0x40 0x04`（Image View On），再发 `0x4F 0x82 <物理地址>`（Active Source） |
| CPU 上限 | 待机时把 `cpu0/cpufreq/scaling_max_freq` 写成 500000，唤醒时恢复原来的值（原值存在 `/dev/.n1remote_maxfreq`）。这台盒子的调频策略只有 `hotplug`、`interactive`、`performance`，没有 `powersave`；4 个核共用一个调频策略，频率档位是 100–1512 MHz 共 7 档 |
| 状态标记 | `/dev/.n1remote_standby`，放在 tmpfs 上，重启后自然消失 |

## 5. 按键怎么送到助手手里

- 无障碍服务开启 `flagRequestFilterKeyEvents` 后，能在按键派发给应用之前拿到它。**但电源键不行**：它在 `interceptKeyBeforeQueueing` 里被清掉了 `ACTION_PASS_TO_USER` 标志，而 `AccessibilityInputFilter` 会直接放过不带这个标志的事件，所以服务根本看不到。
- 于是改用 HID Consumer `0x89`（Media Select TV）。它在内核里变成 `KEY_TV`（377），`Generic.kl` 再把它映射成 `KEYCODE_TV`（170）。这台盒子上没有其他程序处理这个键，实测按下去没有任何副作用。
- 原装斐讯遥控器的布局文件 `Vendor_7045_Product_1820.kl` 本来写的是 `key 116 POWER`，改成 `key 116 TV` 之后，它的电源键就和手机发的是同一个键了。这台机器的 `/system` 本来就是以 rw 挂载的，dm-verity 也没开。
- TV 键用的是 HID Consumer `0x8D`（Program Guide）。它在内核里变成 `KEY_PROGRAM`（362），`Generic.kl` 再映射成 `GUIDE`，同样由助手截获。这个键用来通过 CEC 单独开关电视。
- 布局文件的查找顺序是：先找 `/system/usr/keylayout/`，再找 `/data/system/devices/keylayout/`（`libinput.so` 里的路径字符串就是这个顺序）。

## 6. 蓝牙 HID（手机端）

- 手机用 `BluetoothHidDevice` 注册，子类别是 `SUBCLASS2_REMOTE_CONTROL`。Bluedroid 收到 HID 设备后，会在盒子上用 `/dev/uhid` 的 `UHID_CREATE` 建一个输入设备（这个内核不支持 `UHID_CREATE2`，返回 `ENOTSUP`）。
- 描述符一开始用的是 0x000–0x3FF 的全范围 Consumer 数组。结果内核顺带映射出了 `BTN_MISC` 和 `ABS_VOLUME`，Android 把设备多算成了「手柄」（Classes 里多了 0x100）。后来改成只声明用到的键的位图，设备类型就只剩 `KEYBOARD | DPAD | EXTERNAL`。
- **MIUI（Android 13）的一个坑**：`registerApp()` 实际已经注册成功，返回值却是 false。如果据此重试，第二次注册会因为「已有 App 注册」而失败。所以现在只认 `onAppStatusChanged(registered=true)` 这个回调，超过 1.5 秒没收到才重试。
- **配对确认**：手机发起配对时，盒子会同时弹出 `com.android.settings` 和 `com.android.tv.settings` 两个确认框，TV 版的在最上面。SSP 数字比较大约 35 秒超时。
- **描述符存在盒子上**：Bluedroid 会把配对时拿到的描述符存进 `/data/misc/bluedroid/bt_config.conf`（`HidDescriptor = <hex>`），之后重连不会重新查询。所以手机改了描述符以后，要么重新配对，要么像本项目从 12 键升到 13 键时那样，直接替换存储的那一行：
  1. 用 `BtCtl off` 关蓝牙，**再等几秒**。蓝牙栈报告「已关闭」之后，还会把内存里的配置写回文件一次；太早修改会被覆盖。
  2. 同时替换 `bt_config.conf` 和 `bt_config.bak` 里那一行。用 `cat 新文件 > 原文件` 的方式写，保留属主、权限和 SELinux 标签。
  3. 隔几秒确认没被覆盖，再用 `BtCtl on` 开蓝牙。手机重连后，`getevent -p` 里就能看到新增的 `0179`（KEY_TV）了。

## 7. 蓝牙链路的时序（触控板为什么这样设计）

- 手机和盒子之间的蓝牙链路空闲时会进入嗅探（sniff）模式。实测按键报告大约**每 225 毫秒才成批送达一次**：间隔集中在 225 / 450 / 675 毫秒，同一窗口里的多个报告会在 1–4 毫秒内一起到达。
- 所以手机端按时间生成的连发（比如每 150 毫秒发一个方向键）会被打乱：有的窗口里到两个，焦点一下跳两格；有的窗口里一个没有，又停住了。第一版触控板「每划过 40dp 发一个键」也有同样的问题，快划时几个键挤在一起。
- 现在的做法：划一下只发一对「按下 + 松开」，最多就是被推迟一点，不会变成多格。连续移动则是「划完停住 = 一直按住这个键」，连发由盒子本地按 Android 的标准节奏生成（先停 500 毫秒，之后每 50 毫秒一次），节奏均匀，不受蓝牙影响。实测按住时 `repeatCount` 稳定地每 50 毫秒加一。
- 手机 App 改不了嗅探参数：`BluetoothHidDevice` 没有提供电源管理相关的接口。

## 8. Android 对 HID 设备应用的限制

- Android 13 的 `HidDeviceService` 会监听已注册应用的前台状态。应用一旦降到前台服务级别以下（切到后台、锁屏），服务就会主动 `unregisterApp`，连接随之断开。日志里能看到蓝牙服务自己调用了 `unregisterAppNative`，App 端并没有调用。
- App 在 `onResume` 时检查：如果注册已被注销，就重新注册，然后自动重连，实测约 2 秒恢复。如果要在后台也保持连接，需要一个前台服务（常驻通知）。

## 9. 其他

- 语音键：键值是 `VOICE_ASSIST`。系统会去启动 `android.speech.action.WEB_SEARCH`，但这台盒子上没有能处理它的应用。原装遥控器的布局文件里语音键也映射成这个值，结果应该一样。
- 测试用的这台小米手机，实体音量 + 键的中断计数开机 5 天一直是 0（`/proc/interrupts` 里的 `volume_up`），键本身是坏的。所以「手机音量键转发给盒子」这个功能只写了代码，还没用实体键测通。
- 盒子上有一个 `uiautomator`，可以配合 `input tap`，在没有遥控器的情况下操作界面（`tools/confirm_pairing.py` 就是这么做的）。小米手机要额外打开「USB 调试（安全设置）」，才允许 `input tap` 这类模拟点击。
