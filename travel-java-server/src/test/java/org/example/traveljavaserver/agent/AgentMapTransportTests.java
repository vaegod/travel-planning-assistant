package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class AgentMapTransportTests {
  HttpServer server;AgentMaps maps;AtomicInteger requests=new AtomicInteger();String mode="disconnect_once";
  @BeforeEach void start()throws Exception {
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v3/place/text",e->{
      int n=requests.incrementAndGet();
      if(mode.equals("disconnect_always")||(mode.equals("disconnect_once")&&n==1)){e.close();return;}
      byte[] body=(mode.equals("invalid_json")?"{broken":"{\"status\":\"1\",\"pois\":[]}").getBytes(StandardCharsets.UTF_8);
      e.sendResponseHeaders(mode.equals("http_failure")?503:200,body.length);
      try(var out=e.getResponseBody()){out.write(body);}
    });server.start();
    maps=new AgentMaps("fixture","http://127.0.0.1:"+server.getAddress().getPort());
  }
  @AfterEach void stop(){maps.close();server.stop(0);}
  @Test void reconnectsReadOnlyGetOnceAfterConnectionDropsWithoutAnHttpResponse(){
    var run=AgentFixtures.mappedRun("walking");
    assertTrue(maps.search(run,"公园").isEmpty());
    assertEquals(2,requests.get());assertEquals(2,run.mapRequests);
    assertEquals(1,run.traces.stream().filter(t->t.tool().equals("retry_map")).count());
  }
  @Test void repeatedDisconnectionStopsAfterTwoAttempts(){
    mode="disconnect_always";var run=AgentFixtures.mappedRun("walking");
    assertThrows(AgentMaps.ServiceFailure.class,()->maps.search(run,"公园"));
    assertEquals(2,requests.get());assertEquals(2,run.mapRequests);
  }
  @Test void httpServiceFailureIsNotRetriedOrTurnedIntoNoRoute(){
    mode="http_failure";var run=AgentFixtures.mappedRun("walking");
    assertThrows(AgentMaps.ServiceFailure.class,()->maps.search(run,"公园"));
    assertEquals(1,requests.get());assertTrue(run.traces.isEmpty());
  }
  @Test void malformedSuccessfulResponseIsNotRetried(){
    mode="invalid_json";var run=AgentFixtures.mappedRun("walking");
    assertThrows(AgentMaps.ServiceFailure.class,()->maps.search(run,"公园"));assertEquals(1,requests.get());
  }
  @Test void cancellationPreventsNetworkAndRetry(){
    var run=AgentFixtures.mappedRun("walking");run.cancel();
    assertThrows(java.util.concurrent.CancellationException.class,()->maps.search(run,"公园"));assertEquals(0,requests.get());
  }
}
