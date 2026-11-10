# 微信健康壳验证记录

日期：2026-11-10。范围仅 TASK-01 uni-app mp-weixin 工程骨架，不是业务或微信真机验收。

## 已执行

环境：Windows，Node v24.17.0、npm 11.13.0。官方 registry 实装并生成锁文件。`npm run check` 已全链退出码 0：

- Prettier：PASS。
- ESLint（零 warning）：PASS。
- `vue-tsc --noEmit`：PASS。
- Node 24 内建测试器：17 个用例，全部 PASS。
- `uni build -p mp-weixin`：PASS（Compiler 5.34 / vue3）。真实生成 `dist/build/mp-weixin/`，包括 `app.js`、`app.json`、原生页面 `.wxml/.wxss/.js/.json`，不是 H5 产物。

初次构建已检查：`compileType: miniprogram`、`setting.urlCheck: true`，入口仅 `pages/index/index`。当时源码 manifest 的 DCloud AppID 与微信 AppID 均留空。uni 编译器因此在忽略提交的产物中自动写入标准 `touristappid` 并提示未配置 AppID/统计不可用；这只是初次编译记录。用户后续提供真实微信 AppID 后已重建并完成下述模拟器验证；DCloud 标识独立留空。

17 个单测覆盖：缺配置在网络调用前失败、7 类不允许的域名/URL、尾部斜杠及唯一健康路径、非 200 响应、5 类非法骨架、真实 uni.request 适配器 GET/超时/无认证头、网络失败提示。不请求真实服务。默认 `.env.example` 的域名为空，因此实际页面会提示配置而不是伪造 UP。

## 修复与已知工具告警

初次静态检查发现 uni button 属性被 Vue 类型定义当成 HTML type；改为 CSS class，不禁用类型检查。初次构建发现空 App.vue script 不被 uni 编译器接受；增加生命周期空壳后构建成功。

单测由旧 Vitest 3 改为 Node 24 原生测试器，移除了 Vitest/tinypool 的 2 项 critical 告警及关联依赖，无需破坏 uni 的 Vite peer。Node 会给未声明 package type 的测试模块输出 `MODULE_TYPELESS_PACKAGE_JSON` 重解析提示；保留 uni 的兼容配置，此提示不影响测试或产物。Vite 5 的 CJS Node API 存在弃用提示，没有通过屏蔽日志隐藏。

## 安全审计：仍然非零，不可生产

当前 `npm audit --json` 真实执行返回码 1，机器报告为 [security-audit.json](./security-audit.json)。移除测试器后审计 669 个包，**53 项：10 low、20 moderate、23 high、0 critical**（初始 56 项含 2 critical）。不可一概归为开发依赖：uni-app/uni-components 传递引入了 uni-h5、i18n、SSR 及 CLI 栈，虽本项目只打包微信目标，仍需针对实际产物和使用路径评估暴露。

主要剩余链：

- uni 固定的 Vite 5.2.8：开发服务 fs 限制绕过/文件读取、Windows launch-editor 等公告。插件 exact peer 指定此版本，未用破坏 peer 的强制升级掩盖风险；不得对外开放开发服务器。
- Vue / server-renderer 3.4.21：SSR 属性名过滤 XSS（GHSA-g2v6-rqmx-r4w6），公告修复线 >=3.5.42；uni 编译器内部 Vue 3.4.21，跨版本升级需验证。当前没有 SSR 服务，但不能把整个依赖链宣称无风险。
- 旧 @intlify / vue-i18n：原型污染及 XSS；未开发国际化/富文本业务。
- CLI 传递依赖的 adm-zip、@babel/core、PostCSS、source-map-js、JPEG/HTTP 工具链：不可信压缩包、源码/映射/图片输入和网络服务带来文件读写、拒绝服务等风险。
- uni 平台/调试依赖的 ws：旧版本内存披露/碎片拒绝服务。测试与构建没有启动常驻调试服务。

未运行 `npm audit fix --force`、未越过 peer 限制、未声称安全通过。后续需在上游兼容版本升级或经过针对性兼容验证后逐项关闭告警，再考虑生产部署。

## 微信开发者工具实际验证（2026-11-10 12:13 +08:00）

用户提供微信 AppID `wxb911cea6b9583890` 后已重建，生成配置中的 AppID 正确，`urlCheck:true` 保持不变，独立 DCloud AppID 仍为空。用户开启 IDE 服务端口 `54205`，CLI `islogin` 返回 `login:true`，`auto` 返回本机自动化端口 `9420`。

通过 [模拟器检查脚本](../scripts/smoke_wechat.cjs)，使用官方 `miniprogram-automator@0.13.0` 连接已登录的工具，真实导航并断言：

- 首页 `pages/index/index` 与标题“企业审批系统 · 工程骨架”显示正常。
- 明确显示尚未配置 `VITE_API_BASE_URL`，没有伪造健康 UP。
- 点击“重新检查”后仍正确提示缺配置。
- 本次导航和点击观察期间捕获的异常与 error 级 console 事件均为空；不代表整个工具日志永久无警告。
- 已保存[模拟器截图](./evidence/wechat-simulator-2026-11-10.png)与[机器结果](./evidence/wechat-simulator-2026-11-10.json)。模拟型号 iPhone 12/13 (Pro)，报告 `platform=devtools`、逻辑窗口 390×753、基础库 3.17.6，**不是 iOS 真机证据**。

实际检查退出 0，共 5 项检查。官方客户端隔离安装在被忽略的 `.local/wechat-automation`，禁用安装脚本，未加入 mobile 运行依赖。上游旧 debug/core-js/phin 等有弃用警告，不据此声明供应链安全通过。未上传/发布、未模拟 HTTPS 接通、未关闭域名校验。

## 外部阻断 / 未执行

真实 AppID 已配置、已登录微信开发者工具并完成上述模拟器验证。备案主体/认证、HTTPS 证书与 request 合法域名、体验成员、安卓/iOS 真机、真实请求/断网反馈、其他宽度及 130% 字体截图仍 **NOT_RUN**。没有为验证方便关闭 URL 校验，也未提交私有项目配置。TASK-01 T15/T21 等全系统结论不能由上述构建和模拟器检查代替。
