# 桌面骨架验证记录

日期：2026-11-10。范围仅 TASK-01 桌面健康壳，不是业务验收。

## 已执行

环境：Windows，Node v24.17.0、npm 11.13.0。使用项目级官方 registry；`npm install` 真实生成锁文件（182 个包，审计 183 个包），非手写 lock。`npm run check` 全链完成且退出码 0：

- Prettier 格式检查：PASS。
- ESLint（零 warning）：PASS。
- `vue-tsc --noEmit`：PASS。
- Vitest 5.1.0：1 个文件、9 个用例 PASS。
- Vite 8.3.7 生产构建：PASS，13 个模块，入口 HTML/CSS/JS 成功产出；JS 约 65.01 kB。

单测涵盖健康契约及非法/不完整响应、HTTP 错误、传输失败、请求路径和不携带认证信息；没有调用真实业务服务。未启动常驻服务。

## 安全审计：非零，不可生产

`npm audit --json` 真实执行，返回码 1；结果保存至 [security-audit.json](./security-audit.json)。初次结果：9 moderate，0 high，0 critical。主要由 minimatch（GHSA-g3hv-75wg-m249，globparts 放大造成拒绝服务，修复线 >=11.0.1）经 `typescript-eslint`、`vue-tsc` / `@vue/language-core` 传播。该链用于本项目构建/检查而非浏览器健康页运行包，但依然是供应链风险：不应对不可信源码开放构建服务，生产前需安全升级与复审。未执行 `npm audit fix --force`，不把 audit 失败伪装成 PASS。

## 尚未执行

- 浏览器访问真实同源 API、反向代理和部署：NOT_RUN（未启动服务）。
- 真实网络断开/延迟、视觉布局截图、无障碍及业务角色联调：NOT_RUN。
- TASK-01 全系统 T15/T21：不能由本目录构建结果推定通过；外部环境验收由根任务记录。

界面只展示 UP/SKELETON/businessReady=false 并告知不可办理业务；不实现后续审批、模板或动态表单能力。
