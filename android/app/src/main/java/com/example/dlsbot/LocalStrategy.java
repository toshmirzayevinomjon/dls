package com.example.dlsbot;

import com.example.dlsbot.net.DecisionResponse;

/**
 * Telefon ichidagi aqlli o'yin qarori — server kerak emas.
 * Maydon zonalari + to'p pozitsiyasi/tezligi asosida harakat tanlaydi.
 *
 * action: SHOOT | THROUGH | PASS | DRIBBLE | CLEAR | CHASE | TACKLE
 * dirX,dirY: joystick yo'nalishi (-1..1)
 */
public final class LocalStrategy {

    private static final int GOAL_THRESHOLD = 5;

    private LocalStrategy() {}

    public static DecisionResponse decide(float bx, float by, float velX, float velY,
                                          boolean hasBall, boolean attackRight, int myGoals) {
        DecisionResponse d = new DecisionResponse();
        boolean tiki = myGoals >= GOAL_THRESHOLD;
        d.mode = tiki ? "TIKI_TAKA" : "AGGRESSIVE";
        d.confidence = 0.8f;
        d.myGoals = myGoals;
        d.reason = "local";

        int sign = attackRight ? 1 : -1;
        float goalX = attackRight ? 1f : 0f;
        float ownX = attackRight ? 0f : 1f;
        float distOpp = Math.abs(bx - goalX);
        float distOwn = Math.abs(bx - ownX);
        boolean attThird = distOpp < 0.34f;
        boolean ownThird = distOwn < 0.34f;
        boolean midfield = !attThird && !ownThird;

        if (hasBall) {
            if (attThird) {
                boolean central = by > 0.28f && by < 0.72f;
                if (distOpp < 0.22f || central) {
                    // #1 Bo'sh burchakka mo'ljallab zarba (joriy yarmiga teskari burchak)
                    d.action = "SHOOT";
                    d.dirX = sign;
                    d.dirY = (by < 0.5f) ? 0.35f : -0.35f;
                } else {
                    // #7 Himoya ortiga chiziqli pas
                    d.action = "THROUGH";
                    d.dirX = sign; d.dirY = 0f;
                }
            } else if (midfield) {
                if (tiki) {           // #39 possession
                    d.action = "PASS"; d.dirX = sign; d.dirY = 0f;
                } else {              // #11/#13 oldinga, markazga kesib
                    d.action = "DRIBBLE";
                    d.dirX = sign;
                    d.dirY = (0.5f - by) * 0.6f;
                }
            } else { // o'z yarmida to'p bizda
                if (tiki) { d.action = "PASS"; d.dirX = sign; d.dirY = 0f; }
                else { d.action = "CLEAR"; d.dirX = sign; d.dirY = 0f; } // #27 uloqtirish
            }
        } else {
            // Himoya: to'pga qarab harakat (ekran markaziga nisbatan)
            d.dirX = (bx - 0.5f) * 1.6f;
            d.dirY = (by - 0.5f) * 1.6f;
            d.action = ownThird ? "TACKLE" : "CHASE"; // #21/#28 press/tackle, #48 to'pga yetish
        }
        return d;
    }
}
