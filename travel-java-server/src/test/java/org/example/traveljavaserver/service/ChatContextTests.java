package org.example.traveljavaserver.service;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ChatContextTests {
  final ChatContext context = new ChatContext();
  @Test void freeTextIsPassedVerbatimWithoutChangingReferenceFields() {
    var state = new LinkedHashMap<String,Object>(Map.of("city","成都","days",3,"budget",5000));
    var before = context.encode(state);
    for (String text : List.of("总预算1万元","把预算改为1.5万元","不去北京了","别安排玩5天","预算50元能吃什么")) {
      var messages = context.messages(state,List.of(),text);
      assertEquals(before,context.encode(state));
      assertEquals(text,messages.get(messages.size()-1).get("content"));
      assertTrue(messages.get(0).get("content").contains("不会自动更新"));
    }
  }
  @Test void legacyReferenceAndOverrideDataRemainReadableWithoutMigration() {
    String json = "{\"city\":\"杭州\",\"budget\":1500,\"_overrides\":[\"budget\"],\"_baseVersion\":1}";
    var state = context.decode(json);
    assertEquals(1500,context.publicState(state).get("budget"));
    assertFalse(context.publicState(state).containsKey("_overrides"));
    context.messages(state,List.of(),"总预算改为1万元");
    assertEquals(context.decode(json),state);
  }

  private org.example.traveljavaserver.persistence.Rows.Message row(String role,String id,String text) {
    var message=new org.example.traveljavaserver.persistence.Rows.Message();
    message.setRole(role);message.setRequestId(id);message.setContent(text);message.setStatus("completed");
    return message;
  }
  @Test void longLastAnswerKeepsThePreviousQuestionAndClipsOnlyModelHistory() {
    var user=row("user","last","我去成都玩2天，预算1200元");
    var answer=row("assistant","last","walk ".repeat(2600));
    var result=context.messages(Map.of(),List.of(answer,user),"按上一条要求继续");
    assertEquals(List.of("system","user","assistant","user"),
        result.stream().map(m->m.get("role")).toList());
    assertEquals(user.getContent(),result.get(1).get("content"));
    assertTrue(result.get(2).get("content").endsWith("[历史回答已截短]"));
    assertEquals("按上一条要求继续",result.get(3).get("content"));
    assertTrue(result.stream().mapToInt(m->m.get("content").length()).sum()<=12000);
    assertEquals(13000,answer.getContent().length());
  }
  @Test void ordinaryPairsRemainVerbatimAndClippingDoesNotSplitSurrogatePairs() {
    var shortUser=row("user","short","上一问");
    var shortAnswer=row("assistant","short","上一答");
    var shortResult=context.messages(Map.of(),List.of(shortAnswer,shortUser),"继续");
    assertEquals("上一答",shortResult.get(2).get("content"));
    var user=row("user","long","旅行要求");
    var answer=row("assistant","long","🌏".repeat(8000));
    var result=context.messages(Map.of(),List.of(answer,user),"继续");
    String clipped=result.get(2).get("content");
    for(int i=0;i<clipped.length();i++){
      if(Character.isHighSurrogate(clipped.charAt(i))){
        assertTrue(i+1<clipped.length()&&Character.isLowSurrogate(clipped.charAt(++i)));
      }else assertFalse(Character.isLowSurrogate(clipped.charAt(i)));
    }
    assertTrue(result.stream().mapToInt(m->m.get("content").length()).sum()<=12000);
  }
}
