# 中期设计文档

当前版本：[中期设计文档-修订版.pdf](<中期设计文档-修订版.pdf>)。

## 源文件

- [report.typ](<report.typ>)：正文与封面。
- [draw_design.py](<draw_design.py>)：架构、实体、状态及四张界面原型的绘图源，共7张SVG。
- [template.typ](<vendor/template.typ>)、[LICENSE](<vendor/LICENSE>)：ZJU模板及许可，保留原版。

## 构建

核验环境为 Typst 0.15.1，字体为 Noto Serif SC、Microsoft YaHei、Consolas。绘图仅使用 Python 标准库，首次编译需要取得模板依赖包。

在本目录打开PowerShell，逐行执行：

~~~powershell
python draw_design.py
typst compile report.typ 中期设计文档-修订版.pdf
~~~

只改正文时执行第二行。迁移到其他电脑时需安装上述字体，替换字体可能改变分页。

## 版本

仓库根位于本目录的上两级，文档在 docs/midterm-design 分支维护并同步远端。仓库保留PDF与构建必需文件，最终交付只提供PDF。材料转写、提取脚本、审查记录、设计依据、辅助指导、预览缓存及本地旧PDF均由忽略规则排除；旧版保留在Git历史中。
