# KV 历史生成

本目录使用 BenchBase 的 `kvpredicate` workload 在 PostgreSQL 上执行事务，并将数据库 trace 转换为 PRHIST 历史。

## 注：

当前分布模式暂时有问题，uniform 历史看起来不均匀，主要线索是各 session 重复使用随机序列：设置 RANDOM_SEED 后，每个 worker 都以相同种子创建随机数生成器

## 参数如何配置

运行 `kv/run_kvpredicate_history_case.sh` 时，在命令前设置环境变量。脚本默认读取 `kv/.runtime/kvpredicate_trace.xml`；该文件不存在时读取 `kv/config/kvpredicate_trace.xml`。环境变量覆盖 XML 中对应的配置项，生成 `kv/.runtime/<CASE_NAME>.xml`，再交给 BenchBase 执行。`CASE_NAME` 只决定目录名，不会被解析成参数。


| 环境变量                              | 含义                                                              |
| --------------------------------- | --------------------------------------------------------------- |
| `TERMINALS`                       | session 数                                                       |
| `TXNS_PER_SESSION`                | 每个 session 的已提交事务数，生成后会校验                                       |
| `MIN_TXN_LENGTH`、`MAX_TXN_LENGTH` | 每事务操作数的最小值和最大值；固定长度时设为相同值                                       |
| `KEY_COUNT`                       | 初始 KV 行数                                                        |
| `PREDICATE_READ_RATIO`            | 谓词读操作百分比，范围为 0～100                                              |
| `KEY_DIST`                        | 点读和写的 key 分布：`uniform`、`zipf`/`zipfian`、`hotspot`、`exponential` |


其他可选参数：

- 分布和执行：`SCALEFACTOR`、`KEY_DIST_BASE`、`MAX_WRITES_PER_KEY`、`PREDICATE_GROUP_COUNT`、`RANDOM_SEED`、`ISOLATION`、`TIME_SECONDS`、`RATE`、`MOP_DELAY_MS`。
- 受控生成：`PREDICATE_TRANSACTION_RATIO`、`WRITES_PER_TRANSACTION`、`WRITE_KEY_COUNT`、`PREDICATE_RESULT_CARDINALITY`、`PREDICATE_ABSENT_RESULT_RATIO`。
- 异常注入：`KV_PREDICATE_ANOMALY`（`none`、`write-skew`、`lost-update`）、`KV_PREDICATE_ANOMALY_VARIANT`、`KV_PREDICATE_ANOMALY_SEED`、`KV_PREDICATE_ANOMALY_ISOLATE_BACKGROUND`、`KV_PREDICATE_ANOMALY_DELAY_MS`。
- 运行和连接：`BUILD`、`LOAD`、`CASE_NAME`、`BASE_CONFIG`、`KVPREDICATE_CONFIG`、`BENCHBASE_JAR`、`JAVA_ENV`、`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`、`KVPREDICATE_DSN`、`PGPASSFILE`；预期结果元数据可用 `EXPECTED_VERDICT`、`SERIAL_ORDER`。

目录名中的谓词比例按 0～1 的小数写，生成器参数按百分数写：目录名 `0.002` 对应 `PREDICATE_READ_RATIO=0.20`，表示 0.2%。实际提交历史的比例以 `manifest.json` 中 `predicate_reads / operations` 为准；发生 abort 时可能偏离配置值。固定 `RANDOM_SEED` 时各 worker 会使用相同种子，可能产生重复的 key 序列。

## 生成历史

先准备 PostgreSQL、`psql`、Python 3、Java 23，以及本地数据库连接配置和 `kv/.runtime/pgpass`。以下命令生成 20 个 session、每个 50 个事务、每事务 15 个操作、5000 行、0.2% 谓词读、`uniform` 的历史：

```bash
cd /home/lc/Desktop/predicate/History_Generator
source .tools/java23.env

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

## 历史如何转换

1. 脚本安装 PostgreSQL trace 对象并记录初始表状态，随后运行 BenchBase。
2. 执行结束后导出 `raw_kvpredicate_trace.jsonl`；`kv/kvpredicate_trace_to_prhist.py` 将初始状态写入 `initial_state.json`，将事务操作转换并压缩为 `history.prhist.jsonl.zst`，同时生成含事务、操作和谓词读计数的 `manifest.json`。
3. `kv/audit_kvpredicate_prhist.py` 检查生成结果；主脚本再校验每个 session 的已提交事务数。

