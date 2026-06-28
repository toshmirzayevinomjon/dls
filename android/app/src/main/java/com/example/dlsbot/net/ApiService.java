package com.example.dlsbot.net;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface ApiService {

    @GET("api/bot/settings")
    Call<BotSettings> getSettings();

    @GET("api/bot/templates")
    Call<TemplateItem.Response> getTemplates();

    @POST("api/bot/calibrate")
    Call<Void> saveCalibration(@Body CalibrationRequest body);

    @POST("api/bot/decision")
    Call<DecisionResponse> getDecision(@Body DecisionRequest body);

    @POST("api/bot/score")
    Call<ScoreResponse> reportScore(@Body ScoreRequest body);

    @POST("api/bot/stats")
    Call<Void> reportStats(@Body StatsRequest body);

    @POST("api/bot/log")
    Call<Void> reportLog(@Body LogRequest body);
}
