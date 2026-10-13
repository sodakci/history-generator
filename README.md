# KV 历史生成

本目录使用 BenchBase 的 `kvpredicate` workload 在 PostgreSQL 上执行事务，并将数据库 trace 转换为 PRHIST 历史。

## 注：

当前分布模式暂时有问题，uniform 历史看起来不均匀，主要线索是各 session 重复使用随机序列：设置 RANDOM_SEED 后，每个 worker 都以相同种子创建随机数生成器

本地数据库 XML 配置：`kv/.runtime/kvpredicate_trace.xml`。
默认密码文件：`kv/.runtime/pgpass`，权限应为 `600`。以下路径均相对于项目根目录。

## 首次部署

以下步骤面向 Linux/Bash 环境。先安装并启动 PostgreSQL，准备 `psql`、Python 3、JDK 23、`zstd` 和 `unzip`。Python 转换与审计脚本使用标准库，无需额外安装 pip 包；Java 依赖由 Maven Wrapper 下载，首次构建需要访问 Maven 仓库。

### 克隆与 Java 环境

```bash
git clone https://github.com/sodakci/history-generator.git
cd history-generator
```

安装 JDK 23 后，将下面的 `JAVA_HOME` 替换为实际安装目录，创建本地环境文件：

```bash
mkdir -p .tools
cat > .tools/java23.env <<'ENV'
export JAVA_HOME="/absolute/path/to/jdk-23"
export PATH="$JAVA_HOME/bin:$PATH"
ENV
source .tools/java23.env
java -version
javac -version
```

`java` 和 `javac` 均应显示 23。主脚本也会自动加载这个环境文件；它属于本地配置，不随 Git 克隆。已在系统环境中配置好 JDK 23 时，可省略创建和 source 步骤，确认 `JAVA_HOME`、`PATH` 正确即可。

### 创建数据库与用户

在 PostgreSQL 服务器上使用管理员连接执行初始化。Ubuntu/Debian 本机安装的常见管理员入口如下；远程数据库使用对应的管理员连接方式：

```bash
sudo -u postgres psql -v ON_ERROR_STOP=1
```

在 psql 中执行以下命令。`\password` 会提示输入新用户的密码，后续 XML 和密码文件使用相同密码。数据库和角色已存在时跳过对应的创建命令，并确认所有者及权限。

```sql
CREATE ROLE kvpredicate_user LOGIN;
\password kvpredicate_user
CREATE DATABASE kvpredicate_trace_benchbase OWNER kvpredicate_user;
\connect kvpredicate_trace_benchbase
GRANT USAGE, CREATE ON SCHEMA public TO kvpredicate_user;
\q
```

运行用户作为数据库和生成对象的所有者，可以创建 trace schema、表、函数、索引及触发器。连接认证需允许该用户通过配置的地址和端口登录；远程部署还需要检查 PostgreSQL 监听地址、`pg_hba.conf` 和端口连通性。无需手工安装 trace SQL，生成脚本会在加载 `kv` 表后自动执行安装。

### 准备本地 XML 与密码文件

在项目根目录执行：

```bash
mkdir -p kv/.runtime
cp kv/config/kvpredicate_trace.xml kv/.runtime/kvpredicate_trace.xml
chmod 600 kv/.runtime/kvpredicate_trace.xml
cat > kv/.runtime/pgpass <<'PGPASS'
127.0.0.1:5432:kvpredicate_trace_benchbase:kvpredicate_user:REPLACE_WITH_LOCAL_SECRET
PGPASS
chmod 600 kv/.runtime/pgpass
```

编辑本地 XML，将 URL 中的 `REPLACE_KVPREDICATE_DATABASE` 改为 `kvpredicate_trace_benchbase`，将 `<username>` 改为 `kvpredicate_user`，将 `<password>` 改为实际密码；同时替换 `pgpass` 中的密码占位符。XML 中的特殊字符需要按 XML 规则转义，`pgpass` 密码中的 `:` 和 `\` 需要用 `\` 转义。真实密码只保存在被 Git 忽略的本地文件中。

上述示例使用脚本默认的本机连接；其他地址、端口、库名或用户名需要同时调整 `pgpass` 和下文的 `DB_*` 环境变量。

## 参数如何配置

运行 `kv/run_kvpredicate_history_case.sh` 时，在命令前设置环境变量。脚本默认读取 `kv/.runtime/kvpredicate_trace.xml`；该文件不存在时读取 `kv/config/kvpredicate_trace.xml`。环境变量覆盖 XML 中对应的配置项，生成 `kv/.runtime/<CASE_NAME>.xml`，再交给 BenchBase 执行。`CASE_NAME` 只决定目录名，不会被解析成参数。

数据库连接默认值定义在 `kv/run_kvpredicate_history_case.sh`：

| 环境变量 | 默认值或行为 |
| --- | --- |
| `DB_HOST` | `127.0.0.1` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `kvpredicate_trace_benchbase` |
| `DB_USER` | `kvpredicate_user` |
| `DB_PASSWORD` | 未设置时保留基础 XML 中的密码；非空时覆盖 XML 密码并写入密码文件 |
| `PGPASSFILE` | 默认 `kv/.runtime/pgpass`；提供 `DB_PASSWORD` 且未指定此变量时，使用 `kv/.runtime/<CASE_NAME>.pgpass` |

主脚本始终用 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER` 的环境变量值或上述默认值重写 XML 连接信息，因此换数据库时需要设置这些变量，仅修改基础 XML 的地址或用户名不会生效。`KVPREDICATE_DSN` 用于 `psql`，默认由同一组变量生成；单独指定时应与 BenchBase 的 XML 连接指向同一数据库。`PGPASSFILE` 供 `psql` 使用，BenchBase 使用 XML 中的密码，两处凭据都需要配置。


| 环境变量                              | 含义                                                              |
| --------------------------------- | --------------------------------------------------------------- |
| `TERMINALS`                       | session 数                                                       |
| `TXNS_PER_SESSION`                | 每个 session 的已提交事务数，生成后会校验                                       |
| `MIN_TXN_LENGTH`、`MAX_TXN_LENGTH` | 每事务操作数的最小值和最大值；固定长度时设为相同值                                       |
| `KEY_COUNT`                       | 初始 KV 行数                                                        |
| `READ_RATIO`                      | 总读操作百分比（点读 + 谓词读），范围 0～100；未设置时沿用原有点读／写随机分配 |
| `PREDICATE_READ_RATIO`            | 谓词读操作百分比，范围为 0～100                                              |
| `PREDICATE_RANGE_ENABLED`         | 区间谓词开关，取值 `true`/`false`；XML 参数为 `predicateRangeEnabled`，缺省 `false`；环境变量为空时保留基础 XML 值 |
| `KEY_DIST`                        | 点读和写的 key 分布：`uniform`、`zipf`/`zipfian`、`hotspot`、`exponential` |


其他可选参数：

- 分布和执行：`SCALEFACTOR`、`KEY_DIST_BASE`、`MAX_WRITES_PER_KEY`、`PREDICATE_GROUP_COUNT`、`RANDOM_SEED`、`ISOLATION`、`TIME_SECONDS`、`RATE`、`MOP_DELAY_MS`。
- 受控生成：`PREDICATE_TRANSACTION_RATIO`、`WRITES_PER_TRANSACTION`、`WRITE_KEY_COUNT`、`PREDICATE_RESULT_CARDINALITY`、`PREDICATE_ABSENT_RESULT_RATIO`。
- 异常注入：`KV_PREDICATE_ANOMALY`（`none`、`write-skew`、`lost-update`）、`KV_PREDICATE_ANOMALY_VARIANT`、`KV_PREDICATE_ANOMALY_SEED`、`KV_PREDICATE_ANOMALY_ISOLATE_BACKGROUND`、`KV_PREDICATE_ANOMALY_DELAY_MS`。
- 运行和连接：`BUILD`、`LOAD`、`CASE_NAME`、`BASE_CONFIG`、`KVPREDICATE_CONFIG`、`BENCHBASE_JAR`、`JAVA_ENV`、`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`、`KVPREDICATE_DSN`、`PGPASSFILE`；预期结果元数据可用 `EXPECTED_VERDICT`、`SERIAL_ORDER`。

目录名中的谓词比例按 0～1 的小数写，生成器参数按百分数写：目录名 `0.002` 对应 `PREDICATE_READ_RATIO=0.20`，表示 0.2%。实际提交历史的比例以 `manifest.json` 中 `predicate_reads / operations` 为准；发生 abort 时可能偏离配置值。固定 `RANDOM_SEED` 时各 worker 会使用相同种子，可能产生重复的 key 序列。

指定 `READ_RATIO` 时，按全部事件配额分配总读数，再在读事件内分配谓词读；`PREDICATE_READ_RATIO` 仍表示谓词读占全部事件的百分比，不能大于 `READ_RATIO`。该配置用于普通生成，不能与受控生成或异常注入同时启用。目录 `20_100_10_5000_0.3_0.05` 表示 20 个 session、每个 100 个事务、每事务 10 个事件、5000 个 key、总读比例 0.3、读中谓词比例 0.05，应设置 `READ_RATIO=30`、`PREDICATE_READ_RATIO=1.5`。这份历史包含 20000 个事件，其中 5700 个点读、300 个谓词读和 14000 个写。批量生成使用各配置的 `hist-00000`、`hist-00001`、`hist-00002`；每份通过转换、审计及计数校验后删除该份 `raw_kvpredicate_trace.jsonl`，保留压缩 history、初始状态和 manifest。

普通生成的随机谓词默认关闭区间：`predicateRangeEnabled=false` 或未配置时，从全表、取模、等值、大于、小于（`TRUE`、`MOD`、`EQ`、`GT`、`LT`）五种类型中选择，各占 `1/5`。设置环境变量 `PREDICATE_RANGE_ENABLED=true` 或基础 XML 的 `<predicateRangeEnabled>true</predicateRangeEnabled>` 后，加入区间（`RANGE`），六种类型各占 `1/6`；`PREDICATE_RANGE_ENABLED=false` 可显式关闭，环境变量未设置或为空时沿用基础 XML 值。此开关只控制随机谓词集合，不改变总谓词比例、`READ_RATIO`、受控生成模式或异常核心事务。区间每次随机生成上下界，使用严格开区间，例如 `90 < value < 100` 对应 `value > 90 AND value < 100`，不包含 90 和 100。上下界取样范围沿用当前已分配 value 的范围，取样相同时将上界加一；区间可以返回空结果。

## 生成历史

先准备 PostgreSQL、`psql`、Python 3、JDK 23、`zstd`、`unzip`，以及本地数据库连接配置和 `kv/.runtime/pgpass`。以下命令生成 20 个 session、每个 50 个事务、每事务 15 个操作、5000 行、0.2% 谓词读、`uniform` 的历史：

```bash
# 从克隆后的 history-generator 项目根目录运行

PGPASSFILE=kv/.runtime/pgpass \
BUILD=true \
LOAD=true \
CASE_NAME=20_50_15_5000_0.002_uniform_$(date +%Y%m%d_%H%M%S) \
ISOLATION=TRANSACTION_SERIALIZABLE \
KV_PREDICATE_ANOMALY=none \
TERMINALS=20 \
TXNS_PER_SESSION=50 \
MIN_TXN_LENGTH=15 \
MAX_TXN_LENGTH=15 \
KEY_COUNT=5000 \
PREDICATE_READ_RATIO=0.20 \
KEY_DIST=uniform \
MAX_WRITES_PER_KEY=2147483647 \
TIME_SECONDS=60 \
RATE=unlimited \
./kv/run_kvpredicate_history_case.sh
```

`BUILD=true` 构建 BenchBase，`LOAD=true` 创建并加载初始 KV 表；`MAX_WRITES_PER_KEY=2147483647` 避免运行中轮换 key。输出目录为 `../predicateHistories/kvpredicate/<CASE_NAME>/hist-00000/`。

## 验证环境与小规模生成

在项目根目录检查依赖和数据库连接。以下连接与首次部署示例一致，`-w` 让缺失或错误的密码配置直接报错：

```bash
for tool in java javac python3 psql zstd unzip; do
  command -v "$tool" || exit 1
done
java -version
javac -version
python3 --version
psql --version
PGPASSFILE="$PWD/kv/.runtime/pgpass" \
psql -X -w -v ON_ERROR_STOP=1 \
  'postgresql://kvpredicate_user@127.0.0.1:5432/kvpredicate_trace_benchbase' \
  -c 'SELECT current_database(), current_user, version();'
```

运行现有转换与审计测试：

```bash
python3 -m unittest discover -s kv/tests -v
```

先在专用测试数据库上生成 2 个 session、每个 5 个已提交事务、每事务 2 个操作的小规模历史。该命令的 `LOAD=true` 会重建 `kv` 表：

```bash
smoke_case="smoke_$(date +%Y%m%d_%H%M%S)"
PGPASSFILE="$PWD/kv/.runtime/pgpass" \
DB_HOST=127.0.0.1 \
DB_PORT=5432 \
DB_NAME=kvpredicate_trace_benchbase \
DB_USER=kvpredicate_user \
BUILD=true \
LOAD=true \
CASE_NAME="$smoke_case" \
ISOLATION=TRANSACTION_SERIALIZABLE \
KV_PREDICATE_ANOMALY=none \
TERMINALS=2 \
TXNS_PER_SESSION=5 \
MIN_TXN_LENGTH=2 \
MAX_TXN_LENGTH=2 \
KEY_COUNT=20 \
PREDICATE_READ_RATIO=20 \
KEY_DIST=uniform \
MAX_WRITES_PER_KEY=2147483647 \
TIME_SECONDS=10 \
RATE=unlimited \
./kv/run_kvpredicate_history_case.sh
```

成功时脚本会输出 `transactions/session validated`，确认 2 个 session 各有 5 个已提交事务，并打印结果目录。目录中应包含 `raw_kvpredicate_trace.jsonl`、`history.prhist.jsonl.zst`、`initial_state.json` 和 `manifest.json`。`TIME_SECONDS` 指定计时阶段，设置事务配额后 worker 还会补齐提交事务数，因此它不是整次任务的总耗时上限。

本地已检查的工具版本为 JDK 23.0.2、Python 3.12.3 和 psql 客户端 16.15，转换与审计单元测试通过。PostgreSQL 服务端版本以连接检查中的 `version()` 为准；新部署应以小规模生成结果确认完整管线可用。

## 迁移与运行备注

- 先创建目标数据库和用户，并配置连接认证。运行用户需要能够创建 schema、表、函数、索引和触发器；trace 对象由脚本自动安装。
- `LOAD` 默认为 `true`，会删除并重建 `kv` 表。重新生成历史可从空数据库开始；需要保留旧表数据时设置 `LOAD=false`，并保证已有表符合当前 workload 的结构。
- `.tools/`、`kv/.runtime/`、`benchbase/.m2/` 和 `benchbase/target/` 被 Git 忽略，克隆仓库后需要另行准备。XML 模板含密码占位符，不能直接用于连接；本地 XML 和密码文件应在新环境重新配置或单独迁移。
- `.tools/java23.env` 用于设置 Java 环境，也可包含 BenchBase 构建环境；`JAVA_HOME` 应指向实际 JDK 23 安装目录，例如项目内的 `.tools/jdk-23`。迁移时可准备对应 JDK，或直接在环境中配置 JDK 23，并通过 `JAVA_ENV` 指定自己的环境文件。首次构建需要下载 Maven 和依赖；离线环境应携带依赖缓存及 Maven Wrapper 所需文件，或复制完整 BenchBase 构建分发目录并设置 `BUILD=false`。其中必须包含当前自定义 `kvpredicate` 插件。
- 历史文件位于项目同级的 `../predicateHistories/`，需要单独迁移，并确保目标目录可写。每次运行使用不同的 `CASE_NAME`，重复名称会覆盖该目录中的结果；默认名称只有日期，同一天多次运行应显式指定名称。
- 同一数据库的 `kv` 表和 trace schema 会被运行过程重建或清空，多个生成任务应使用独立数据库。检查提交事务数和实际操作比例时，以生成的历史及 `manifest.json` 为准。

## 历史如何转换

1. 脚本安装 PostgreSQL trace 对象并记录初始表状态，随后运行 BenchBase。
2. 执行结束后导出 `raw_kvpredicate_trace.jsonl`；`kv/kvpredicate_trace_to_prhist.py` 将初始状态写入 `initial_state.json`，将事务操作转换并压缩为 `history.prhist.jsonl.zst`，同时生成含事务、操作和谓词读计数的 `manifest.json`。
3. `kv/audit_kvpredicate_prhist.py` 检查生成结果；主脚本再校验每个 session 的已提交事务数。
