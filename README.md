# N1 蓝牙遥控器

把安卓手机变成**斐讯 N1** 盒子的蓝牙遥控器，包括开关机。盒子的固件里删掉了休眠功能，这里用一个盒子端的小助手补上了「待机 / 唤醒」。原装的斐讯蓝牙遥控器也能用这套开关机。

**中文** | [English](README.en.md)

![build](https://github.com/wuuduf/n1-bt-remote/actions/workflows/build.yml/badge.svg)

| 组件 | 装在哪 | 作用 |
|---|---|---|
| `n1-remote.apk` | 安卓手机（Android 9+） | 遥控器界面。所有按键都走蓝牙 HID，不需要 Wi-Fi |
| `n1-standby-helper.apk` | N1 盒子 | 无障碍服务：收到电源键后切换「假待机」，待机时吞掉其他按键 |
| `scripts/box.sh` | 电脑 | 通过网络 ADB 给盒子装助手、改原装遥控器的按键映射 |
| `tools/confirm_pairing.py` | 电脑 | 没有遥控器时，替你在盒子上点「配对」 |
| `tools/selftest.py` | 电脑 | 一条命令自检盒子端：按键映射、长按、TV 键、待机、唤醒 |

App 界面跟随手机的系统语言：中文手机显示中文，其他语言显示英文。

## 测试环境

- 盒子：斐讯 N1，固件 `N1_A0_V2.32_A00_0904_NS`（Android 7.1.2，内核 3.14）
- 手机：Xiaomi 13 Ultra（Android 13）
- 前提：盒子的网络 ADB（5555 端口）已开启。测试机出厂时就开着，以 root 运行，不需要授权。可以用 `python3 tools/adb_tcp.py <盒子IP> --probe` 检查：返回 `device::ro.product.name=p230;...` 就说明开着。

其他固件（比如基于 v2.19 的第三方固件）的电源键逻辑不一样，没有测试过。

## 快速开始

### 1. 准备安装包

从 Releases 下载 `n1-remote.apk` 和 `n1-standby-helper.apk`，或者自己编译（见下文）。

### 2. 给盒子装待机助手

电脑需要装 `adb`（Android platform-tools）。

```bash
scripts/box.sh <盒子IP> install-helper
```

这一步会做三件事：

- 安装助手；
- 启用它的无障碍服务；
- 把盒子的 `power_key_definition` 从 1（关机）改成 0（无动作），这样任何遥控器发出的原生电源键都不会再把盒子关死。

### 3. 手机配对

1. 手机装上 `n1-remote.apk`，打开后允许「附近的设备」权限。
2. 填上盒子的蓝牙地址：盒子 设置 → 关于 → 状态 里能看到，也可以用 `python3 tools/adb_tcp.py <盒子IP> 'settings get secure bluetooth_address'` 查。
3. 点「连接」。手机和盒子上都会弹出配对确认，两边都点「配对」。
   - 盒子会同时弹出两个确认框（手机版设置和 TV 版设置），最上面那个才有效。
   - **没有遥控器的话**，先在电脑上运行下面的命令，再去手机上点「连接」。它只会给指定名字的设备点确认：

     ```bash
     python3 tools/confirm_pairing.py <盒子IP> "<手机的蓝牙名称>"
     ```
4. 状态变绿，显示「已连接」，就可以用了。以后打开 App 会自动重连。
   - 断线以后（比如盒子断电重启、手机走出范围），App 会每 5 秒自动重连一次，持续 3 分钟；回到 App 时会重新开始重连。点「断开」是主动断开，不会自动重连。
   - **App 必须在前台**：Android 规定蓝牙 HID 设备只能由前台应用使用，一切到别的 App 或锁屏，系统就会断开遥控。回到 App 后大约 2 秒自动重连。
   - 连着盒子时，**手机侧面的音量键调的是盒子的音量**，按住可以连续调。没连上时，音量键照常调手机自己的音量。

### 4. 开关机和其他功能

- **OTT 键（红色）**：开关机。按一下进入待机：回到桌面、画面变黑、关掉 HDMI 信号、CPU 上限降到 500 MHz，并通过 CEC 让电视待机。再按一下恢复。
- **TV 键**：只开关电视，通过 HDMI-CEC 实现，盒子本身不受影响。需要电视支持并开启 CEC。
- **触控板**：点电源键中间的「触控板」按钮，方向键会换成一块滑动区域。**划一下移一格**（不管划多长多快）；**划完手指停住不松**，就像按住方向键一样连续移动，松手即停；轻点一下就是 OK。再点「方向键」可以换回来，App 会记住你的选择。
- **长按**：按住方向键或音量键，盒子会自动连发。

## 用原装斐讯遥控器

原装遥控器（设备 ID `7045:1820`）的电源键发的是 `KEY_POWER`，系统会在所有应用之前把它处理掉，助手收不到。下面这条命令把它布局文件里的电源键改成 TV 键，这样它和手机发的就一样了。改之前会先备份：

```bash
scripts/box.sh <盒子IP> patch-remote
```

```bash
scripts/box.sh <盒子IP> unpatch-remote
```

第二条用来还原。改动的内容只有一行，见 [box-config/](box-config/)：`key 116 POWER` → `key 116 TV`。

兼容款遥控器的设备 ID 可能不一样。配对后用 `dumpsys input` 查到它的 `vendor` 和 `product`，再写一份 `Vendor_XXXX_Product_YYYY.kl`。如果 `/system/usr/keylayout/` 里没有这个 ID 的文件，可以放在 `/data/system/devices/keylayout/` 下面，不用动系统分区。

## 为什么是「假待机」

这台 N1 的固件做不到真正的待机和遥控开机，详细分析见 [docs/findings.md](docs/findings.md)：

- 斐讯把 Android 的休眠删掉了：`PowerManagerService.goToSleepNoUpdateLocked()` 直接返回 false。所以电源键只能设成「关机」。
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
| 语音 | 0xCF Voice Command | KEY_VOICECOMMAND | VOICE_ASSIST（盒子上没有语音应用，按了无反应） |
| OTT 电源 | 0x89 Media Select TV | KEY_TV | TV → 待机助手切换待机 |
| TV 电源 | 0x8D Media Select Program Guide | KEY_PROGRAM | GUIDE → 待机助手通过 CEC 开关电视 |

描述符是 14 位位图（加 2 位填充，共 2 字节），里面没有字母键。所以盒子不会把手机当成实体键盘，屏幕软键盘照常能弹出来。

## 升级 App 后按键不全

盒子会把配对时拿到的按键描述符存下来，以后不再重新读取。所以如果新版 App 增加了按键（比如 2.2.0 新增了 TV 键），盒子不会认得新键。两种办法任选：

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

加上 `--phicomm` 时，还会检查原装斐讯遥控器的映射：冒充设备 ID `7045:1820` 按两次电源键。

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
