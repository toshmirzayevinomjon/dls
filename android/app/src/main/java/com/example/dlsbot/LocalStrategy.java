package com.example.dlsbot;

import com.example.dlsbot.net.DecisionResponse;

/**
 * Telefon ichidagi strategik qaror — server (Groq) KERAK EMAS.
 * Gol hisobiga qarab rejim almashadi:
 *  - 5 goldan kam -> AGGRESSIVE (hujum, ko'p zarba)
 *  - 5+ gol       -> TIKI_TAKA (xavfsiz pas, golni saqlash)
 */
public final class LocalStrategy {

    private static final int GOAL_THRESHOLD = 5;

    private LocalStrategy() {}

    public static DecisionResponse decide(float ballNormX, boolean hasBall,
                                          boolean attackRight, int myGoals) {
        DecisionResponse d = new DecisionResponse();
        boolean tiki = myGoals >= GOAL_THRESHOLD;
        d.mode = tiki ? "TIKI_TAKA" : "AGGRESSIVE";
        d.confidence = 0.8f;
        d.myGoals = myGoals;

        boolean nearOppGoal = attackRight ? (ballNormX > 0.66f) : (ballNormX < 0.34f);
        boolean nearOwnGoal = attackRight ? (ballNormX < 0.20f) : (ballNormX > 0.80f);
        boolean midfield = ballNormX > 0.40f && ballNormX < 0.60f;

        if (tiki) {
            // Golni saqlash: ko'proq pas, kam tavakkal
            if (hasBall) d.action = nearOppGoal ? "ZARBA" : "PAS";
            else d.action = nearOwnGoal ? "HIMOYA" : "SURISH";
        } else {
            // Agressiv hujum: imkon bo'lsa zarba
            if (hasBall) {
                if (nearOppGoal) d.action = "ZARBA";
                else if (midfield) d.action = "PAS";
                else d.action = "SURISH";
            } else {
                d.action = nearOwnGoal ? "HIMOYA" : "SURISH";
            }
        }
        d.reason = "local";
        return d;
    }
}
