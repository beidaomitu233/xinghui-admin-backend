package com.xinghuiTec.ai.scenedata;

import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime;
import com.xinghuiTec.ai.contract.AiTemplateRuntime.VariableType;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Static metadata compilation only. No records, provider, tests or approval writes. */
public final class ScenePromptCompiler {
    private ScenePromptCompiler() {}
    static final Set<String> FIELDS = Set.of("subject", "summary");
    static final Pattern SLOT = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");

    public static CompiledScene compile(EditorInput input, Function<Selection, FieldCatalog> metadata) {
        if (input == null) fail("definition", IssueCode.REQUIRED);
        if (input.prompt().isBlank()) fail("prompt", IssueCode.REQUIRED);
        if (input.prompt().length() + input.outputInstructions().length() > 32000) fail("prompt", IssueCode.TOO_LONG);
        if (input.selections().size() > 1) fail("selections", IssueCode.INVALID_SELECTION);
        var snapshots = new ArrayList<SelectionSnapshot>();
        var variables = new HashSet<>(Set.of("user_request"));
        for (var s : input.selections()) {
            if (!"lowcode.record".equals(s.objectKey())) fail("selections", IssueCode.INVALID_OBJECT);
            if (s.fields().isEmpty() || s.fields().size() > 2 || new HashSet<>(s.fields()).size() != s.fields().size()
                    || !FIELDS.containsAll(s.fields())) fail("selections.fields", IssueCode.INVALID_FIELD);
            var catalog = metadata.apply(s);
            var selected = new ArrayList<FieldOption>();
            for (var key : s.fields()) {
                var field = catalog.fields().stream().filter(f -> key.equals(f.fieldKey())).findFirst().orElse(null);
                if (field == null) fail("selections.fields", IssueCode.SCHEMA_MISMATCH);
                selected.add(field); variables.add(field.variableName());
            }
            snapshots.add(new SelectionSnapshot(s.objectKey(), s.formId(), catalog.schemaVersion(), selected));
        }
        boolean required = slots(input.prompt(), variables, "prompt") | slots(input.outputInstructions(), variables, "outputInstructions");
        return new CompiledScene(AiSceneDataRuntime.COMPILER_VERSION, input, snapshots,
                List.of(new InputVariable("user_request", "用户要求", VariableType.STRING, required, 2048)));
    }

    private static boolean slots(String text, Set<String> allowed, String path) {
        var matcher = SLOT.matcher(text); boolean required = false;
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) fail(path, IssueCode.UNKNOWN_VARIABLE);
            required |= matcher.group(1).equals("user_request");
        }
        var rest = matcher.replaceAll("");
        if (rest.contains("{{") || rest.contains("}}")) fail(path, IssueCode.INVALID_SYNTAX);
        return required;
    }

    static void fail(String path, IssueCode code) { throw new InvalidConfiguration(List.of(new Diagnostic(path, code))); }
}
