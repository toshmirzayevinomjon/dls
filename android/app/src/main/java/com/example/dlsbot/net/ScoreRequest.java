package com.example.dlsbot.net;

public class ScoreRequest {
    public String matchId;
    public String event;     // MY_GOAL | OPP_GOAL | RESET
    public Integer myGoals;
    public Integer oppGoals;

    public ScoreRequest(String matchId, String event) {
        this.matchId = matchId;
        this.event = event;
    }
}
