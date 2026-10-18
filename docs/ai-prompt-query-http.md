# 授权数据集与只读 SQL 合同

本页配套 `AiSceneQueryRuntime` 与 `AiSceneDataRuntime` 新增可选SQL绑定字段。执行器、目录、Controller及场景接线已实现；实际测试结果以交付记录为准，源码和测试类的存在不代表验收通过。

## Java 接缝与归属

C独占 `ai.contract.AiSceneQueryRuntime`、`AiSceneDataRuntime`、`ai.scenedata` 编译/取数实现、新 `ai.query` 包及查询Controller；B独占生成编排/隐藏prompt、生成Controller/DTO及scene外层定义和runtime，不同时修改C文件。

```java
AuthorizedSchema authorizedSchema(TrustedActor actor, QueryContext context, InvocationControl control);
SqlValidation validateGeneratedSql(SqlQuery query, TrustedActor actor, QueryContext context, InvocationControl control);
CompiledQuery compile(SqlQuery query, TrustedActor actor, QueryContext context, InvocationControl control);
QueryResult preview(SqlQuery query, TrustedActor actor, QueryContext context, InvocationControl control);
QueryResult read(CompiledQuery compiled, TrustedActor actor, QueryContext context, InvocationControl control);
void authorize(CompiledQuery compiled, TrustedActor actor, QueryContext context, InvocationControl control);
```

QueryContext=`datasetKeys,businessRef`；真实actor来自服务端认证，businessRef仍须重新匹配数据集/记录/租户。datasetKeys只缩小选择，不授予权利。Schema方法只读授权定义/元数据，不读业务行；validateGeneratedSql只做AST与元数据审查，不执行SQL。B生成必须先取得当前caller的AuthorizedSchema，将其中datasets/columns/scopeDescription/supportedSyntax放入不可由客户端替换的隐藏系统提示词；生成后再调用validateGeneratedSql，失败候选不标成功、不自动试查或保存。

AuthorizedSchema字段：datasets（DatasetSchema[]）、supportedSyntax、trustedParameters、scopeDescription、fingerprint。DatasetSchema为datasetKey/sqlName/label/description/scope/scopeDescription/columns/schemaFingerprint，scope为PLATFORM/TENANT/PERSONAL，columns为name/label/type/nullable。scope说明由代码产生，不采用模型自报权限。

## 独立查询 HTTP

新Controller独立于旧平台数据库目录Controller，统一前缀 `/system/ai/scene-data/sql`。不能沿用旧Controller的整类平台权限把SQL路径当成已授权，也不能移除旧平台目录限制。

- POST `/schema`，body为QueryContext，如 `{"datasetKeys":["lowcode.42"],"businessRef":null}`，Result.data为AuthorizedSchema。
- GET `/catalog?keyword=&pageNum=1&pageSize=20`，Result.data为Page：records/total（字符串）/pageNum/pageSize。records为DatasetOption：datasetKey/sqlName/label/scope/scopeDescription；可用于一个可选范围搜索。schema选择空集时最多自动载入16个数据集；超过返回RESULT_LIMIT，需通过目录缩小范围。
- POST `/validate`，body为 `{"datasetKeys":["lowcode.42"],"businessRef":null,"query":{"sql":"SELECT f.subject FROM form_42 f WHERE f.subject = :term","parameters":{"term":"示例"},"maxRows":20}}`；Result.data为SqlValidation。
- POST `/preview`，同上body；Result.data为QueryResult。

示例数据集/字段只说明结构；实际名称来自当前授权schema，不允许拿任意物理表名替代。控制器从AiInvocationIdentity解析身份，不接受actor/tenant/owner/scope/systemPrompt。入口要求已认证且具有ai:scene:query/add/edit/test/invoke之一；每次重新检查当前账号、有效角色授予，以及非平台租户是否有效、未过期。低代码要求record:list+query并限制当前租户。平台身份且具备既有ai:data:database:read可发现普通非敏感业务表（db.表名），沿用旧目录的系统/凭据表排除及已知业务权限，实际tenant_id与软删除列仍强制限制。非平台身份不能继承该通用政策。模块可通过服务端SqlDatasetPolicyProvider bean登记明确权限与归属；不提供HTTP授权登记。存在creator列不构成个人归属政策，PERSONAL只有显式服务端登记时启用。

SqlQuery为sql（1..16000字符）、parameters（最多32业务参数）、maxRows（1..200）。当前user/tenant/record的保留参数由服务端绑定，客户端同名值拒绝。输出Result均no-store/private；不记通用操作日志，不回显SQL异常、系统schema或真实参数值。受控参数须使用PreparedStatement；SQL字面常量在AST生成执行计划时也转换为绑定值，不直接拼接用户业务值。

数值参数的HTTP及持久化编码为单键对象，如 `{"record_id":{"$number":"9007199254740993123"},"amount":{"$number":"12.34"},"code":"00123","active":true,"missing":null}`。服务端恢复BigDecimal，普通字符串不猜类型；数值精度最多65位、scale绝对值最多64。AI/服务端原始JSON number仍可被精确解析，但对外一律输出精确编码，以免浏览器JSON.parse后保存丢精度。高级JSON编辑器必须拒绝不安全普通number或做无损解析，不可在丢精度后安静保存。`$number`对象只接受该单键及合法十进制字符串；不能作为任意嵌套对象入口。

公共QueryResult.rows中的数值单元格返回精确十进制**字符串**，例如 `"1234567890123456789.12345678"`，columns.type仍为DECIMAL/INTEGER。表格直接显示原文；不要Number()转换。布尔、null和字符串原样返回。Java内部rows仍保留Number；模型渲染读取内部rows后独立JSON编码，保持实际数字类型和精度。此规则只作用于查询预览rows，不修改全局Jackson。

SqlValidation为valid/normalizedSql/columns/diagnostics/schemaFingerprint/currentRecordDatasetKey（可空）；静态非法SQL返回valid=false、安全枚举诊断，不执行查询。非空currentRecordDatasetKey表示动态上下文依赖，可以静态校验后保存并标注尚未试查；实际preview/read/authorize才校验可信业务上下文及来源行。QueryResult为columns/rows/rowCount/truncated/notices；rows保持实际JSON类型/null。查询最多200行、结果256KiB，并受10秒及InvocationControl总deadline/取消限制。长整型遵循项目REST Long字符串规则，调用模型的服务端JSON保持实际值精度。业务数字参数以精确十进制读取、规范化并持久化，避免默认ObjectMapper经double往返丢失精度。

鉴权拒绝HTTP403；语法/数据集字段或不支持语法的实际执行请求HTTP422；参数/体积边界400/413；固定schema变化409；强制只读不可用503；查询超时504；取消按现有运行取消码处理。安全分类为QueryIssue中的固定枚举，不返回输入SQL/参数/provider正文。最终Controller映射随实现逐项验证，不由路径可见性授予权限。

## 持久化接线

B的SceneDtos.Definition新增可选 `sqlBindings: List<AiSceneQueryRuntime.QueryBinding>`；QueryBinding为variable/label/description/datasetKeys/query。

```json
{
  "variable":"order_summary",
  "label":"订单汇总",
  "description":"当前租户内按金额汇总可读订单",
  "datasetKeys":["lowcode.42"],
  "query":{"sql":"SELECT f.subject, COUNT(*) AS record_count FROM form_42 f GROUP BY f.subject","parameters":{},"maxRows":20}
}
```

variable是1..64字符的稳定英文身份（字母/下划线开头，后续字母数字下划线），label最多100、description最多2000。label禁止控制字符、换行和花括号，允许冒号；标记按第一个冒号切分，如 `{{order_summary:订单:汇总}}` 身份仍是order_summary。内置 `{{user_request:用户要求}}` 与旧 `{{user_request}}`、旧中文变量/历史快照保持兼容。旧中文变量仍写 `{{中文变量}}`，不写成 `{{中文变量:标签}}`。

C的EditorInput新增第5参数sqlBindings并保留原3/4参构造器；CompiledScene新增第7字段sqlBindings（QuerySnapshot[]），保留原4/5/6参。缺失JSON归空。B将完整定义传给C静态compile并完整持久化返回快照，不由客户端提供CompiledQuery；sqlBindings与原dataBindings合计最多16项。原v1/v2执行不重编译、不扩大权限；新查询编译使用scene-data-v3，SQL内部编译版本scene-query-v1。

QuerySnapshot包含binding及compiled，CompiledQuery保存query、datasets固定依赖/政策及schema指纹、固定输出columns/fingerprint和可选currentRecordDatasetKey。后者供B描述CURRENT上下文，不用扫描SQL字符串猜权限；涉及当前记录时只能从可信调用businessRef绑定。render/read每次以本次caller重新授权并核对原快照字段/依赖，不能继承保存者管理员权；schema新增字段不扩大旧投影，删除/类型或政策变化失败关闭。SQL结果只作为数据消息/变量值，不成为SYSTEM指令。

## 安全执行子集

初版支持单条SELECT、明确列/安全别名、允许的比较/AND/OR、受控INNER/LEFT JOIN、GROUP BY/有界排序及白名单聚合。UNION、子查询、CTE、递归/窗口、写入/DDL、多语句、系统schema、文件/锁/会话操作、危险或未知函数一律拒绝。隐藏prompt使用authorizedSchema返回的实际支持语法，不引导生成必失败SQL。

通用业务表支持整数、十进制、布尔、日期时间及文本；不开放物理JSON、二进制和未知复杂类型。低代码多选仍按已声明JSON数组投影，既有安全JSON绑定保持兼容。只在服务端最终结果投影截取8193字符识别超限，响应最多保留8192并带RESULT_LIMIT通知；来源字段不预截断，以保留过滤、DISTINCT及聚合的真实语义。每查询最多64列、四个来源。当前记录参数只匹配真实metadata单列主键；非id主键支持，复合主键的单参数上下文首版拒绝。对同名SQL虚拟数据集不按列表顺序猜来源。

每个来源先重写为服务端授权投影及内部tenant/owner/软删条件，再执行用户表达式，OR或JOIN不能消掉来源边界；敏感列在投影、过滤、JOIN、表达式中均不存在。数据库强制RO事务与Wall是第二道防线，不能以SELECT前缀或JDBC hint替代。SQL专用事务REQUIRES_NEW、readOnly=true；Connector/J默认将其传播为服务器SESSION READ ONLY，专属manager在事务准备时通过同一连接固定SELECT复核transaction_read_only=1（MySQL5.7使用tx_read_only），关闭传播或读不到真实只读位均失败关闭。Spring额外SET TRANSACTION READ ONLY会被当前Druid Wall拒绝，因此仅覆盖该专属manager的事务准备钩子，不放开Wall配置、不改全局事务管理器。禁止意外加入外层RW。可选SELECT-only账号是部署强化，不新增必须填写的连接配置页面。此行为依据[Connector/J驱动实现](https://raw.githubusercontent.com/mysql/mysql-connector-j/8.0.33/src/main/user-impl/java/com/mysql/cj/jdbc/ConnectionImpl.java)及[Spring事务准备扩展点](https://docs.spring.io/spring-framework/docs/6.1.15/javadoc-api/org/springframework/jdbc/datasource/DataSourceTransactionManager.html)。
