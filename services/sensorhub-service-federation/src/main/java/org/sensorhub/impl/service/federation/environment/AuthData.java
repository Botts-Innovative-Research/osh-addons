package org.sensorhub.impl.service.federation.environment;

import java.util.Base64;

public class AuthData {

    private String type;
    private String username;
    private String password;
    private String base64;
    private String secret;

    public AuthData() {}

    public void encode() {
        if ("basic".equals(type)) {
            String userPass = username + ":" + password;
            this.base64 = Base64.getEncoder().encodeToString(userPass.getBytes());
        }
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getBase64() {
        return base64;
    }

    public void setBase64(String base64) {
        this.base64 = base64;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}