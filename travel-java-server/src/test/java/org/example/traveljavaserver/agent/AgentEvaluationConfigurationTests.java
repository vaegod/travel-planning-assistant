package org.example.traveljavaserver.agent;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class AgentEvaluationConfigurationTests {
  @Test void rejectsRemoteMapsAndProductionDatabase() {
    var config=new AgentEvaluationConfiguration();
    assertThrows(IllegalArgumentException.class,()->config.evaluationMaps("fixture","https://restapi.amap.com","jdbc:mysql://127.0.0.1/travel_ai_test"));
    assertThrows(IllegalArgumentException.class,()->config.evaluationMaps("fixture","http://127.0.0.1:9000","jdbc:mysql://127.0.0.1/travel_ai"));
  }
  @Test void concurrentTasksCarryIndependentFixtureContext() throws Exception {
    var seen=new ConcurrentHashMap<String,String>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v3/place/text",e->{
      seen.put(e.getRequestURI().getQuery().contains("one")?"one":"two",e.getRequestHeaders().getFirst("X-Travel-Eval-Case"));
      byte[] response="{\"status\":\"1\",\"pois\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      e.sendResponseHeaders(200,response.length);try(var out=e.getResponseBody()){out.write(response);}
    });server.start();
    var maps=new AgentEvaluationConfiguration().evaluationMaps("fixture","http://127.0.0.1:"+server.getAddress().getPort(),"jdbc:mysql://127.0.0.1/travel_ai_test");
    var executor=Executors.newFixedThreadPool(2);
    try {
      var futures=new ArrayList<Future<?>>();
      for(var entry:Map.of("one","CT01","two","CT02").entrySet()){
        futures.add(executor.submit(()->{
          var start=new AgentTypes.Start(UUID.randomUUID().toString(),"杭州",1,new BigDecimal("1000"),"[EVAL_CASE:"+entry.getValue()+"]");
          maps.search(new AgentRun(1,start,null,null,null,"test",10000),entry.getKey());
        }));
      }
      for(var future:futures)future.get(5,TimeUnit.SECONDS);
      assertEquals(Map.of("one","CT01","two","CT02"),seen);
    }finally{executor.shutdownNow();maps.close();server.stop(0);}
  }
}

