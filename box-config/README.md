# 原装斐讯遥控器的按键布局

`Vendor_7045_Product_1820.kl` 是斐讯蓝牙遥控器（设备 ID `7045:1820`）在 N1 固件里的按键布局，位置是 `/system/usr/keylayout/`。文件头声明为 AOSP 的 Apache License 2.0。

- `original/`：固件 V2.32 自带的原版
- `modified/`：把电源键映射成 TV 键，交给待机助手处理

两者只差一行：

```diff
-key 116   POWER
+key 116   TV
```

在盒子上直接改：`scripts/box.sh <盒子IP> patch-remote`，还原用 `unpatch-remote`。
