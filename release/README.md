# Release files / 发布文件

本目录包含 `main-legacy-third-party-nav` 分支的配套部署文件。

- `carplay_hook.jar`：legacy + Luka 通用第三方地图兼容版本（不含 main 的 Amap 专项状态机）
- `libcarplay_hook.so`：与 `main` 分支相同的 native hook
- `maneuver_render`：与 `main` 分支相同的 renderer
- `flag_atlas.rgba`：与 `main` 分支相同的旗帜纹理资源

当前分支真正的 RGI 逻辑差异主要在 `carplay_hook.jar`；其余三个支撑文件与 `main` 共用同一份内容。`carplay_hook.jar`、`libcarplay_hook.so` 和 `maneuver_render` 的校验值见 `SHA256SUMS`。

## English

This directory contains the matched deployment files for the `main-legacy-third-party-nav` branch.

- `carplay_hook.jar`: legacy build plus Luka's generic third-party navigation compatibility fixes, without main's Amap-specific state machine
- `libcarplay_hook.so`: byte-identical to `main`
- `maneuver_render`: byte-identical to `main`
- `flag_atlas.rgba`: byte-identical to `main`

The branch-specific RGI behavior is primarily in `carplay_hook.jar`; the other three support files are shared with `main`. Checksums for `carplay_hook.jar`, `libcarplay_hook.so`, and `maneuver_render` are listed in `SHA256SUMS`.
