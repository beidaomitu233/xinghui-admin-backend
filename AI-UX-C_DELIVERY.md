# AI-UX-C 交付记录

## 领取与边界

2026-10-17（Asia/Shanghai），原C领取AI一期产品纠偏，非二期F/F0。后端 `worktrees/AI-UX-C/backend` 起点 `79b4655b56621fbcdafe48eb858d312bd76eb465`；前端 `worktrees/AI-UX-C/frontend` 起点 `63391497cc112d7b633b78feb3369583480395d2`；双分支 `feature/AI-UX-C-scene-data`。中央板PM独占；A产品前端/API/demo、B运行/账本/V225不改。必要迁移仅V226，尚未创建。

## C0：共享接缝首提交（待评审）

唯一新增接口 `system-admin/src/main/java/com/xinghuiTec/ai/contract/AiSceneDataRuntime.java`，无Spring Bean/生产假实现，无新依赖，不改一期接口。

- `catalog/forms/fields/samples`：真实身份、固定对象与有界分页，候选不是授权；表单/样本名称不得泄漏无权数据。
- `compile(EditorInput, TrustedActor, InvocationControl)`：单编辑器正文/可选输出要求/对象字段选择，静态语法与授权schema元数据校验，不读取样本、不调用模型。合法结果为不可变CompiledScene，B在scene保存事务中持久化并切当前。
- `render(CompiledScene, SceneContext, AiInputs, InvocationControl)`：显式sceneKey/sceneVersion，不使用假appId；实际用户/tenant/记录查询、原schemaVersion与字段类型/null校验后输出独立业务数据消息。
- 语法/引用错误返回InvalidConfiguration及固定字段路径/IssueCode，B转换为局部422错误；真实权限等失败继续使用既有AiRuntimeException固定ErrorCode。HTTP签名/权限以C1实现与PM冻结稿为准，不让A猜字段。
- 新内置对象只允许lowcode.record的subject/summary，internal_note及新增字段默认拒绝。旧批准资源继续走原AiTemplateRuntime/AiTemplatePreviewRuntime及真实旧app映射，保留原批准版本、主体和applications allowlist；不由勾选生成批准记录。

下一阶段C1实现目录/编译器和定向测试，C2实现授权读取与旧资源回归，C3在合同稳定后更新公开文档。每阶段单独记录commit、实际测试及未验证项。当前仅接缝，完整功能未实现，不能标验收通过。

### C0窄授权增量

新增 `authorize(CompiledScene, SceneContext, InvocationControl): void`，用于每attempt/SSE重连当前授权复查，不需要user_request，不生成/返回渲染正文，不调用模型。C实现负责实际主体/tenant/绑定form-record、原schema与固定字段范围；B不得为授权而伪造输入再render。样本DTO的toString改为脱敏，避免候选标签进入普通日志。该小提交仍只包含共享接口和交付记录，C1实现另交。

### C0实际验证

JDK17 `javac --release 17 -encoding UTF-8` 编译全部8个共享契约源文件通过（无外部classpath）。私有轻量契约检查5组通过：选择集合深层防改、正文toString脱敏、JSON类型/null保留、有界分页/ID溢出拒绝、诊断不可变。首次编译发现record访问器与keyword辅助方法同名，已限定辅助方法调用后重新编译通过。未运行Maven/完整产品测试、DB迁移、真实读取、页面或模型；没有生产Bean，不能将这些检查算作功能验收。
