package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.agent.runtime.AgentWorkbench;
import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.scene.SceneFailure;
import com.xinghuiTec.ai.scene.SceneManagementService;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;

@RestController
@RequestMapping("/system/ai/workbench")
@PreAuthorize("hasAuthority('ai:agent:invoke')")
public class AgentWorkbenchController {
    public record Create(String title) {}
    public record Rename(String title,String expectedVersion) {}
    public record Submit(String input,String sceneKey,String clientRequestId,List<String> imageIds) {
        public Submit(String input,String sceneKey,String clientRequestId){this(input,sceneKey,clientRequestId,List.of());}
        public Submit {imageIds=imageIds==null?List.of():List.copyOf(imageIds);}
        @Override public String toString(){return "Submit[REDACTED]";}
    }
    public record Cancel() {}
    public record SessionView(String id,String title,String activeRunId,String version,Instant updatedAt) {}
    /** No encrypted/plain input or hidden prompt in status responses. User text is delivered through owned history. */
    public record RunView(String id,String sessionId,String status,String sceneKey,String errorCode,Instant deadline) {}
    public record Admission(RunView run,boolean created) {}
    public record PresetView(String sceneKey,String name) {}
    private final AiInvocationIdentity identity;
    private final AgentAccess access;
    private final AiRuntimeBodyCodec codec;
    private final JdbcAgentRepository repository;
    private final AgentWorkbench workbench;
    private final SceneManagementService scenes;
    @org.springframework.beans.factory.annotation.Autowired private AgentImages images;
    public AgentWorkbenchController(AiInvocationIdentity identity,AgentAccess access,AiRuntimeBodyCodec codec,
                                    JdbcAgentRepository repository,AgentWorkbench workbench,SceneManagementService scenes){
        this.identity=identity;this.access=access;this.codec=codec;this.repository=repository;this.workbench=workbench;this.scenes=scenes;
    }
    @GetMapping("/presets") public Result<List<PresetView>> presets(){return owned((actor,current)->Result.ok(scenes.agentChoices(current.actor()).stream()
            .map(entry->new PresetView(entry.sceneKey(),entry.name())).toList()));}
    @GetMapping("/sessions") public Result<List<SessionView>> sessions(){return owned((actor,current)->Result.ok(repository.sessions(actor).stream().map(AgentWorkbenchController::session).toList()));}
    @PostMapping("/sessions") public Result<SessionView> create(HttpServletRequest request){var input=body(request,Create.class);return owned((actor,current)->Result.ok(session(repository.create(actor,input.title()))));}
    @GetMapping("/sessions/{sessionId}") public Result<SessionView> session(@PathVariable String sessionId){uuid(sessionId);return owned((actor,current)->Result.ok(session(repository.session(actor,sessionId))));}
    @PutMapping("/sessions/{sessionId}") public Result<SessionView> rename(@PathVariable String sessionId,HttpServletRequest request){
        uuid(sessionId);var input=body(request,Rename.class);long version=positive(input.expectedVersion());
        return owned((actor,current)->{repository.rename(actor,sessionId,input.title(),version);return Result.ok(session(repository.session(actor,sessionId)));});
    }
    @GetMapping("/sessions/{sessionId}/events") public Result<List<JdbcAgentRepository.Event>> events(@PathVariable String sessionId,@RequestParam(defaultValue="0")String after){
        uuid(sessionId);long cursor=decimal(after);return owned((actor,current)->Result.ok(repository.events(actor,sessionId,cursor)));
    }
    @PostMapping("/sessions/{sessionId}/images") public Result<com.xinghuiTec.agent.persistence.JdbcAgentImages.Image> image(@PathVariable String sessionId,@RequestParam("file")org.springframework.web.multipart.MultipartFile file){
        uuid(sessionId);return owned((actor,current)->Result.ok(images.upload(actor,sessionId,file)));
    }
    @GetMapping("/sessions/{sessionId}/images/{imageId}") public ResponseEntity<byte[]> image(@PathVariable String sessionId,@PathVariable String imageId){
        uuid(sessionId);uuid(imageId);return owned((actor,current)->{
            var image=images.read(actor,sessionId,imageId);
            return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(image.image().mediaType())).body(image.bytes());
        });
    }
    @PostMapping("/sessions/{sessionId}/runs") public ResponseEntity<Result<Admission>> submit(@PathVariable String sessionId,HttpServletRequest request){
        uuid(sessionId);var input=body(request,Submit.class);
        return owned((actor,current)->{
            scenes.agentRoute(input.sceneKey(),current.actor());
            var admission=input.imageIds().isEmpty()?workbench.submit(actor,sessionId,input.clientRequestId(),input.input(),input.sceneKey()):workbench.submit(actor,sessionId,input.clientRequestId(),input.input(),input.sceneKey(),input.imageIds());
            return ResponseEntity.accepted().body(Result.ok(new Admission(run(admission.run()),admission.created())));
        });
    }
    @GetMapping("/runs/{runId}") public Result<RunView> run(@PathVariable String runId){uuid(runId);return owned((actor,current)->Result.ok(run(repository.run(actor,runId))));}
    @PostMapping("/runs/{runId}/cancel") public Result<RunView> cancel(@PathVariable String runId,HttpServletRequest request){
        uuid(runId);body(request,Cancel.class);return owned((actor,current)->{workbench.cancel(actor,runId);return Result.ok(run(repository.run(actor,runId)));});
    }
    private <T>T owned(BiFunction<AgentActor,AgentAccess.Context,T> operation){
        var captured=identity.current();var actor=new AgentActor(captured.tenantId(),captured.subjectId());
        return access.as(actor,current->operation.apply(actor,current));
    }
    private <T>T body(HttpServletRequest request,Class<T> type){return codec.readSceneConfiguration((byte[])request.getAttribute(AiRuntimeBoundaryFilter.BODY),type);}
    private static SessionView session(JdbcAgentRepository.Session value){return new SessionView(value.id(),value.title(),value.activeRunId(),Long.toString(value.version()),value.updatedAt());}
    private static RunView run(JdbcAgentRepository.Run value){return new RunView(value.id(),value.sessionId(),value.status(),value.promptSceneKey(),value.errorCode(),value.deadline());}
    private static void uuid(String value){try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new AgentProblem("INVALID_ID","标识无效");}}
    private static long decimal(String value){try{if(value==null||!value.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();return Long.parseLong(value);}catch(RuntimeException invalid){throw new AgentProblem("INVALID_CURSOR","游标或版本无效");}}
    private static long positive(String value){long number=decimal(value);if(number==0)throw new AgentProblem("INVALID_VERSION","版本无效");return number;}
    @ExceptionHandler(AgentProblem.class) public ResponseEntity<Result<Void>> agent(AgentProblem error){
        int status=switch(error.code()){
            case "FORBIDDEN"->403;
            case "VERSION_CONFLICT","RUN_BUSY","IDEMPOTENCY_CONFLICT","RUN_STOPPED"->409;
            case "INVALID_ID","INVALID_CURSOR","INVALID_VERSION","INVALID_TITLE","INVALID_REQUEST_ID","INVALID_MESSAGE","INVALID_IMAGE","IMAGE_CONTEXT_LIMIT"->400;
            default->503;
        };return AiHttpFailures.response(status,error.code());
    }
    @ExceptionHandler(AiRuntimeException.class) public ResponseEntity<Result<Void>> runtime(AiRuntimeException error){return AiHttpFailures.response(switch(error.code()){
        case UNAUTHENTICATED->401;case FORBIDDEN,RESOURCE_FORBIDDEN->403;case INVALID_INPUT->400;case VERSION_CONFLICT->409;default->503;
    },error.code().name());}
    @ExceptionHandler(SceneFailure.class) public ResponseEntity<Result<Void>> scene(SceneFailure error){return AiHttpFailures.response(error.status(),error.getMessage());}
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) public ResponseEntity<Result<Void>> denied(){return AiHttpFailures.response(403,"FORBIDDEN");}
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<Result<Void>> unavailable(){return AiHttpFailures.response(503,"AGENT_UNAVAILABLE");}
}
