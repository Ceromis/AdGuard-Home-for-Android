package com.adguardhome.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal, formatting-preserving editor for the DNS block of Clash/Mihomo YAML files. */
public final class ProxyConfigEditor {
    private static final Pattern KEY = Pattern.compile("^(\\s*)(['\\\"]?)([A-Za-z0-9_-]+)\\2\\s*:(.*)$");
    private static final Set<String> FILTERED_DNS_SERVERS = new HashSet<>(Arrays.asList(
            "nameserver", "fallback", "direct-nameserver"));
    private static final Set<String> BOOTSTRAP_DNS_SERVERS = new HashSet<>(Arrays.asList(
            "default-nameserver", "proxy-server-nameserver"));
    private static final List<String> PROXY_BOOTSTRAP_DNS = Arrays.asList(
            "223.5.5.5", "119.29.29.29");

    private ProxyConfigEditor() {
    }

    public static void main(String[] args) throws Exception {
        if ((args.length != 3 && args.length != 4)
                || !("check".equals(args[0]) || "apply".equals(args[0]) || "urls".equals(args[0]))) {
            throw new IllegalArgumentException("usage: ProxyConfigEditor {check|apply|urls} FILE LOCAL_DNS [SUBSCRIPTION_URL]");
        }
        Path file = Paths.get(args[1]);
        List<String> original = Files.readAllLines(file, StandardCharsets.UTF_8);
        if ("urls".equals(args[0])) {
            for (String url : subscriptionUrls(original)) System.out.println(url);
            return;
        }
        List<String> updated = update(original, args[2]);
        if (args.length == 4 && !args[3].isEmpty()) updated = updateSubscriptions(updated, args[3]);
        if (updated.equals(original)) {
            System.out.println("compatible");
            return;
        }
        if ("check".equals(args[0])) {
            System.out.println("needs-change");
            return;
        }
        writeInPlace(file, updated);
        System.out.println("changed");
    }

    static List<String> subscriptionUrls(List<String> source) {
        List<String> urls = new ArrayList<>();
        int providers = findTopLevelKey(source, "proxy-providers");
        if (providers < 0) return urls;
        int end = blockEnd(source, providers, indentation(source.get(providers)));
        for (int i = providers + 1; i < end; i++) {
            Matcher matcher = KEY.matcher(source.get(i));
            if (!matcher.matches() || !"url".equals(matcher.group(3))) continue;
            String value = matcher.group(4).trim();
            int comment = value.indexOf(" #");
            if (comment >= 0) value = value.substring(0, comment).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            if ((value.startsWith("https://") || value.startsWith("http://")) && !urls.contains(value)) urls.add(value);
        }
        return urls;
    }

    static List<String> updateSubscription(List<String> source, String url) {
        return updateSubscriptions(source, url);
    }

    static List<String> updateSubscriptions(List<String> source, String values) {
        List<String> urls = new ArrayList<>();
        for (String value : values.split("\\r?\\n")) {
            String url = value.trim();
            if (url.isEmpty()) continue;
            if (!(url.startsWith("https://") || url.startsWith("http://")) || url.indexOf('\t') >= 0
                    || url.indexOf(' ') >= 0) throw new IllegalArgumentException("invalid subscription URL");
            if (!urls.contains(url)) urls.add(url);
        }
        if (urls.isEmpty()) throw new IllegalArgumentException("invalid subscription URL");
        int providers = findTopLevelKey(source, "proxy-providers");
        if (providers < 0) return source;
        int end = blockEnd(source, providers, indentation(source.get(providers)));
        List<String> result = new ArrayList<>(source);
        int providerIndent = firstChildIndent(result, providers + 1, end, indentation(result.get(providers)));
        if (providerIndent < 0) providerIndent = indentation(result.get(providers)) + 2;
        end = removeGeneratedProviders(result, providers, end, providerIndent);
        int found = 0;
        for (int i = providers + 1; i < end; i++) {
            Matcher matcher = KEY.matcher(result.get(i));
            if (matcher.matches() && "url".equals(matcher.group(3))) {
                if (found >= urls.size()) break;
                String escaped = urls.get(found++).replace("\\", "\\\\").replace("\"", "\\\"");
                result.set(i, matcher.group(1) + "url: \"" + escaped + "\"");
            }
        }
        if (found == urls.size()) return result;
        int fieldIndent = providerIndent + 2;
        for (int index = found; index < urls.size(); index++) {
            String name = "agh-subscription-" + (index + 1);
            String escaped = urls.get(index).replace("\\", "\\\\").replace("\"", "\\\"");
            result.add(end++, spaces(providerIndent) + name + ":");
            result.add(end++, spaces(fieldIndent) + "type: http");
            result.add(end++, spaces(fieldIndent) + "url: \"" + escaped + "\"");
            result.add(end++, spaces(fieldIndent) + "path: ./providers/" + name + ".yaml");
        }
        return result;
    }

    private static int removeGeneratedProviders(List<String> lines, int providers, int end, int providerIndent) {
        for (int i = end - 1; i > providers; i--) {
            Matcher matcher = KEY.matcher(lines.get(i));
            if (!matcher.matches() || matcher.group(1).length() != providerIndent
                    || !matcher.group(3).startsWith("agh-subscription-")) continue;
            int block = blockEnd(lines, i, providerIndent);
            for (int remove = block - 1; remove >= i; remove--) lines.remove(remove);
            end -= block - i;
        }
        return end;
    }

    static List<String> update(List<String> source, String localDns) {
        if (!localDns.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}")) {
            throw new IllegalArgumentException("LOCAL_DNS must use an IPv4 loopback address and explicit port");
        }
        for (String line : source) {
            if (line.indexOf('\t') >= 0) throw new IllegalArgumentException("tab-indented YAML is unsupported");
        }

        int dnsStart = findTopLevelKey(source, "dns");
        if (dnsStart < 0 || !looksLikeClash(source)) {
            throw new IllegalArgumentException("not a supported Clash/Mihomo configuration");
        }
        int dnsIndent = indentation(source.get(dnsStart));
        int dnsEnd = blockEnd(source, dnsStart, dnsIndent);
        int childIndent = firstChildIndent(source, dnsStart + 1, dnsEnd, dnsIndent);
        if (childIndent < 0) childIndent = dnsIndent + 2;

        List<String> result = new ArrayList<>(source);
        dnsEnd = transformNameserverPolicy(result, dnsStart, dnsEnd, childIndent, localDns);
        dnsEnd = transformServerSections(result, dnsStart, dnsEnd, childIndent, localDns);

        boolean enhancedFound = false;
        boolean nameserverFound = false;
        boolean defaultNameserverFound = false;
        boolean proxyServerNameserverFound = false;
        // 修复 bug:Clash.Meta 的 tun 模式与 enhanced-mode=fake-ip 配套才能正确解析,
        // 原版 Clash 用 redir-host。强制写 redir-host 会让 tun 用户 DNS 解析失败。
        boolean tunMode = findTopLevelKey(source, "tun") >= 0;
        String enhancedModeValue = tunMode ? "fake-ip" : "redir-host";
        for (int i = dnsStart + 1; i < dnsEnd; i++) {
            Matcher matcher = KEY.matcher(result.get(i));
            if (!matcher.matches() || matcher.group(1).length() != childIndent) continue;
            String key = matcher.group(3);
            if ("enable".equals(key)) {
                result.set(i, spaces(childIndent) + "enable: true");
            } else if ("enhanced-mode".equals(key)) {
                result.set(i, spaces(childIndent) + "enhanced-mode: " + enhancedModeValue);
                enhancedFound = true;
            } else if ("nameserver".equals(key)) {
                nameserverFound = true;
            } else if ("default-nameserver".equals(key)) {
                defaultNameserverFound = true;
            } else if ("proxy-server-nameserver".equals(key)) {
                proxyServerNameserverFound = true;
            }
        }

        int insertion = dnsStart + 1;
        if (!enhancedFound) {
            result.add(insertion++, spaces(childIndent) + "enhanced-mode: redir-host");
            dnsEnd++;
        }
        if (!nameserverFound) {
            result.add(insertion, spaces(childIndent) + "nameserver:");
            result.add(insertion + 1, spaces(childIndent + 2) + "- " + localDns);
            insertion += 2;
        }
        if (!defaultNameserverFound) {
            result.add(insertion++, spaces(childIndent) + "default-nameserver:");
            for (String server : PROXY_BOOTSTRAP_DNS) {
                result.add(insertion++, spaces(childIndent + 2) + "- " + server);
            }
        }
        // 修复 bug:`proxy-server-nameserver` 是 Mihomo / Clash Meta 专属字段,
        // 原版 Clash 没有这个 key。原代码无差别追加,对原版 Clash 来说是无效字段,
        // 多数情况会被忽略,但部分 Clash 校验严格的内核会拒绝加载,造成兼容问题。
        // 仅在判定为 Mihomo 时才注入。
        if (!proxyServerNameserverFound && looksLikeMihomo(source)) {
            result.add(insertion++, spaces(childIndent) + "proxy-server-nameserver:");
            for (String server : PROXY_BOOTSTRAP_DNS) {
                result.add(insertion++, spaces(childIndent + 2) + "- " + server);
            }
        }
        return result;
    }

    private static int transformServerSections(List<String> lines, int start, int end,
                                                int childIndent, String localDns) {
        for (int i = end - 1; i > start; i--) {
            Matcher matcher = KEY.matcher(lines.get(i));
            if (!matcher.matches() || matcher.group(1).length() != childIndent
                    || (!FILTERED_DNS_SERVERS.contains(matcher.group(3))
                    && !BOOTSTRAP_DNS_SERVERS.contains(matcher.group(3)))) continue;
            int sectionEnd = blockEnd(lines, i, childIndent);
            List<String> servers = BOOTSTRAP_DNS_SERVERS.contains(matcher.group(3))
                    ? PROXY_BOOTSTRAP_DNS : Arrays.asList(localDns);
            replaceValue(lines, i, sectionEnd, childIndent, matcher.group(2), matcher.group(3), servers);
            end += servers.size() + 1 - (sectionEnd - i);
        }
        return end;
    }

    private static int transformNameserverPolicy(List<String> lines, int start, int end,
                                                  int childIndent, String localDns) {
        int policy = -1;
        for (int i = start + 1; i < end; i++) {
            Matcher matcher = KEY.matcher(lines.get(i));
            if (matcher.matches() && matcher.group(1).length() == childIndent
                    && "nameserver-policy".equals(matcher.group(3))) {
                policy = i;
                break;
            }
        }
        if (policy < 0) return end;
        int policyEnd = blockEnd(lines, policy, childIndent);
        int entryIndent = firstChildIndent(lines, policy + 1, policyEnd, childIndent);
        if (entryIndent < 0) return end;
        for (int i = policyEnd - 1; i > policy; i--) {
            String line = lines.get(i);
            if (isIgnorable(line) || indentation(line) != entryIndent || !line.trim().contains(":")) continue;
            int entryEnd = blockEnd(lines, i, entryIndent);
            int separator = mappingSeparator(line);
            if (separator < 0) continue;
            String key = line.substring(0, separator).trim();
            for (int remove = entryEnd - 1; remove >= i; remove--) lines.remove(remove);
            lines.add(i, spaces(entryIndent) + key + ":");
            lines.add(i + 1, spaces(entryIndent + 2) + "- " + localDns);
            int delta = 2 - (entryEnd - i);
            policyEnd += delta;
            end += delta;
        }
        return end;
    }

    private static void replaceValue(List<String> lines, int start, int end, int indent,
                                     String quote, String key, List<String> servers) {
        for (int i = end - 1; i >= start; i--) lines.remove(i);
        lines.add(start, spaces(indent) + quote + key + quote + ":");
        for (int i = 0; i < servers.size(); i++) {
            lines.add(start + 1 + i, spaces(indent + 2) + "- " + servers.get(i));
        }
    }

    private static boolean looksLikeClash(List<String> lines) {
        return findTopLevelKey(lines, "proxies") >= 0 || findTopLevelKey(lines, "proxy-providers") >= 0
                || findTopLevelKey(lines, "rules") >= 0 || findTopLevelKey(lines, "tun") >= 0;
    }

    /**
     * Mihomo / Clash.Meta 专属识别:仅当配置里出现 Mihomo 才有的 {@code proxy-providers}
     * 时才算 Mihomo。Clash Meta for Android 与 Mihomo 一致;原版 Clash 没有
     * proxy-providers 字段,会落到 false。
     */
    private static boolean looksLikeMihomo(List<String> lines) {
        return findTopLevelKey(lines, "proxy-providers") >= 0;
    }

    private static int findTopLevelKey(List<String> lines, String wanted) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = KEY.matcher(lines.get(i));
            if (matcher.matches() && matcher.group(1).isEmpty() && wanted.equals(matcher.group(3))) return i;
        }
        return -1;
    }

    private static int blockEnd(List<String> lines, int start, int indent) {
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isIgnorable(line) && indentation(line) <= indent) return i;
        }
        return lines.size();
    }

    private static int firstChildIndent(List<String> lines, int start, int end, int parentIndent) {
        int found = Integer.MAX_VALUE;
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            if (isIgnorable(line)) continue;
            int indent = indentation(line);
            if (indent > parentIndent) found = Math.min(found, indent);
        }
        return found == Integer.MAX_VALUE ? -1 : found;
    }

    private static int indentation(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }

    private static boolean isIgnorable(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    private static int mappingSeparator(String line) {
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (quote == '"' && value == '\\') {
                escaped = true;
            } else if (quote != 0 && value == quote) {
                quote = 0;
            } else if (quote == 0 && (value == '\'' || value == '"')) {
                quote = value;
            } else if (quote == 0 && value == ':') {
                return i;
            }
        }
        return -1;
    }

    private static String spaces(int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ' ');
        return new String(chars);
    }

    private static void writeInPlace(Path target, List<String> lines) throws IOException {
        // Keep the original inode.  Android proxy apps store profiles in their private
        // directory; replacing the file as root changes its owner/SELinux label and can
        // make the proxy unable to read its own configuration.
        byte[] content = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
            channel.position(0);
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.truncate(content.length);
            channel.force(true);
        }
    }
}
