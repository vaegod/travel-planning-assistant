package org.example.traveljavaserver.auth;

import java.io.Serializable;

public record UserPrincipal(
    long id, String username, @com.fasterxml.jackson.annotation.JsonIgnore long credentialVersion)
    implements Serializable {}
