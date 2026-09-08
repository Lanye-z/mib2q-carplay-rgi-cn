# Release files / 发布文件

本目录保留 `main-legacy-backup` 的 legacy Java 版本，同时补齐与其他分支共用的 release 支撑文件，便于直接打包部署。

- `carplay_hook.jar`：`main-legacy-backup` 自己的 legacy Java 版本
- `libcarplay_hook.so`：与当前 `main` 同步的 native hook 支撑文件
- `maneuver_render`：与当前 `main` 相同的 renderer
- `flag_atlas.rgba`：与当前 `main` 相同的 renderer 资源

其中 `carplay_hook.jar` 保持本分支原版本不变；本次只补齐缺失的共用支撑文件。`libcarplay_hook.so` 和 `flag_atlas.rgba` 直接复用 `main` 中对应的 Git blob。

校验值见 `SHA256SUMS`。源码构建方法见仓库根目录 `README.md`。

## English

This directory keeps the legacy Java build from `main-legacy-backup` while including the shared release support files used by the other branches.

- `carplay_hook.jar`: branch-specific legacy Java build
- `libcarplay_hook.so`: native hook support file synchronized from current `main`
- `maneuver_render`: renderer identical to current `main`
- `flag_atlas.rgba`: renderer resource identical to current `main`

The branch-specific `carplay_hook.jar` is unchanged by this synchronization.
