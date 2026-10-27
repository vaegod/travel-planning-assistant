package org.example.traveljavaserver.agent;

import java.util.*;

/** Bounds model-directed exploration and reserves the remaining rounds for submission/repair. */
final class AgentFlow {
  static final int QUERY_LIMIT=3, TEXT_CORRECTIONS=2;
  private AgentFlow() {}
  static Set<String> offered(AgentRun run,int maxRounds) {
    if(run.modelQueries>=QUERY_LIMIT||run.modelRounds>=Math.max(1,maxRounds-2))run.forceDraft=true;
    if(run.forceDraft)return run.budgetEstimated?Set.of("validate_itinerary","report_infeasible")
        :Set.of("estimate_budget","report_infeasible");
    return run.budgetEstimated?Set.of("estimate_budget","search_places","route_places","validate_itinerary","report_infeasible")
        :Set.of("estimate_budget","search_places","route_places","report_infeasible");
  }
  static boolean query(String name){return name.equals("search_places")||name.equals("route_places");}
  static boolean canExecute(AgentRun run,String name) {
    if(name.equals("report_infeasible"))return true;
    if(name.equals("validate_itinerary"))return run.budgetEstimated;
    if(name.equals("estimate_budget"))return !run.forceDraft||!run.budgetEstimated;
    return query(name)&&!run.forceDraft&&run.modelQueries<QUERY_LIMIT;
  }
  static String instruction(AgentRun run) {
    return run.budgetEstimated
        ?"候选探索阶段已结束。请现在实际调用validate_itinerary提交完整草稿（局部修改提交指定补丁），后端会自动查询地点及相邻路线；有校验问题只修正这些问题。若硬约束确实无法满足，调用report_infeasible说明原因。不要继续查候选、重复核算或询问用户是否需要规划。"
        :"请实际调用estimate_budget完成费用核算，再提交草稿；若硬约束确实不可满足，调用report_infeasible。不要只给预算说明或询问用户是否继续。";
  }
}
