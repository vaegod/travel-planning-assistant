package org.example.traveljavaserver.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** Explicit evaluation profile. Restricts replay traffic and writes to local test resources. */
@Configuration(proxyBeanMethods = false)
@Profile("agent-eval")
public class AgentEvaluationConfiguration {
  @Bean
  @Primary
  AgentMaps evaluationMaps(@Value("${agent.amap-key:}") String key,
      @Value("${agent.amap-base-url}") String base,
      @Value("${spring.datasource.url}") String database) {
    var uri = java.net.URI.create(base);
    if (!java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
        || !"http".equals(uri.getScheme()))
      throw new IllegalArgumentException("Evaluation map replay must use a local HTTP fixture");
    if (!database.matches("jdbc:mysql://[^/]+/travel_ai_test(?:[?].*)?"))
      throw new IllegalArgumentException("Evaluation requires the isolated travel_ai_test database");
    return new AgentMaps(key, base) {
      @Override protected void prepareRequest(AgentRun run, okhttp3.Request.Builder request) {
        var match = java.util.regex.Pattern.compile("\\[EVAL_CASE:([A-Z0-9]+)\\]").matcher(run.start.instruction());
        if (!match.find()) throw new IllegalArgumentException("Evaluation case marker is required");
        request.header("X-Travel-Eval-Case", match.group(1));
      }
    };
  }
}
