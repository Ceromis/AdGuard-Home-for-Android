/*
 * ==============================================================================
 * AdGuard Home for Android - 原生控制桥 (aghbridge)
 * ==============================================================================
 *
 * 功能：作为本地控制桥，监听 127.0.0.1:3001 端口，接受来自管理器 App 的控制命令
 *
 * 设计目标：
 *   1. 安全性：仅监听回环地址，不暴露到网络
 *   2. 认证：使用 token 验证请求合法性
 *   3. 权限控制：白名单机制限制可执行的操作
 *   4. 并发处理：fork 子进程处理每个请求
 *   5. 网络监控：启动时自动启动网络监控进程
 *
 * 通信协议：AGHCTL/1
 *   请求格式：<protocol> <token> <action> [argument]
 *   响应格式：<protocol> <exit_code>\n<output>
 *
 * 编译方式：
 *   aarch64-linux-android26-clang -Oz -fPIE -pie -fstack-protector-strong \
 *     -D_FORTIFY_SOURCE=2 -Wall -Wextra -Werror -s -o aghbridge aghbridge.c
 *
 * ==============================================================================
 */

#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/* ==============================================================================
 * 常量定义
 * ============================================================================== */

#define BRIDGE_PORT 3001        /* 监听端口（仅回环地址） */
#define MAX_REQUEST 8192        /* 最大请求长度 */
#define MAX_OUTPUT 65536        /* 最大输出长度（64KB） */

/* 通信协议标识 */
static const char *protocol = "AGHCTL/1";

/* 认证 token 由构建脚本通过 -DBRIDGE_TOKEN="..." 注入；
 * 不再使用硬编码常量，防止源码泄漏导致任意 App 在设备上获得 root 级动作。 */
#ifndef BRIDGE_TOKEN
#error "BRIDGE_TOKEN must be defined at compile time (see build.ps1)"
#endif
static const char *token = BRIDGE_TOKEN;

/* ==============================================================================
 * 网络监控进程
 * ============================================================================== */

/*
 * start_network_monitor - 启动网络监控子进程
 *
 * 功能：fork 一个子进程执行 aghctl network-watch
 *       该子进程会持续监控网络状态，当检测到网络异常时触发故障保护
 *
 * 特殊处理：
 *   - 使用 PR_SET_PDEATHSIG 确保父进程退出时子进程也退出
 *   - 检查父进程是否为 init（孤儿进程），如果是则自行退出
 *   - 设置进程名为 "aghnetwatch" 便于识别
 */
static void start_network_monitor(void) {
    const char *ctl = getenv("AGH_CTL");
    pid_t pid;
    if (ctl == NULL || ctl[0] == '\0') ctl = "/data/adb/modules/adguardhome/system/bin/aghctl";
    pid = fork();
    if (pid != 0) return;                                       /* 父进程直接返回 */
    prctl(PR_SET_PDEATHSIG, SIGTERM);                          /* 父进程退出时收到 SIGTERM */
    if (getppid() == 1) _exit(0);                              /* 已是孤儿进程，退出 */
    prctl(PR_SET_NAME, "aghnetwatch", 0, 0, 0);               /* 设置进程名 */
    execl("/system/bin/sh", "sh", ctl, "network-watch", (char *) NULL);
    _exit(127);                                                /* exec 失败 */
}

/* ==============================================================================
 * 网络通信辅助函数
 * ============================================================================== */

/*
 * send_all - 可靠地发送全部数据
 *
 * 参数：
 *   fd     - 目标文件描述符
 *   buffer - 发送缓冲区
 *   length - 发送长度
 *
 * 返回：0 = 成功，-1 = 失败
 *
 * 说明：处理部分发送和 EINTR 中断的情况
 */
static int send_all(int fd, const char *buffer, size_t length) {
    while (length > 0) {
        ssize_t sent = send(fd, buffer, length, MSG_NOSIGNAL);
        if (sent < 0) {
            if (errno == EINTR) continue;                      /* 被信号中断，重试 */
            return -1;
        }
        buffer += sent;
        length -= (size_t) sent;
    }
    return 0;
}

/*
 * send_error - 发送错误响应
 *
 * 参数：
 *   fd      - 客户端文件描述符
 *   code    - 错误码
 *   message - 错误信息
 */
static void send_error(int fd, int code, const char *message) {
    char response[384];
    int length = snprintf(response, sizeof(response), "%s %d\n%s\n", protocol, code, message);
    if (length > 0) send_all(fd, response, (size_t) length);
}

/* ==============================================================================
 * 操作白名单验证
 * ============================================================================== */

/*
 * valid_action - 验证操作是否在白名单中
 *
 * 参数：action - 操作名称
 * 返回：1 = 有效，0 = 无效
 *
 * 支持的操作：
 *   - 服务控制：start, stop, restart, status, rules, recover
 *   - 过滤器：filter-seed, factory-rules
 *   - 网络故障：network-failsafe, network-alert, network-alert-ack
 *   - 代理兼容：proxy-enable, proxy-disable, proxy-status, proxy-sync,
 *                proxy-paths, proxy-path-add, proxy-path-delete,
 *                proxy-subscription, proxy-subscription-set,
 *                proxy-subscription-apply, proxy-subscription-delete
 *   - 日志管理：logs-clean, logs-export
 *   - 管理端口：port-set, port-get
 */
static int valid_action(const char *action) {
    static const char *actions[] = {
        "start", "stop", "restart", "status", "rules", "recover", "filter-seed", "factory-rules",
        "network-failsafe", "network-alert", "network-alert-ack",
        "proxy-enable", "proxy-disable", "proxy-status", "proxy-sync",
        "proxy-paths", "proxy-path-add", "proxy-path-delete",
        "proxy-subscription", "proxy-subscription-set", "proxy-subscription-apply",
        "proxy-subscription-delete", "logs-clean", "logs-export", "port-set", "port-get"
    };
    size_t i;
    for (i = 0; i < sizeof(actions) / sizeof(actions[0]); ++i) {
        if (strcmp(action, actions[i]) == 0) return 1;
    }
    return 0;
}

/*
 * argument_contains_unsafe_char - 检查参数里是否含有 shell 元字符或控制字符。
 * 用于 proxy-path-* 与 proxy-subscription-* 的参数，避免下游 aghctl / proxy-config-editor
 * 收到未净化输入时被解释为 shell 命令或路径穿越。
 */
static int argument_contains_unsafe_char(const char *argument) {
    unsigned char ch;
    for (; *argument != '\0'; ++argument) {
        ch = (unsigned char) *argument;
        /* 禁止：空白、控制字符、shell 元字符 */
        if (ch <= 0x20 || ch == 0x7F) return 1;
        if (ch == '\'' || ch == '"' || ch == '\\' || ch == '`' ||
            ch == '$'  || ch == ';'  || ch == '&'  || ch == '|' ||
            ch == '<'  || ch == '>'  || ch == '('  || ch == ')' ||
            ch == '{'  || ch == '}'  || ch == '['  || ch == ']' ||
            ch == '!'  || ch == '#'  || ch == '~'  || ch == '*' ||
            ch == '?'  || ch == '\n' || ch == '\r') return 1;
    }
    return 0;
}

/*
 * argument_is_safe_path - 验证文件路径参数。仅允许 /data/... 下的绝对路径，
 * 防止普通应用通过 path-add 把模块目录外的文件加入代理配置。
 */
static int argument_is_safe_path(const char *argument) {
    if (argument[0] != '/') return 0;
    /* 禁止父目录引用 */
    if (strstr(argument, "/../") != NULL || strstr(argument, "../") == argument + 1) return 0;
    /* 仅允许 /data/ 下的常见前缀 */
    if (strncmp(argument, "/data/", 6) != 0) return 0;
    return 1;
}

/*
 * argument_is_safe_url - 验证原始订阅 URL。
 * 允许 http/https 以及 URL 常见字符(? & = # ! ~ * + , : @ % / [ ]),
 * 仍拒绝空白、控制字符和可能被下游 shell 误解释的元字符。
 */
static int argument_is_safe_url(const char *argument) {
    unsigned char ch;
    if (strncmp(argument, "https://", 8) != 0 && strncmp(argument, "http://", 7) != 0) return 0;
    for (; *argument != '\0'; ++argument) {
        ch = (unsigned char) *argument;
        if (ch <= 0x20 || ch == 0x7F) return 0;
        if (ch == '\\' || ch == '\'' || ch == '"' || ch == '`' ||
            ch == '$' || ch == ';' || ch == '|' || ch == '<' || ch == '>' ||
            ch == '{' || ch == '}' || ch == '^') return 0;
    }
    return 1;
}

/*
 * argument_is_safe_subscription - 兼容管理器 App 的订阅参数格式。
 * App 会把多行订阅 URL 做 Base64(NO_WRAP) 编码后传入,旧版调用方可能直接传
 * 原始 http(s) URL。两种都必须接受,否则添加订阅会 100% 报
 * "control bridge rejected unsafe argument"。
 */
static int argument_is_safe_subscription(const char *argument) {
    unsigned char ch;
    size_t length = 0;
    if (strncmp(argument, "http://", 7) == 0 || strncmp(argument, "https://", 8) == 0) {
        return argument_is_safe_url(argument);
    }
    while ((ch = (unsigned char) *argument++) != '\0') {
        length++;
        if (length > 8000) return 0;
        if (ch >= 'A' && ch <= 'Z') continue;
        if (ch >= 'a' && ch <= 'z') continue;
        if (ch >= '0' && ch <= '9') continue;
        if (ch == '+' || ch == '/' || ch == '=') continue;
        return 0;
    }
    return length >= 8;
}

/*
 * argument_is_safe_port - 验证管理端口参数。仅接受纯数字且位于 1-65535。
 */
static int argument_is_safe_port(const char *argument) {
    unsigned char ch;
    long value = 0;
    if (argument[0] == '\0') return 0;
    for (; *argument != '\0'; ++argument) {
        ch = (unsigned char) *argument;
        if (ch < '0' || ch > '9') return 0;
        value = value * 10 + (ch - '0');
        if (value > 65535) return 0;
    }
    return value >= 1;
}

/*
 * valid_argument - 验证需要参数的操作其参数是否合法。
 * 返回 1 表示合法，0 表示应当拒绝。
 */
static int valid_argument(const char *action, const char *argument) {
    if (argument == NULL || argument[0] == '\0') return 0;
    if (strcmp(action, "proxy-subscription-set") == 0 ||
        strcmp(action, "proxy-subscription-apply") == 0) {
        return argument_is_safe_subscription(argument);
    }
    if (argument_contains_unsafe_char(argument)) return 0;
    if (strcmp(action, "proxy-path-add") == 0 || strcmp(action, "proxy-path-delete") == 0) {
        return argument_is_safe_path(argument);
    }
    if (strcmp(action, "port-set") == 0) {
        return argument_is_safe_port(argument);
    }
    return 1;
}

/* ==============================================================================
 * 请求读取
 * ============================================================================== */

/*
 * read_request - 从客户端读取一行请求
 *
 * 参数：
 *   fd     - 客户端文件描述符
 *   buffer - 接收缓冲区
 *   size   - 缓冲区大小
 *
 * 返回：0 = 成功，-1 = 失败
 *
 * 说明：读取到换行符或缓冲区满为止，忽略 \r 字符
 */
static int read_request(int fd, char *buffer, size_t size) {
    size_t used = 0;
    while (used + 1 < size) {
        char value;
        ssize_t count = recv(fd, &value, 1, 0);
        if (count == 0) break;                                 /* 连接关闭 */
        if (count < 0) {
            if (errno == EINTR) continue;                      /* 被信号中断，重试 */
            return -1;
        }
        if (value == '\n') break;                              /* 行结束 */
        if (value == '\r') continue;                           /* 忽略回车 */
        buffer[used++] = value;
    }
    buffer[used] = '\0';
    return used > 0 ? 0 : -1;
}

/* ==============================================================================
 * 命令执行
 * ============================================================================== */

/*
 * run_action - 执行控制命令并返回结果
 *
 * 参数：
 *   fd       - 客户端文件描述符
 *   action   - 要执行的操作
 *   argument - 操作参数（可选）
 *
 * 流程：
 *   1. 创建管道用于捕获子进程输出
 *   2. fork 子进程执行 aghctl 命令
 *   3. 从管道读取子进程输出
 *   4. 等待子进程结束并获取退出码
 *   5. 将结果发送给客户端
 */
static void run_action(int fd, const char *action, const char *argument) {
    const char *ctl = getenv("AGH_CTL");
    int output_pipe[2];
    pid_t pid;
    char output[MAX_OUTPUT + 1];
    size_t used = 0;
    int status = 0;
    int exit_code;

    /* 获取 aghctl 路径 */
    if (ctl == NULL || ctl[0] == '\0') {
        ctl = "/data/adb/modules/adguardhome/system/bin/aghctl";
    }

    /* 创建管道 */
    if (pipe(output_pipe) != 0) {
        send_error(fd, 70, "control bridge could not create a process pipe");
        return;
    }

    /* fork 子进程 */
    pid = fork();
    if (pid < 0) {
        close(output_pipe[0]);
        close(output_pipe[1]);
        send_error(fd, 70, "control bridge could not create a worker process");
        return;
    }

    if (pid == 0) {
        /* === 子进程 === */
        dup2(output_pipe[1], STDOUT_FILENO);                   /* 重定向 stdout */
        /* stderr 不进入协议通道,避免脚本告警污染 App 解析到的输出 */
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        close(output_pipe[0]);                                 /* 关闭读端 */
        close(output_pipe[1]);                                 /* 关闭写端（已 dup） */

        /* 执行 aghctl 命令 */
        if (argument != NULL && argument[0] != '\0') {
            execl("/system/bin/sh", "sh", ctl, action, argument, (char *) NULL);
        } else {
            execl("/system/bin/sh", "sh", ctl, action, (char *) NULL);
        }
        _exit(127);                                            /* exec 失败 */
    }

    /* === 父进程 === */
    close(output_pipe[1]);                                     /* 关闭写端 */

    /* 从管道读取子进程输出 */
    for (;;) {
        char chunk[512];
        ssize_t count = read(output_pipe[0], chunk, sizeof(chunk));
        if (count == 0) break;                                 /* 管道关闭 */
        if (count < 0) {
            if (errno == EINTR) continue;                      /* 被信号中断，重试 */
            break;
        }
        /* 将数据追加到输出缓冲区（限制大小） */
        if (used < MAX_OUTPUT) {
            size_t available = MAX_OUTPUT - used;
            size_t copy = (size_t) count < available ? (size_t) count : available;
            memcpy(output + used, chunk, copy);
            used += copy;
        }
    }
    close(output_pipe[0]);

    /* 等待子进程结束 */
    pid_t waited;
    do {
        waited = waitpid(pid, &status, 0);
    } while (waited < 0 && errno == EINTR);

    /* 获取退出码 */
    if (waited != pid) exit_code = 70;                         /* waitpid 失败 */
    else if (WIFEXITED(status)) exit_code = WEXITSTATUS(status);  /* 正常退出 */
    else if (WIFSIGNALED(status)) exit_code = 128 + WTERMSIG(status);  /* 被信号终止 */
    else exit_code = 70;                                       /* 其他情况 */

    /* 发送响应给客户端 */
    output[used] = '\0';
    char header[48];
    int header_length = snprintf(header, sizeof(header), "%s %d\n", protocol, exit_code);
    if (header_length > 0 && send_all(fd, header, (size_t) header_length) == 0 && used > 0) {
        send_all(fd, output, used);
    }
}

/* ==============================================================================
 * 客户端连接处理
 * ============================================================================== */

/*
 * handle_client - 处理单个客户端连接
 *
 * 参数：fd - 客户端文件描述符
 *
 * 流程：
 *   1. 设置接收和发送超时
 *   2. 设置总超时（60秒）
 *   3. 读取请求
 *   4. 解析协议、token、操作和参数
 *   5. 验证认证信息
 *   6. 验证操作是否在白名单中
 *   7. 执行操作并返回结果
 *
 * 请求格式：
 *   AGHCTL/1 <token> <action> [argument]\n
 *
 * 示例：
 *   AGHCTL/1 79d87c95... start\n
 *   AGHCTL/1 79d87c95... proxy-path-add /path/to/config\n
 */
static void handle_client(int fd) {
    struct timeval receive_timeout = {.tv_sec = 5, .tv_usec = 0};    /* 接收超时 5 秒 */
    struct timeval send_timeout = {.tv_sec = 10, .tv_usec = 0};     /* 发送超时 10 秒 */
    char request[MAX_REQUEST];
    char request_protocol[16];
    char request_token[65];
    char action[32];
    char argument[6145] = {0};
    int fields;

    /* 设置 socket 超时 */
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, sizeof(receive_timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof(send_timeout));
    alarm(180);                                                /* 总超时 180 秒(重启等长动作) */

    /* 读取请求 */
    if (read_request(fd, request, sizeof(request)) != 0) {
        send_error(fd, 64, "invalid control request");
        return;
    }

    /* 解析请求字段：协议、令牌、操作、可选参数。严格 3 或 4 个字段，
     * 不再用 %c 多扫一格，避免任何额外字段被静默吞掉。 */
    fields = sscanf(request, "%15s %64s %31s %6144s", request_protocol, request_token, action, argument);

    /* 验证字段数量和认证信息 */
    if ((fields != 3 && fields != 4) ||
        strcmp(request_protocol, protocol) != 0 || strcmp(request_token, token) != 0) {
        send_error(fd, 77, "control bridge authentication failed");
        return;
    }

    /* 验证操作是否在白名单中 */
    if (!valid_action(action)) {
        send_error(fd, 64, "unsupported control action");
        return;
    }

    /* 验证需要参数的操作是否提供了参数 */
    if ((strcmp(action, "proxy-path-add") == 0 || strcmp(action, "proxy-path-delete") == 0 ||
         strcmp(action, "proxy-subscription-set") == 0 ||
         strcmp(action, "proxy-subscription-apply") == 0 ||
         strcmp(action, "port-set") == 0) != (fields == 4)) {
        send_error(fd, 64, "invalid control action argument");
        return;
    }

    /* 内容级校验：阻止 shell 元字符与跨目录路径 */
    if (fields == 4 && !valid_argument(action, argument)) {
        send_error(fd, 64, "control bridge rejected unsafe argument");
        return;
    }

    /* 执行操作 */
    run_action(fd, action, fields == 4 ? argument : NULL);
}

/* ==============================================================================
 * 主函数
 * ============================================================================== */

/*
 * main - 控制桥主函数
 *
 * 功能：
 *   1. 忽略 SIGPIPE（避免写入已关闭连接时崩溃）
 *   2. 忽略 SIGCHLD（自动回收子进程，避免僵尸进程）
 *   3. 设置进程名为 "aghbridge"
 *   4. 启动网络监控子进程
 *   5. 创建 TCP 服务器监听 127.0.0.1:3001
 *   6. 循环接受连接，fork 子进程处理每个请求
 *
 * 安全设计：
 *   - 仅监听回环地址（127.0.0.1），不暴露到网络
 *   - 使用 token 认证
 *   - 白名单限制可执行的操作
 *   - 子进程隔离，单个请求崩溃不影响主进程
 */
int main(void) {
    int server;
    int reuse = 1;
    struct sockaddr_in address;

    /* 信号处理 */
    signal(SIGPIPE, SIG_IGN);                                  /* 忽略 SIGPIPE */
    signal(SIGCHLD, SIG_IGN);                                  /* 自动回收子进程 */

    /* 设置进程名 */
    prctl(PR_SET_NAME, "aghbridge", 0, 0, 0);

    /* 启动网络监控子进程 */
    start_network_monitor();

    /* 创建 TCP socket */
    server = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) return 1;

    /* 设置 SO_REUSEADDR 允许快速重启 */
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    /* 绑定到回环地址 */
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons(BRIDGE_PORT);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(server, (struct sockaddr *) &address, sizeof(address)) != 0) {
        close(server);
        return 2;
    }

    /* 开始监听 */
    if (listen(server, 8) != 0) {
        close(server);
        return 3;
    }

    /* 主循环：接受连接并处理 */
    for (;;) {
        int client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) continue;                      /* 被信号中断，重试 */
            sleep(1);
            continue;
        }

        /* fork 子进程处理客户端请求 */
        pid_t worker = fork();
        if (worker == 0) {
            signal(SIGCHLD, SIG_DFL);                          /* 子进程恢复默认处理 */
            close(server);                                     /* 关闭监听 socket */
            handle_client(client);                             /* 处理请求 */
            close(client);                                     /* 关闭客户端 socket */
            _exit(0);                                          /* 子进程退出 */
        }
        close(client);                                         /* 父进程关闭客户端 socket */
    }
}
