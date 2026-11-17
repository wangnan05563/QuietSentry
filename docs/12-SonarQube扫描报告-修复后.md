# QuietSentry SonarQube 扫描报告（修复后复扫）

- 项目：QuietSentry（`projectKey=QuietSentry`）
- 服务：本地 SonarQube Community Build 26.1.0.118079，`http://127.0.0.1:9000`
- 扫描器：sonar-scanner 8.0.1.6346（Windows x64）
- 分析范围：`app/src/main/java`，62 个 Kotlin 文件，分析完成 62/62
- 质量配置：Kotlin / Sonar way
- 复扫时间：2026-11-14 17:52（UTC+8）
- CE 任务：`5889b7d8-d75c-4af1-b74f-81f4ffb9a8b4`（SUCCESS）
- 代码规模：ncloc 10,326；圈复杂度合计 1,962；认知复杂度合计 1,394

## 一、总体结果

| 指标 | 修复前 | 修复后 |
|---|---:|---:|
| OPEN 问题总数 | 61 | **0** |
| CRITICAL | 25 | **0** |
| MAJOR | 20 | **0** |
| MINOR | 16 | **0** |
| CODE_SMELL | 52 | **0** |
| BUG | 7 | **0** |
| VULNERABILITY | 2 | **0** |
| 重复行密度 | — | 0.4% |
| 新引入问题（new_violations） | — | **0** |

接口核验（`/api/issues/search?componentKeys=QuietSentry&resolved=false`）：

```
TOTAL=0
```

`/api/measures/component` 关键指标：`code_smells=0`、`bugs=0`、`vulnerabilities=0`。

## 二、质量门状态

| 条件 | 状态 | 实际值 | 阈值 |
|---|---|---|---|
| new_coverage | **ERROR** | 0.2% | ≥ 80% |
| new_duplicated_lines_density | OK | 0.0% | ≤ 3% |
| new_violations | OK | 0 | ≤ 0 |

质量门整体为 **ERROR**，唯一不达标项仍是 `new_coverage`。

> 更新（2026-11-14 晚，补测后复扫）：已接入 JaCoCo 并让 SonarQube 读到报告，项目整体覆盖率 `coverage=2.2%`（行覆盖 125/7016=1.78%，分支 3.0%），`new_coverage` 由 0.0% 升至 0.2%，仍远低于 80% 阈值。原因是本轮新增的 48 个 JVM 单测只覆盖 7 个纯逻辑类，占 7016 可覆盖行的 1.78%；CameraX/服务层代码在 JVM 单测中不可达。详见 `14-JaCoCo覆盖率与单元测试报告.md`。

## 三、结论

- 代码质量问题：**已清零**（61 → 0），且 `new_violations=0`，说明本轮修复没有引入新的规范/安全问题。
- 质量门：**未通过**，仅因 `new_coverage`（补测后 0.2% < 80%）；覆盖率链路已打通，差距来自 Android 依赖代码无法用 JVM 单测覆盖。
- 另有 2 个 Security Hotspot（安全热点，非漏洞、不计入 issue 数）在指标中显示，属待人工复核项；本次令牌对 `/api/hotspots/*` 返回 403，未能导出明细。

## 四、原始数据

- 复扫日志：`scan-2.log`（含 `62 files indexed`、`ANALYSIS SUCCESSFUL`、CE 任务链接）
- 对比基线：`baseline-issues.json` / `baseline-issues.txt`（61 条 OPEN）

> 原始文件保存在本次任务工作区 `sonar/` 目录，未复制进仓库。
