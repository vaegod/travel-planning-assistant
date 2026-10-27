package org.example.traveljavaserver.knowledge;

import java.util.List;
import static org.example.traveljavaserver.knowledge.KnowledgeTypes.*;

/** Server-validated citation snapshot, persisted with each assistant turn. */
public record ChatEvidence(String mode, String city, String notice, List<Match> sources, List<Claim> claims) {}
