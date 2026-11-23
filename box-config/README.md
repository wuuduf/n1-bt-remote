# 遥控器的按键布局

这里是待机助手（以及 `scripts/box.sh patch-remote`）放到盒子 `/system/usr/keylayout/` 里的按键布局。它们都来自 AOSP，文件头声明为 Apache License 2.0。

## 原装斐讯遥控器（`7045:1820`）

`Vendor_7045_Product_1820.kl` 是固件自带的：

- `original/`：固件 V2.32 自带的原版
- `modified/`：电源键交给待机助手，语音键改成播放/暂停

```diff
-key 194   VOICE_ASSIST
+key 194   MEDIA_PLAY_PAUSE
-key 116   POWER
+key 116   TV
```

## 小米蓝牙语音遥控器（`2717:32b8`）

固件里没有它专用的布局，所以默认用 `Generic.kl`。这个遥控器发的是 HID 键盘类的用法码，用 `Generic.kl` 时有三个键不对：

| 键 | 内核键码 | `Generic.kl` 里的映射 | 问题 |
|---|---|---|---|
| 电源 | 116 KEY_POWER | POWER | N1 上 `power_key_definition=0`，按了没反应，待机助手也收不到 |
| 主页 | 102 KEY_HOME | MOVE_HOME | 这是键盘上把光标移到行首的 Home 键，回不了桌面 |
| 语音 | 63 KEY_F5 | F5 | 没有程序处理 F5；麦克风走小米自己的协议（hidraw），Android 认不到 |

`modified/Vendor_2717_Product_32b8.kl` 就是 `Generic.kl` 改了这三行，其余完全一样：

```diff
-key 63    F5
+key 63    MEDIA_PLAY_PAUSE
-key 102   MOVE_HOME
+key 102   HOME
-key 116   POWER
+key 116   TV
```

文件第一行的注释里有 `made from Generic.kl`，用来标明这个文件是我们放的。还原时只删除带这个标记的文件。

在盒子上直接改：`scripts/box.sh <盒子IP> patch-remote`，还原用 `unpatch-remote`。布局只在遥控器连接时读取，改完要让遥控器重新连一次。
