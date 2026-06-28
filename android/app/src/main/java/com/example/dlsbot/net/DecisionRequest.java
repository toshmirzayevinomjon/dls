package com.example.dlsbot.net;

public class DecisionRequest {
    public String matchId;
    public String profile;     // #9 taktika profili
    public Coord ball;
    public Coord oppGoal;
    public Coord ownGoal;
    public Coord nearestOpponent;
    public boolean hasBall;
    public boolean attackRight;
    public Integer myGoals;
    public Integer oppGoals;

    public static class Coord {
        public float x;
        public float y;
        public Coord(float x, float y) { this.x = x; this.y = y; }
    }
}
