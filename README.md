# smart-mes · AI 生产助手台（DSH Java Native 插件场景案例 P59）

> 基于 **deepseek-harness-java（DSH）Java Native 插件机制** 的智能制造 MES 场景案例：4 条产线（运行中/换型中/停机/保养中）+ 工单排产（产能校验 + 停机拦截 + 二次确认）+ 工单报工（状态自动流转）+ 设备体检（温度/振动/运行时长三阈值维保建议）+ 质量帕累托分析（不良率 + top 缺陷定位）+ 生产看板，通过 `mes-copilot` 插件接入 AI 助手，支持自然语言看产线、排产、报工、查设备、析质量。

![总览](docs/images/01-overview.png)

## 一、项目组成

| 模块 | 说明 |
|------|------|
| `m-app` | Spring Boot 3.2 应用（端口 **18098**），MES REST API 与前端页面 |
| `m-plugin` | DSH Java Native 插件（`mes-copilot`），打包 6 个 AI 工具 |

业务数据：4 条产线（SMT 贴片/总装/包装/注塑，含停机与换型状态）、5 张工单（生产中/待开工/已暂停/已完成）、5 台设备（贴片机预警、注塑机故障、包装机保养中）、4 条质量检验记录（焊点虚焊/外观划伤/缩水痕）。

## 二、插件工具（6 个）

| 工具 | 说明 |
|------|------|
| `line_list` | 产线列表：产线号/名称/产品/状态/计划与完成数/班次/OEE 目标 |
| `schedule` | 工单排产：停机与保养中拦截；在产余量+新单超两倍日产能建议拆单 |
| `report` | 工单报工：累计完成数，状态自动流转（待开工→生产中→已完成） |
| `equipment` | 设备体检：温度≥80℃/振动≥4.5mm/s/运行≥8000h 三阈值风险与维保建议 |
| `quality` | 质量分析：综合不良率 + 帕累托 top 缺陷与占比 + 改善建议 |
| `stats` | 生产看板：在产工单/整体进度/设备预警数/各产线进度/处置建议 |

## 三、REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/lines` | 产线列表 |
| GET | `/api/orders?status=` | 工单列表 |
| POST | `/api/schedule` | 排产 `{lineId,product,qty,due}` |
| POST | `/api/report` | 报工 `{orderId,qty}` |
| GET | `/api/equipment?id=` | 设备体检 |
| GET | `/api/equipments` | 设备列表 |
| GET | `/api/quality` | 质量分析 |
| GET | `/api/stats` | 生产看板 |
| POST | `/api/assistant/stream` | AI 助手 SSE（透传 DSH） |

## 四、快速开始

```bash
mvn clean package -DskipTests
java -Dserver.port=18098 -jar m-app/target/m-app-1.0.0-SNAPSHOT.jar

bash install_plugin.sh m-plugin/target/m-plugin-1.0.0-SNAPSHOT.jar \
  mes-copilot 1.0.0-SNAPSHOT m-plugin-1.0.0-SNAPSHOT.jar "AI 生产助手"

open http://127.0.0.1:18098/
```

## 五、端到端验证

```bash
bash agent_stream.sh 127.0.0.1:8090 mes-copilot "现在各产线什么状态？整体进度如何？"
bash agent_stream.sh 127.0.0.1:8090 mes-copilot "E101 贴片机状态怎么样？需要维修吗？"
bash agent_stream.sh 127.0.0.1:8090 mes-copilot "在 L03 排一个 6000 件的智能温控器工单，交期 10-08"
bash agent_stream.sh 127.0.0.1:8090 mes-copilot "确认排产"
bash agent_stream.sh 127.0.0.1:8090 mes-copilot "帮 W8001 报工 400 件"
bash agent_stream.sh 127.0.0.1:8090 mes-copilot "最近质量怎么样？主要缺陷是什么？怎么改善？"
bash agent_stream.sh 127.0.0.1:8090 mes-copilot "L04 现在能排产吗？为什么？"
```

6 个工具全部验证通过（含停机排产拦截）。验证截图：

| 截图 | 内容 |
|------|------|
| ![AI 设备体检](docs/images/02-ai-equipment.png) | AI 报 E101 振动 4.8 超 4.5 阈值轴承磨损风险 + 过热 82.5℃，建议停机检修 |
| ![AI 排产确认](docs/images/03-ai-schedule.png) | AI 复述排产要素（L01/传感器模块/3000 件/交期 10-10）并提示 E101 风险 |
| ![AI 质量分析](docs/images/04-ai-quality.png) | AI 报不良率 2.77% 与帕累托 top3 缺陷（缩水痕 50%/虚焊 38.9%/划伤 11.1%） |

## 六、技术要点

- **排产闸门**：停机/保养中直接拦截；在产余量（未完成量）+ 新单 > 2×日产能（planQty）拦截并建议拆单或延后交期；排产前系统提示强制复述要素二次确认。
- **状态机**：工单 待开工→（首次报工）生产中→（done≥qty）已完成；已暂停/已完成不可报工。
- **设备三阈值**：温度 80℃、振动 4.5mm/s、运行 8000h 大修周期，任一超限列风险并给维保建议；预警/故障数进看板。
- **质量帕累托**：按缺陷类型聚合排序，`share = count/totalDefect`；综合不良率 >3% 触发改善建议并点名 top 缺陷。
- **超时修复**：SSE 代理配置 `spring.mvc.async.request-timeout: 180s` 解决长回答 503。
- **结论约束**：排产与报工必报工单号（W 前缀）；设备异常必须给阈值对比；质量分析必点 top 缺陷占比；数据全部来自工具返回。

## 七、目录结构

```
smart-mes/
├── pom.xml                  # 父 pom（maven.compiler.parameters=true）
├── m-app/                   # Spring Boot 应用 (18098)
│   └── src/main/java/cn/xiaofuge/m/app/
│       ├── MesApplication.java
│       ├── MStore.java        # 产线/工单/设备/质量/排产/报工/统计
│       ├── MController.java   # REST API
│       └── AssistantController.java # SSE 透传 DSH
├── m-plugin/                # DSH 插件 (mes-copilot)
│   └── src/main/
│       ├── java/.../MesPlugin.java  # 6 工具
│       └── resources/META-INF/       # plugin.yaml + SPI
└── docs/
    ├── 使用说明.md
    └── images/              # 验证截图 ×4
```
