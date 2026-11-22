# 本机开发与演示运行手册

## 工具与依赖

在仓库根目录使用 PowerShell。在当前终端设置 `JAVA_HOME`，将示例路径替换为本机的 JDK 21 目录。打包和启动都在这个终端执行。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& "$env:JAVA_HOME/bin/java.exe" -version
& "$env:JAVA_HOME/bin/javac.exe" -version
mvn.cmd -version
node.exe --version
npm.cmd --version
```

`mvn.cmd -version` 中的 Java 须为 21。出现“不支持发行版本 21”时，先切换 JDK 再重新打包。打包成功并生成 `backend/target/gpu-queue.jar` 后再运行启动脚本。

| 项目 | 版本 |
| --- | --- |
| 实施环境的 Java / javac | 21.0.9 |
| 实施环境的 Maven | 3.8.8 |
| 实施环境的 Node.js / npm | 24.12.0 / 11.18.0 |
| Spring Boot / Spring Security / Spring Framework | 3.3.13 / 6.3.10 / 6.1.21 |
| H2 / Flyway | 2.2.224 / 10.10.0 |
| 官方 create-vue | 3.24.0 |
| Vue / Vite / TypeScript | 3.5.45 / 8.3.8 / 6.0.3 |

Spring 依赖由 `backend/pom.xml` 的 Spring Boot 父 POM 锁定。前端直接依赖和传递依赖分别见 `frontend/package.json` 和 `frontend/package-lock.json`。脚手架使用 `npm create vue@3.24.0 -- frontend --typescript --default` 生成。

## 数据位置与迁移

| 用途 | H2 文件 |
| --- | --- |
| 开发 | `<仓库>/.local-data/dev/gpu-queue.mv.db` |
| 演示 | `<仓库>/.local-data/demo/gpu-queue.mv.db` |
| 验收 | `<系统临时目录>/gpu-queue-test-<TestRunId>/gpu-queue.mv.db` |

`scripts/start.ps1` 会显示所选 H2 文件的绝对路径，并在独立进程中运行 Flyway。迁移成功后才执行初始化或启动 HTTP。`-Mode migrate` 只运行迁移。Flyway 在 `flyway_schema_history` 记录 `V1__accounts.sql`，后续运行仅执行待应用的版本。迁移或连接失败会终止启动。H2 数据目录路径不能含分号。

`accounts` 使用独立 UUID、唯一登录名、显示姓名、口令散列和 `ADMIN` / `MEMBER` 角色。API 将 UUID 表示为字符串。登录名去除首尾空白，并按 `Locale.ROOT` 转小写。登录名和显示姓名最多 128 个字符，口令使用 Spring Security 的带随机盐 PBKDF2 散列。

## 开发启动

```powershell
npm.cmd --prefix frontend ci --registry https://registry.npmjs.org
mvn.cmd -f backend/pom.xml package
.\scripts\start.ps1 -DataProfile dev -Mode init-admin
.\scripts\start.ps1 -DataProfile dev
```

第一次初始化时在可交互终端输入登录名、显示姓名和口令。终端隐藏口令。已有管理员时命令会退出并保留原账号。初始化在非 Web 模式中执行，凭据通过交互输入提供。

另开终端运行 `npm.cmd --prefix frontend run dev`，浏览器访问 `http://127.0.0.1:5173/`。Vite 将同源 `/api` 请求代理到 `127.0.0.1:8080`。用 `Ctrl+C` 停止前端和后端。

## 演示启动

```powershell
npm.cmd --prefix frontend ci --registry https://registry.npmjs.org
npm.cmd --prefix frontend run build
mvn.cmd -f backend/pom.xml clean package
.\scripts\start.ps1 -DataProfile demo -Mode init-admin
.\scripts\start.ps1 -DataProfile demo
```

访问 `http://127.0.0.1:8080/`。Spring Boot JAR 同时提供构建后的页面和 API。一次启动使用一个数据目录和一个后端实例。

## macOS 启动

在仓库根目录使用 zsh。将 `JAVA_HOME` 设置为本机 JDK 21 路径，并确保 Maven 与符合 `frontend/package.json` 的 Node.js、npm 在 `PATH` 中。

```zsh
export JAVA_HOME="/path/to/jdk-21/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
npm --prefix frontend ci
npm --prefix frontend run build
mvn -f backend/pom.xml package
data_dir="$PWD/.local-data/dev"
mkdir -p "$data_dir"
java -jar backend/target/gpu-queue.jar migrate "$data_dir" &&
  java -jar backend/target/gpu-queue.jar init-admin "$data_dir" &&
  java -jar backend/target/gpu-queue.jar serve "$data_dir"
```

访问 `http://127.0.0.1:8080/`。开发时可另开终端运行 `npm --prefix frontend run dev`，通过 `http://127.0.0.1:5173/` 访问 Vite。演示时将数据目录换成 `.local-data/demo`。

执行下方验收时，在当前交互终端用 `data_dir=$(mktemp -d "${TMPDIR:-/tmp}/gpu-queue-test-XXXXXXXX")` 建立独立目录。使用同一目录依次运行 `migrate` 两次、`init-admin` 两次和 `serve`。迁移失败用例另建临时目录，创建名为 `gpu-queue.mv.db` 的子目录，再用 `migrate && serve` 串联验证非零退出与端口不监听。

## Story 1.1 验收步骤

生成包含前端页面的 JAR 后，为这轮验收生成一个 GUID。迁移、初始化和启动复用同一个 GUID，各轮验收分别使用新 GUID。

```powershell
$testRunId = [guid]::NewGuid()
.\scripts\start.ps1 -DataProfile test -TestRunId $testRunId -Mode migrate
.\scripts\start.ps1 -DataProfile test -TestRunId $testRunId -Mode init-admin
.\scripts\start.ps1 -DataProfile test -TestRunId $testRunId -Mode init-admin
.\scripts\start.ps1 -DataProfile test -TestRunId $testRunId
```

1. 核对首次迁移应用 V1，后续迁移没有待应用版本。记录脚本输出的临时 H2 文件路径。重复初始化应报告管理员已存在，保留首次输入的凭据和角色。
2. 在未登录的终端请求 `GET /api/me`，预期 `401`。直接向 `POST /api/login` 和 `POST /api/logout` 发出无 CSRF 请求，预期均为 `403`。

   ```powershell
   curl.exe -sS -o NUL -w '%{http_code}\n' http://127.0.0.1:8080/api/me
   curl.exe -sS -o NUL -w '%{http_code}\n' -X POST http://127.0.0.1:8080/api/login
   curl.exe -sS -o NUL -w '%{http_code}\n' -X POST http://127.0.0.1:8080/api/logout
   ```

3. 在浏览器先用错误口令登录，再用初始口令登录。浏览器网络面板中，登录前应获取 `GET /api/csrf`，错误口令返回 `401`，正确登录返回 `204`。确认首页展示本人身份及 `JSESSIONID` 带 `HttpOnly` 属性。退出后用更换字母大小写、添加首尾空格的登录名再次登录，确认两次对应同一个账号。
4. 登录后，分别省略 `X-CSRF-TOKEN` 请求头和使用任意无效值提交 `POST /api/logout`。两次都应返回 `403`，每次随后请求 `GET /api/me` 都应返回 `200` 并保持原身份。
5. 点击退出，确认 `POST /api/logout` 返回 `204`，页面回到登录表单。向 `GET /api/me` 重新发送退出前的会话 Cookie，预期 `401`。在临时验收会话中重放，记录状态码即可。
6. 停止服务后，以只读方式核对临时库中只有一条 `ADMIN` 记录，且 `password_hash` 符合本项目带盐 PBKDF2 的 96 位十六进制格式。证据只记记录数和格式检查结果。
7. 为迁移失败场景生成另一个 GUID，在其专用临时目录下创建同名目录 `gpu-queue.mv.db`，再用该 GUID 启动 `test` 配置。确认脚本报告迁移失败并退出，`127.0.0.1:8080` 未开始监听。

记录实际执行结果，省略口令、散列、CSRF 值及会话值。验收记录见 [Story 1.1 证据](../../_bmad-output/implementation-artifacts/evidence/story-1.1.md)。
