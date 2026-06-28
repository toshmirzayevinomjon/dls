package com.example.dlsbot.net;

import java.util.List;

public class TemplateItem {
    public String name;
    public String url;

    public static class Response {
        public List<TemplateItem> templates;
    }
}
