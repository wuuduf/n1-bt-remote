# N1 蓝牙遥控器

**专为斐讯 N1「W 大（webpad）固件」设计的遥控与开关机方案。**

W 大固件沿用了斐讯官方 V2.32 的系统框架，而 V2.32 删掉了 Android 的待机功能：电源键一按就真关机，关机后只能拔电重启。这个项目把安卓手机变成 N1 的蓝牙遥控器，并在盒子上加一个小助手，补上用遥控器「待机 / 唤醒」的能力。原装的斐讯蓝牙遥控器也能用这套开关机。

**中文** | [English](README.en.md)

![build](https://github.com/wuuduf/n1-bt-remote/actions/workflows/build.yml/badge.svg)

| 组件 | 装在哪 | 作用 |
|---|---|---|
| `n1-remote.apk` | 安卓手机（Android 9+） | 遥控器界面。所有按键都走蓝牙 HID，不需要 Wi-Fi |
| `n1-standby-helper.apk` | N1 盒子 | 装上后打开一次就自动完成设置；之后在后台接收电源键，切换「假待机」，待机时吞掉其他按键 |
| `scripts/box.sh` | 电脑（可选） | 通过网络 ADB 安装助手，以及其他维护操作 |
| `tools/confirm_pairing.py` | 电脑 | 没有遥控器时，替你在盒子上点「配对」 |
| `tools/selftest.py` | 电脑 | 一条命令自检盒子端：按键映射、长按、TV 键、待机、唤醒 |

App 界面跟随手机的系统语言：中文手机显示中文，其他语言显示英文。

## 适用固件

| 项目 | 内容 |
|---|---|
| 固件 | W 大（webpad）修改版 v2.2，文件名 `N1_mod_by_webpad_v2.2_20180920.img` |
| 镜像 SHA-256 | `6adffe7f9085ddd5411930d7fb1829f2b06c281255dfcf9b73822884be208863` |
| 底包 | 斐讯官方 V2.32：`N1_A0_V2.32_A00_0904_NS`，2018-09-04 编译，Android 7.1.2，内核 3.14 |
| 测试手机 | Xiaomi 13 Ultra（Android 13） |

在盒子上确认版本号（需要先开启网络 ADB，见下一条）：

```bash
python3 tools/adb_tcp.py <盒子IP> 'getprop ro.build.display.id'
```

返回 `N1_A0_V2.32_A00_0904_NS` 就对了。

- **为什么说是为 W 大固件设计的**：开关机部分针对的正是 V2.32「删掉待机、关机叫不醒」这个问题。具体做法依赖这套固件的细节，包括电源键设置项、Amlogic 的 HDMI 和 CEC 接口、以 root 运行的网络 ADB 等。分析过程见 [docs/findings.md](docs/findings.md)。
- **斐讯官方原版 V2.32**：系统框架和 W 大 v2.2 逐个文件一致，理论上同样适用，但没有单独测试过。
- **基于 v2.19 的固件**（比如 Rush 精简版）：据说保留了原生待机，用不着这里的开关机部分。这一点没有验证过。手机遥控部分是标准的蓝牙 HID，按理在这类固件上也能用。
- **前提**：盒子的网络 ADB（5555 端口）要开着。在这台装着 W 大固件的盒子上，它开机就以 root 运行，而且不需要授权。可以用 `python3 tools/adb_tcp.py <盒子IP> --probe` 检查，返回 `device::ro.product.name=p230;...` 就说明开着。

## 快速开始

### 1. 准备安装包

从 Releases 下载 `n1-remote.apk` 和 `n1-standby-helper.apk`，或者自己编译（见下文）。

### 2. 给盒子装待机助手

**装上，再打开一次，就设置好了。** 两种装法任选：

- **不用电脑**：把 `n1-standby-helper.apk` 拷到 U 盘，用盒子自带的文件管理器安装，然后在盒子桌面打开「N1 待机助手」。需要先有一种能操作盒子的东西：原装遥控器、USB 鼠标，或者已经配好对的手机。
- **用电脑**（要装 `adb`）：执行下面的命令，它会安装助手并替你打开一次。

  ```bash
  scripts/box.sh <盒子IP> install-helper
  ```

助手第一次打开时，会通过盒子本机的 root ADB 自动完成三件事，然后在界面上逐项显示 ✔ / ✘：

- 启用自己的无障碍服务，用来接收遥控器的电源键；
- 把盒子的 `power_key_definition` 从 1（关机）改成 0（无动作），这样任何遥控器发出的原生电源键都不会再把盒子关死；
- 把原装斐讯遥控器的电源键改成待机（改之前会先备份；界面上可以一键改回原样）。

之后每次开机、每次助手更新，它都会自动检查一遍。如果设置被重置了（比如无障碍服务被关掉），会自己修复。界面上还有「还原并卸载」按钮，可以把盒子恢复成出厂状态。

### 3. 手机配对

1. 手机装上 `n1-remote.apk`，打开后允许「附近的设备」权限。Android 13 及以上还会问通知权限，建议允许，通知栏的按钮要靠它显示。
2. 找到盒子：先在盒子上打开蓝牙设置页并停在那里，盒子只有在这个页面打开时才能被搜到。没有遥控器的话，可以用 `python3 tools/adb_tcp.py <盒子IP> 'am start -a android.settings.BLUETOOTH_SETTINGS'` 打开。然后在手机上点「查找」，在「附近的设备」里选「p230（斐讯 N1）」，p230 是 N1 默认的蓝牙名称。列表只显示能连的经典蓝牙设备，只支持 BLE 的手环、耳机仓之类不会出现。
   - 也可以不扫描，直接填盒子的蓝牙地址再点「连接」。地址在盒子 设置 → 关于 → 状态 里能看到，也可以用 `python3 tools/adb_tcp.py <盒子IP> 'settings get secure bluetooth_address'` 查。
3. 选中盒子后会自动开始配对。手机和盒子上都会弹出配对确认，两边都点「配对」。
   - 盒子会同时弹出两个确认框（手机版设置和 TV 版设置），最上面那个才有效。
   - **没有遥控器的话**，先在电脑上运行下面的命令，再去手机上选中盒子（或点「连接」）。它只会给指定名字的设备点确认：

     ```bash
     python3 tools/confirm_pairing.py <盒子IP> "<手机的蓝牙名称>"
     ```
4. 状态变绿，显示「已连接」，就可以用了。以后打开 App 会自动重连。
   - 断线以后（比如盒子断电重启、手机走出范围），App 会每 5 秒自动重连一次，持续 3 分钟；回到 App 时会重新开始重连。点「断开」是主动断开，不会自动重连。
   - **切到后台也不断开**：连上以后，App 会在通知栏挂一条「已连接」通知（前台服务）。切到别的 App 或锁屏，连接都会保持，回来马上能用。通知上有两个按钮：「待机/唤醒」和「断开」。
   - 点了「断开」，通知就会消失。这时 App 不在前台，系统会照常断开蓝牙遥控（Android 只让前台应用当蓝牙 HID 设备），回到 App 后大约 2 秒自动重连。不给通知权限的话，连接照样能在后台保持，只是看不到这条通知。
   - 连着盒子时，**手机侧面的音量键调的是盒子的音量**，按住可以连续调。没连上时，音量键照常调手机自己的音量。

### 4. 开关机和其他功能

- **OTT 键（红色）**：开关机。按一下进入待机：回到桌面、画面变黑、关掉 HDMI 信号、CPU 上限降到 500 MHz，并通过 CEC 让电视待机。再按一下恢复。
- **TV 键**：只开关电视，通过 HDMI-CEC 实现，盒子本身不受影响。需要电视支持并开启 CEC。
- **播放/暂停键（橙色）**：看视频时暂停、继续。它是标准的媒体键，播放器先收到，播放器不处理的话再交给系统的媒体控制。这个位置原来是语音键，但盒子上没有语音应用，按了没反应，2.6.0 起改成了播放/暂停。从旧版升级的话，要按下一节更新一次盒子存的按键描述符。
- **触控板**：点电源键中间的「触控板」按钮，方向键会换成一块滑动区域。**划一下移一格**（不管划多长多快）；**划完手指停住不松**，就像按住方向键一样连续移动，松手即停。停住约 0.75 秒后开始连续移动，这时触控板会变蓝，显示「连续移动中」；轻点一下就是 OK。再点「方向键」可以换回来，App 会记住你的选择。
- **长按**：按住方向键或音量键，盒子会自动连发。

## 用原装斐讯遥控器或其他蓝牙遥控器

原装遥控器（设备 ID `7045:1820`）的电源键发的是 `KEY_POWER`，系统会在所有应用之前把它处理掉，助手收不到。助手第一次打开时，会自动把它布局文件里的电源键改成 TV 键，这样它和手机发的就一样了。同时把它的语音键（盒子上没有语音应用，按了没反应）改成播放/暂停，和手机 App 的橙色键一样（助手 1.4 起）。助手界面上也可以随时改回原样。用电脑的话，也可以用下面两条命令分别修改和还原，修改前同样会先备份：

```bash
scripts/box.sh <盒子IP> patch-remote
```

```bash
scripts/box.sh <盒子IP> unpatch-remote
```

第二条用来还原。具体改了哪几行，见 [box-config/](box-config/)。

**小米蓝牙语音遥控器**（设备 ID `2717:32b8`，助手 1.5 起）：固件里没有它专用的按键布局，默认的 `Generic.kl` 会让它的电源键没反应、主页键回不了桌面（被当成键盘上的 Home 键）、语音键只发一个 F5。助手改键时会顺带给它加一份专用布局：电源键改成待机，主页键回桌面，语音键改成播放/暂停，其他键不变。它的麦克风用的是小米自己的协议，这台盒子用不了。布局只在遥控器连接时读取，加好以后让遥控器重新连一次（关掉盒子蓝牙再打开，然后按一下遥控器）。

**其他蓝牙遥控器**（助手 1.5 起）：没有专用布局的遥控器都用 `Generic.kl`，它的电源键在 N1 上本来也是按了没反应。助手改键时会把 `Generic.kl` 里的电源键也改成 TV 键，所以这类遥控器按电源键同样能待机/唤醒。改之前会先备份原版，改回原样时整份恢复。注意外接键盘如果有电源键，也会变成待机。

电视通过 HDMI-CEC 发来的电源信号（盒子上的 `cec_input` 设备）也用 `Generic.kl`，而且电视关机时会发一个。为了不让它把盒子来回切换，待机助手会忽略来自 `cec_input` 的电源键，和改之前一样什么也不做。

兼容款遥控器的设备 ID 可能不一样。配对后用 `dumpsys input` 查到它的 `vendor` 和 `product`，再写一份 `Vendor_XXXX_Product_YYYY.kl`。如果 `/system/usr/keylayout/` 里没有这个 ID 的文件，可以放在 `/data/system/devices/keylayout/` 下面，不用动系统分区。

## 为什么是「假待机」

W 大固件（底包是官方 V2.32）做不到真正的待机和遥控开机，详细分析见 [docs/findings.md](docs/findings.md)：

- 斐讯在官方 V2.32 里把 Android 的休眠删掉了：`PowerManagerService.goToSleepNoUpdateLocked()` 直接返回 false。W 大固件没有改动这部分，所以电源键只能设成「关机」。
- 真关机以后，没有任何东西能把它叫醒：N1 没有红外接收头，关机时蓝牙也会断电，只能拔电重插。
- 所以助手做的是：盒子保持运行，只关掉画面、HDMI 和电视。蓝牙一直连着，所以随时能唤醒。

助手截获电源键用的是无障碍服务。具体的操作（写 sysfs、发 CEC）需要 root，而普通 App 被 SELinux 拦着，所以助手通过 `127.0.0.1:5555` 调用盒子本机的 root adbd 来执行。这条连接只在盒子内部走，不经过网络。

## 按键对照

| 按键 | 手机发送（HID Consumer） | 盒子内核键码 | Android 键值 |
|---|---|---|---|
| OK | 0x41 Menu Pick | KEY_SELECT | DPAD_CENTER |
| 上 / 下 / 左 / 右 | 0x42–0x45 | KEY_UP/DOWN/LEFT/RIGHT | DPAD_* |
| 返回 | 0x224 AC Back | KEY_BACK | BACK |
| 主页 | 0x223 AC Home | KEY_HOMEPAGE | HOME |
| 菜单 | 0x40 Menu | KEY_MENU | MENU |
| 音量 + / − | 0xE9 / 0xEA | KEY_VOLUMEUP/DOWN | VOLUME_UP/DOWN |
| 播放 / 暂停 | 0xCD Play/Pause | KEY_PLAYPAUSE | MEDIA_PLAY_PAUSE（2.6.0 起，原来是没反应的语音键） |
| OTT 电源 | 0x89 Media Select TV | KEY_TV | TV → 待机助手切换待机 |
| TV 电源 | 0x8D Media Select Program Guide | KEY_PROGRAM | GUIDE → 待机助手通过 CEC 开关电视 |

描述符是 14 位位图（加 2 位填充，共 2 字节），里面没有字母键。所以盒子不会把手机当成实体键盘，屏幕软键盘照常能弹出来。

## 升级 App 后按键不全

盒子会把配对时拿到的按键描述符存下来，以后不再重新读取。所以如果新版 App 增加或改了按键（比如 2.2.0 新增了 TV 键，2.6.0 把语音键改成了播放/暂停），盒子不会认得新键。两种办法任选：

- 在手机和盒子上都取消配对，再重新配对；
- 不重新配对，直接替换盒子存的那份描述符：

  ```bash
  scripts/box.sh <盒子IP> update-descriptor <手机蓝牙地址>
  ```

  它会先备份盒子的蓝牙配置，然后关蓝牙、替换、再开蓝牙。手机的蓝牙地址可以在 设置 → 我的设备 → 全部参数 里看到。

## 注意事项

- **盒子的 adbd 同一时间只接受一个 TCP 连接。** 如果电脑上的 `adb connect` 一直挂着，助手就执行不了待机命令（它会重试 3 次）。用完 adb 记得 `adb disconnect`；`scripts/box.sh` 和 `tools/adb_tcp.py` 都会自动断开。
- **安全**：N1 的网络 ADB 以 root 运行、不需要授权，同一局域网里的任何设备都能完全控制盒子。这是固件本身的问题，这个项目只是利用了它。不要把盒子放在不可信的网络里。
- **不要在盒子上打开 TV 设置的关机界面**（`android.intent.action.SHOW_SHUTDOWN`）。它带倒计时，几秒后会真关机，之后只能拔电重启。
- 待机时盒子照常运行，只是 CPU 上限降到 500 MHz，所以耗电仍然比真关机高。实际耗电我没有测过。
- TV 键开关电视时，盒子无法读到 CEC 的回复，助手只能自己记录电视当前是开还是关。如果你用电视自己的遥控器开关过电视，这个记录就会和实际不一致，这时多按一下 TV 键就好。
- 手机音量键转发：代码已经写好，但还没在实体按键上测通。测试用的那台手机的音量 + 键本身是坏的，内核从来没收到过它的中断。
- 关 HDMI 信号、通过 CEC 让电视待机，需要盒子接着电视，并且电视开启了 CEC（各品牌叫法不同：Anynet+、SimpLink、Bravia Sync 等）。没接电视时，这部分只验证了命令执行成功。
- 断电重启后，盒子是正常开机状态，助手会自动启动。

## 编译

需要 JDK，以及 Android SDK 里的 `platforms;android-35` 和 `build-tools;35.0.0`。不需要 Gradle 或 Android Studio。

```bash
./build.sh
```

产物放在 `dist/`：

- `n1-remote.apk`：手机 App
- `n1-standby-helper.apk`：盒子端助手
- `n1-tools.dex`：盒子端测试工具

SDK 路径用 `ANDROID_HOME` 指定，默认是 Homebrew 的 `android-commandlinetools`。签名密钥默认是 `n1remote.keystore`，第一次编译时自动生成，已经被 gitignore 忽略。之后要一直用同一个密钥，否则 Android 不允许覆盖安装。

### 自动编译（GitHub Actions）

每次推送到 `main`、打 `v*` 标签或提交 PR，[`.github/workflows/build.yml`](.github/workflows/build.yml) 都会做这几件事：

1. 装好 SDK；
2. 检查脚本语法；
3. 编译三个产物，打包成 artifact 上传。

默认用一把临时生成的密钥签名，这样的 APK 适合测试，但**不能覆盖安装**用你自己密钥签名的版本。如果想让 CI 用你的密钥签名，在仓库的 Settings → Secrets 里加两项：

- `KEYSTORE_B64`：`base64 -i n1remote.keystore` 的输出；
- `KS_PASS`：密钥密码。

**发版**：配好这两个 Secret 后，推送一个 `v*` 标签，CI 就会自动编译、签名，并发布 Release。Release 的说明文字取自标签的附注信息，CI 会在后面附上 SHA-256 校验值和这次编译的链接：

```bash
git tag -a v2.3.1 -F release-notes.md
```

```bash
git push origin v2.3.1
```

为防止误发，打了标签但没配正式密钥时，编译会直接失败，不会用临时密钥发出无法覆盖安装的包。

## 一键自检

装好待机助手后，可以用一条命令检查盒子端是否一切正常。只需要 Python 3，不需要装 adb：

```bash
python3 tools/selftest.py <盒子IP>
```

它会在盒子上注册一个虚拟蓝牙遥控器，走的路径和手机完全一样。然后逐项检查并输出 PASS / FAIL：

- 内核键码和描述符一致，Android 用的是 `Generic.kl`，设备类型是遥控器（不是键盘或手柄）；
- 11 个按键都以正确的键值到达 Android；
- 方向键能长按（长按连发靠这个）；
- TV 键被助手收到；
- 待机时 CPU 上限降到 500 MHz、其他按键被吞掉；唤醒后恢复。

加上 `--phicomm` 时，还会检查两个遥控器的改键：分别冒充设备 ID `7045:1820`（斐讯原装）和 `2717:32b8`（小米蓝牙语音遥控器），确认 Android 用的是改过的按键布局，并且按两次电源键能进入待机再唤醒。

整个过程大约一分钟，期间盒子画面会跟着跳动，结束时回到桌面。运行时不要有别的 adb 连着盒子。

## 开发与测试工具

`dist/n1-tools.dex` 推到盒子上以后，用 `app_process` 运行：

- `n1.tools.UhidTest`：通过 `/dev/uhid` 注册一个总线类型为蓝牙的虚拟 HID 遥控器，按指定的键。走的内核和 Android 路径跟真蓝牙遥控器完全一样，可以在不碰真设备的情况下验证按键映射。
  - `UHID_ID=vvvv:pppp` 可以冒充指定的设备 ID，`UHID_NAME=名字` 可以指定设备名。
  - 参数里的数字表示按下哪一位，`wN` 表示等待 N 毫秒，`hB:N` 表示按住第 B 位 N 毫秒。
- `n1.tools.BtCtl off|on|state`：开关盒子的蓝牙。

```bash
python3 tools/adb_tcp.py <盒子IP> --push dist/n1-tools.dex /data/local/tmp/n1-tools.dex
```

```bash
adb shell 'UHID_ID=7045:1820 CLASSPATH=/data/local/tmp/n1-tools.dex app_process /system/bin n1.tools.UhidTest 3000 0 w5000 0'
```

上面的例子冒充原装遥控器，按两次电源键（第 0 位）。注意：测试待机助手时不要用前台 adb 会话，因为它会占住 adbd，助手就执行不了命令。用 `setsid` 放到后台跑，再断开，具体做法见 [docs/findings.md](docs/findings.md)。

## 第三方内容

- 按键图标来自 [Material Icons](https://github.com/google/material-design-icons)，Apache License 2.0。
- `box-config/` 里的按键布局文件来自盒子固件，文件头声明为 AOSP 的 Apache License 2.0。

## License

[GPL-3.0](LICENSE)。引用的 Apache License 2.0 素材（见上一节）按各自的协议保留。
