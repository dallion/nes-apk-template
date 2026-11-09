# 架构与设计取舍

## 单游戏模板

一个游戏目录对应一个独立 Android 应用，启动直接进入内置游戏。替换 ROM 和配置后重新构建 APK；特殊 Mapper 改版通过修改内核源码适配。

模板使用 Kotlin、Android View、LibretroDroid 和源码构建的 FCEUmm。复用 LibretroDroid 的音视频、输入和状态接口，保留 FCEUmm 的 Mapper 修改能力。范围限于 FC / NES 单游戏封装，不引入游戏库、在线下载、动态 Mapper 插件或多内核管理。

上游源码固定提交并纳入 `native/`，让本地内核修改可以直接追踪。来源、版本及许可证见 [DEPENDENCIES.md](../DEPENDENCIES.md) 和 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

## 模块职责

- `app/` 管理界面、Android 输入、菜单、存档和金手指列表。
- `frontend/` 将 LibretroDroid 的 Java/Kotlin 接口及 CMake 原生宿主接入构建。
- `native/fceumm/` 提供 libretro 核心；Mapper 和核心金手指适配在此实现。
- `tools/resolve-game.ps1` 与 `tools/resolve-game.sh` 分别解析 Windows 与 Unix 的游戏目录，供对应打包入口和 Gradle 使用。

UI 和输入处理留在 Android 层，Mapper 适配留在内核。ROM 从 assets 写入应用私有目录，以完整文件路径加载，满足 FCEUmm 的 `need_fullpath` 要求。

## 输入和生命周期

触屏、手柄按键及摇杆统一汇入输入状态。不同来源独立记录，释放一个来源不会清除另一个来源仍按住的按钮；断连、切后台和暂停时释放输入，避免粘键。

实体 Gamepad / Joystick 存在时隐藏触屏区并扩大游戏区域；普通键盘和虚拟输入设备不触发此布局。布局切换前释放触屏输入，不重建模拟器。

桌面入口采用 `singleTask`，用于返回已有游戏 Activity。进程被系统回收后不自动恢复即时状态。

变速复用 `GLRetroView.frameSpeed`，限制为 1～4 倍。L1/R1 按设备和键码记录按下边沿，过滤长按和重复 DOWN。FPS 使用渲染回调和单调时钟计算，暂停时清空采样，不将显示值乘以游戏倍率。

## 存储隔离

存档目录按 ROM 内容 SHA-256 隔离，避免更换 ROM 后误读另一版本的数据。SaveRAM、即时存档和金手指记录分开保存。

即时存档携带格式标记、内核指纹、长度和内容摘要；损坏或内核指纹不匹配时拒绝读取。内核指纹由 FCEUmm 源文件生成，核心修改可能使旧即时存档失效，SaveRAM 独立保留。

存储写入经过临时文件、备份和替换；读取时恢复未完成替换留下的备份。切后台和打开菜单保存 SaveRAM，即时存档由用户手动保存和读取。

## 打包一致性

Windows 使用 PowerShell，Linux 和 macOS 使用 Bash。各自负责环境准备、输入校验和 APK 交付。Gradle 按宿主平台调用解析器并编译同一份应用源码。打包入口建立配置快照，Gradle 复查当前输入并将本次构建的 APK 和元数据暂存到快照旁。对应的打包入口核对身份及 ROM/CHT 摘要后交付。两套实现使用相同的配置规则、快照格式和测试数据。

身份、命名、覆盖保护和资源清理的完整约束统一维护在 [打包规则](packaging.md)。
