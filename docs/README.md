# 文档索引与维护约定

| 文档 | 负责什么 | 状态 |
|---|---|---|
| [README.md](../README.md) | 用户/维护者入口：功能、构建安装、设置语义、已知限制、版本与验证记录 | 长期维护 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 分层与边界、命名/存储/权限约定、技术取舍、扩展与测试入口 | 长期维护 |
| [exposure/design.md](exposure/design.md) | 曝光方案与取舍、设备约束、当前状态与限制 | 长期维护 |
| [exposure/validation.md](exposure/validation.md) | 离线→实验室→路测三层验证协议、命令与验收判据 | 长期维护 |
| [exposure/forensics.md](exposure/forensics.md) | 素材取证：失败模式、测量数据、可读阈值、复现命令 | 历史证据，数值不改 |
| [exposure/research.md](exposure/research.md) | Camera2/车牌物理/业界方案调研与引用 | 历史素材，参数以代码为准 |
| [../tools/exposure/README.md](../tools/exposure/README.md) | 离线仿真、CSV 分析、靶标生成工具的最短用法 | 随工具更新 |

## 维护约定

1. **长期文档只写稳定的东西**：行为/用法/决策/限制/证据/验收。类名、方法名、字段名、逐步参数表、
   伪代码、逐文件调用流程不进出长期文档，以代码为准。
2. **测量数据与引用不改**：forensics/research 是历史材料，测量值、结论、出处保持原样；
   代码演进后只更新"当前状态"类描述，或在文首标注历史状态。
3. **不重复**：README 讲用户可见行为，ARCHITECTURE 讲边界与约定，design 讲方案取舍，
   validation 讲怎么验证；新增内容先判断该放哪份。
4. **链接用相对路径**，新增/删除文档时同步更新本索引；证据图片放在 `exposure/crops/`。
5. 代码变化导致文档失真时，优先修正文档；不要让文档包含只能靠读代码才能维护的细节。
