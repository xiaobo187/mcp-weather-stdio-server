/*
 * Copyright 2024 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.mcp.sample.server;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class WeatherService {

    // 高德API的基础URL
    private static final String BASE_URL = "https://restapi.amap.com/v3";


    //# 高德API密钥,一组32位字符,获取方式: https://lbs.amap.com/api/webservice/create-project-and-key
    private static final String staticGaoDeAPIKey = "your AIP KEY";

    private final RestClient restClient;

    public WeatherService() {

        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Points(@JsonProperty("regeocode") Props regeocode) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Props(@JsonProperty("addressComponent") Address addressComponent) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            public record Address(@JsonProperty("adcode") String adcode) {
            }
        }
    }



    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lives(@JsonProperty("lives") List<Live> lives) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Live(
                @JsonProperty("province") String province, @JsonProperty("city") String city,
                @JsonProperty("adcode") String adcode, @JsonProperty("weather") String weather,
                @JsonProperty("temperature") String temperature, @JsonProperty("winddirection") String winddirection,
                @JsonProperty("windpower") String windpower,
                @JsonProperty("humidity") String humidity,
                @JsonProperty("reporttime") String reporttime,
                @JsonProperty("temperature_float") String temperature_float,
                @JsonProperty("humidity_float") String humidity_float) {

        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Forecasts(@JsonProperty("forecasts") List<Forecast> forecasts) {
        public record Forecast(
            @JsonProperty("city") String city,
            @JsonProperty("province") String province,
            @JsonProperty("reporttime") String reporttime,
            @JsonProperty("casts") List<Cast> casts) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Cast(
                @JsonProperty("date") String date, @JsonProperty("week") String week,
                @JsonProperty("dayweather") String dayweather, @JsonProperty("daytemp") String daytemp,
                @JsonProperty("daywind") String daywind, @JsonProperty("daypower") String daypower,
                @JsonProperty("nightweather") String nightweather,
                @JsonProperty("nighttemp") String nighttemp,
                @JsonProperty("nightwind") String nightwind,
                @JsonProperty("nightpower") String nightpower){

        }}
    }






    @Tool(description = "获取指定位置的实况天气,根据用户输入的经纬度,查询目标区域当前的天气情况")
    public String getCurrentWeather(double latitude, double longitude) {

        var points =getCityCode(latitude,longitude);
        if(Objects.isNull(points) || points.regeocode() ==null
                   ||points.regeocode().addressComponent() ==null  ){
            return "`Failed to retrieve grid point data for points: "+points+". ";
        }

        String adcode = points.regeocode().addressComponent().adcode();

        var lives = restClient.get()
                .uri("/weather/weatherInfo?key={staticGaoDeAPIKey}&city={adcode}&extensions=base&output=json", staticGaoDeAPIKey, adcode)
                .retrieve()
                .body(Lives.class);

        if(Objects.isNull(lives) || lives.lives().isEmpty() ){
            return "`Failed to retrieve grid point data for adcode: "+adcode+". This adcode may not be supported by the Gaode API (only China locations are supported)";
        }
         String forecastText =  lives.lives().stream()
                .map(p -> String.format("""
                                %s%s:
                                实况天气: %s
                                温度: %s
                                风向: %s
                                风力: %s
                                空气湿度: %s
                                """,
                        p.province(), p.city(),
                        p.weather(),
                        p.temperature(),
                        p.winddirection(),
                        p.windpower(),
                        p.humidity()
                ))
                .collect(Collectors.joining());
        return forecastText;
    }


    @Tool(description = "获取指定位置的未来几天的天气预报,根据用户输入的经纬度,查询目标区域未来的天气情况")
    public String getWeiLaiWeather(double latitude, double longitude) {

        var points =getCityCode(latitude,longitude);
        String adcode = points.regeocode().addressComponent().adcode();
        var weatherData = restClient.get()
                .uri("/weather/weatherInfo?key={staticGaoDeAPIKey}&city={adcode}&extensions=all&output=json", staticGaoDeAPIKey, adcode)
                .retrieve()
                .body(Forecasts.class);

        StringBuilder text = new StringBuilder();

        // 处理实况天气（lives）
        assert weatherData != null;
        if (weatherData.forecasts() != null && !weatherData.forecasts().isEmpty()) {
            Forecasts.Forecast forecast = weatherData.forecasts().get(0);
            text.append(String.format(
                    "%s %s 预报：",
                    forecast.province(),
                    forecast.city()

            ));
            text.append("未来").append(forecast.casts().size()).append("日天气：");
            // 处理预报天气（forecasts）
            if (!forecast.casts().isEmpty()) {
                forecast = weatherData.forecasts().get(0);


                for (Forecasts.Forecast.Cast cast : forecast.casts()) {
                    text.append(String.format(
                            "\n----------------------------\n" +
                                    "%s(周%s)天气\n" +
                                    "白天: %s, 白天温度是 %s 度, 白天风向是 %s, 白天风力 %s 级\n" +
                                    "夜间: %s, 夜间温度：%s, 夜间风向是 %s, 夜间风力为 %s 级\n",
                            cast.date(),
                            cast.week(),
                            cast.dayweather(),
                            cast.daytemp(),
                            cast.daywind(),
                            cast.daypower(),
                            cast.nightweather(),
                            cast.nighttemp(),
                            cast.nightwind(),
                            cast.nightpower()
                    ));
                }
            }


        }
        return text.toString();

    }

    private Points getCityCode(double latitude, double longitude){


        return  restClient.get()
                .uri("/geocode/regeo?output=json&location={longitude},{latitude}&key={staticGaoDeAPIKey}", longitude, latitude, staticGaoDeAPIKey)
                .retrieve()
                .body(Points.class);
    }

//    public static void main(String[] args) {
//        WeatherService client = new WeatherService();
//        System.out.println(client.getCurrentWeather(34.247311, 108.948303));
//        System.out.println(client.getWeiLaiWeather(34.247311, 108.948303));
//    }

}