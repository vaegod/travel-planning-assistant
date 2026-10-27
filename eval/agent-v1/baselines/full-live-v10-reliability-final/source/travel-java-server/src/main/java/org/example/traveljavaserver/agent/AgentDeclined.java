package org.example.traveljavaserver.agent;

import org.example.traveljavaserver.common.AppException;
import org.springframework.http.HttpStatus;

/** A business outcome, distinct from infrastructure and malformed-draft errors. */
final class AgentDeclined extends AppException {
  final AgentTypes.Refusal refusal;
  AgentDeclined(String code, String reason) {
    this(new AgentTypes.Refusal(code, reason));
  }
  AgentDeclined(AgentTypes.Refusal refusal) {
    super(HttpStatus.valueOf(422), refusal.code(), refusal.reason());
    this.refusal = refusal;
  }
}
