package com.tianmu.agent.adapters.business;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.runtime.store.RunRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessAdapterTest {
    HttpServer server; RemoteBusinessAdapter adapter; RunRepository repository;
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    List<String> headers=new CopyOnWriteArrayList<>(); Set<Long> allowed=Set.of(1001L); volatile int metricStatus=200;
    volatile boolean oversized;
    @BeforeEach void start() throws Exception {
        repository=mock(RunRepository.class); ObjectProvider<RunRepository> provider=mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(repository);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/agent/", exchange -> {
            headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String path=exchange.getRequestURI().getPath(); Object value; int status=200;
            if(path.endsWith("principal")) value=Map.of("tenantId","tianmu","userId","881");
            else if(path.endsWith("authorize")) {
                JsonNode body=mapper.readTree(exchange.getRequestBody()); Set<Long> result=new HashSet<>();
                for(var id:body.path("videoIds")) if(allowed.contains(id.longValue())) result.add(id.longValue());
                value=Map.of("videoIds",result);
            } else if(path.endsWith("danmaku-analysis")) {
                status=metricStatus;
                String query=exchange.getRequestURI().getRawQuery();
                Map<String,String> params=new HashMap<>();
                for(String entry:query.split("&")) {String[] pair=entry.split("=",2);params.put(pair[0],URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8));}
                var q=new com.tianmu.agent.core.DanmakuTypes.Query(Long.parseLong(params.get("videoId")),LocalDateTime.parse(params.get("start")),LocalDateTime.parse(params.get("end")));
                var bins=new ArrayList<com.tianmu.agent.core.DanmakuTypes.Bucket>();for(int i=0;i<12;i++)bins.add(new com.tianmu.agent.core.DanmakuTypes.Bucket(i,0));
                value=new com.tianmu.agent.core.DanmakuTypes.Dataset(q,0,0,bins,List.of(),Instant.now(),"fixture",false);
            } else {
                status=metricStatus;
                value=new MetricData(1001,LocalDate.now().minusDays(1),"bullet_count",3,"bullet-retained-created-v1",
                        "mysql:bullet:retained-persisted-v1",Instant.now(),"Asia/Shanghai",true,false);
            }
            byte[] bytes=mapper.writeValueAsBytes(oversized && path.endsWith("metrics") ? "x".repeat(100000) : value); exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        adapter=new RemoteBusinessAdapter(new BusinessProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),5000),mapper,provider);
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void onlyRequestedVideoIsGrantedAndRawCredentialIsNeverSerialized() throws Exception {
        var scope=adapter.forCreate("private-test-token","查询视频1001昨天的弹幕数",null);
        assertEquals(Set.of(1001L),scope.videoIds()); assertFalse(scope.authorizationRef().isBlank());
        assertFalse(mapper.writeValueAsString(scope).contains("private-test-token"));
        assertEquals(List.of("Bearer private-test-token","Bearer private-test-token"),headers);
    }
    @Test void forbiddenTargetStopsBeforeRunCreation(){
        assertEquals("FORBIDDEN",assertThrows(AgentException.class,()->adapter.forCreate("token","查询视频1002昨天的弹幕数",null)).code());
    }
    @Test void videoAndRoomAliasesUseTheSameAuthorizationBoundary() {
        for (String target : List.of("视频1001", "直播间1001", "视频ID：1001", "videoId=1001", "video_id:1001"))
            assertEquals(Set.of(1001L), adapter.forCreate("token", "分析" + target + "昨天的弹幕", null).videoIds());
        assertEquals("FORBIDDEN", assertThrows(AgentException.class, () -> adapter.forCreate("token", "查询videoId=1002昨天的弹幕数", null)).code());
    }
    @Test void liveRoomAliasIsAuthorizedAndAnalysisUsesRevalidatedCredential() {
        var scope=adapter.forCreate("token","分析直播间1001昨天的弹幕报告",null);
        assertEquals(Set.of(1001L),scope.videoIds());
        var day=LocalDate.now(com.tianmu.agent.core.DanmakuTypes.ZONE).minusDays(1);
        var q=new com.tianmu.agent.core.DanmakuTypes.Query(1001,day.atStartOfDay(),day.plusDays(1).atStartOfDay());
        var result=adapter.query(q,scope,new CallControl(Instant.now().plusSeconds(5),new CancellationToken()));
        assertEquals(q,result.query());assertEquals(0,result.totalMessages());
        metricStatus=403;
        assertEquals("FORBIDDEN",assertThrows(AgentException.class,()->adapter.query(q,scope,new CallControl(Instant.now().plusSeconds(5),new CancellationToken()))).code());
    }
    @Test void runAndClarificationUseOwnerCheckedMetadata(){
        UUID id=UUID.randomUUID(); when(repository.resourcesForRun(eq(id),any())).thenReturn(Set.of(1001L));
        assertEquals(Set.of(1001L),adapter.forRun("token",id).videoIds());
        assertEquals(Set.of(1001L),adapter.forCreate("token","昨天",id).videoIds());
        verify(repository,times(2)).resourcesForRun(eq(id),argThat(scope->scope.userId().equals("881")));
    }
    @Test void historyDropsResourcesNoLongerOwned(){
        UUID id=UUID.randomUUID(); when(repository.resourcesForSession(eq(id),any(),eq(20L))).thenReturn(Set.of(1001L,1002L));
        assertEquals(Set.of(1001L),adapter.forSession("token",id,20).videoIds());
    }
    @Test void credentialsAreReusedForQueryAndBusinessChecksCanRevokeThem(){
        var scope=adapter.forCreate("token","查询视频1001昨天的弹幕数",null);
        var query=new MetricQuery(1001,LocalDate.now().minusDays(1),"bullet_count");
        assertEquals(3,adapter.query(query,scope).value());
        metricStatus=401; assertEquals("UNAUTHORIZED",assertThrows(AgentException.class,()->adapter.query(query,scope)).code());
        metricStatus=403; assertEquals("FORBIDDEN",assertThrows(AgentException.class,()->adapter.query(query,scope)).code());
    }
    @Test void persistedOpaqueReferenceCannotRestoreRawTokenAfterRestart(){
        var scope=new AccessScope("tianmu","881",Set.of(1001L),UUID.randomUUID().toString());
        assertEquals("AUTH_CONTEXT_EXPIRED",assertThrows(AgentException.class,()->adapter.query(new MetricQuery(1001,LocalDate.now().minusDays(1),"bullet_count"),scope)).code());
    }
    @Test void oversizedBusinessResponseFailsWithinTransportBoundary(){
        var scope=adapter.forCreate("token","查询视频1001昨天的弹幕数",null); oversized=true;
        assertEquals("BUSINESS_UNAVAILABLE",assertThrows(AgentException.class,()->adapter.query(new MetricQuery(1001,LocalDate.now().minusDays(1),"bullet_count"),scope)).code());
    }
}
