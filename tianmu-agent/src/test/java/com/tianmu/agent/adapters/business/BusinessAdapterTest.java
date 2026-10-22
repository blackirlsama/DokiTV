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
