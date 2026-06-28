package com.example.dlsbot.net;

public class StatsRequest {
    public String matchId;
    public String action;   // ZARBA | PAS | ...

    public StatsRequest(String matchId, String action) {
        this.matchId = matchId;
        this.action = action;
    }
}
