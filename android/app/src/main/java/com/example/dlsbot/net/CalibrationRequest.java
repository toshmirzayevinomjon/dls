package com.example.dlsbot.net;

import java.util.List;

public class CalibrationRequest {
    public List<ButtonCoord> buttons;
    public CalibrationRequest(List<ButtonCoord> buttons) { this.buttons = buttons; }
}
