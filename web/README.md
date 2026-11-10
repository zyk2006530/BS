# 桌面端：TASK-01 健康壳

本目录是 Vue 3 + TypeScript + Vite 桌面骨架，只请求同源 `GET /api/v1/health`，验证 `{status:'UP',stage:'SKELETON',businessReady:false}`。成功也明确显示业务尚不可办理；不含身份、申请、审批、模板或动态表单实现。Element Plus / ECharts 将在真正需要的后续业务任务中按规格引入，当前不加入无用途依赖。

## 可复现运行

固定 Node **24.17.0**、npm **11.13.0**。`engines`、`packageManager` 和 `.npmrc` 的 `engine-strict` 共同记录并约束版本；所有直接依赖精确版本，传递依赖由 [package-lock.json](./package-lock.json) 锁定。使用官方 npm registry，未更改用户全局配置。

```sh
npm ci
npm run check
```

根 CI 可以运行 `npm --prefix web ci`、`npm --prefix web run check`。独立脚本：`format:check`、`lint`、`typecheck`、`test`、`build`；`format` 仅用于开发者显式格式化。测试是无常驻服务的 `vitest run`。

产物为 `dist/`，由同源 Nginx 等静态服务器托管，`/api/v1/` 转发后端。本目录未配置跨源 CORS 或生产域名。可自行执行 `npm run dev` 查看纯壳，但开发服务器**不**代理后端，未配置同源 API 时页面将明确显示失败，不伪造 UP。本次未启动常驻服务。

## 依赖用途及许可证

| 直接依赖                                                                    | 用途                 | 上游许可证 |
| --------------------------------------------------------------------------- | -------------------- | ---------- |
| vue                                                                         | Vue 3 页面和状态     | MIT        |
| vite、@vitejs/plugin-vue                                                    | 构建 Vue SFC         | MIT        |
| typescript                                                                  | 静态类型             | Apache-2.0 |
| vue-tsc                                                                     | SFC 类型检查         | MIT        |
| @types/node                                                                 | 构建及单测 Node 类型 | MIT        |
| eslint、@eslint/js、eslint-plugin-vue、vue-eslint-parser、typescript-eslint | JS/TS/Vue 静态检查   | MIT        |
| prettier                                                                    | 格式检查             | MIT        |
| vitest                                                                      | 无服务单元测试       | MIT        |

**安全状态：仅开发骨架，不可直接部署生产。** 初次 `npm audit` 存在 9 项 moderate 告警（均由构建/类型/静态检查链中的 minimatch 传播，详见验证记录），审计非零。代码质量门禁与依赖安全审计不是同一门禁，不能将构建通过解释为安全通过。

项目本身未获得开源授权，`license: UNLICENSED` 不代表上游依赖无许可证；发布时须保留上游版权/许可并复查锁文件的传递依赖许可。安装及构建实测与限制见 [VALIDATION.md](./VALIDATION.md)。
