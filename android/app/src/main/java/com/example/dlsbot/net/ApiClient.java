package com.example.dlsbot.net;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {

    // O'z serveringiz manzili. Oxirida '/' bo'lishi SHART.
    private static final String BASE_URL = "http://192.168.1.100:3000/";

    private static volatile Retrofit retrofit;

    public static ApiService getService() {
        if (retrofit == null) {
            synchronized (ApiClient.class) {
                if (retrofit == null) {
                    HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
                    logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .addInterceptor(logging)
                            .build();

                    retrofit = new Retrofit.Builder()
                            .baseUrl(BASE_URL)
                            .client(client)
                            .addConverterFactory(GsonConverterFactory.create())
                            .build();
                }
            }
        }
        return retrofit.create(ApiService.class);
    }

    public static OkHttpClient rawHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }
}
