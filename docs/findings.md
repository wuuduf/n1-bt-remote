# 技术调查记录

这里记录做这个项目时，在斐讯 N1 上查到的东西。测试机装的是 W 大（webpad）修改版 v2.2 固件，底包是斐讯官方 `N1_A0_V2.32_A00_0904_NS`（Android 7.1.2，内核 3.14.29，arm64），时间是 2026 年 11 月。下面写的是这台机器上实际看到的情况，别的固件版本可能不一样。

## 0. 固件来源

镜像文件是 `N1_mod_by_webpad_v2.2_20180920.img`，SHA-256 为 `6adffe7f9085ddd5411930d7fb1829f2b06c281255dfcf9b73822884be208863`。它是 Amlogic USB Burning Tool 的 v2 镜像，一共 21 个条目，其中 system 分区是 Android sparse 格式的 ext4。

- **测试机装的就是这个镜像**：解出 system 分区后，和盒子上的同名文件比对，`build.prop`、`framework/oat/arm64/services.odex`、`services.jar`、`framework.jar`、`usr/keylayout/Generic.kl`、`etc/remote.conf` 的 MD5 全部相同；`/system/app`（31 个）和 `/system/priv-app`（29 个）的列表也完全一致。boot 分区的 ramdisk 是加密的（开头是 `5a07b210…`），没有比对。
- **底包是官方 V2.32，不是 v2.19**：`build.prop` 里写的是 `ro.build.display.id=N1_A0_V2.32_A00_0904_NS`、`ro.build.date=Tue Sep 4 16:24:36 CST 2018`、`ro.build.user=jenkins`、`ro.build.host=slave2`，指纹是 `Phicomm/p230/p230:7.1.2/NHG47L/20180904:user/dev-keys`。
- **去掉待机的是斐讯官方，不是 W 大**：`services.odex` 的 OAT 头里记录了 dex2oat 的命令行，里面是 `--dex-file=out/target/common/obj/JAVA_LIBRARIES/services_intermediates/javalib.jar`、`--boot-image=out/target/product/p230/...` 这种路径。只有在完整的 AOSP 源码编译环境里才会产生它们。所以这个文件就是斐讯官方编译出来的原件，W 大的修改没有动到它。下面第 2 节反编译出的「休眠被删」，因此是官方 V2.32 自带的。
- 斐讯没有公开过固件源码。这个改动最初来自斐讯自己，还是来自 Amlogic 提供的开发套件，现在无法区分。

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
- 原装斐讯遥控器的布局文件 `Vendor_7045_Product_1820.kl` 本来写的是 `key 116 POWER`，改成 `key 116 TV` 之后，它的电源键就和手机发的是同一个键了。语音键是 `key 194 VOICE_ASSIST`，助手 1.4 起顺带改成 `key 194 MEDIA_PLAY_PAUSE`，和手机 App 的橙色键一致；两处在同一次修改里完成，助手只有两处都改好才显示「已改好」，所以从 1.3 升级后打开一次助手就会补上。改动后用 uhid 以 `7045:1820` 身份创建虚拟设备，Android 仍然选用这份布局文件，说明文件能正常解析（布局文件有一行写错，整份都不会加载）。真遥控器的语音键还没实测：这台盒子目前没和原装遥控器配对。这台机器的 `/system` 本来就是以 rw 挂载的，dm-verity 也没开。
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
- 手指静止多久算「停住」：2.4.0 从 400 毫秒改成 250 毫秒。盒子还要再等 500 毫秒才开始连发，所以原来手指停下后要将近 1 秒才连续移动。停住不到 1 秒就松手，看起来就像「停住不动」。现在约 0.75 秒开始连续移动；按住生效时触控板会一直变蓝，显示「连续移动中」，看得出已经进入连续模式。停顿不到 250 毫秒的划动仍然只发一对「按下 + 松开」。
- 验证方法：测试版里临时加一个调试广播，让 App 往自己的窗口发一组合成触摸（划一下、停住指定时长、松开），走的是和手指一样的界面、触控板逻辑和蓝牙链路。然后读盒子 `dumpsys input` 里 `RecentQueue` 的按键记录，看连发次数。按住 1.5 秒和 2.5 秒交替跑 8 轮，盒子的最大 `repeatCount` 都和算出来的相符（约 15 次和 35 次）；快速划 4 次，每次都只收到一对按下 + 松开。调试广播没有留在正式版里，任何 App 都能发它。
- 排查时的坑：盒子在待机时，助手会挡住电源键以外的所有按键，触控板看起来就完全「不连续」。唤醒后 HDMI 画面要好几秒才回来，这期间很容易再按一次电源键，结果又进了待机。先看 `/dev/.n1remote_standby` 在不在，再看按键。
- 手机 App 改不了嗅探参数：`BluetoothHidDevice` 没有提供电源管理相关的接口。

## 8. Android 对 HID 设备应用的限制

- Android 13 的 `HidDeviceService` 会监听已注册应用的前台状态。应用一旦降到前台服务级别以下（切到后台、锁屏），服务就会主动 `unregisterApp`，连接随之断开。日志里能看到蓝牙服务自己调用了 `unregisterAppNative`，App 端并没有调用。
- App 在 `onResume` 时检查：如果注册已被注销，就重新注册，然后自动重连，实测约 2 秒恢复。
- 2.4.0 起，蓝牙部分移到了前台服务 `RemoteService`（类型 `connectedDevice`）。只要连着盒子，或者正在把掉线的连接找回来，它就挂着通知运行，进程保持在前台服务级别，`HidDeviceService` 不会再注销它。实测（小米 13 Ultra，Android 13）：切到桌面 30 秒后，进程状态是前台服务（`procState=3`），盒子的 `/proc/bus/input/devices` 里还能看到手机这个输入设备。断开以后服务退出前台，行为和以前一样。
- MIUI 默认不允许 adb 注入按键和触摸（`INJECT_EVENTS`）。通知按钮的逻辑是用上面那个临时调试广播直接调服务测的，锁屏只能手动测。
- **通知权限的坑**：App 第一次打开时，大约 1 秒就连上盒子并发出通知，那时用户还没回答通知权限弹窗。没有权限时发出的通知会被直接丢掉（`dumpsys notification` 里 `numBlocked=1`、`numPostedByApp=0`），不会等有了权限再显示。所以权限结果回来时、以及每次回到 App 时，都会重新发一次通知。
- **断开以后又被连回去**：在通知里点「断开」后服务退出前台，App 不在前台时 `HidDeviceService` 会注销它。回到 App 重新注册时，系统会在 `onAppStatusChanged` 里把上次连过的设备交回来，旧代码会顺手连上它。现在用户主动断开后，就不再接这个设备，要等用户自己点「连接」。
- **注册时序的坑**：旧进程被杀后，蓝牙服务要过一小会儿才注销完它的注册。如果新进程在这期间就去注册，底层会返回「忙」（`register_app() returned 4`）。可服务端已经把这个 uid 记成「已注册」，之后每次重试都会被 `failed because another app is registered` 挡回来，一直卡住。现在的做法是：重试前先调用一次 `unregisterApp()`，清掉这条属于自己的失效记录。实测连续强制重启 3 次，每次都能连上。

## 9. 助手怎么自己完成设置

- App 安装时，Android 不会运行它的任何代码；无障碍服务又必须先在设置里启用才会启动。所以助手（1.3 起）加了一个能在盒子桌面打开的设置界面（`SetupActivity`）。第一次打开时，它通过 `127.0.0.1:5555` 的 root adbd 完成全部配置：启用无障碍服务、`power_key_definition=0`、修改原装遥控器的按键布局。之后在界面上逐项显示 ✔ / ✘。所有配置逻辑都集中在 `Setup.java` 一个地方，`scripts/box.sh install-helper` 只负责安装和打开。
- `BootReceiver` 会接收 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED` 和 `MEDIA_MOUNTED`（原因见下），发现配置不完整就自动修复。它不会去动原装遥控器的按键布局，那项由用户在界面上选择。实测：手动关掉无障碍服务、把电源键改回 1 之后，覆盖安装一次助手，就自动修好了（日志 `boot check: ready=true`）。
- **这套固件不给第三方 App 发开机广播。** 在 `ActivityManagerService.broadcastIntentLocked` 里，对 `BOOT_COMPLETED` 和 `LOCKED_BOOT_COMPLETED` 的每个清单接收器都做了检查：系统应用保留；其他应用只有包名在 `mBootCompletedWhiteListApps` 里才保留，否则直接移除。这个白名单读自 framework-res 的 `array/config_boot_completed_white_list`（`0x01070054`），而它是**空的**。所以真重启时，开机广播只发给了 `/system` 里的应用，在 root shell 里手动发给助手的开机广播也被丢掉了（广播历史里派发时间为空）。
- 但存储挂载广播 `MEDIA_MOUNTED`（`file` scheme）不受这个限制，开机时第三方 App 也收得到（日志里 Link2SD 就是因此被唤起的）。所以助手把它当作开机触发；插 U 盘也会触发一次检查，检查本身没有副作用。
- 在 adb shell 里用 `(sleep 1; reboot) &` 这种方式放到后台的命令，会在会话结束时被一起杀掉，盒子并没有重启。要重启就直接在前台执行 `reboot`，连接断开是正常的。
- 自检时发现的一个坑：助手的 ADB 客户端原本只有一个 3 秒超时，而且对整条命令都生效。一次完整配置要调用好几次 `settings`，每次大约 1 秒，结果超时被当成「连不上」，界面显示全是 ✘，可实际上配置已经成功了。现在握手阶段 3 秒超时（用来尽快发现 adbd 被占用），命令执行阶段最多等 30 秒。

## 10. 其他

- **小米蓝牙语音遥控器**（`2717:32b8`，BLE / HOGP，在盒子上叫「小米蓝牙语音遥控器」）：所有按键发的都是 HID 键盘页（0x07）的用法码。电源 0x070066（KEY_POWER），语音 0x07003E（KEY_F5），方向键 0x070052/50/4F/51，OK 0x070028（KEY_ENTER，这台固件的 `Generic.kl` 把 28 映射成 DPAD_CENTER），主页 0x07004A（KEY_HOME），返回 0x0700F1（KEY_BACK），菜单 0x070065（KEY_COMPOSE），音量 0x070080/81。Android 把它归为非字母键盘，所以不影响屏幕键盘弹出。
  - 用 `Generic.kl` 时，主页键变成 MOVE_HOME（键盘上把光标移到行首），电源键因为 `power_key_definition=0` 没反应，语音键只是 F5。麦克风数据走 `/dev/hidraw*`，用的是小米自己的协议，`dumpsys input` 里 HasMic=false。
  - 助手 1.5 起，从 `Generic.kl` 生成 `Vendor_2717_Product_32b8.kl`，只改 116→TV、102→HOME、63→MEDIA_PLAY_PAUSE。自检用 uhid 以 `2717:32b8` 创建虚拟设备，确认 Android 选用了这份布局，并且电源键能进入待机再唤醒。
  - 按键布局只在设备连接时读取。关掉盒子蓝牙再打开以后，这个 BLE 遥控器要按一下键才会重新连上。
- **`Generic.kl` 的电源键**（助手 1.5 起改成 TV）：所有没有专用布局的遥控器和键盘都用它。`cec_input`（Amlogic 的 HDMI-CEC 输入设备）也用它，`getevent -lp` 显示它只能发 KEY_POWER。`/sys/class/cec/fun_cfg` 是 `0x2f`，打开了「电视关机时盒子跟着关」，所以电视待机时 CEC 驱动会通过 `cec_input` 发 KEY_POWER。改成 TV 以后，这会变成「切换待机」：盒子本来就在待机的话，反而会被唤醒，还可能和电视来回切。所以 `StandbyService` 按设备名忽略来自 `cec_input` 的 TV 键。自检用 uhid 创建一个也叫 `cec_input` 的虚拟设备来验证这一点。
- 助手启动时，用本机 adb 查询现在是不是待机。安装刚结束时 adbd 常常还被安装方占着，查询会失败；1.4 及之前失败了就当作「醒着」，待机中按方向键会作用到黑屏后面的桌面。1.5 起查询失败会每 10 秒重试一次，最多 5 次。

- **扫描找盒子**（App 2.5.0 起的「查找」）：盒子只有在手机版设置的蓝牙页面打开时才能被搜到（`android.settings.BLUETOOTH_SETTINGS` 对应 `com.android.settings/.Settings$BluetoothSettingsActivity`）。打开后，手机的经典蓝牙扫描能扫到它，名字是 `p230`（N1 的型号），类型是经典蓝牙（`DEVICE_TYPE_CLASSIC`）。实测一次扫描大约 12 秒，周围扫到约 80 个设备，其中约 75 个是只支持 BLE 的设备（大多没有名字），列表里直接过滤掉了。扫描权限用 `BLUETOOTH_SCAN` 加 `neverForLocation`，Android 12 起不需要定位权限。

- 第 7 位原来是语音键（0xCF Voice Command → `VOICE_ASSIST`）。盒子上没有任何应用处理 `VOICE_SEARCH_HANDS_FREE`、`VOICE_ASSIST`、`ASSIST` 或 `WEB_SEARCH`，`settings get secure assistant` 也是空的，所以按了没反应；N1 本身也没有麦克风。
- 2.6.0 起第 7 位改成播放/暂停（0xCD Play/Pause）。用 uhid 虚拟设备和新描述符在盒子上验证过：内核报 `KEY_PLAYPAUSE`（扫描码 164），`Generic.kl` 映射成 `MEDIA_PLAY_PAUSE`（键值 85）。它和普通按键一样分发，前台播放器先收到，没处理的话 PhoneWindowManager 再交给媒体会话。
- 为什么不让助手把语音键「翻译」成播放/暂停：无障碍服务不能发按键；用 root 跑 `input keyevent` 要启动 Java 进程，在 N1 上慢；用 `sendevent` 往 `aml_keypad` 写 164 也不行，因为它用的 `Vendor_0001_Product_0001.kl` 没有映射 164，要改系统分区。改描述符是最干净的，代价是已配对的盒子要执行一次 `update-descriptor`（或重新配对）。没更新之前，这一位还是旧的语音键，按了没反应，不会出错。
- 测试用的这台小米手机，实体音量 + 键的中断计数开机 5 天一直是 0（`/proc/interrupts` 里的 `volume_up`），键本身是坏的。所以「手机音量键转发给盒子」这个功能只写了代码，还没用实体键测通。
- 盒子上有一个 `uiautomator`，可以配合 `input tap`，在没有遥控器的情况下操作界面（`tools/confirm_pairing.py` 就是这么做的）。小米手机要额外打开「USB 调试（安全设置）」，才允许 `input tap` 这类模拟点击。
