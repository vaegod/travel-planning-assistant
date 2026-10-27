package org.example.traveljavaserver.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface StoreMapper {
  @Select("SELECT * FROM users WHERE username=#{username}")
  Rows.User user(String username);

  @Select("SELECT * FROM users WHERE id=#{id} FOR UPDATE")
  Rows.User lockUser(long id);

  @Select("SELECT credential_version FROM users WHERE id=#{id}")
  Long credentialVersion(long id);

  @Update(
      "UPDATE users SET username=#{username},credential_version=credential_version+1 WHERE"
          + " id=#{id}")
  int renameUser(@Param("id") long id, @Param("username") String username);

  @Update(
      "UPDATE users SET password_hash=#{hash},credential_version=credential_version+1 WHERE"
          + " id=#{id}")
  int changePassword(@Param("id") long id, @Param("hash") String hash);

  @Insert("INSERT INTO users(username,password_hash) VALUES(#{username},#{passwordHash})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int addUser(Rows.User row);

  @Insert(
      "INSERT INTO trips(id,user_id,title,city,days,budget)"
          + " VALUES(#{id},#{userId},#{title},#{city},#{days},#{budget})")
  int addTrip(Rows.Trip row);

  @Insert(
      "INSERT INTO trip_versions(id,trip_id,version,plan_json)"
          + " VALUES(#{id},#{tripId},#{version},#{planJson})")
  int addVersion(Rows.Version row);

  @Select("SELECT * FROM trips WHERE id=#{id} AND user_id=#{owner}")
  Rows.Trip trip(@Param("owner") long owner, @Param("id") String id);

  @Select("SELECT * FROM trips WHERE id=#{id} AND user_id=#{owner} FOR UPDATE")
  Rows.Trip lockTrip(@Param("owner") long owner, @Param("id") String id);

  @Select("SELECT * FROM trip_versions WHERE trip_id=#{tripId} AND version=#{version}")
  Rows.Version version(@Param("tripId") String tripId, @Param("version") int version);

  @Select("SELECT * FROM trip_versions WHERE trip_id=#{tripId} ORDER BY version DESC")
  List<Rows.Version> versions(String tripId);

  @Select(
      "SELECT * FROM trips WHERE user_id=#{owner} ORDER BY updated_at DESC,id LIMIT #{limit} OFFSET"
          + " #{offset}")
  List<Rows.Trip> trips(
      @Param("owner") long owner, @Param("limit") int limit, @Param("offset") int offset);

  @Select("SELECT COUNT(*) FROM trips WHERE user_id=#{owner}")
  long tripCount(long owner);

  @Update(
      "UPDATE trips SET"
          + " title=#{title},city=#{city},days=#{days},budget=#{budget},current_version=#{currentVersion}"
          + " WHERE id=#{id} AND user_id=#{userId}")
  int updateTrip(Rows.Trip trip);

  @Delete("DELETE FROM trips WHERE id=#{id} AND user_id=#{owner}")
  int deleteTrip(@Param("owner") long owner, @Param("id") String id);

  @Insert(
      "INSERT INTO conversations(id,user_id,trip_id,title,context_json)"
          + " VALUES(#{id},#{userId},#{tripId},#{title},#{contextJson})")
  int addConversation(Rows.Conversation row);

  @Select("SELECT * FROM conversations WHERE id=#{id} AND user_id=#{owner}")
  Rows.Conversation conversation(@Param("owner") long owner, @Param("id") String id);

  @Select("SELECT * FROM conversations WHERE id=#{id} AND user_id=#{owner} FOR UPDATE")
  Rows.Conversation lockConversation(@Param("owner") long owner, @Param("id") String id);

  @Select(
      "SELECT * FROM conversations WHERE user_id=#{owner} ORDER BY updated_at DESC,id LIMIT"
          + " #{limit} OFFSET #{offset}")
  List<Rows.Conversation> conversations(
      @Param("owner") long owner, @Param("limit") int limit, @Param("offset") int offset);

  @Select("SELECT COUNT(*) FROM conversations WHERE user_id=#{owner}")
  long conversationCount(long owner);

  @Update(
      "UPDATE conversations SET"
          + " title=#{title},context_json=#{contextJson},active_request_id=#{activeRequestId},updated_at=CURRENT_TIMESTAMP(6)"
          + " WHERE id=#{id} AND user_id=#{userId}")
  int updateConversation(Rows.Conversation row);

  @Update(
      "UPDATE conversations SET active_request_id=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE"
          + " id=#{id} AND user_id=#{owner} AND active_request_id=#{requestId}")
  int releaseConversation(
      @Param("owner") long owner, @Param("id") String id, @Param("requestId") String requestId);

  @Delete("DELETE FROM conversations WHERE id=#{id} AND user_id=#{owner}")
  int deleteConversation(@Param("owner") long owner, @Param("id") String id);

  @Insert(
      "INSERT INTO messages(id,conversation_id,user_id,request_id,role,content,status)"
          + " VALUES(#{id},#{conversationId},#{userId},#{requestId},#{role},#{content},#{status})")
  @Options(useGeneratedKeys = true, keyProperty = "seq")
  int addMessage(Rows.Message row);

  @Select(
      "<script>SELECT * FROM messages WHERE conversation_id=#{conversationId} AND"
          + " user_id=#{owner}<if test='before != null'> AND seq &lt; #{before}</if> ORDER BY seq"
          + " DESC LIMIT #{limit}</script>")
  List<Rows.Message> messages(
      @Param("owner") long owner,
      @Param("conversationId") String conversationId,
      @Param("before") Long before,
      @Param("limit") int limit);

  @Select(
      "SELECT m.* FROM messages m JOIN messages a ON m.user_id=a.user_id AND"
          + " m.request_id=a.request_id AND a.role='assistant' AND a.status='completed' WHERE"
          + " m.conversation_id=#{conversationId} AND m.user_id=#{owner} ORDER BY m.seq DESC LIMIT"
          + " 24")
  List<Rows.Message> context(
      @Param("owner") long owner, @Param("conversationId") String conversationId);

  @Select("SELECT COUNT(*) FROM messages WHERE user_id=#{owner} AND request_id=#{requestId}")
  int requestExists(@Param("owner") long owner, @Param("requestId") String requestId);

  @Update(
      "UPDATE messages SET content=#{content},updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND"
          + " user_id=#{owner} AND status='streaming'")
  int checkpoint(
      @Param("owner") long owner, @Param("id") String id, @Param("content") String content);

  @Update("UPDATE messages SET evidence_json=#{evidence} WHERE id=#{id} AND user_id=#{owner} AND status='streaming'")
  int messageEvidence(@Param("owner") long owner, @Param("id") String id, @Param("evidence") String evidence);

  @Update(
      "UPDATE messages SET"
          + " content=#{content},status=#{status},error_code=#{code},error_message=#{message},updated_at=CURRENT_TIMESTAMP(6)"
          + " WHERE id=#{id} AND user_id=#{owner} AND status='streaming'")
  int finishMessage(
      @Param("owner") long owner,
      @Param("id") String id,
      @Param("content") String content,
      @Param("status") String status,
      @Param("code") String code,
      @Param("message") String message);

  @Update(
      "UPDATE messages SET"
          + " status='error',error_code='SERVER_RESTARTED',error_message='服务已重启，请重试',updated_at=CURRENT_TIMESTAMP(6)"
          + " WHERE status='streaming'")
  int recoverMessages();

  @Update("UPDATE conversations SET active_request_id=NULL WHERE active_request_id IS NOT NULL")
  int recoverConversations();
}
