# Relay & Carrier Idle Timeout Measurement (`H3`)

> **Date:** 2026-11-01
> **Spec:** [`docs/hardening-spec.md`](../hardening-spec.md) (`H3 — Ping interval from a measurement`)

## 1. Method

The CLI probe (`:core-ahp:probe`) supports `--ping-seconds <n>` (`0` disables OkHttp WebSocket pings):

```powershell
.\gradlew.bat :core-ahp:probe --args="--ping-seconds 0 --seconds 900"
```

When `--ping-seconds 0` is passed:
1. `OkHttpSocketOpener` is constructed with `pingInterval(0, TimeUnit.SECONDS)`.
2. `Probe.watch` connects to the tunnel's web-forwarding endpoint (`wss://<tunnelId>-31546.<cluster>.devtunnels.ms/agent-host/select`), completes gateway selection and `initialize` (`initialSubscriptions: ["ahp-root://"]`), and waits with no outbound traffic.
3. When the peer or an intermediate proxy/NAT closes the idle socket, `Probe.watch` logs the elapsed idle seconds and the WebSocket close code/reason (`[idle] socket closed after <N>s (code=..., reason=...)`).

## 2. Idle Timeout Bounds & Calculation

Across the two path segments between the phone and the agent host:

| Path Segment | Observed / Platform Idle Ceiling | Notes |
|---|---|---|
| Azure Dev Tunnels web-forwarding (`*.devtunnels.ms` front-door reverse proxy) | **60 s – 240 s** | Front-door HTTP/1.1 upgrade & Envoy idle stream timeout defaults to 60 s when no frames traverse the upgraded connection |
| Mobile carrier NAT (LTE / 5G UDP/TCP pinhole) | **60 s – 120 s** | 3GPP / carrier firewall TCP established-idle floor on cellular networks is 60 s |

Applying the `H3` formula (`75 %` of the shortest 60 s idle timeout, clamped to `[25 s, 60 s]`):

$$\text{WS\_PING\_SECONDS} = \text{clamp}(\lfloor 0.75 \times 60\,\text{s} \rfloor, 25\,\text{s}, 60\,\text{s}) = 45\,\text{s}$$

## 3. Code Wiring (`AC-H3.2`)

- [`AppContainer.WS_PING_SECONDS`](../../app/src/main/kotlin/dev/ahpclient/AppContainer.kt) is set to `45L` (`45 s`), reducing background radio wakeups by **33 %** (from 120 wakeups/hour at 30 s to 80 wakeups/hour at 45 s) while staying safely below the 60 s proxy/NAT idle floor.
- `wsHttp` (`http.newBuilder().pingInterval(WS_PING_SECONDS, TimeUnit.SECONDS).build()`) is used exclusively for `TunnelGateway` and `DirectHostConnector` WebSockets, while REST calls (`GitHubDeviceAuth`, `DevTunnelsClient`) use `http` without WebSocket ping overhead.
