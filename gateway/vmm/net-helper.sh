#!/usr/bin/env bash
# The ONLY root-privileged surface of the VM manager. Invoked as:
#
#     sudo net-helper.sh up   <idx>
#     sudo net-helper.sh down <idx>
#
# via a narrow NOPASSWD sudoers rule (see setup.sh). It takes exactly an
# action and an integer index and derives everything else itself — no
# caller-supplied paths, interfaces, or rules — so granting it passwordless
# root does not widen the attack surface beyond "create/destroy one VM's
# network". <idx> is strictly validated.
#
# Per VM index N:
#   tap  fc-tapN, host side 172.30.N.1/30, guest 172.30.N.2
#   NAT  MASQUERADE 172.30.N.0/30 out the default-route interface
#   iso  dedicated chain VMM-N drops guest->RFC1918/CGNAT/link-local, so
#        agent code reaches the internet but never the host, tailnet, or
#        another VM. ACCEPTs only public destinations.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

ACTION="${1:-}"
IDX="${2:-}"

# ---- strict validation (this runs as root) --------------------------------
case "$ACTION" in up|down) ;; *) echo "usage: net-helper.sh {up|down} <idx>" >&2; exit 2;; esac
[[ "$IDX" =~ ^[0-9]+$ ]] || { echo "idx must be an integer" >&2; exit 2; }
if (( IDX < 0 || IDX > 253 )); then echo "idx out of range 0..253" >&2; exit 2; fi

TAP="$(tap_name "$IDX")"
HOST_IP="$(host_ip "$IDX")"
SUBNET="${NET_PREFIX}.${IDX}.0/30"
CHAIN="VMM-${IDX}"
EXT_IF="$(ip route show default 2>/dev/null | awk '{print $5; exit}')"

ipt()  { iptables "$@"; }
# add a rule only if an identical one isn't already present
ensure() { local t=(); if [[ "$1" == "-t" ]]; then t=("$1" "$2"); shift 2; fi
           ipt "${t[@]}" -C "$@" 2>/dev/null || ipt "${t[@]}" -A "$@"; }
insert() { ipt -C "$@" 2>/dev/null || ipt -I "$@"; }  # top-of-chain jumps

up() {
  [[ -n "$EXT_IF" ]] || { echo "no default route interface found" >&2; exit 1; }

  # tap owned by the daemon user so an unprivileged process can drive the VM
  if ! ip link show "$TAP" >/dev/null 2>&1; then
    ip tuntap add dev "$TAP" mode tap user "$VMM_USER"
  fi
  ip addr replace "${HOST_IP}/30" dev "$TAP"
  ip link set "$TAP" up
  sysctl -qw net.ipv4.ip_forward=1

  # isolation chain: private ranges dropped, everything else (public) allowed
  ipt -N "$CHAIN" 2>/dev/null || ipt -F "$CHAIN"
  ipt -A "$CHAIN" -d 10.0.0.0/8      -j DROP
  ipt -A "$CHAIN" -d 172.16.0.0/12   -j DROP
  ipt -A "$CHAIN" -d 192.168.0.0/16  -j DROP
  ipt -A "$CHAIN" -d 100.64.0.0/10   -j DROP
  ipt -A "$CHAIN" -d 169.254.0.0/16  -j DROP
  ipt -A "$CHAIN" -j ACCEPT

  # egress from this tap goes through the isolation chain; return traffic ok
  insert FORWARD 1 -i "$TAP" -j "$CHAIN"
  insert FORWARD 1 -o "$TAP" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
  ensure -t nat POSTROUTING -s "$SUBNET" -o "$EXT_IF" -j MASQUERADE

  echo "up: $TAP $HOST_IP/30 -> $EXT_IF (chain $CHAIN)"
}

down() {
  ipt -D FORWARD -i "$TAP" -j "$CHAIN" 2>/dev/null || true
  ipt -D FORWARD -o "$TAP" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || true
  ipt -F "$CHAIN" 2>/dev/null || true
  ipt -X "$CHAIN" 2>/dev/null || true
  if [[ -n "$EXT_IF" ]]; then
    ipt -t nat -D POSTROUTING -s "$SUBNET" -o "$EXT_IF" -j MASQUERADE 2>/dev/null || true
  fi
  # also sweep any stale NAT rule for this subnet on other interfaces
  ip link del "$TAP" 2>/dev/null || true
  echo "down: $TAP torn down"
}

case "$ACTION" in
  up)   up ;;
  down) down ;;
esac
