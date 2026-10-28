package xyz.triosoft.serverdoctor.chunk;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.scheduler.BukkitTask;
import xyz.triosoft.serverdoctor.ServerDoctorPlugin;
import xyz.triosoft.serverdoctor.model.AdvancedData;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Bounded examination of already-loaded chunks. Density is a heuristic, not actual tick cost. */
public final class ChunkInspector {
    private final ServerDoctorPlugin plugin;
    private BukkitTask task;
    private final ArrayDeque<Chunk> pending = new ArrayDeque<>();
    private final List<AdvancedData.Hotspot> inspected = new ArrayList<>();
    private volatile AdvancedData.Heatmap latest = AdvancedData.Heatmap.empty();
    private Consumer<AdvancedData.Heatmap> finished;
    private int total;
    private boolean truncated;

    public ChunkInspector(ServerDoctorPlugin plugin) { this.plugin = plugin; }
    public boolean running() { return task != null; }
    public AdvancedData.Heatmap latest() { return latest; }
    public void stop() { if (task != null) task.cancel(); task = null; pending.clear(); finished = null; }
    public boolean begin(Consumer<AdvancedData.Heatmap> callback) {
        if (running()) return false;
        pending.clear(); inspected.clear(); finished = callback;
        int max = Math.max(64, Math.min(10000, plugin.getConfig().getInt("chunks.max-chunks", 1024)));
        int encountered = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                encountered++;
                if (pending.size() < max) pending.addLast(chunk);
            }
        }
        total = pending.size();
        truncated = encountered > max;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return true;
    }
    private void tick() {
        int budget = Math.max(1, Math.min(64, plugin.getConfig().getInt("chunks.per-tick", 12)));
        for (int i = 0; i < budget && !pending.isEmpty(); i++) {
            Chunk chunk = pending.removeFirst();
            try {
                if (!chunk.isLoaded()) continue; // Never trigger a chunk load.
                int entities = chunk.isEntitiesLoaded() ? chunk.getEntities().length : -1;
                BlockState[] tiles = chunk.getTileEntities(false);
                int hoppers = 0;
                for (BlockState state : tiles) if (state.getType() == Material.HOPPER) hoppers++;
                // Weighted density can suggest a hotspot; does not measure actual CPU cost.
                int score = Math.max(0, entities) * 2 + tiles.length * 3 + hoppers * 4;
                inspected.add(new AdvancedData.Hotspot(chunk.getWorld().getName(), chunk.getX(),
                        chunk.getZ(), entities, tiles.length, hoppers, score));
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping a chunk during inspection: " + e.getMessage());
            }
        }
        if (pending.isEmpty()) {
            task.cancel(); task = null;
            inspected.sort(Comparator.comparingInt(AdvancedData.Hotspot::densityScore).reversed());
            int max = Math.max(1, Math.min(200, plugin.getConfig().getInt("chunks.top-results", 40)));
            latest = new AdvancedData.Heatmap(List.copyOf(inspected.subList(0, Math.min(inspected.size(), max))),
                    inspected.size(), total, truncated, Instant.now());
            if (finished != null) { var cb = finished; finished = null; cb.accept(latest); }
            inspected.clear();
        }
    }
}
