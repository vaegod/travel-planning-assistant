package org.example.traveljavaserver.agent;

import java.util.*;

/** Explicit input contract: compatible providers must see the fields inside an object. */
final class AgentDraftSchema {
  private AgentDraftSchema() {}
  static String input(AgentRun run) {
    var fixed=org.example.traveljavaserver.planning.PlanRules.fixedExpenses(run.start.days());
    var costs=object(Map.of("accommodation",run.revision==null?amount(fixed.accommodation()):amount(),
        "food",run.revision==null?amount(fixed.food()):amount(),"transportation",amount(),
        "tickets",amount(),"other",run.revision==null?amount(fixed.other()):amount()),
        List.of("accommodation","food","transportation","tickets","other"));
    var fields=new LinkedHashMap<String,Object>();
    List<String> required;
    if(run.revision!=null){
      required=AgentRules.SLOTS.stream().filter(AgentRules.targets(run)::contains).toList();
      for(String name:required)fields.put(name,slot());
    }else{
      fields.put("success",Map.of("type","boolean","enum",List.of(true)));
      fields.put("city",Map.of("type","string","enum",List.of(run.start.city())));
      fields.put("days",Map.of("type","integer","enum",List.of(run.start.days())));
      fields.put("totalBudget",Map.of("type","number","enum",List.of(run.start.budget())));
      fields.put("travelMode",Map.of("type","string","enum",List.of(run.start.travelMode())));
      fields.put("dailyItinerary",Map.of("type","array","minItems",run.start.days(),"maxItems",run.start.days(),
          "items",object(Map.of("day",Map.of("type","integer","minimum",1,"maximum",run.start.days()),
              "date",text(),"morning",slot(),"afternoon",slot(),"evening",slot()),
              List.of("day","date","morning","afternoon","evening"))));
      fields.put("tips",Map.of("type","array","items",text(),"maxItems",8));
      required=List.of("success","city","days","totalBudget","travelMode","dailyItinerary","budgetBreakdown");
    }
    fields.put("budgetBreakdown",costs);
    return AgentRules.JSON.valueToTree(object(Map.of("draftJson",object(fields,required)),List.of("draftJson"))).toString();
  }
  private static Map<String,Object> slot(){
    var fields=new LinkedHashMap<String,Object>();
    for(String name:List.of("spot","duration","description","transportation","ticket"))fields.put(name,text());
    fields.put("visitMinutes",Map.of("type","integer","minimum",15,"maximum",360));
    fields.put("travelMinutes",Map.of("type","integer","minimum",0,"maximum",240));
    fields.put("estimatedCost",amount());
    fields.put("placeId",Map.of("type","string","description","仅限本次查询返回的ID；未查询时省略"));
    fields.put("rest",Map.of("type","boolean","description","休息、住宿、用餐必须为true；仍需填写15～360分钟"));
    return object(fields,List.of("spot","duration","description","transportation","ticket","visitMinutes","travelMinutes","estimatedCost"));
  }
  private static Map<String,Object> amount(){return Map.of("type","number","minimum",0,"maximum",1000000,"multipleOf",0.01);}
  private static Map<String,Object> amount(java.math.BigDecimal minimum){
    return Map.of("type","number","minimum",minimum,"maximum",1000000,"multipleOf",0.01);
  }
  private static Map<String,Object> text(){return Map.of("type","string");}
  private static Map<String,Object> object(Map<String,?> properties,List<String> required){
    return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);
  }
}
