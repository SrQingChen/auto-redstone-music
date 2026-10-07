# 自动红石音乐 · Auto Redstone Music

<p align="center">
  <img src="https://img.shields.io/badge/状态-Alpha%20测试版-ffb400?style=for-the-badge" alt="Alpha">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2-62b47a?style=for-the-badge" alt="Minecraft">
  <img src="https://img.shields.io/badge/NeoForge-26.1.2.87+-e08a2e?style=for-the-badge" alt="NeoForge">
  <img src="https://img.shields.io/badge/Java-25-007396?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/许可-GPL--3.0-blue?style=for-the-badge" alt="License">
  <img src="https://img.shields.io/badge/采样库-29%20族%20·%20433%20音色-9c6ade?style=for-the-badge" alt="Samples">
</p>

<p align="center"><b>导入一份 MIDI，在世界中铺设一条可以穿行的 3D 红石音乐隧道。</b></p>

> 🚧 **Alpha 测试版**：功能已全面跑通、锐意开发中！后续将带来**粒子演出**（音符粒子、波前光效）、音色库扩展、性能与观感的大量优化——敬请期待，欢迎尝鲜与反馈。

---

## ✨ 为什么值得一试

| | |
|---|---|
| 🎻 **全乐器真实采样** | 不再是 20 种方块音色！29 个乐器族、**433 个真实录音采样**（钢琴、弦乐、铜管、木管、竖琴、管风琴、萨克斯、合成器、全套鼓组……），全部来自 **CC0 / CC-BY** 开源音源库 |
| 🎯 **像素级音准** | 真实 MIDI 键位**无界变调**——音符盒 2 个八度的限制被彻底打破；力度→音量、CC7 通道音量、CC11 表情逐音还原 |
| 🎚️ **智能响度平衡** | 旋律自动提升、内声部自动收敛，和弦不再盖过主旋律；整体响度对齐原版音符盒量级 |
| ⏱️ **逐音精确刻链** | 每个音符由独立延迟的精密中继器计时，**±25ms 零累积误差**；曲中变速（tempo map）精确复刻，误差表现为隧道中的音符密度 |
| 🎼 **自动编配** | GM 128 音色→乐器自动映射；鼓组键位智能重映射；复音自动拆轨，最多 **17 条轨道**环绕布局（两侧 4+4、下层 4+4、正下方 1），响度排序、左右镜像对称 |
| 🏗️ **一键铺设** | 超大立方体自动清空 → 玻璃栈道 → 分层轨道自动铺设 → 起点自动生成**音乐控制器**，全程进度提示 |
| 🚶 **三种播放模式** | **经典**（骑乘载具随波前滑行）· **观赏**（落后 N 格看轨道亮起）· **录制**（内录画面 + 游戏真实声音，直接输出 mp4） |
| 💡 **细节可控** | 可选红石灯装饰；轨道可整体一键拆除（确定性重建，绝无残留） |
| ⚙️ **仍是纯红石** | 轨道由中继器链与音符盒构成——**卸载模组后，建筑依然是可运转的原版红石音乐** |

## 🚀 快速开始

1. 安装 **NeoForge 26.1.2**（26.1.2.87 或更高），把模组 jar 放入 `mods/`；视频录制功能需要系统安装 [ffmpeg](https://ffmpeg.org/)。*（目前请自行构建，Release 即将提供）*
2. 启动游戏——根目录会自动出现 `midi/` 文件夹，把 `.mid` 文件放进去
3. 创造模式取出「**音乐生成器**」→ **Shift+右键** 打开 MIDI 浏览器 → 导入解析（完整报告：音轨/乐器/事件统计）→ 应用
4. 手持生成器**右键任意方块**：自动清空区域并铺设圆柱形音乐隧道
5. 空手右键圆心的「**音乐控制器**」→ 三种模式任选，出发！

```
MIDI 文件 → 自动解析 → GM 乐器映射/复音拆轨/响度平衡 → 红石链编译（逐音精确刻）
        → 世界铺设（清空/轨道/控制器）→ 骑乘随波前穿越整首音乐
```

## 🗺️ 路线图

- [ ] ✨ **粒子演出**：音符粒子、波前光效、控制器氛围特效
- [ ] 🎹 更多乐器族与音色细化（力度分层、真实延音）
- [ ] 🧭 交互与观感优化（铺设预览、轨道自定义布局）
- [ ] 🎬 录制管线增强（更多编码参数、镜头路径）
- [ ] 🌍 英文本地化完善与文档

## 🧱 技术亮点（给好奇的开发者）

- **音高通道 + Mixin 选择性接管**：在原版音符盒上做注入——登记过的音符盒播放自定义采样（豁免"上方必须空气"的原版限制），未登记的音符盒 **100% 原版行为**，世界安全
- **逐音精确刻链**：自动设计每种音符之间的中继器延迟分解（1-200 刻任意值），编译后自检重放整条链，时间失配立即失败
- **确定性建造**：区域由几何参数 + 编排数据完整重建，拆除无需存储方块清单
- **全链路自检**：时序/变速/乐器族/响度四个场景的离线测试随构建运行（`dev.TimingSelfTest`）

更多设计细节见 [`docs/开发规划.md`](docs/开发规划.md) 与 [`docs/实现说明.md`](docs/实现说明.md)。

## 📜 许可与致谢

- **代码：[GPL-3.0](LICENSE)**
- 采样素材（各自遵循原许可，保留署名）：
  - [Salamander Grand Piano](https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html) — CC-BY 3.0，Alexander Holm
  - [VSCO-2-CE](https://github.com/sgossner/VSCO-2-CE)（弦乐/铜管/木管）— CC0
  - [VCSL](https://github.com/sgossner/VCSL)（竖琴/键盘/打击乐/合成器）— CC0
  - [FreePats Spanish Classical Guitar](https://freepats.zenvoid.org/Guitar/acoustic-guitar.html) — CC0

**作者：SrQingChen** · 欢迎 Issue / PR / 反馈！
