# ServerDoctor

**Server diagnostics and performance monitoring for Minecraft Paper 1.21.11.**

ServerDoctor provides on-server performance measurements, lag incident tracking, chunk density inspection, a private live dashboard, and standalone diagnostic reports. It does **not** modify worlds or automatically remove entities.

## Features

- **TPS, MSPT, CPU and memory:** performance samples and a server health overview.
- **Lag spike detector:** recent slow-tick events with configurable thresholds and cooldowns.
- **Chunk inspector:** safely scans already loaded chunks in batches, counting entities, block entities and hoppers. Density is **not** a measurement of per-chunk CPU time.
- **JVM diagnostics:** heap/non-heap memory and garbage collection statistics.
- **Optional alerts:** in-game, console, and administrator-configured Discord webhook messages.
- **Private web dashboard:** responsive live charts, history, chunk overview, and token authentication. Disabled by default and bound to the loopback interface.
- **Scheduled scans:** optional recurring diagnostics with history retention.
- **Offline reports:** HTML, JSON, and CSV outputs stored locally.
- **spark integration:** manually start or stop Paper's built-in spark profiler. Detailed profiling results remain in spark.

## Requirements

| Requirement | Value |
| --- | --- |
| Minecraft | 1.21.11 |
| Server implementation | Paper |
| Java | 21 or newer |
| Client installation | Not required |
| Mandatory extra plugins | None |

Other server implementations and Minecraft versions have not been verified.

## Installation

1. Build with `BUILD.cmd` on Windows or `mvn -B -ntp clean verify` using JDK 21+ and Maven.
2. Copy `target/ServerDoctor-v1.1.2.jar` into the Paper server's `plugins/` directory.
3. Restart the server and run `/doctor scan`.
4. Find generated reports in `plugins/ServerDoctor/reports/`.

### Web dashboard

The web dashboard is disabled by default. To enable it, edit `plugins/ServerDoctor/config.yml`:

```yaml
web:
  enabled: true
  bind: '127.0.0.1'
  port: 8765
  token: ''
```

Run `/doctor reload` or restart the server. A blank token is replaced with a generated secret stored in the configuration file. Open `http://127.0.0.1:8765` **on the server host**, and keep the token private. For remote access, use a secured tunnel or HTTPS reverse proxy. **Do not expose the HTTP port directly to the public internet.**

## Commands

| Command | Action |
| --- | --- |
| `/doctor scan` | Run a scan and export reports |
| `/doctor status` | View current metrics and scan progress |
| `/doctor latest` | Show the latest scan |
| `/doctor history [page]` | Browse scan history |
| `/doctor report [id\|latest]` | Find exported report files |
| `/doctor chunks` | Inspect the density of loaded chunks |
| `/doctor spark start` / `stop` | Control Paper's spark profiler |
| `/doctor web` | Display local dashboard status |
| `/doctor schedule` | View scheduled scan settings |
| `/doctor reload` | Reload configuration and services |

Administrator permission: `serverdoctor.admin` (OP by default). In-game alert recipients require `serverdoctor.alerts` (OP by default).

## Privacy and limitations

Reports are saved locally, and may include technical information, world names, and chunk coordinates. Review exports before sharing. Optional Discord webhook notifications can transmit short performance alerts to a service chosen by the administrator. The plugin does not require a cloud account.

TPS represents a rolling average; a high entity count may suggest a problem without proving CPU cost. Use spark to investigate expensive operations. The dashboard reads prepared snapshots rather than calling world APIs from HTTP worker threads.

## Version 1.1.2

This version introduces a redesigned **live web dashboard** in `src/main/resources/dashboard.html`: updated responsive layout, token login screen, TPS/MSPT/memory charts, chunk visualizations, incident feed, and scan history. The separate offline report template remains available in `src/main/resources/report-template.html`.

## License and credits

MIT License. Copyright (c) 2026 Meldixx.

The project icon used on Modrinth is adapted from **Pixelarticons** by Gerrit Halfmann (MIT License): https://github.com/halfmage/pixelarticons.

Development disclosure: portions of the project's code, interface, and documentation were created with substantial assistance from generative AI. Any public distribution must comply with the hosting platform's rules.
