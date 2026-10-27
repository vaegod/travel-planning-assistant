package org.example.traveljavaserver.knowledge;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Local intent rules only: deciding whether to retrieve never calls a model or embedding service. */
public final class ChatRetrievalPolicy {
  private ChatRetrievalPolicy() {}

  private static final Pattern RULES = Pattern.compile(
      "规则|条款|规定|须知|政策|退改签|退票|改签|退订|退款|行李限制|免票条件|入园要求|"
          + "预约要求|预约流程|预约方式|携带限制|入住说明|入住要求|开放时间|营业时间|"
          + "\\b(policy|policies|terms|regulations|refund|cancellation|baggage)\\b");
  private static final Pattern DOCUMENTS = Pattern.compile(
      "知识库|上传的|导入的|上传.{0,12}(资料|攻略|笔记|文档|文件)|导入.{0,12}(资料|攻略|笔记|文档|文件)|"
          + "(资料|文档|笔记|攻略|文件)(里|中|内|上|写)|这份(资料|攻略|文档|笔记)|"
          + "我的(资料|攻略|笔记|文档)|团队安排|集合安排|集合地点|"
          + "\\b(uploaded|uploaded documents|my notes|my documents)\\b");
  private static final Pattern SOURCES = Pattern.compile(
      "(根据|依据|按照|参考|查阅|检索|查询|查找|查一下|查下)(.{0,8})(资料|原文|文档|攻略|笔记)|"
          + "(给出|提供|附上|附带|标注|注明|列出|显示|带上|需要|要求|要有|请给|加上).{0,10}(来源|出处|引用|依据)|"
          + "来源|出处|引用|证据|依据呢|依据是什么|有什么依据|有何依据|"
          + "\\b(sources?|citations?|cite|references?)\\b");
  private static final Pattern NO_RETRIEVAL = Pattern.compile(
      "(不要|不用|无需|不必|别).{0,3}(查|检索|查询|使用|参考)(.{0,3})(知识库|资料)|"
          + "不(用|需要)知识库|不查(资料|知识库)|\\b(without rag|do not retrieve)\\b");
  private static final Pattern NEGATED_SOURCE = Pattern.compile(
      "(不需要|无需|不用|不要|不必)(再)?(提供|给出|附上|标注|注明|列出|加|带|展示)?(来源|出处|引用|依据)");
  private static final Pattern FOLLOWUP = Pattern.compile(
      "^(那|它|这|其|那里|刚才|上面|上述|前面|还有|以及|另外|具体|分别|地址呢|来源呢|出处呢|"
          + "怎么预约|如何预约|要提前|需要提前|有优惠|多少钱|能退吗|可以退吗|能改吗|能带吗|为什么|继续说|再详细|"
          + "给出来源|请给|给我|请注明|注明|附上|来源|出处|依据|第二天|第三天|接着|然后|继续|再说|能不能|是否).*");
  private static final Pattern NEW_TOPIC = Pattern.compile(
      "规划|安排行程|日游|天游|两日|两天|三日|三天|预算|推荐|介绍|美食|天气|闲聊|换个话题|换个城市|不说这个");
  private static final Pattern UPLOAD_HELP = Pattern.compile(
      "(怎么|如何|哪里|在哪)(上传|导入|添加|删除)(资料|文件|文档|攻略|笔记)");

  public record Decision(boolean retrieve, boolean followup, String priorQuestion) {}

  public static Decision decide(String message, List<Map<String, String>> history) {
    String text = normalize(message);
    if (NO_RETRIEVAL.matcher(text).find() || UPLOAD_HELP.matcher(text).find()) return model();
    boolean requested = explicit(text);
    if (requested && !followup(text)) return new Decision(true, false, "");
    if (!followup(text)) return model();
    // Walk only an uninterrupted chain of short follow-ups. A new ordinary topic resets retrieval.
    int current = history.size() - 1;
    if (current >= 0 && "user".equals(history.get(current).get("role"))
        && message.equals(history.get(current).get("content"))) current--;
    List<String> recentFollowups = new ArrayList<>();
    for (int i = current; i >= 0; i--) {
      if (!"user".equals(history.get(i).get("role"))) continue;
      String prior = history.get(i).getOrDefault("content", "");
      String normalized = normalize(prior);
      if (NO_RETRIEVAL.matcher(normalized).find() || UPLOAD_HELP.matcher(normalized).find()) return model();
      requested = requested || explicit(normalized);
      if (followup(normalized)) {
        if (recentFollowups.size() < 2) recentFollowups.add(prior);
        continue;
      }
      return requested ? new Decision(true, true, context(prior, recentFollowups)) : model();
    }
    return requested ? new Decision(true, !recentFollowups.isEmpty(), context("", recentFollowups)) : model();
  }

  private static boolean explicit(String text) {
    String positive = NEGATED_SOURCE.matcher(text).replaceAll("");
    return RULES.matcher(positive).find() || DOCUMENTS.matcher(positive).find() || SOURCES.matcher(positive).find();
  }

  private static boolean followup(String text) {
    return text.length() <= 60 && FOLLOWUP.matcher(text).matches() && !NEW_TOPIC.matcher(text).find();
  }

  private static String context(String anchor, List<String> recentFollowups) {
    Collections.reverse(recentFollowups);
    String suffix = recentFollowups.isEmpty() ? "" : "\n最近用户追问：" + String.join("；", recentFollowups);
    return anchor.substring(0, Math.min(300, anchor.length())) + suffix;
  }

  private static String normalize(String value) { return value.strip().toLowerCase(Locale.ROOT); }
  private static Decision model() { return new Decision(false, false, ""); }
}
