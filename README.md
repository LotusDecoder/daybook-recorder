# 录音豆伴侣 · Daybook Recorder

一款适用于 Soundcore Work（D3200）的非官方 Android 客户端。它通过蓝牙低功耗（BLE）接收用户所选录音豆上的录音，在手机上保存为可播放的 Ogg Opus 文件，也可以把完整录音上传到用户自己的 HTTPS 接收服务。

[下载 APK 和校验文件](https://github.com/LotusDecoder/daybook-recorder/releases/latest)

## 使用

1. 在 Android 8.0 或更高版本上安装，打开蓝牙，授予附近设备权限。Android 8–11 的蓝牙扫描需要系统定位权限。
2. 点「扫描并选择录音豆」，选中自己的设备，再开启后台接收。录音由录音豆的实体键控制。手机通过前台服务维持连接，已收录音可在「手机副本」中播放或导出。
3. 只在手机上保存时，保持上传地址为空。需要上传时，在「设置与维护」填写接收服务的完整 HTTPS 地址和可选的 Bearer Token。保存地址后，队列也会发送此前已收齐、尚待上传的录音。
4. 默认开启「连接后自动同步遗留录音」。配置接收服务后，经该功能补收的录音会在手机和接收端核验成功后从豆内清理。希望长期在录音豆上保留原件时，请在首次同步前关闭此开关，并保持「核验中转后自动清理豆内副本」关闭。手机副本和服务器副本各自管理。

## 数据与权限

| 数据 | 存储位置及用途 |
| --- | --- |
| 音频及同步状态 | Ogg 音频、原始分片与 SQLite 索引保存在应用私有目录。音频按普通文件保存，Android 云备份和设备迁移规则均排除这些文件。手动导出时由用户选择目的地。 |
| 录音豆身份 | 手机保存所选设备的蓝牙地址及名称；设备广播标识和硬件序列号使用摘要参与重连校验。更换地址且缺少可验证的设备身份时，需要重新选择设备。 |
| 上传 | 默认地址为空。配置后只通过 HTTPS 向该地址发送音频、录制时间、时长、文件编号、稳定的设备代号及音频摘要。接收端可用代号关联同一设备的多条录音。客户端拒绝 HTTP 重定向。 |
| 凭据 | Bearer Token 以 Android Keystore 管理的 AES-GCM 密钥加密保存。更换完整上传地址时需要重填 Token。清空地址会清除凭据。 |
| 诊断 | 本地日志记录连接与传输事件。手动复制或导出的诊断可能包含手机型号、固件、时间、录音编号和设备代号。录音权限来自蓝牙；应用不申请手机麦克风权限，也不集成统计或广告 SDK。 |

开机恢复后台连接需在设置中单独启用。省电豁免和通知权限由 Android 管理。不同录音豆固件的同步与清理能力可能有差异。

## 接收服务接口

接收端由用户提供。客户端以 HTTPS POST 发送完整的 `audio/ogg` 请求体；请求头包括 `Idempotency-Key`、`X-Audio-SHA256`、`X-Recorded-At`、`X-Duration-Ms`、`X-Source-File-Id` 和 `X-Source-Device`。配置了凭据时附带 `Authorization: Bearer …`。

接收端应以幂等键处理重试，保存音频并独立计算 SHA-256。用 JSON 回复 `status`（值为 `accepted`）、原始 `import_key` 和实算的 `audio_sha256`。普通上传接受 HTTP 200、201、202；豆内清理的复核要求 200 或 201。接收端应在已保存并重新校验实际文件后返回清理所需的回执。测试用接收器只在本机回环地址处理合成音频。

## 构建和测试

需要 JDK 17 和 Android SDK Platform 36。配置 `ANDROID_HOME` 或 Android Studio 的 SDK 位置，然后运行 Gradle Wrapper。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease
```

`:app:assembleRelease` 生成未签名的 release APK，可用自己的 Android 签名密钥签署。Android 覆盖安装要求包名和签名证书均一致。此项目的应用 ID 是 `io.github.lotusdecoder.daybookrecorder`，当前版本为 `0.2.4`（versionCode 1）。应用对接收地址与 Token 做关联校验，并在后台重连时先核对候选设备的身份。

仪器测试使用 Android 15 / API 35 模拟器、Python 3.10+ 和 OpenSSL。测试会清空模拟器中此应用的数据。先启动一个专用且空白的模拟器，在一个终端运行以下命令。

```sh
python3 scripts/prepare_android_tests.py
python3 scripts/test_receiver.py
```

另开终端运行 `./gradlew :app:connectedDebugAndroidTest`。测试用证书仅打入仪器测试 APK，两个本机 HTTPS 接收器分别监听 18769 和 18770。

## 许可证与致谢

本项目以 MIT 许可证发布。设备协议、加解密和接收流程参考了 Rocky Shi 的 Soundcore 项目；所参考文件在上游采用 MIT 的版本中内容一致。应用内附双方的 MIT 许可文本。[上游项目](https://github.com/tacshi/Soundcore) · [上游许可提交](https://github.com/tacshi/Soundcore/pull/13)

Gradle Wrapper 按其原有许可证分发，见 licenses/Gradle-LICENSE.txt。
