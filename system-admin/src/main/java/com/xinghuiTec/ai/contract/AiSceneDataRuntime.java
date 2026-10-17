package com.xinghuiTec.ai.contract;

import com.xinghuiTec.ai.contract.AiTemplateRuntime.VariableType;
import com.xinghuiTec.ai.contract.AiTypes.AiMessage;
import com.xinghuiTec.ai.contract.AiTypes.BusinessRef;
import com.xinghuiTec.ai.contract.AiTypes.InvocationControl;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;

import java.util.List;
import java.util.Objects;

/**
 * C-owned scene data boundary. Implementations never call a model or activate a scene.
 * Actors and scene context are resolved by the server, not deserialized as caller authority.
 * Static compilation may read authorized definition/schema metadata, never a sample record.
 */
public interface AiSceneDataRuntime {
    String COMPILER_VERSION = "scene-data-v1";

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
    }

    record Selection(String objectKey, String formId, List<String> fields) {
        public Selection { reference(objectKey); decimal(formId); fields = List.copyOf(fields); }
    }

    /** A single editor plus optional output instructions; no resource/app/template IDs or mapping JSON. */
    record EditorInput(String prompt, String outputInstructions, List<Selection> selections) {
        public EditorInput {
            Objects.requireNonNull(prompt); outputInstructions = outputInstructions == null ? "" : outputInstructions;
            selections = List.copyOf(selections);
        }
        @Override public String toString() { return "SceneEditorInput[REDACTED]"; }
    }

    record SelectionSnapshot(String objectKey, String formId, int schemaVersion, List<FieldOption> fields) {
        public SelectionSnapshot { reference(objectKey); decimal(formId); fields = List.copyOf(fields); }
    }

    /** Persist exactly this compiled snapshot with the scene version; do not refresh it from latest metadata. */
    record CompiledScene(String compilerVersion, EditorInput editor, List<SelectionSnapshot> selections,
                         List<InputVariable> inputVariables) {
        public CompiledScene {
            reference(compilerVersion); Objects.requireNonNull(editor);
            selections = List.copyOf(selections); inputVariables = List.copyOf(inputVariables);
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

    record RenderedScene(List<AiMessage> messages, List<DataUse> dataUses, List<Notice> notices) {
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
