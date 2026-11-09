# 中期设计文档

当前版本：[中期设计文档-修订版.pdf](<中期设计文档-修订版.pdf>)。

开发入口：[规格、任务与验收基线](<../../specs/001-enterprise-approval/README.md>)。当前仍为设计/规格阶段，原型与验收方案不代表业务已实现；后续按需求、计划、任务和真实测试证据推进。

## 源文件

- [report.typ](<report.typ>)：正文与封面。
- [draw_design.py](<draw_design.py>)：架构、实体、状态和基础业务原型，共7张SVG。
- [draw_supplement.py](<draw_supplement.py>)：账户、工作台、管理、差旅与自定义申请、通知统计及移动补充原型，共7张SVG。
- [template.typ](<vendor/template.typ>)、[LICENSE](<vendor/LICENSE>)：ZJU模板及许可，保留原版。

## 构建

核验环境为 Typst 0.15.1，字体为 Noto Serif SC、Microsoft YaHei、Consolas。绘图仅使用 Python 标准库，首次编译需要取得模板依赖包。

在本目录打开PowerShell，逐行执行：

~~~powershell
python draw_design.py
python draw_supplement.py
typst compile report.typ 中期设计文档-修订版.pdf
~~~

只改正文时执行第三行。迁移到其他电脑时需安装上述字体，替换字体可能改变分页。文档中的图为设计原型，不是已实现页面的截图；测试表为验收方案。

## 版本

当前交付为[中期设计文档-优化版.pdf](<../../../交付/中期设计文档-优化版.pdf>)，35页，与本目录修订版摘要一致。本轮补充接口契约、关键字段类型与约束、动作权限矩阵、安全参数、管理版本及通知摘要同步规则，保留原有14张图与两项增强范围。上一轮[补全版](<../../../交付/中期设计文档-补全版.pdf>)作为历史版本保留，原交付文件未覆盖。构建不自动提交Git或推送远端；材料转写与预览缓存不纳入交付。自定义表单与流程的业务实现仍由学生按课程约束独立编写，本目录只包含设计正文和静态图件。
