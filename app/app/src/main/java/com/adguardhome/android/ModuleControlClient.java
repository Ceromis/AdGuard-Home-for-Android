package com.adguardhome.android;

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
    private static final String TOKEN = "79d87c953bd74ab488758620a32dc5f48a8e46ad85c37a76c88f138b556ef2cb";
    private static final int PORT = 3001;

    public Result execute(String action) throws IOException {
        return execute(action, null);
    }

    public Result execute(String action, String argument) throws IOException {
        if (!isAllowedAction(action)) throw new IllegalArgumentException("Unsupported control action");
        boolean requiresArgument = "proxy-path-add".equals(action) || "proxy-path-delete".equals(action)
                || "proxy-subscription-set".equals(action) || "proxy-subscription-apply".equals(action);
        if (requiresArgument != (argument != null && !argument.isEmpty())) {
            throw new IllegalArgumentException("Invalid control action argument");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
            socket.setSoTimeout(60000);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(PROTOCOL);
            writer.write(' ');
            writer.write(TOKEN);
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
                || "logs-clean".equals(action) || "logs-export".equals(action);
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
