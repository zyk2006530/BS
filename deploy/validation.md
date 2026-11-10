# TASK-01 deploy 验证记录

日期：2026-11-10。范围仅 [deploy](<./>) 和 [部署测试](<../tests/test_deploy_skeleton.py>)；未提交 Git。该记录证明当前源码的配置回归和独立 Java 探针测试，不证明业务、镜像、Docker 或数据库交付。

## 实际环境

- Windows；工作区 `C:\Users\PC\Desktop\study\BS设计\BS`。
- Python 3.12.14，调用 DSH 既有 Python；测试仅导入标准库。
- 已有 JDK：`openjdk version "17.0.17" 2025-10-21 LTS`；`OpenJDK Runtime Environment Zulu17.62+17-CA (build 17.0.17+10-LTS)`。
- 没有 Docker 可执行程序；没有为本任务安装 Docker、MySQL、Nginx 或替代应用服务。
- Java 探针测试由标准库测试夹具短暂监听 `127.0.0.1:8080`，结束已关闭；这是单元测试输入源，不是实际后端或开发部署。

## 真实命令与结果

仓库根目录：

```powershell
$env:DEPLOY_JAVA_HOME = 'C:\Users\PC\Desktop\study\BS设计\BS\.local\zulu17.62.17-ca-jdk17.0.17-win_x64'
& 'D:\dsh-data\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe' -m unittest discover -s tests -p test_deploy_skeleton.py -v
```

结果：`Ran 27 tests in 3.375s`，`OK`，退出码 `0`。

- 21 项配置静态检查/反例：解析 JSON 形式的 Compose；拒绝数据库和后端宿主端口、外部开发 HTTP 监听、非私有数据库网络、host 网络、前端直连库、字面/缺省密码、空密码开关、root 后端账号、浮动基础镜像/数据库镜像、Nginx 挂附件卷或 alias、偷带初始化 SQL、curl 健康命令、伪造来源头；检查无密码示例、构建上下文白名单、Java 骨架标识和文档未实测声明。
- 6 项真实 Java/HTTP 单元测试：实际 `javac --release 17`；编译类启动和源码启动；接受正确字段顺序/空白变化；拒绝 `businessReady=true`、错误阶段、503、畸形/重复字段、状态字符串夹空格、超大响应。测试编译产物使用临时本机目录并清理。
- `git diff --check -- deploy tests/test_deploy_skeleton.py` 退出码 `0`，无输出；文件当时均为新增未跟踪，所以此命令不替代代码审查或测试。

## 首次检查的缺口（历史状态，当前结论见后续复核）

| 项目 | 状态 | 原因/下一步 |
| --- | --- | --- |
| Registry manifest/digest | 请求失败，NOT_RUN | 对三个精确标签的 Docker Registry 真实网络请求均 `URLError <urlopen error [WinError 10060] ...>`；Hub API `TypeError: fetch failed`。没有杜撰 digest，没有声称已解析或拉取 |
| Compose `config/build/up`、Nginx `-t` | NOT_RUN | 没有 Docker，未以纯 Python 检查冒充 Docker 测试 |
| Temurin/Linux 镜像内探针运行 | NOT_RUN | 实际只在既有 Windows Zulu 同版本 Java 上编译/执行 |
| MySQL空库/迁移/权限/重启 | NOT_RUN | 未运行 MySQL；没有表、迁移或种子；TASK-02和后续负责 |
| 附件读写、真实下载、成组备份恢复 | NOT_RUN | 仅声明私有卷，没有业务文件接口 |
| 生产 HTTPS / Cookie安全 / 小程序域名 / 真机 / SMTP | NOT_RUN | 本配置仅回环开发 HTTP，需外部接入条件和后续实现 |
| T15/T21/T22及其他业务验收 | NOT_RUN | 当前健康契约明确 `stage=SKELETON`、`businessReady=false` |

首次检查时，仅锁定精确标签 `eclipse-temurin:17.0.17_10-jdk-jammy`、`nginx:1.28.0-alpine3.21`、`mysql:8.4.7`，未证明可获得性。该次网络失败是历史结果，已由下方网络恢复后的 manifest 核验补充；Docker、MySQL 和恢复实测缺口没有因此消失。

## 网络恢复后的复核（2026-11-10 北京时间 11:02—11:05）

用户确认网络恢复后，重新访问 Docker 官方认证端点与 `https://registry-1.docker.io/v2/library/.../manifests/<tag>`。三个标签均 **HTTP 200**，`mediaType=application/vnd.oci.image.index.v1+json`；SHA-256 由真实响应原始字节计算，逐项等于 `Docker-Content-Digest` 响应头。随后执行以下命令再次核对同一摘要并保留原始字节：

```powershell
& 'D:\dsh-data\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe' deploy/verify_image_lock.py --capture
```

命令退出码 `0`，三项打印 `PASS`。元数据见[镜像锁定记录](<image-lock.json>)，复核脚本见[镜像核验脚本](<verify_image_lock.py>)。脚本未打印或持久化认证 token。

| 官方标签 | 已核对响应 SHA-256 / Docker-Content-Digest | 原始字节数 | 抓取 UTC |
| --- | --- | --- | --- |
| `eclipse-temurin:17.0.17_10-jdk-jammy` | `sha256:73c73439d25b953b34e0065172ad8ce01bea5bc8fc25158f0eeecb4c06d14fed` | 6555 | 03:05:09.751811Z |
| `nginx:1.28.0-alpine3.21` | `sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284` | 10317 | 03:05:11.824636Z |
| `mysql:8.4.7` | `sha256:0426ec38c7a10aa45ba383887df7878f74ee70e2fd589c7b69207f3577901903` | 2603 | 03:05:13.981999Z |

三项均为**多架构 index**，均包含 Linux amd64 和 arm64，`unknown/unknown` 条目为镜像附属元数据，不作为运行平台。保留原始响应供离线重算：[Temurin index](<registry/library-eclipse-temurin.index.json>)、[Nginx index](<registry/library-nginx.index.json>)、[MySQL index](<registry/library-mysql.index.json>)。原始字节未经 JSON 重排或格式化，文件哈希就是上述摘要。

已将[后端 Dockerfile](<backend.Dockerfile>)、[前端 Dockerfile](<frontend.Dockerfile>) 和[Compose](<compose.yaml>) 的外部镜像锁为 `tag@sha256:...`，本地构建产物仍用本项目 `0.1.0-skeleton` 版本标签。新增 5 项离线测试核对原始证据摘要、元数据与配置一致性，并拒绝字节篡改、响应头摘要错误或移除 digest。

重新执行上方带 `DEPLOY_JAVA_HOME` 的测试命令，实际结果：`Ran 32 tests in 3.201s`，`OK`，退出码 `0`。构成为 **26 项静态/证据检查 + 6 项真实 Java/HTTP 探针单测**，无 skip。HTTP 单测夹具已关闭，临时编译产物已清理。

### 当前结论

- Registry manifest/digest：**PASS**，已真实取得并复核，不再是未核实候选标签。
- 镜像层拉取、Docker build / Compose config/up、Nginx配置运行检查：**NOT_RUN**，本机仍无 Docker，未安装或启动容器服务。
- 数据库迁移/最小权限/持久恢复、业务和附件下载、生产 HTTPS、真机/SMTP以及 T15/T21/T22：**NOT_RUN**；核实 index 不替代这些运行验收，也不等于镜像漏洞或签名验证。
