package org.example.traveljavaserver.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChatRetrievalPolicyTests {
  private List<Map<String,String>> history(String... questions) {
    List<Map<String,String>> result = new ArrayList<>();
    result.add(Map.of("role", "system", "content", "知识库来源不得影响意图判断"));
    for (String question : questions) {
      result.add(Map.of("role", "user", "content", question));
      result.add(Map.of("role", "assistant", "content", "根据上传的资料，退票规则如下……"));
    }
    return result;
  }

  @Test void ordinaryTravelAndPlanningDoNotRetrieve() {
    for (String text : List.of("你好", "西安古称什么？", "北京有哪些必去的景点？", "成都三日游攻略",
        "帮我规划成都两日游，预算800元", "介绍杭州", "如何选择旅行保险？", "上海美食推荐",
        "今天杭州下雨吗？", "推荐一个适合老人的安排", "不需要引用，帮我规划旅行", "如何上传资料"))
      assertFalse(ChatRetrievalPolicy.decide(text, history()).retrieve(), text);
  }

  @Test void specificRulesDocumentsAndSourcesRetrieve() {
    for (String text : List.of("退改签规则是什么？", "酒店入住须知", "请解释门票预约流程", "行李限制有哪些？",
        "根据我上传的攻略安排第二天", "我的笔记里集合地点在哪？", "资料中的测试编号是什么？",
        "西安古称什么？请给出来源", "请附上引用", "给我来源", "这个说法的依据呢？",
        "查一下资料，西湖哪年入遗？", "What is the cancellation policy?"))
      assertTrue(ChatRetrievalPolicy.decide(text, history()).retrieve(), text);
  }

  @Test void referencesFollowOnlyTheLatestUserTopic() {
    var chain = history("根据我上传的资料，茶叶博物馆有哪些馆区？", "它的地址呢？");
    var decision = ChatRetrievalPolicy.decide("要提前预约吗？", chain);
    assertTrue(decision.retrieve()); assertTrue(decision.followup());
    assertTrue(decision.priorQuestion().contains("茶叶博物馆"));
    assertFalse(ChatRetrievalPolicy.decide("帮我规划成都两日游", chain).retrieve());
    assertFalse(ChatRetrievalPolicy.decide("它适合老人吗？", history("根据资料解释退票规则", "介绍成都")).retrieve());
    assertFalse(ChatRetrievalPolicy.decide("那推荐上海美食", chain).retrieve());
  }

  @Test void sourceFollowupCanGroundAPreviousModelOnlyQuestion() {
    var decision = ChatRetrievalPolicy.decide("给出来源", history("西安古称什么？"));
    assertTrue(decision.retrieve()); assertTrue(decision.followup());
    assertEquals("西安古称什么？", decision.priorQuestion());
    // Production history includes the current user message; it must not refer to itself.
    var production = new ArrayList<>(history("西安古称什么？"));
    production.add(Map.of("role", "user", "content", "给出来源"));
    assertEquals(decision, ChatRetrievalPolicy.decide("给出来源", production));
    var chained = ChatRetrievalPolicy.decide("给我来源", history("根据资料，茶叶博物馆有哪些馆区？", "它的地址呢？"));
    assertTrue(chained.retrieve()); assertTrue(chained.followup());
    assertTrue(chained.priorQuestion().contains("茶叶博物馆"));
    assertTrue(chained.priorQuestion().contains("地址"));
  }

  @Test void explicitChoicesAndNegationsDoNotAccidentallyInheritRetrieval() {
    assertFalse(ChatRetrievalPolicy.decide("不要查知识库，直接介绍成都", history("退票规则是什么？")).retrieve());
    assertFalse(ChatRetrievalPolicy.decide("不用提供来源，西安古称什么？", history()).retrieve());
    assertTrue(ChatRetrievalPolicy.decide("不需要引用，告诉我退票规定", history()).retrieve());
    assertTrue(ChatRetrievalPolicy.decide("参考上传的攻略，规划成都两日游", history()).retrieve());
  }
}
