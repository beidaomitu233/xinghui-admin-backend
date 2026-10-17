package com.xinghuiTec.ai.contract;

import com.xinghuiTec.ai.contract.AiTemplateRuntime.VariableType;
import com.xinghuiTec.ai.contract.AiTypes.AiMessage;
import com.xinghuiTec.ai.contract.AiTypes.BusinessRef;
import com.xinghuiTec.ai.contract.AiTypes.InvocationControl;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * C-owned scene data boundary. Implementations never call a model or activate a scene.
 * Actors and scene context are resolved by the server, not deserialized as caller authority.
 * Static compilation may read authorized definition/schema metadata, never a sample record.
 */
public interface AiSceneDataRuntime {
    String COMPILER_VERSION = "scene-data-v1";
    String BINDING_COMPILER_VERSION = "scene-data-v2";

    Catalog catalog(TrustedActor actor, InvocationControl control);

    Page<FormOption> forms(OptionQuery query, TrustedActor actor, InvocationControl control);

    FieldCatalog fields(String objectKey, String formId, TrustedActor actor, InvocationControl control);

    Page<SampleOption> samples(SampleQuery query, TrustedActor actor, InvocationControl control);

    /** Validate syntax, registered variables, selection and schema; no real record or provider I/O. */
    CompiledScene compile(EditorInput input, TrustedActor actor, InvocationControl control);

    /**
     * Recheck actor/tenant/record/selection before reading. Use the record's original schemaVersion,
     * check selected field types against the compiled definition and preserve JSON type and null.
     * Business context is an independent data message, never substituted into a SYSTEM instruction.
     * CompiledScene is an internal snapshot, not an authorization token or a public request DTO.
     */
    RenderedScene render(CompiledScene compiled, SceneContext context, AiInputs inputs, InvocationControl control);

    /** Recheck current identity, business scope and pinned field schema without inputs or returning any content. */
    void authorize(CompiledScene compiled, SceneContext context, InvocationControl control);

    /** Dynamic sources are read from this application's database, not configured SQL/URLs. */
    default Page<DatabaseTable> tables(OptionQuery query, TrustedActor actor, InvocationControl control) { throw unsupported(); }
    default DatabaseFields databaseFields(String sourceKey, TrustedActor actor, InvocationControl control) { throw unsupported(); }
    default List<DatabaseRelation> relations(String sourceKey, TrustedActor actor, InvocationControl control) { throw unsupported(); }
    default DatabaseRecords records(DatabaseRecordQuery query, TrustedActor actor, InvocationControl control) { throw unsupported(); }
    /** Actual ordinary authorized values. Never a provider call or an automatic scene save. */
    default BindingPreview previewBindings(BindingPreviewRequest request, TrustedActor actor, InvocationControl control) { throw unsupported(); }

    enum ReadMode { FIELD, ROW, TABLE, CHILDREN }
    enum FilterOperator { EQ, NE, GT, GE, LT, LE, CONTAINS, IS_NULL, NOT_NULL }
    record DataFilter(String field, FilterOperator operator, Object value) {
        public DataFilter { reference(field); Objects.requireNonNull(operator); value=new AiInputs(java.util.Collections.singletonMap("value",value)).values().get("value"); }
        @Override public String toString(){return "DataFilter[REDACTED]";}
    }
    /** currentRecord uses the server invocation's businessRef; explicit keys select a fixed row. */
    record RowSelector(boolean currentRecord, Map<String,Object> key) {
        public RowSelector { key=key==null?Map.of():new AiInputs(key).values(); if(key.size()>8 || (currentRecord && !key.isEmpty()))throw new IllegalArgumentException("Invalid row selector"); }
        @Override public String toString(){return "RowSelector[REDACTED]";}
    }
    /** The parent is another ROW binding. Actual parent value becomes a parameter in the child query. */
    record RelationBinding(String parentVariable,String parentField,String childField) {
        public RelationBinding { reference(parentVariable);reference(parentField);reference(childField); }
    }
    /** Empty fields means all readable fields. FIELD requires exactly one field. No arbitrary expressions. */
    record DataBinding(String variable,String label,String sourceKey,ReadMode mode,List<String> fields,
                       List<DataFilter> filters,int limit,RowSelector row,RelationBinding relation) {
        public DataBinding {
            reference(variable);reference(label);reference(sourceKey);Objects.requireNonNull(mode);
            fields=fields==null?List.of():List.copyOf(fields);filters=filters==null?List.of():List.copyOf(filters);
            if(fields.size()>64 || filters.size()>16 || limit<1 || limit>200)throw new IllegalArgumentException("Invalid binding bounds");
        }
        @Override public String toString(){return "DataBinding[REDACTED]";}
    }
    record DatabaseTable(String sourceKey,String tableName,String label,String comment,List<String> primaryKey) {
        public DatabaseTable { primaryKey=List.copyOf(primaryKey); }
    }
    record DatabaseField(String field,String label,String comment,String type,boolean nullable,boolean primaryKey) {}
    record DatabaseFields(String sourceKey,List<DatabaseField> fields,List<String> primaryKey) {
        public DatabaseFields { fields=List.copyOf(fields);primaryKey=List.copyOf(primaryKey); }
    }
    record DatabaseRelation(String name,String parentSourceKey,String parentField,String childSourceKey,String childField,boolean foreignKey) {}
    record DatabaseRecordQuery(String sourceKey,List<DataFilter> filters,int limit) {
        public DatabaseRecordQuery { reference(sourceKey);filters=filters==null?List.of():List.copyOf(filters);if(filters.size()>16 || limit<1 || limit>200)throw new IllegalArgumentException("Invalid query bounds"); }
        @Override public String toString(){return "DatabaseRecordQuery[REDACTED]";}
    }
    record DatabaseRecord(Map<String,Object> key,String label,Map<String,Object> values,BusinessRef businessRef) {
        public DatabaseRecord { key=new AiInputs(key).values();values=new AiInputs(values).values(); }
        @Override public String toString(){return "DatabaseRecord[REDACTED]";}
    }
    record DatabaseRecords(List<DatabaseRecord> records,boolean truncated) {
        public DatabaseRecords { records=List.copyOf(records); }
        @Override public String toString(){return "DatabaseRecords[REDACTED]";}
    }
    record BindingSnapshot(DataBinding binding,List<DatabaseField> fields,String schemaFingerprint) {
        public BindingSnapshot { Objects.requireNonNull(binding);fields=List.copyOf(fields);reference(schemaFingerprint); }
    }
    record BindingPreviewRequest(List<DataBinding> bindings,BusinessRef businessRef) {
        public BindingPreviewRequest { bindings=List.copyOf(bindings);if(bindings.size()>16)throw new IllegalArgumentException("Too many bindings"); }
        @Override public String toString(){return "BindingPreviewRequest[REDACTED]";}
    }
    record ReadReceipt(String variable,String sourceKey,int rowCount,boolean truncated) {}
    record BindingPreview(Map<String,Object> values,List<ReadReceipt> reads,List<Notice> notices) {
        public BindingPreview { values=new AiInputs(values).values();reads=List.copyOf(reads);notices=List.copyOf(notices); }
        @Override public String toString(){return "BindingPreview[REDACTED]";}
    }
    private static AiRuntimeException unsupported(){return new AiRuntimeException(AiTypes.ErrorCode.CAPABILITY_UNSUPPORTED);}

    record OptionQuery(String keyword, int pageNum, int pageSize) {
        public OptionQuery { keyword = AiSceneDataRuntime.keyword(keyword); page(pageNum, pageSize); }
    }

    record SampleQuery(String objectKey, String formId, String keyword, int pageNum, int pageSize) {
        public SampleQuery { reference(objectKey); decimal(formId); keyword = AiSceneDataRuntime.keyword(keyword); page(pageNum, pageSize); }
    }

    record Page<T>(List<T> records, String total, int pageNum, int pageSize) {
        public Page {
            records = List.copyOf(records); page(pageNum, pageSize);
            if (total == null || !total.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid total");
        }
    }

    record DataObject(String objectKey, String name, String description) {}

    record InputVariable(String name, String label, VariableType type, boolean required, int maxChars) {}

    record Catalog(List<DataObject> objects, List<InputVariable> inputVariables) {
        public Catalog { objects = List.copyOf(objects); inputVariables = List.copyOf(inputVariables); }
    }

    record FormOption(String formId, String name, int schemaVersion) {
        public FormOption { decimal(formId); }
    }

    /** type is the actual registered lowcode field type; variableName is server generated. */
    record FieldOption(String fieldKey, String label, String type, boolean nullable, String variableName) {}

    record FieldCatalog(String objectKey, String formId, int schemaVersion, List<FieldOption> fields) {
        public FieldCatalog { reference(objectKey); decimal(formId); fields = List.copyOf(fields); }
    }

    /** label is bounded and derived only from authorized, allowed fields; never whole record JSON. */
    record SampleOption(String recordId, String label, int schemaVersion, int revision) {
        public SampleOption { decimal(recordId); }
        @Override public String toString() { return "SceneSampleOption[REDACTED]"; }
    }

    record Selection(String objectKey, String formId, List<String> fields) {
        public Selection { reference(objectKey); decimal(formId); fields = List.copyOf(fields); }
    }

    /** A single editor plus optional output instructions; no resource/app/template IDs or mapping JSON. */
    record EditorInput(String prompt, String outputInstructions, List<Selection> selections,List<DataBinding> bindings) {
        public EditorInput(String prompt,String outputInstructions,List<Selection> selections){this(prompt,outputInstructions,selections,List.of());}
        public EditorInput {
            Objects.requireNonNull(prompt); outputInstructions = outputInstructions == null ? "" : outputInstructions;
            selections = List.copyOf(selections); bindings=bindings==null?List.of():List.copyOf(bindings);if(bindings.size()>16)throw new IllegalArgumentException("Too many bindings");
        }
        @Override public String toString() { return "SceneEditorInput[REDACTED]"; }
    }

    record SelectionSnapshot(String objectKey, String formId, int schemaVersion, List<FieldOption> fields) {
        public SelectionSnapshot { reference(objectKey); decimal(formId); fields = List.copyOf(fields); }
    }

    /** Persist exactly this compiled snapshot with the scene version; do not refresh it from latest metadata. */
    record LegacyScope(AiTypes.ResourceVersionRef resource, String applicationId, String applicationVersion,
                       String objectKey, String formId, List<String> fields) {
        public LegacyScope { Objects.requireNonNull(resource); decimal(applicationId); reference(applicationVersion); fields=List.copyOf(fields); }
    }

    record CompiledScene(String compilerVersion, EditorInput editor, List<SelectionSnapshot> selections,
                         List<InputVariable> inputVariables, List<LegacyScope> legacyScopes,List<BindingSnapshot> bindings) {
        public CompiledScene(String compilerVersion,EditorInput editor,List<SelectionSnapshot> selections,List<InputVariable> inputVariables,List<LegacyScope> legacyScopes){this(compilerVersion,editor,selections,inputVariables,legacyScopes,List.of());}
        public CompiledScene(String compilerVersion, EditorInput editor, List<SelectionSnapshot> selections,List<InputVariable> inputVariables) {
            this(compilerVersion,editor,selections,inputVariables,List.of());
        }
        public CompiledScene {
            reference(compilerVersion); Objects.requireNonNull(editor);
            selections = List.copyOf(selections); inputVariables = List.copyOf(inputVariables);
            legacyScopes=legacyScopes==null?List.of():List.copyOf(legacyScopes);bindings=bindings==null?List.of():List.copyOf(bindings);
        }
        @Override public String toString() { return "CompiledScene[REDACTED]"; }
    }

    /** Scene identity is explicit; a scene key is not an application ID. B constructs this after admission. */
    record SceneContext(String sceneKey, String sceneVersion, TrustedActor actor, BusinessRef businessRef, String requestId) {
        public SceneContext {
            reference(sceneKey); reference(sceneVersion); Objects.requireNonNull(actor);
            Objects.requireNonNull(businessRef); reference(requestId);
        }
        @Override public String toString() { return "SceneContext[REDACTED]"; }
    }

    record DataUse(String objectKey, String formId, String recordId, int schemaVersion, List<String> fields) {
        public DataUse { fields = List.copyOf(fields); }
        @Override public String toString() { return "SceneDataUse[REDACTED]"; }
    }

    record Notice(String code, String reference) {}

    record RenderedScene(List<AiMessage> messages, List<DataUse> dataUses, List<Notice> notices, BindingPreview bindingPreview) {
        public RenderedScene(List<AiMessage> messages,List<DataUse> dataUses,List<Notice> notices){this(messages,dataUses,notices,null);}
        public RenderedScene { messages = List.copyOf(messages); dataUses = List.copyOf(dataUses); notices = List.copyOf(notices); }
        @Override public String toString() { return "RenderedScene[REDACTED]"; }
    }

    enum IssueCode { REQUIRED, TOO_LONG, INVALID_SYNTAX, UNKNOWN_VARIABLE, INVALID_OBJECT, INVALID_FIELD, SCHEMA_MISMATCH, INVALID_SELECTION }

    /** Paths are fixed field/index paths, never raw input, SQL, provider errors or business data. */
    record Diagnostic(String path, IssueCode code) {
        public Diagnostic { reference(path); Objects.requireNonNull(code); }
    }

    final class InvalidConfiguration extends RuntimeException {
        private final List<Diagnostic> diagnostics;
        public InvalidConfiguration(List<Diagnostic> diagnostics) {
            super("INVALID_SCENE_CONFIGURATION");
            this.diagnostics = List.copyOf(diagnostics);
            if (this.diagnostics.isEmpty()) throw new IllegalArgumentException("Missing diagnostic");
        }
        public List<Diagnostic> diagnostics() { return diagnostics; }
    }

    private static String keyword(String value) {
        if (value != null && value.length() > 100) throw new IllegalArgumentException("Keyword too long");
        return value == null ? null : value.trim();
    }
    private static void page(int number, int size) {
        if (number < 1 || number > 100_000 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page");
    }
    private static void decimal(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid identifier");
        try { Long.parseLong(value); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid identifier"); }
    }
    private static void reference(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException("Invalid reference");
    }
}
