package com.adguardhome.android;

import android.content.Context;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class ModuleControlClient {
    private static final String PROTOCOL = "AGHCTL/1";
    // The token is injected at build time by build.ps1 from a freshly
    // generated random source.  Never rely on a literal value here.
    private static final String TOKEN = BuildConstants.BRIDGE_TOKEN;
    private static final int PORT = 3001;
    private static final String PREFS = "bridge_state";
    private static final String KEY_TOKEN = "accepted_token";
    private static volatile Context appContext;

    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public Result execute(String action) throws IOException {
        return execute(action, null);
    }

    public Result execute(String action, String argument) throws IOException {
        if (!isAllowedAction(action)) throw new IllegalArgumentException("Unsupported control action");
        boolean requiresArgument = "proxy-path-add".equals(action) || "proxy-path-delete".equals(action)
                || "proxy-subscription-set".equals(action) || "proxy-subscription-apply".equals(action)
                || "port-set".equals(action);
        if (requiresArgument != (argument != null && !argument.isEmpty())) {
            throw new IllegalArgumentException("Invalid control action argument");
        }
        String fallback = savedToken();
        String[] tokens = (fallback == null || fallback.equals(TOKEN))
                ? new String[] { TOKEN }
                : new String[] { TOKEN, fallback };
        Result lastAuthFailure = null;
        for (String candidate : tokens) {
            Result result = executeWithToken(action, argument, candidate);
            if (result.code == 77) {
                // 旧桥进程可能还在用上一个版本的 token,尝试保存过的 token。
                lastAuthFailure = result;
                continue;
            }
            if (candidate.equals(TOKEN)) {
                forgetSavedToken();
            } else {
                rememberToken(candidate);
            }
            return result;
        }
        return lastAuthFailure;
    }

    private Result executeWithToken(String action, String argument, String token) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
            socket.setSoTimeout(180000);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(PROTOCOL);
            writer.write(' ');
            writer.write(token);
            writer.write(' ');
            writer.write(action);
            if (argument != null) {
                writer.write(' ');
                writer.write(argument);
            }
            writer.write('\n');
            writer.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String header = reader.readLine();
            if (header == null || !header.startsWith(PROTOCOL + " ")) {
                throw new IOException("模块控制桥返回了无效响应");
            }
            int code;
            try {
                code = Integer.parseInt(header.substring(PROTOCOL.length() + 1).trim());
            } catch (NumberFormatException exception) {
                throw new IOException("模块控制桥返回了无效状态码", exception);
            }
            StringBuilder output = new StringBuilder();
            char[] buffer = new char[512];
            int count;
            while ((count = reader.read(buffer)) != -1) output.append(buffer, 0, count);
            return new Result(code, output.toString());
        }
    }

    private static void rememberToken(String token) {
        Context context = appContext;
        if (context == null || token == null || token.isEmpty()) return;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_TOKEN, token).apply();
        } catch (Throwable ignored) {
        }
    }

    private static void forgetSavedToken() {
        Context context = appContext;
        if (context == null) return;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_TOKEN).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String savedToken() {
        Context context = appContext;
        if (context == null) return null;
        try {
            String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null);
            if (value == null || value.isEmpty() || value.equals(TOKEN)) return null;
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isAllowedAction(String action) {
        return "start".equals(action) || "stop".equals(action) || "restart".equals(action)
                || "status".equals(action) || "rules".equals(action) || "recover".equals(action)
                || "filter-seed".equals(action) || "factory-rules".equals(action)
                || "network-alert".equals(action) || "network-alert-ack".equals(action)
                || "proxy-enable".equals(action) || "proxy-disable".equals(action)
                || "proxy-status".equals(action) || "proxy-sync".equals(action)
                || "proxy-paths".equals(action) || "proxy-path-add".equals(action)
                || "proxy-path-delete".equals(action) || "proxy-subscription".equals(action)
                || "proxy-subscription-set".equals(action) || "proxy-subscription-apply".equals(action)
                || "proxy-subscription-delete".equals(action)
                || "logs-clean".equals(action) || "logs-export".equals(action)
                || "port-set".equals(action) || "port-get".equals(action);
    }

    public static final class Result {
        public final int code;
        public final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }
}
