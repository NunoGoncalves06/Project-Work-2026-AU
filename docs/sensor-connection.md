# Connecting a sensor to the parking server

For the firmware team. This page has everything a board needs except the password.

## Connection settings

| Setting | Value |
|---|---|
| Broker | `mqtts://au-parking.duckdns.org:8883` |
| Host | `au-parking.duckdns.org` (use the name, never the IP: the certificate check fails with the IP) |
| TLS | on, version 1.2 or newer |
| User | `sensor` |
| Password | given to you privately. Keep it in a git-ignored secrets file, never in the repo |
| Client id | unique per board, e.g. `spot-sensor-9`. **Never `parking-backend`**: that is the server's own id, and the broker would kick the server off |
| QoS | 1 |
| Retain flag | **do not set it**. The broker disconnects a client that publishes with retain |
| Max payload | under 4 KB (ours are about 40 bytes) |

## Certificate

The server uses a Let's Encrypt certificate (ECDSA). It chains to ISRG Root X1 or X2.

- ESP-IDF: use the built-in certificate bundle (`esp_crt_bundle`), so no certificate file goes on the board. Confirm that your CA bundle includes the ISRG roots.
- Other libraries: use the system or library CA store, or add the ISRG roots.

## Topics and payloads

Send JSON, exactly like this.

Parking spot changed:

- Topic: `carcounter/spots` (sub-topics such as `carcounter/spots/9` are also accepted)
- Payload: `{"device_id": 9, "occupied": true}`

Car through the entrance:

- Topic: `carcounter/entrance`
- Payload: `{"device_id": 1, "dir": 1}` (`1` = car came in, `-1` = car went out)

Rules:

- Types must be exact: `device_id` and `dir` are numbers (not strings), `occupied` is `true` or `false` (not `1` or `0`). Otherwise the server skips the message.
- `device_id` must already exist in the database, or the message is dropped silently. Demo data: spot sensors 1 to 16, entrance sensor 1.
- Publish when the spot changes, not on a timer.
- The time of a reading is the time the server receives it. Do not send a timestamp.
- A sensor may only write to `carcounter/spots/#` and `carcounter/entrance/#`.

## Reconnect

Reconnect automatically after any disconnect. The broker restarts for a few seconds when its certificate renews (about every 60 days).

Wrong topic: with MQTT 3.1.1 the message is silently dropped and the publish looks successful. MQTT 5 clients get "Not authorized" (reason code 135). Prefer MQTT 5 if your library supports it.

## Testing without a board

1. Open MQTT Explorer. Host `au-parking.duckdns.org`, port 8883, TLS on, user `sensor`, the password.
2. Publish `{"device_id": 9, "occupied": true}` to `carcounter/spots`.
3. In a terminal, check the server saw it:

```bash
curl -N https://au-parking.duckdns.org/parking-lots/stream
```

It prints `event:changed` when a reading changes a spot.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| TLS or certificate error | connected by IP instead of the DNS name, or the CA bundle on the board lacks the ISRG roots |
| Not authorised / connection refused by the broker | wrong user or password |
| Connects, but nothing shows in the app | wrong topic, wrong JSON type (string instead of number, `1` instead of `true`), unknown `device_id`, retain flag set, or payload too big |
| Boards keep kicking each other off | two boards use the same client id |
| No connection at all | port 8883 closed, or the server instance is stopped. Ask the server team |
