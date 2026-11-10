# 微信小程序：TASK-01 健康壳

本目录使用 uni-app Vue 3 + TypeScript，实际编译目标是 **mp-weixin**，不是 H5。仅有一个原生页面，调用 `GET /api/v1/health` 并校验 `{status:'UP',stage:'SKELETON',businessReady:false}`。未生成任何登录、业务申请、流程执行、模板或动态表单模块。

## 安装与根 CI

固定 **Node 24.17.0 / npm 11.13.0**。所有直接依赖精确版本，传递依赖由 [package-lock.json](./package-lock.json) 锁定；`.npmrc` 启用 `engine-strict` 和精确保存，使用官方 registry。无需 AppID/域名也能进行编译与无网络单元测试，但不能据此声称微信工具/真机通过。

```sh
npm ci
npm run check
# 仅构建微信小程序：
npm run build:mp-weixin
```

根 CI：`npm --prefix mobile ci`、`npm --prefix mobile run check`。独立脚本：`format:check`、`lint`、`typecheck`、`test`、`build`（与 `build:mp-weixin` 同目标）。没有 H5 构建脚本，没有常驻服务。

## 微信外部接入：必须由用户补充

1. 将 [.env.example](./.env.example) 复制为本地 `.env.local`，填写 `VITE_API_BASE_URL`，格式为纯 `https://你的真实域名`，不含路径、端口、查询、账号。环境变量缺省时，界面明确显示“尚未配置”，且不发送网络请求；不默认 localhost/IP 或虚构域名。Vite 环境变量是公开编译配置，不能放密钥。
2. [manifest.json](./src/manifest.json) 的 `mp-weixin.appid` 已填入用户提供的 `wxb911cea6b9583890`；已登录开发者工具并完成本项目模拟器检查，主体/备案和正式平台权限仍未验证。外层 `appid` 是独立 DCloud 标识，仍留空，不填微信 AppID。
3. 在微信公众平台核实主体/备案认证、HTTPS 证书链和 `request` 合法域名，把同一域名加入白名单；后续上传/下载应另核对应白名单，本任务不实现文件接口。
4. 执行构建后，将 `dist/build/mp-weixin/` 导入微信开发者工具。`setting.urlCheck` 固定为 **true**，不可勾选“不校验合法域名、web-view、TLS及HTTPS证书”来冒充通过。不要将本机私有配置文件提交。
5. 添加体验成员，分别记录开发者工具及安卓/iOS 真机实际结果、网络/配置失败反馈；本轮 **NOT_RUN**。后续业务/宽度/130%字体/权限验收不是本壳测试能够替代的。

未填写配置会得到可操作提示，不影响 CI 编译；填写配置后必须重新构建，不能运行时替换域名。

## 开发者工具本机自动化

2026-11-10 已用真实 AppID 完成缺 API 配置页面与重试按钮的模拟器检查；截图和边界见 [VALIDATION.md](./VALIDATION.md)。工具登录与本次项目模拟器可用，不代表主体/备案、HTTPS 接口、体验版或真机验收。

从仓库根目录执行（IDE 安装路径和服务端口按实际调整）：

```powershell
# 先由用户在 IDE 设置中开启服务端口；不关闭合法域名校验。
npm install --prefix .local/wechat-automation --ignore-scripts --no-audit --no-fund --save-exact miniprogram-automator@0.13.0
& 'D:\微信web开发者工具\cli.bat' auto --project "$PWD/mobile/dist/build/mp-weixin" --port 54205 --auto-port 9420
node scripts/smoke_wechat.cjs
```

脚本只连接本机回环自动化端口，重载首页、检查缺配置提示、点击重试并截图，最后断开客户端；不会上传发布或关闭 IDE。API 以后配置后，此“缺配置场景”脚本需要明确调整，不能把预期缺配置误写为联调成功。输出默认保存在忽略的 `.local/wechat-automation/results`，正式记录另存至 evidence。检查完成后可在 IDE 设置中关闭服务端口；不需要对局域网或公网开放任何端口。

## 兼容组与许可证

registry 元数据实查后选择稳定构建 **3.0.0-5030420261029002**。`@dcloudio/uni-app`、`uni-components`、`uni-mp-weixin`、`vite-plugin-uni` 同版本，其内部 uni 依赖也由锁文件固定；不使用误指向旧版 alpha 的 `latest` 标签。编译插件 peer 指定 **Vite 5.2.8**，内部 Vue 编译器 **3.4.21**，故 Vue 与 `@vue/compiler-sfc` 同为 **3.4.21**。`@dcloudio/types` 是独立版本线，遵守 uni-app peer 固定 **3.4.31**，并非错误地强行与 uni 包同版本。

| 直接依赖                                                                    | 用途                                  | 上游许可证 |
| --------------------------------------------------------------------------- | ------------------------------------- | ---------- |
| @dcloudio/uni-app、uni-components、uni-mp-weixin                            | 原生微信小程序运行/组件/平台          | Apache-2.0 |
| @dcloudio/vite-plugin-uni、@dcloudio/types                                  | 编译及平台 API 类型                   | Apache-2.0 |
| vue、@vue/compiler-sfc                                                      | 与 uni 编译器一致的 Vue 运行时/编译器 | MIT        |
| vite                                                                        | uni peer 固定的构建器                 | MIT        |
| typescript                                                                  | 类型检查                              | Apache-2.0 |
| vue-tsc、@types/node                                                        | SFC/构建单测类型                      | MIT        |
| eslint、@eslint/js、eslint-plugin-vue、vue-eslint-parser、typescript-eslint | 静态检查                              | MIT        |
| prettier                                                                    | 格式检查                              | MIT        |

单元测试使用 Node 24 内置 `node:test`、断言和 TypeScript 去类型执行，不额外安装 Vitest；这样不必让测试器跟随 uni 的旧 Vite peer，亦避免引入旧 Vitest/tinypool 的 critical 告警。

**安全状态：仅开发骨架，不可直接部署生产。** 当前锁定 uni 兼容组仍存在 npm audit 安全告警，`npm audit` 非零；不得把构建通过当作安全通过。主要涉及旧 Vite 开发服务、uni 传递依赖的 SSR/i18n/CLI/网络库，不能笼统声称“全为 dev-only”。不对外开放开发服务器，不导入不可信编译输入，不关闭微信域名校验；生产前需验证上游兼容升级并重新审计。

本项目源码尚未获得开源授权（`UNLICENSED`）；第三方许可独立有效。需复核传递依赖许可和安全公告，不能用不兼容升级隐藏构建工具风险。实际测试、告警和外部阻断见 [VALIDATION.md](./VALIDATION.md)。
