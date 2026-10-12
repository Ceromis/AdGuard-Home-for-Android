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
    private static final int DEFAULT_PORT = 3000;
    private static volatile int port = DEFAULT_PORT;
    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("root:root".getBytes(StandardCharsets.UTF_8));
    // HttpURLConnection 只从 CookieHandler.getDefault() 里取 cookie 存储,
    // 因此整个应用必须共用同一个 CookieManager 与同一个 loggedIn 状态。
    // 之前每个 Compose recomposition 都会 new 一个 ApiClient 并 setDefault,
    // 导致两个实例交替成为全局 handler,登录后的 cookie 被覆盖丢失。
    private static volatile ApiClient instance;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private boolean loggedIn;

    private ApiClient() {
        CookieHandler.setDefault(cookies);
    }

    public static ApiClient getInstance() {
        ApiClient local = instance;
        if (local != null) return local;
        synchronized (ApiClient.class) {
            if (instance == null) instance = new ApiClient();
            return instance;
        }
    }

    public int getPort() {
        return port;
    }

    public static void setPort(int newPort) {
        if (newPort < 1 || newPort > 65535) throw new IllegalArgumentException("Port must be 1..65535");
        synchronized (ApiClient.class) {
            if (port == newPort) return;
            port = newPort;
            if (instance != null) {
                instance.loggedIn = false;
                instance.cookies.getCookieStore().removeAll();
            }
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public JSONObject get(String path) throws Exception {
        return request("GET", path, null, true);
    }

    public JSONObject tryGet(String path) {
        try {
            return get(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body == null ? new JSONObject() : body, true);
    }

    private JSONObject request(String method, String path, JSONObject body, boolean retryAuth) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
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
        if ((code == 401 || code == 403 || code == 404 || code == 302) && retryAuth && login()) {
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
            connection = (HttpURLConnection) new URL(baseUrl() + "/control/login").openConnection();
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
