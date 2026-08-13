#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
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

#define BRIDGE_PORT 3001
#define MAX_REQUEST 8192
#define MAX_OUTPUT 65536

static const char *protocol = "AGHCTL/1";
static const char *token = "79d87c953bd74ab488758620a32dc5f48a8e46ad85c37a76c88f138b556ef2cb";

static void start_network_monitor(void) {
    const char *ctl = getenv("AGH_CTL");
    pid_t pid;
    if (ctl == NULL || ctl[0] == '\0') ctl = "/data/adb/modules/adguardhome/system/bin/aghctl";
    pid = fork();
    if (pid != 0) return;
    prctl(PR_SET_PDEATHSIG, SIGTERM);
    if (getppid() == 1) _exit(0);
    prctl(PR_SET_NAME, "aghnetwatch", 0, 0, 0);
    execl("/system/bin/sh", "sh", ctl, "network-watch", (char *) NULL);
    _exit(127);
}

static int send_all(int fd, const char *buffer, size_t length) {
    while (length > 0) {
        ssize_t sent = send(fd, buffer, length, MSG_NOSIGNAL);
        if (sent < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        buffer += sent;
        length -= (size_t) sent;
    }
    return 0;
}

static void send_error(int fd, int code, const char *message) {
    char response[384];
    int length = snprintf(response, sizeof(response), "%s %d\n%s\n", protocol, code, message);
    if (length > 0) send_all(fd, response, (size_t) length);
}

static int valid_action(const char *action) {
    static const char *actions[] = {
        "start", "stop", "restart", "status", "rules", "recover", "filter-seed", "factory-rules",
        "network-failsafe", "network-alert", "network-alert-ack",
        "proxy-enable", "proxy-disable", "proxy-status", "proxy-sync",
        "proxy-paths", "proxy-path-add", "proxy-path-delete",
        "proxy-subscription", "proxy-subscription-set", "proxy-subscription-apply",
        "proxy-subscription-delete", "logs-clean", "logs-export"
    };
    size_t i;
    for (i = 0; i < sizeof(actions) / sizeof(actions[0]); ++i) {
        if (strcmp(action, actions[i]) == 0) return 1;
    }
    return 0;
}

static int read_request(int fd, char *buffer, size_t size) {
    size_t used = 0;
    while (used + 1 < size) {
        char value;
        ssize_t count = recv(fd, &value, 1, 0);
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (value == '\n') break;
        if (value == '\r') continue;
        buffer[used++] = value;
    }
    buffer[used] = '\0';
    return used > 0 ? 0 : -1;
}

static void run_action(int fd, const char *action, const char *argument) {
    const char *ctl = getenv("AGH_CTL");
    int output_pipe[2];
    pid_t pid;
    char output[MAX_OUTPUT + 1];
    size_t used = 0;
    int status = 0;
    int exit_code;

    if (ctl == NULL || ctl[0] == '\0') {
        ctl = "/data/adb/modules/adguardhome/system/bin/aghctl";
    }
    if (pipe(output_pipe) != 0) {
        send_error(fd, 70, "control bridge could not create a process pipe");
        return;
    }
    pid = fork();
    if (pid < 0) {
        close(output_pipe[0]);
        close(output_pipe[1]);
        send_error(fd, 70, "control bridge could not create a worker process");
        return;
    }
    if (pid == 0) {
        dup2(output_pipe[1], STDOUT_FILENO);
        dup2(output_pipe[1], STDERR_FILENO);
        close(output_pipe[0]);
        close(output_pipe[1]);
        if (argument != NULL && argument[0] != '\0') {
            execl("/system/bin/sh", "sh", ctl, action, argument, (char *) NULL);
        } else {
            execl("/system/bin/sh", "sh", ctl, action, (char *) NULL);
        }
        _exit(127);
    }

    close(output_pipe[1]);
    for (;;) {
        char chunk[512];
        ssize_t count = read(output_pipe[0], chunk, sizeof(chunk));
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (used < MAX_OUTPUT) {
            size_t available = MAX_OUTPUT - used;
            size_t copy = (size_t) count < available ? (size_t) count : available;
            memcpy(output + used, chunk, copy);
            used += copy;
        }
    }
    close(output_pipe[0]);
    pid_t waited;
    do {
        waited = waitpid(pid, &status, 0);
    } while (waited < 0 && errno == EINTR);
    if (waited != pid) exit_code = 70;
    else if (WIFEXITED(status)) exit_code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) exit_code = 128 + WTERMSIG(status);
    else exit_code = 70;

    output[used] = '\0';
    char header[48];
    int header_length = snprintf(header, sizeof(header), "%s %d\n", protocol, exit_code);
    if (header_length > 0 && send_all(fd, header, (size_t) header_length) == 0 && used > 0) {
        send_all(fd, output, used);
    }
}

static void handle_client(int fd) {
    struct timeval receive_timeout = {.tv_sec = 5, .tv_usec = 0};
    struct timeval send_timeout = {.tv_sec = 10, .tv_usec = 0};
    char request[MAX_REQUEST];
    char request_protocol[16];
    char request_token[65];
    char action[32];
    char argument[6145] = {0};
    char extra;
    int fields;

    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, sizeof(receive_timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof(send_timeout));
    alarm(60);
    if (read_request(fd, request, sizeof(request)) != 0) {
        send_error(fd, 64, "invalid control request");
        return;
    }
    fields = sscanf(request, "%15s %64s %31s %6144s %c", request_protocol, request_token, action, argument, &extra);
    if ((fields != 3 && fields != 4) ||
        strcmp(request_protocol, protocol) != 0 || strcmp(request_token, token) != 0) {
        send_error(fd, 77, "control bridge authentication failed");
        return;
    }
    if (!valid_action(action)) {
        send_error(fd, 64, "unsupported control action");
        return;
    }
    if ((strcmp(action, "proxy-path-add") == 0 || strcmp(action, "proxy-path-delete") == 0 ||
         strcmp(action, "proxy-subscription-set") == 0 ||
         strcmp(action, "proxy-subscription-apply") == 0) != (fields == 4)) {
        send_error(fd, 64, "invalid control action argument");
        return;
    }
    run_action(fd, action, fields == 4 ? argument : NULL);
}

int main(void) {
    int server;
    int reuse = 1;
    struct sockaddr_in address;

    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_IGN);
    prctl(PR_SET_NAME, "aghbridge", 0, 0, 0);
    start_network_monitor();

    server = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) return 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons(BRIDGE_PORT);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(server, (struct sockaddr *) &address, sizeof(address)) != 0) {
        close(server);
        return 2;
    }
    if (listen(server, 8) != 0) {
        close(server);
        return 3;
    }

    for (;;) {
        int client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) continue;
            sleep(1);
            continue;
        }
        pid_t worker = fork();
        if (worker == 0) {
            signal(SIGCHLD, SIG_DFL);
            close(server);
            handle_client(client);
            close(client);
            _exit(0);
        }
        close(client);
    }
}
