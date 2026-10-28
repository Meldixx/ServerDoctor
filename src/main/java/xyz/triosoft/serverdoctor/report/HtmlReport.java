package xyz.triosoft.serverdoctor.report;

import xyz.triosoft.serverdoctor.model.ScanResult;
import xyz.triosoft.serverdoctor.model.AdvancedData;
import xyz.triosoft.serverdoctor.util.Numbers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.triosoft.serverdoctor.util.Numbers.escapeHtml;

/** Standalone HTML report: bundled CSS, local scripts, inline SVG, no remote assets. */
public final class HtmlReport {
    private HtmlReport() { }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm z", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Z][A-Z0-9_]*)\\}\\}");
    private static final String TEMPLATE = loadTemplate();

    private static String loadTemplate() {
        try (InputStream in = HtmlReport.class.getResourceAsStream("/report-template.html")) {
            if (in == null) throw new IllegalStateException("Missing bundled report-template.html");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read report template", e);
        }
    }

    public static String generate(ScanResult r) {
        long critical = r.findings().stream().filter(f -> f.severity() == ScanResult.Severity.CRITICAL).count();
        long warning = r.findings().stream().filter(f -> f.severity() == ScanResult.Severity.WARNING).count();
        long info = r.findings().stream().filter(f -> f.severity() == ScanResult.Severity.INFO).count();
        String stateClass = critical > 0 ? "critical" : warning > 0 ? "warning" : "ok";
        String stateText = critical > 0 ? "Critical attention required" : warning > 0 ? "Review recommended" : "Within configured thresholds";
        int score = Math.max(0, Math.min(100, r.score()));

        long chunks = r.worlds().stream().mapToLong(ScanResult.WorldStats::chunks).sum();
        long entities = r.worlds().stream().mapToLong(ScanResult.WorldStats::entities).sum();
        long blockEntities = r.worlds().stream().mapToLong(ScanResult.WorldStats::blockEntities).sum();

        StringBuilder metrics = new StringBuilder(4096);
        metric(metrics, "Server TPS", Numbers.fixed(r.tpsMean(), 2), "", "MIN " + Numbers.fixed(r.tpsMin(), 2) + " TPS", "01", r.samples(), ScanResult.Sample::tps, 20, "#c5f6a0");
        metric(metrics, "Tick duration", Numbers.fixed(r.msptMean(), 2), "ms", "PEAK " + Numbers.fixed(r.msptMax(), 2) + " MS", "02", r.samples(), ScanResult.Sample::mspt, Math.max(50, max(r.samples(), ScanResult.Sample::mspt)*1.15), "#82e5e7");
        metric(metrics, "Heap used", Numbers.fixed(r.heapUsedMiB()/1024d, 2), "GiB", "OF " + Numbers.fixed(r.heapMaxMiB()/1024d, 2) + " GIB MAX", "03", r.samples(), ScanResult.Sample::heapPercent, 100, "#ae9cff");
        metric(metrics, "Process CPU", Numbers.fixed(r.cpuMean(), 1), "%", "JVM PROCESS LOAD", "04", r.samples(), ScanResult.Sample::cpuPercent, 100, "#9eacd5");

        StringBuilder quick = new StringBuilder();
        quick(quick, "Players online", r.onlinePlayers() + " <span style=\"color:#7c899e;font-size:12px\">/ " + r.maxPlayers() + "</span>");
        quick(quick, "Loaded chunks", formatted(chunks));
        quick(quick, "Loaded entities", formatted(entities));
        quick(quick, "Free disk space", r.diskUsableBytes() < 0 ? "N/A" : Numbers.fixed(r.diskUsableBytes()/1_073_741_824d, 1) + " <span style=\"color:#7c899e;font-size:12px\">GiB</span>");

        Map<String,String> data = new LinkedHashMap<>();
        String id = r.id() == null ? "unknown" : r.id();
        data.put("REPORT_ID", escapeHtml(id));
        data.put("SHORT_ID", escapeHtml(id.length() >= 6 ? id.substring(id.length()-6).toUpperCase(Locale.ROOT) : id));
        data.put("SCORE", Integer.toString(score));
        data.put("SCORE_OFFSET", String.format(Locale.US,"%.2f", 584.336 * (100 - score) / 100d));
        data.put("STATE_CLASS", stateClass);
        data.put("STATE_TEXT", stateText);
        data.put("DATE", escapeHtml(date(r.createdAt())));
        data.put("GENERATED", escapeHtml(date(Instant.now())));
        data.put("SAMPLE_COUNT", Integer.toString(r.samples().size()));
        data.put("DURATION", Numbers.fixed(r.elapsedMillis()/1000d,1));
        data.put("METRICS", metrics.toString());
        data.put("QUICK_STATS", quick.toString());
        data.put("CHART_TPS", chart("TPS over time", "1-minute moving average", Numbers.fixed(r.tpsMean(),2), "TPS", r.samples(), ScanResult.Sample::tps, 20, "#b1ed9c", "tps", 20));
        data.put("CHART_MSPT", chart("Tick duration", "Paper rolling average", Numbers.fixed(r.msptMean(),2), "ms", r.samples(), ScanResult.Sample::mspt, Math.max(50,max(r.samples(),ScanResult.Sample::mspt)*1.2), "#7cddd8", "mspt", 50));
        data.put("FINDING_COUNT", Integer.toString(r.findings().size()));
        data.put("WORLD_COUNT", Integer.toString(r.worlds().size()));
        data.put("CRITICAL_COUNT", Long.toString(critical));
        data.put("WARNING_COUNT", Long.toString(warning));
        data.put("INFO_COUNT", Long.toString(info));
        data.put("FINDINGS", findings(r.findings()));
        data.put("WORLD_ROWS", worldRows(r.worlds(), chunks));
        data.put("TOTAL_CHUNKS", formatted(chunks));
        data.put("TOTAL_ENTITIES", formatted(entities));
        data.put("TOTAL_BLOCK_ENTITIES", formatted(blockEntities));
        data.put("PLUGIN_COUNT", Integer.toString(r.pluginCount()));
        data.put("ENVIRONMENT", environment(r));
        data.put("PLUGINS", plugins(r.plugins(),r.pluginCount()));
        data.put("ADVANCED", advanced(r));

        // A single-pass substitution means untrusted data can never become a template token.
        Matcher matcher = TOKEN.matcher(TEMPLATE);
        StringBuffer html = new StringBuffer(TEMPLATE.length()+16_384);
        while (matcher.find()) {
            String replacement = data.get(matcher.group(1));
            if (replacement == null) throw new IllegalStateException("Unknown report template token: " + matcher.group(1));
            matcher.appendReplacement(html, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(html);
        return html.toString();
    }

    private static String date(Instant instant) { return instant == null ? "Unknown" : DATE.format(instant); }
    private static String formatted(long n) { return String.format(Locale.US,"%,d",n); }

    private static void quick(StringBuilder html, String label, String valTrustedHtml) {
        html.append("<div class=\"quick-stat\"><div class=\"name\">").append(escapeHtml(label))
                .append("</div><div class=\"val\">").append(valTrustedHtml).append("</div></div>");
    }

    private static void metric(StringBuilder html, String title, String val, String suffix,
                               String note, String number, List<ScanResult.Sample> samples,
                               ToDoubleFunction<ScanResult.Sample> accessor, double upper, String color) {
        html.append("<article class=\"metric\"><div class=\"metric-top\"><span>")
                .append(escapeHtml(title)).append("</span><span class=\"metric-tag\">")
                .append(number).append("</span></div><div class=\"metric-value\">")
                .append(escapeHtml(val));
        if (!suffix.isEmpty()) html.append(" <span class=\"metric-unit\">").append(escapeHtml(suffix)).append("</span>");
        html.append("</div><div class=\"metric-desc\">").append(escapeHtml(note)).append("</div>");
        html.append(spark(samples,accessor,upper,color)).append("</article>");
    }

    private static String spark(List<ScanResult.Sample> samples, ToDoubleFunction<ScanResult.Sample> accessor,
                                double upper, String color) {
        List<double[]> values = usable(samples,accessor);
        if (values.isEmpty()) return "<div class=\"spark-muted\">No samples available</div>";
        double maxTime = Math.max(1,values.getLast()[0]-values.getFirst()[0]);
        double origin = values.getFirst()[0];
        StringBuilder out = new StringBuilder("<svg class=\"spark\" viewBox=\"0 0 180 40\" preserveAspectRatio=\"none\" aria-hidden=\"true\"><path stroke=\"");
        out.append(color).append("\" d=\"");
        for(int i=0;i<values.size();i++) {
            double x = 2+(values.get(i)[0]-origin)/maxTime*176;
            double y = 35-Math.max(0,Math.min(1,values.get(i)[1]/Math.max(.01,upper)))*29;
            out.append(i==0?'M':'L').append(coord(x)).append(' ').append(coord(y)).append(' ');
        }
        return out.append("\"/></svg>").toString();
    }

    private static List<double[]> usable(List<ScanResult.Sample> samples,ToDoubleFunction<ScanResult.Sample> accessor) {
        List<double[]> values = new ArrayList<>();
        for(ScanResult.Sample s:samples) {
            double n=accessor.applyAsDouble(s);
            if(Double.isFinite(n)&&n>=0&&Double.isFinite(s.second()))values.add(new double[]{s.second(),n});
        }
        return values;
    }

    private static String chart(String name,String subtitle,String headline,String unit,List<ScanResult.Sample> samples,
                                ToDoubleFunction<ScanResult.Sample> accessor,double upper,String color,String id,double threshold) {
        StringBuilder sb=new StringBuilder(5_000);
        sb.append("<article class=\"chart-panel\"><div class=\"top\"><div><div class=\"tag\">")
                .append(escapeHtml(name)).append("</div><div class=\"headline\">").append(escapeHtml(headline))
                .append(" <small>").append(escapeHtml(unit)).append("</small></div></div><div class=\"icon-chip")
                .append(id.equals("mspt")?" cyan":"").append("\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\"><path d=\"M2 15l5-5 4 3 5-8 6 5\"/></svg></div></div>");
        List<double[]> v=usable(samples,accessor);
        sb.append("<svg class=\"chart-svg\" viewBox=\"0 0 620 250\" tabindex=\"0\" role=\"img\" aria-label=\"")
                .append(escapeHtml(name)).append(" line chart. Use left or right arrow keys to inspect samples\" data-unit=\"")
                .append(escapeHtml(unit)).append("\" data-series=\"");
        double originSample=v.isEmpty()?0:v.getFirst()[0], timeSample=v.isEmpty()?1:Math.max(1,v.getLast()[0]-originSample);
        for (double[] sample : v) {
            double px=46+(sample[0]-originSample)/timeSample*562;
            double py=194-Math.max(0,Math.min(1,sample[1]/Math.max(.01,upper)))*162;
            sb.append(coord(px)).append(',').append(coord(py)).append(',')
              .append(coord(sample[0]-originSample)).append(',').append(coord(sample[1])).append('|');
        }
        sb.append("\" xmlns=\"http://www.w3.org/2000/svg\"><defs><linearGradient id=\"fill-")
                .append(id).append("\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop stop-color=\"")
                .append(color).append("\" stop-opacity=\".22\"/><stop offset=\"1\" stop-color=\"")
                .append(color).append("\" stop-opacity=\"0\"/></linearGradient></defs>");
        double hi=Math.max(.01,upper);
        for(int i=0;i<4;i++) {
            double vTick=upper*(3-i)/3d;
            int y=32+i*54;
            sb.append("<line class=\"gridline\" x1=\"46\" x2=\"608\" y1=\"").append(y)
                    .append("\" y2=\"").append(y).append("\"/><text x=\"35\" y=\"").append(y+3)
                    .append("\" text-anchor=\"end\">").append(escapeHtml(Numbers.fixed(vTick, vTick<100 ? 0:0))).append("</text>");
        }
        if (threshold < hi) {
            double ty=194-threshold/hi*162;
            sb.append("<line class=\"threshold\" x1=\"46\" x2=\"608\" y1=\"").append(coord(ty))
                    .append("\" y2=\"").append(coord(ty)).append("\"/>");
        }
        if (!v.isEmpty()) {
            double origin=v.getFirst()[0],time=Math.max(1,v.getLast()[0]-origin);
            StringBuilder d=new StringBuilder();
            double lastX=46,lastY=194;
            for(int i=0;i<v.size();i++) {
                double x=46+(v.get(i)[0]-origin)/time*562;
                double y=194-Math.max(0,Math.min(1,v.get(i)[1]/hi))*162;
                if(i>0)d.append('L'); else d.append('M');
                d.append(coord(x)).append(' ').append(coord(y)).append(' ');
                lastX=x;lastY=y;
            }
            String path=d.toString();
            sb.append("<path d=\"").append(path).append("L ").append(coord(lastX)).append(" 194 L ")
                    .append(coord(46+(v.getFirst()[0]-origin)/time*562)).append(" 194 Z\" fill=\"url(#fill-").append(id).append(")\"/>");
            sb.append("<path d=\"").append(path).append("\" stroke=\"").append(color)
                    .append("\" fill=\"none\" stroke-width=\"3\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>");
            sb.append("<circle cx=\"").append(coord(lastX)).append("\" cy=\"").append(coord(lastY))
                    .append("\" r=\"6\" fill=\"").append(color).append("\" stroke=\"#141720\" stroke-width=\"3\"/>");
            sb.append("<text x=\"46\" y=\"225\">0s</text><text x=\"608\" y=\"225\" text-anchor=\"end\">")
                    .append(escapeHtml(Numbers.fixed(time,0))).append("s</text>");
        } else {
            sb.append("<text x=\"310\" y=\"120\" text-anchor=\"middle\">No valid samples collected</text>");
        }
        sb.append("<g class=\"chart-cursor\"><line x1=\"46\" x2=\"46\" y1=\"30\" y2=\"194\"/><circle cx=\"46\" cy=\"194\" r=\"5\" fill=\"")
          .append(color).append("\"/></g><rect class=\"plot-hit\" x=\"46\" y=\"27\" width=\"562\" height=\"171\"/>");
        sb.append("</svg><div class=\"chart-tooltip\" role=\"status\"></div><div class=\"chart-legend\"><span class=\"legend-live")
                .append(id.equals("mspt")?" cyan":"").append("\"><i></i>").append(escapeHtml(subtitle))
                .append("</span><span>").append(v.size()).append(" valid samples</span></div></article>");
        return sb.toString();
    }

    private static String coord(double n){return String.format(Locale.US,"%.1f",n);}
    private static double max(List<ScanResult.Sample> samples,ToDoubleFunction<ScanResult.Sample> fn) {
        return samples.stream().mapToDouble(fn).filter(n->Double.isFinite(n)&&n>=0).max().orElse(0);
    }

    private static String findings(List<ScanResult.Finding> findings) {
        StringBuilder html=new StringBuilder();
        if(findings.isEmpty())return "<div class=\"empty-state\"><b>No diagnostic findings recorded.</b>All sampled metrics appear within the reported limits, but brief or intermittent issues may go undetected.</div>";
        for (ScanResult.Finding finding:findings) {
            String severity=finding.severity().name().toLowerCase(Locale.ROOT);
            String symbol = switch(finding.severity()) {
                case CRITICAL -> "<path d=\"M12 3 21 20H3L12 3Z\"/><path d=\"M12 9v5m0 3v.5\"/>";
                case WARNING -> "<path d=\"M12 3 21 20H3L12 3Z\"/><path d=\"M12 9v5m0 3v.5\"/>";
                case INFO -> "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 11v5m0-8v.5\"/>";
            };
            html.append("<article class=\"finding ").append(severity).append("\" data-severity=\"").append(severity)
                .append("\"><div class=\"finding-symbol\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linecap=\"round\" stroke-linejoin=\"round\">")
                .append(symbol).append("</svg></div><div><div class=\"finding-severity\">")
                .append(severity).append(" / Diagnostic note</div><div class=\"finding-title\">")
                .append(escapeHtml(finding.title())).append("</div><div class=\"finding-text\">")
                .append(escapeHtml(finding.detail())).append("</div><div class=\"finding-action\"><b>RECOMMENDATION</b> · ")
                .append(escapeHtml(finding.action())).append("</div></div><span class=\"finding-chevron\">↗</span></article>");
        }
        return html.toString();
    }

    private static String worldRows(List<ScanResult.WorldStats> worlds, long totalChunks) {
        if(worlds.isEmpty())return "<div class=\"empty-state\"><b>No worlds collected.</b>World metrics were not available for this scan.</div>";
        StringBuilder html=new StringBuilder();
        for(ScanResult.WorldStats world:worlds) {
            double p=totalChunks>0 ? 100d*world.chunks()/totalChunks : 0;
            html.append("<div class=\"world-row\"><div><div class=\"world-name\">").append(escapeHtml(world.name()))
                .append("</div><div class=\"world-sub\">").append(escapeHtml(world.environment()))
                .append(" · ").append(world.players()).append(" players · ").append(formatted(world.entities()))
                .append(" entities · ").append(formatted(world.blockEntities())).append(" block entities</div>")
                .append("<div class=\"world-meter\"><span style=\"width:").append(coord(p))
                .append("%\"></span></div></div><div class=\"world-value\">").append(formatted(world.chunks()))
                .append("<small>").append(escapeHtml(Numbers.fixed(p,1))).append("% of loaded chunks</small></div></div>");
        }
        return html.toString();
    }

    private static String environment(ScanResult r) {
        StringBuilder html=new StringBuilder();
        info(html,"Report ID",r.id());
        info(html,"Scan date",date(r.createdAt()));
        info(html,"Paper / Minecraft",r.serverVersion());
        info(html,"Java runtime",r.javaVersion());
        info(html,"Operating system",r.operatingSystem());
        info(html,"JVM uptime",Numbers.fixed(r.uptimeMillis()/3_600_000d,1)+" hours");
        info(html,"Recorded samples",Integer.toString(r.samples().size()));
        info(html,"Plugin data volume",r.diskTotalBytes()>0?Numbers.fixed(r.diskTotalBytes()/1_073_741_824d,1)+" GiB":"N/A");
        return html.toString();
    }

    private static void info(StringBuilder html,String label,String value) {
        html.append("<div class=\"info-row\"><span>").append(escapeHtml(label))
            .append("</span><strong>").append(escapeHtml(value)).append("</strong></div>");
    }

    private static String plugins(List<ScanResult.PluginInfo> plugins,int count) {
        if(plugins.isEmpty())return "<div class=\"empty-state\"><b>Plugin listing is private.</b>" +
            "Names and versions were omitted by the scan configuration. A total of " + count + " plugins were detected.</div>";
        StringBuilder html=new StringBuilder("<input id=\"pluginSearch\" class=\"search-input\" type=\"search\" aria-label=\"Filter plugins\" placeholder=\"Search installed plugins...\"><div class=\"plugins-scroll\">");
        for(ScanResult.PluginInfo plugin:plugins) {
            html.append("<div class=\"plugin-row\"><b>").append(escapeHtml(plugin.name()))
                .append("</b><span>").append(escapeHtml(plugin.version())).append("</span></div>");
        }
        return html.append("</div>").toString();
    }
    private static String advanced(ScanResult r) {
        AdvancedData.Insight insight = r.insight();
        AdvancedData.Heatmap map = r.heatmap();
        StringBuilder b = new StringBuilder(3000);
        b.append("<div class=\"advanced-stats\">");
        advancedStat(b, "GC cycles · JVM lifetime", Long.toString(insight.gcCount()), "cumulative count");
        advancedStat(b, "GC pause · JVM lifetime", insight.gcTimeMs() + " ms", "collector-reported time");
        advancedStat(b, "Non-heap usage", Numbers.fixed(insight.nonHeapUsedMiB(),1) + " MiB", "metaspace, code cache and more");
        advancedStat(b, "Observed tick spikes", Integer.toString(insight.spikes().size()), "rolling Paper tick-buffer detection");
        b.append("</div><div class=\"advanced-columns\"><section class=\"advanced-panel\"><div class=\"advanced-head\">Lag events <small>Recent observations</small></div>");
        if (insight.spikes().isEmpty()) b.append("<p class=\"advanced-empty\">No lag spikes were recorded during monitoring.</p>");
        else {
            int begin = Math.max(0, insight.spikes().size() - 12);
            for (int i = insight.spikes().size() - 1; i >= begin; i--) {
                var event = insight.spikes().get(i);
                b.append("<div class=\"advanced-line\"><span>").append(escapeHtml(date(event.when())))
                 .append("</span><b>").append(escapeHtml(Numbers.fixed(event.maxTickMs(),1))).append(" ms</b></div>");
            }
        }
        if (!insight.gcEvents().isEmpty()) {
            b.append("<p class=\"advanced-note\">GC pause activity: ");
            for (var event : insight.gcEvents().subList(Math.max(0, insight.gcEvents().size() - 3), insight.gcEvents().size())) {
                b.append(escapeHtml(Numbers.fixed(event.pauseDeltaMs(), 0))).append(" ms · ");
            }
            b.append("measured across monitoring intervals.</p>");
        }
        b.append("</section><section class=\"advanced-panel\"><div class=\"advanced-head\">Chunk density <small>")
         .append(map.chunksInspected()).append(" scanned / ").append(map.chunksAvailable())
         .append(map.truncated() ? " · capped" : "").append("</small></div>");
        if (map.hotspots().isEmpty()) b.append("<p class=\"advanced-empty\">No hotspot scan captured. Run /doctor chunks before starting a scan.</p>");
        else {
            b.append(chunkPlot(map.hotspots()));
            int maxScore = map.hotspots().stream().mapToInt(AdvancedData.Hotspot::densityScore).max().orElse(1);
            for (var h : map.hotspots().subList(0, Math.min(10, map.hotspots().size()))) {
                int percentage = maxScore == 0 ? 0 : (int)Math.round(h.densityScore()*100.0/maxScore);
                b.append("<div class=\"advanced-line\"><span>").append(escapeHtml(h.world()))
                 .append(" [").append(h.x()).append(", ").append(h.z()).append("] · entities ")
                 .append(h.entities() == -1 ? "not loaded" : h.entities()).append(" · tiles ")
                 .append(h.blockEntities()).append("</span><b>").append(h.densityScore()).append(" pts</b></div>")
                 .append("<div class=\"advanced-bar\"><i style=\"width:").append(percentage).append("%\"></i></div>");
            }
        }
        return b.append("</section></div><p class=\"advanced-note\">Density is not measured CPU use. For actual plugin performance, run Paper spark while the problem occurs. The GC values are cumulative JVM counters, not scan-window deltas.</p>").toString();
    }
    private static void advancedStat(StringBuilder b, String title, String value, String note) {
        b.append("<div class=\"advanced-stat\"><span>").append(escapeHtml(title))
            .append("</span><strong>").append(escapeHtml(value)).append("</strong><small>")
            .append(escapeHtml(note)).append("</small></div>");
    }

    /** Relative coordinates for the sampled hotspots only, not a complete map of all chunks. */
    private static String chunkPlot(List<AdvancedData.Hotspot> all) {
        if (all.isEmpty()) return "";
        String world = all.getFirst().world();
        var spots = all.stream().filter(h -> h.world().equals(world)).toList();
        int minX = spots.stream().mapToInt(AdvancedData.Hotspot::x).min().orElse(0);
        int maxX = spots.stream().mapToInt(AdvancedData.Hotspot::x).max().orElse(0);
        int minZ = spots.stream().mapToInt(AdvancedData.Hotspot::z).min().orElse(0);
        int maxZ = spots.stream().mapToInt(AdvancedData.Hotspot::z).max().orElse(0);
        int maxDensity = Math.max(1, spots.stream().mapToInt(AdvancedData.Hotspot::densityScore).max().orElse(1));
        StringBuilder b = new StringBuilder("<div class=\"chunk-plot-label\">Relative hotspot positions · ")
                .append(escapeHtml(world)).append("</div><svg class=\"chunk-plot\" viewBox=\"0 0 500 220\" role=\"img\" aria-label=\"Positions of inspected loaded-chunk hotspots\">")
                .append("<path d=\"M20 40H480M20 80H480M20 120H480M20 160H480M20 200H480M100 15V210M200 15V210M300 15V210M400 15V210\" fill=\"none\" stroke=\"#2a3549\" stroke-dasharray=\"3 6\"/>");
        for (var spot : spots) {
            double x = 26 + (maxX == minX ? .5 : (spot.x()-minX)/(double)(maxX-minX)) * 448;
            double y = 20 + (maxZ == minZ ? .5 : (spot.z()-minZ)/(double)(maxZ-minZ)) * 180;
            double radius = 3.5 + 6.5 * spot.densityScore() / maxDensity;
            b.append("<circle cx=\"").append(coord(x)).append("\" cy=\"").append(coord(y))
                    .append("\" r=\"").append(coord(radius)).append("\" fill=\"#afa7ff\" fill-opacity=\"")
                    .append(coord(.35 + .65*spot.densityScore()/maxDensity)).append("\"><title>")
                    .append(escapeHtml(world)).append(" [").append(spot.x()).append(", ").append(spot.z())
                    .append("] · density ").append(spot.densityScore()).append("</title></circle>");
        }
        return b.append("</svg><div class=\"advanced-note\">Coordinates normalized within sampled hotspots. Circle size reflects density heuristic.</div>").toString();
    }

}
