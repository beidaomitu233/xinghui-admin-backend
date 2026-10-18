package com.xinghuiTec.ai.modelhub;

import java.time.Instant;
import java.util.List;

/** Business identities and safe configuration health; never credential or provider response bodies. */
public final class ModelHubDtos {
    private ModelHubDtos() {}
    public enum Strategy { PRIORITY, ROUND_ROBIN }
    public record Route(String routeId,String channelId,String upstreamModel,boolean enabled) {}
    public record Change(Integer expectedRevision,String clientMutationId,String name,String slug,String capabilities,
                         String contextWindow,boolean enabled,Strategy strategy,boolean fallback,List<Route> routes) {
        public Change { routes=routes==null?List.of():List.copyOf(routes); }
    }
    public record Enabled(int expectedRevision,String clientMutationId,boolean enabled) {}
    public record RouteHealth(boolean ready,List<String> reasons,String providerCode,String channelName) {}
    public record RouteView(String routeId,String channelId,String upstreamModel,boolean enabled,RouteHealth health) {}
    public record Health(boolean ready,int readyRoutes,int distinctProviders,boolean multiProviderProtected,List<String> reasons) {}
    public record Model(String modelId,int revision,String name,String slug,String capabilities,String contextWindow,
                        boolean enabled,Strategy strategy,boolean fallback,List<RouteView> routes,Health health,Instant updatedAt) {}
    public record Page(List<Model> records,String total,int pageNum,int pageSize) {}
    public record Test(int expectedRevision,String clientMutationId,boolean confirmedCosts) {}
    public record TestAttempt(int number,String providerCode,String upstreamModel,String status,String errorCode,String switchReason,Long elapsedMs) {}
    public record TestView(String requestId,String status,String errorCode,int attemptCount,Instant deadline,List<TestAttempt> attempts) {
        public TestView(String requestId,String status,String errorCode,int attemptCount,Instant deadline){this(requestId,status,errorCode,attemptCount,deadline,List.of());}
        public TestView {attempts=List.copyOf(attempts);}
    }
}
