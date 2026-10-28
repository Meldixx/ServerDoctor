package xyz.triosoft.serverdoctor.analysis;

/** Validated numeric settings loaded from the on-disk config on Bukkit's main thread. */
public record Thresholds(double minTps, double maxMspt, double maxHeapPercent,
                         double maxCpuPercent, int maxWorldEntities, int maxWorldBlockEntities,
                         int maxWorldChunks, double minFreeDiskGiB) { }
