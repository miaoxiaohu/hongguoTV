# R86S 路由器（192.168.1.1）关键配置备份

设备：R86S，OpenWrt。这个目录里的文件是**当前正在生效的内容**，重刷固件或恢复出厂设置后需要照着放回去。

## firewall.user

生效文件：`/etc/firewall.user`（本目录 `firewall.user`）

里面有一段 `lan_ipv6_open_codex` 块。这段是 2026-10-26 为红果 TV 播放加的，**不要删**。

原来的块叫 `lan_ipv6_only_192_168_1_11_codex`：它只按 MAC 白名单放行 192.168.1.11（MAC `52:54:00:20:D6:62`，本机 PC）的 LAN→WAN IPv6，其余 LAN 设备一律 `ip6tables REJECT --reject-with icmp6-port-unreachable`。

问题在于红果 302 之后的 CDN 随机子域（例如 `vuqsneiqnjiqqninnbqq.jspcdn.cn`）**只有 AAAA 记录**，没有 A 记录。电视拿不到 IPv6 就没有任何路可走，于是播放反复失败。

现在的做法：删掉那条按 MAC 的 REJECT，**LAN→WAN 的 IPv6 对所有客户端放行**；分流仍然由 passwall 决定（chn6/gfw6 名单直连，其余走代理）。

改完之后没有重启防火墙，而是直接删掉了运行态的链：

```sh
ip6tables -D FORWARD -i br-lan -o pppoe-wan -j LAN_IPV6_ONLY_192168111
ip6tables -F LAN_IPV6_ONLY_192168111
ip6tables -X LAN_IPV6_ONLY_192168111
```

改之前的备份还在路由器上：`/etc/firewall.user.bak.20261026`。

真机侧证（2026-10-27 06:20，路由器抓包 90 秒）：电视 23,802 帧 / 21.57 MB 载荷，其中 IPv6 占 16,540 帧；对 `240e:960:3600:6::100:443`（那个只有 AAAA 的 CDN 地址）有 194 帧。

## passwall 主节点

当前主节点 section id：`YbsV3eKT`。

**section id 不可硬编码**。passwall 的订阅每天 04:08 自动刷新，会重写 `/etc/config/passwall` 并重置所有节点的 section 名（同一个节点同一个端口，id 可能从 `EyWLCn0R` 变成 `N58wL9wZ`）。切换脚本要按 remarks 前缀动态解析：

```sh
# /root/switch_node.sh "Large 美国03"
```

## 抓包注意

本机 busybox-mini 的 tcpdump **会把 `ether host <MAC>` 里的 `ether` 吞掉**，退化成 `host <MAC>`（按 IP 匹配），永远 0 包 —— 会被误判成「设备没流量」。

正确做法：**不加任何 BPF 过滤全量抓**，抓完再 `tcpdump -r <pcap> -n -e` 按 MAC 文本挑。`-e` 必须加，否则 `-r` 的输出不打印 MAC。

- `-e` 输出里是 `ethertype IPv4 (0x0800)` / `ethertype IPv6 (0x86dd)`，**没有** ` IP ` / ` IP6 ` 字段，grep 要按这个来。
- 长窗口抓包**不要加 `-c N`**（繁忙 LAN 上一秒打满上限，窗口瞬间结束）。
- `/tmp` 是内存盘，抓完要删。

## 工具链

本机没有 python3（只有 busybox ash + sed/awk）。复杂命令一律写成 `.sh` 上传再执行：

```powershell
$env:ASKPASS_PASSWORD = '<password>'
python C:\Users\server\.ssh\ssh_run.py put "D:\document\project\hongguoTV\.tmp-x.sh" "/root/x.sh"
$env:ASKPASS_PASSWORD = '<password>'
python C:\Users\server\.ssh\ssh_run.py cmd 'sh /root/x.sh'
```

PowerShell 会先解析 `$(...)`、管道、双引号、`|`（`grep -E "a|b"` 会被拆成两条命令报 `ash: can.t: not found`）。多 host 循环必须写进 `.sh`。
