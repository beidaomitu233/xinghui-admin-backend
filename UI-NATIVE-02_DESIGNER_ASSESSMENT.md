# UI-NATIVE-02 成熟设计器依赖评估（待PM确认package/lock）

2026-10-24，原A；当前Vue catalog ^3.5.27、Element Plus ^2.13.1。

## 建议的精确版本

- `@form-create/designer@3.5.0`，官方npm真实发布（正式semver，无prerelease后缀；该包latest仍是Vue2的1.3.0，Vue3发布tag为next，故必须固定3.5.0，不安装无版本latest/漂移next）。peer Vue^3.5、依赖ElPlus^2.11.1，当前catalog满足，毋须升级全站Vue/Element。
- `@form-create/element-ui@3.3.5`，官方Vue3渲染器，peer Vue^3.5.12，当前catalog满足。包名虽element-ui，3.x为Element Plus版本，不能误用2.x。
- 两包MIT，已下载官方tarball读取实际LICENSE/类型/源码；没有高级版授权依赖。registry元数据和包文件留根.qa-local/20261024-vben-reuse/ui-native-02，未安装、未执行、未改package/lock。

## 体积与依赖

| 包 | npm tar.gz实际字节 | 解包字节 |
| --- | ---: | ---: |
| designer3.5.0 | 1268260 | 4720305 |
| element-ui3.3.5 | 144693 | 503088 |

Designer自带ES bundle原始1679056字节，单独gzip404733字节；此为包内ES文件，不是本项目最终生产增量。包含富文本/代码编辑/签名等通用设计能力，即使只展示七类型也不能把这些依赖成本声称为零。

直接依赖：component-wangeditor^3.3.0、element-ui^3.3.1、utils^3.3.0、codemirror^6.65.7、element-plus^2.11.1、js-beautify^1.15.1、marked^17.0.0、signature_pad^5.0.10、sortablejs固定1.14.0、vuedraggable固定4.1.0。官方registry已逐项确认所有下限版本确有发布，声明许可证均MIT。renderer另含core与subform/tree/frame/group/radio/select/upload/checkbox组件依赖。实际最终传递依赖/重复版本/生产增量需PM统一lock解析及构建后核对，不提前宣称全依赖审计通过。项目已有sortablejs^1.15.6，designer固定1.14.0可能产生第二版本，不改全站依赖来强行dedupe。

建议加载：仅打开设计弹窗时动态import官方designer及renderer，沿官方API注册设计器；不放bootstrap主入口，不引入第二套UI。表单管理/记录/公开填写无需下载设计器chunk。VbenModal承载设计器，画布、组件面板、属性编辑/排序由官方成熟组件提供，不保留原自制Sortable画布。

## 七类型与既有FormSchema映射

| 当前type | 官方rule.type / palette name | 准确字段与限制 |
| --- | --- | --- |
| text | input / input | props.type=text；maxlength映射maxLength |
| textarea | input / textarea | props.type=textarea；maxlength映射maxLength，不能把两者往返都误判text |
| number | inputNumber / inputNumber | 原生number或null；min/max/precision6，保留0及6位小数，不转字符串 |
| date | datePicker / datePicker | 仅type=date、valueFormat=YYYY-MM-DD，值为日期串或null；排除datetime/范围/多日期 |
| select | select / select | props.multiple=false；options.label/value仍严格string，保留空默认null |
| multiselect | select / select多选描述 | props.multiple=true；默认string[]；不改成checkbox枚举、不允许任意create/remote |
| switch | switch / switch | boolean；activeValue=true/inactiveValue=false，不接受自定义真假字符串 |

通用：field↔fieldKey、title↔label、info↔help、$required↔required、value↔defaultValue；默认0/false/[]与null精确区分。官方实际源码确认textarea/inputNumber/datePicker的真实rule.type；并非按显示文案猜类型。

listVisible/searchable通过官方setBaseRuleConfig/baseRule扩展业务字段，或由稳定fieldKey附带元数据保存；映射输出仍仅现有FormSchema，不新增设计器规则DB，不覆盖历史发布schema。旧fieldKey必须原样载入/导出，新增/复制字段以合法且唯一的小写key初始化；旧表单/记录原版本和发布兼容校验保留。

## 明确不兼容能力与处理

仅开放七种现有类型。布局/子表单/分组/富文本/HTML/上传/远程选项/联动/函数事件/自定义校验脚本/签名/AI生成、代码导出等能力超出现有schema，不加入本轮协议或偷偷丢数据。使用官方menu/config隐藏，并在导出边界严格校验/拒绝未知类型、嵌套、事件及远程配置。关闭showAi/showEventForm/showControl/showStyleForm/showFormConfig/showJsonPreview/showInputData等不适用能力；日期和switch props固定合同，select选项只本地静态。

设计器内部UI规则不直接发送后端，不解析任意JS/HTML字符串作为保存schema；发布/保存只走既有schema校验。已发布旧字段重命名/改type及有记录后的删除继续被原后端拒绝，失败保留草稿。7类型往返、默认值边界、旧schema/fieldKey、未知配置拒绝需要适配器行为测试及真正成熟设计器组件UI联调，目前尚未安装，不能声称完成。

## 来源

- 官方Vue3安装：https://view.form-create.com/start_vue3
- 官方仓库：https://github.com/xaboy/form-create-designer/tree/next/packages/element-ui
- 精确npm：https://www.npmjs.com/package/@form-create/designer/v/3.5.0
- 官方registry：https://registry.npmjs.org/@form-create%2Fdesigner/3.5.0 、https://registry.npmjs.org/@form-create%2Felement-ui/3.3.5

## 请求PM决策

建议确认上述两精确版本后，由PM统一写package/lock；原A再接入官方设计器。若不能接受富文本/代码编辑等依赖体积，需在安装前明确替代成熟组件，不以原画布换色作为交付。当前记录/分享/通知/文件的原生组件迁移继续，不等待依赖决策。
