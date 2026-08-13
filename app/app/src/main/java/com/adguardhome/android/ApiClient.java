package com.adguardhome.android;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class ApiClient {
    private static final String BASE_URL = "http://127.0.0.1:3000";
    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("root:root".getBytes(StandardCharsets.UTF_8));
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private boolean loggedIn;

    public ApiClient() {
        CookieHandler.setDefault(cookies);
    }

    public JSONObject get(String path) throws Exception {
        return request("GET", path, null, true);
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body == null ? new JSONObject() : body, true);
    }

    private JSONObject request(String method, String path, JSONObject body, boolean retryAuth) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(path.contains("filtering/") ? 120000 : 4500);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", AUTH);
        connection.setInstanceFollowRedirects(false);
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.getOutputStream().write(bytes);
        }

        int code = connection.getResponseCode();
        String response = read(connection, code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        if ((code == 401 || code == 403 || code == 302) && retryAuth && login()) {
            return request(method, path, body, false);
        }
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + (response.isEmpty() ? "" : ": " + response));
        }
        if (response.isEmpty()) return new JSONObject();
        String trimmed = response.trim();
        if (trimmed.startsWith("{")) return new JSONObject(trimmed);
        return new JSONObject().put("raw", response);
    }

    private boolean login() {
        if (loggedIn) return false;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE_URL + "/control/login").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(3500);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = new JSONObject().put("name", "root").put("password", "root").toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.getOutputStream().write(bytes);
            int code = connection.getResponseCode();
            read(connection, code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            loggedIn = code >= 200 && code < 300;
            return loggedIn;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String read(HttpURLConnection connection, InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) result.append(line);
        return result.toString();
    }
}
