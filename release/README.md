# Release files / 发布文件

本目录包含 `ios27-amap-v38-merge` 分支的完整配套安装文件。

- 源码提交：`3c1b5ab`
- Java Build ID：`2026-08-30-3c1b5ab`

| 文件 | 说明 | SHA-256 |
| --- | --- | --- |
| `carplay_hook.jar` | 当前融合版 Java，包含 main 路径引导逻辑及 V38 Amap 兼容状态机 | `94d0356d9a12730aca6dd430552c3aaf15ef9021ce3ddee87a760de966f5ad92` |
| `libcarplay_hook.so` | main native hook，加上 V38 route-generation、physical-head、alignment 和 distance-fresh metadata | `87d10f67fbb3dc142642d899977bab0a6eb4009f61d3bcd873d0cce9e01511f7` |
| `maneuver_render` | 当前 main QNX renderer | `f86c7a44288d55c352837b3432874cf81836431929e625e7cded42d5664e993e` |

部署时请先备份原文件，然后同时替换以上三个文件。`libcarplay_hook.so` 和 `maneuver_render` 需要保留可执行权限。独立校验清单见 `SHA256SUMS`，源码构建说明见仓库根目录 `README.md`。

## English

This directory contains the complete matched installation set for the
`ios27-amap-v38-merge` branch, built from commit `3c1b5ab`. The Java Build ID is
`2026-08-30-3c1b5ab`.

Back up the original files and replace `carplay_hook.jar`,
`libcarplay_hook.so`, and `maneuver_render` together. Preserve executable
permissions on the two QNX binaries. See `SHA256SUMS` for machine-readable
checksums and the repository root `README.md` for build instructions.
