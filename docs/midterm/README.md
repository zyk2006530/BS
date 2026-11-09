# 中期设计文档

直接阅读[中期设计文档.pdf](<中期设计文档.pdf>)。本文为设计阶段交付，使用 ZJU-Project-Report-Template 的 project 主题，未包含业务系统实现代码。

## 文件

- [report.typ](<report.typ>)：报告正文与封面。
- [截图指导.md](<截图指导.md>)：材料核对、图件生成、编译和Git核验步骤；本阶段无需运行截图，无空图。
- [设计依据.md](<设计依据.md>)：材料出处、技术方案收敛和真实封面信息。
- [审查记录.md](<审查记录.md>)：内容、构建与版式核验结果。
- [draw_design.py](<draw_design.py>)：7张SVG架构、关系、状态图和界面原型的绘图源。
- [extract_materials.py](<extract_materials.py>)：从上级项目课件提取文本；构建报告不依赖此脚本及材料转写。
- [template.typ](<vendor/template.typ>)及[LICENSE](<vendor/LICENSE>)：原模板副本与许可，未改动。

## 构建

本机核验使用 Typst 0.15.1、Noto Serif SC、Microsoft YaHei、Consolas。重新绘图只需要 Python 标准库；仅提取DOCX材料需要 python-docx 和 lxml。Typst模板依赖在首次编译时从包缓存或网络取得。

在本目录打开PowerShell，逐行执行：

```powershell
python draw_design.py
typst compile report.typ 中期设计文档.pdf
```

只改正文时执行第二行。图件保存在 `images/`，不依赖原模板所在的外部目录。迁移到其他电脑需安装所用字体；字体替换可能改变分页。

## Git

仓库根为本目录的上两级；本次工作位于 `docs/midterm-design` 分支，保留旧稿，未推送远端。课程原始材料位于仓库外；本地提取文本、预览页和缓存不纳入提交。提交编号以 `git log` 为准。
