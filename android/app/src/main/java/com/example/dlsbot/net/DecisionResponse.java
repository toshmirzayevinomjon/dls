package com.example.dlsbot.net;

public class DecisionResponse {
    public String action;     // PAS | ZARBA | HIMOYA | SURISH
    public String direction;  // left | right | up | down | none
    public float confidence;
    public String reason;
    public String mode;       // AGGRESSIVE | TIKI_TAKA
    public int myGoals;

    // Joystick yo'nalishi / zarba mo'ljali (-1..1)
    public float dirX;
    public float dirY;
    public boolean sprint;
}
