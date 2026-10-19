package org.example.traveljavaserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequestDTO {
  @NotBlank(message = "消息不能为空")
  @Size(max = 2000, message = "消息不能超过2000个字符")
  private String message;

  @NotBlank(message = "requestId不能为空")
  @Pattern(
      regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
      message = "requestId必须是UUID")
  private String requestId;

  @NotBlank(message = "conversationId不能为空")
  @Pattern(regexp = "[0-9a-fA-F-]{36}", message = "conversationId必须是UUID")
  private String conversationId;

  @Size(max = 50)
  @Pattern(regexp = "[^\\p{Cntrl}]*")
  private String city;

  /** Accepted for old clients only; /travel/chat always uses the model and ignores this flag. */
  @Deprecated
  private Boolean useKnowledge;
}
