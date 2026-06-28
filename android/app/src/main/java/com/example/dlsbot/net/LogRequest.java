package com.example.dlsbot.net;

public class LogRequest {
    public String matchId;
    public String level;    // INFO | WARN | ERROR
    public String message;

    public LogRequest(String matchId, String level, String message) {
        this.matchId = matchId;
        this.level = level;
        this.message = message;
    }
}
