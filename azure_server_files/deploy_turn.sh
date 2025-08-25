#!/bin/bash
set -eux

# === Settings ===
TURN_SECRET="y05hgLPoZKPtzPsBsM1FHqlH9POX7nct1Y1FIGpHq1I="   # your secret
REALM="turn.local"
RELAY_MIN=49160
RELAY_MAX=49200

# === Install packages ===
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y coturn golang-go curl

# === Enable coturn ===
if grep -q '^#\?TURNSERVER_ENABLED' /etc/default/coturn; then
  sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
else
  echo 'TURNSERVER_ENABLED=1' >> /etc/default/coturn
fi

PUB_IP=$(curl -fsS https://api.ipify.org)
PRIV_IP=$(hostname -I | awk '{print $1}')

# === Configure turnserver.conf ===
cat >/etc/turnserver.conf <<EOF
listening-ip=${PRIV_IP}
listening-port=3478
external-ip=${PUB_IP}/${PRIV_IP}
realm=${REALM}

fingerprint
use-auth-secret
static-auth-secret="${TURN_SECRET}"

min-port=${RELAY_MIN}
max-port=${RELAY_MAX}

log-file=/var/log/turnserver/turnserver.log
simple-log
EOF

mkdir -p /var/log/turnserver
chown turnserver:turnserver /var/log/turnserver || true

systemctl enable coturn
systemctl restart coturn

# === ICE API (Go) ===
mkdir -p /iceapi
cd /iceapi

# /iceapi/main.go must be copied here beforehand
if [ ! -f go.mod ]; then
  /usr/bin/go mod init iceapi
fi
/usr/bin/go mod tidy

/usr/bin/go build -o /iceapi/iceapi /iceapi/main.go

cat >/etc/systemd/system/iceapi.service <<EOF
[Unit]
Description=ICE API
After=network.target

[Service]
Environment=TURN_SECRET=${TURN_SECRET}
Environment=TURN_HOST=${PUB_IP}
Environment=TURN_TTL=3600
WorkingDirectory=/iceapi
ExecStart=/iceapi/iceapi
Restart=always

[Install]
WantedBy=multi-user.target
EOF

# === SIGNAL API (Go) ===
mkdir -p /signalapi
cd /signalapi

# /signalapi/main.go must be copied here beforehand
if [ ! -f go.mod ]; then
  /usr/bin/go mod init signalapi
fi
/usr/bin/go mod tidy

/usr/bin/go build -o /signalapi/signalapi /signalapi/main.go

cat >/etc/systemd/system/signalapi.service <<EOF
[Unit]
Description=Signal Gateway API
After=network.target

[Service]
WorkingDirectory=/signalapi
ExecStart=/signalapi/signalapi
Restart=always

[Install]
WantedBy=multi-user.target
EOF

# === Reload systemd and start services ===
systemctl daemon-reload
systemctl enable iceapi
systemctl restart iceapi
systemctl enable signalapi
systemctl restart signalapi

echo "TURN + ICE API + Signal API deployed."
echo "Check:"
echo "  curl http://${PUB_IP}:8080/ice?u=test"
echo "  curl http://${PUB_IP}:9090/health"
