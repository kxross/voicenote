#!/bin/sh
# Applied by a privileged sidecar that shares a sandbox's network namespace
# (docker run --network container:<sandbox> --cap-add NET_ADMIN). The sandbox
# itself runs with NO NET_ADMIN, so the agent (even as root) can't flush these.
# Drops sandbox -> private ranges (host, LAN, tailnet CGNAT, link-local); the
# public internet stays reachable, and loopback/embedded-DNS keep working.
set -e
iptables -A OUTPUT -o lo -j ACCEPT
iptables -A OUTPUT -m state --state ESTABLISHED,RELATED -j ACCEPT
for r in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 100.64.0.0/10 169.254.0.0/16; do
  iptables -A OUTPUT -d "$r" -j DROP
done
echo "egress-lockdown applied"
