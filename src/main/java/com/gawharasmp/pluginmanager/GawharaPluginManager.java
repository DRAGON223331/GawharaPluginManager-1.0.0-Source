package com.gawharasmp.pluginmanager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;

public final class GawharaPluginManager extends JavaPlugin implements CommandExecutor, TabCompleter {
    private File pluginsDirectory;
    private File dropboxDirectory;
    private File failedDirectory;
    private BukkitTask scannerTask;
    private final Map<String, Long> lastReportedFailure = new HashMap<>();
    private boolean scanInProgress = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pluginsDirectory = getDataFolder().getParentFile();
        String dropboxName = getConfig().getString("folders.dropbox", "dropbox");
        String failedName = getConfig().getString("folders.failed", "dropbox/failed");
        dropboxDirectory = new File(pluginsDirectory, dropboxName);
        failedDirectory = new File(pluginsDirectory, failedName);
        if (!dropboxDirectory.exists() && !dropboxDirectory.mkdirs()) {
            getLogger().severe("Could not create dropbox folder: " + dropboxDirectory.getAbsolutePath());
        }
        if (!failedDirectory.exists() && !failedDirectory.mkdirs()) {
            getLogger().warning("Could not create failed folder: " + failedDirectory.getAbsolutePath());
        }
        getCommand("gpm").setExecutor(this);
        getCommand("gpm").setTabCompleter(this);
        startScanner();
        getLogger().info("Enabled. Drop new Bukkit-compatible plugin JARs into " + dropboxDirectory.getPath());
        getLogger().warning("Runtime loading is best-effort. Replacing/updating an already loaded plugin requires a full restart.");
    }

    @Override
    public void onDisable() {
        if (scannerTask != null) scannerTask.cancel();
    }

    private void startScanner() {
        if (scannerTask != null) scannerTask.cancel();
        if (!getConfig().getBoolean("auto-scan.enabled", true)) return;
        long seconds = Math.max(10L, getConfig().getLong("auto-scan.interval-seconds", 20L));
        scannerTask = Bukkit.getScheduler().runTaskTimer(this, () -> scanFolder(null, true), seconds * 20L, seconds * 20L);
    }

    private String prefix() {
        return color(getConfig().getString("messages.prefix", "&8[&bGawharaPM&8] &r"));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(prefix() + color(message));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gawharapm.admin")) {
            send(sender, "&cYou don't have permission.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan" -> {
                int count = scanFolder(sender, false);
                send(sender, "&aScan finished. Loaded &f" + count + " &anew plugin(s).");
            }
            case "load" -> {
                if (args.length < 2) {
                    send(sender, "&eUsage: /gpm load <file.jar>");
                    return true;
                }
                File requested = new File(dropboxDirectory, args[1]).getAbsoluteFile();
                if (!requested.getParentFile().equals(dropboxDirectory.getAbsoluteFile())) {
                    send(sender, "&cInvalid file path.");
                    return true;
                }
                LoadResult result = loadNewPlugin(requested);
                send(sender, result.success ? "&a" + result.message : "&c" + result.message);
            }
            case "list" -> {
                Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
                send(sender, "&bLoaded plugins (&f" + plugins.length + "&b):");
                for (Plugin plugin : plugins) {
                    send(sender, (plugin.isEnabled() ? "&a✔ " : "&c✘ ") + "&f" + plugin.getName() + " &7v" + plugin.getPluginMeta().getVersion());
                }
            }
            case "status" -> {
                send(sender, "&7Dropbox: &f" + dropboxDirectory.getAbsolutePath());
                send(sender, "&7Auto-scan: " + (getConfig().getBoolean("auto-scan.enabled", true) ? "&aON" : "&cOFF"));
                send(sender, "&7Interval: &f" + getConfig().getLong("auto-scan.interval-seconds", 20L) + " seconds");
            }
            case "reload" -> {
                reloadConfig();
                startScanner();
                send(sender, "&aConfiguration reloaded.");
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        send(sender, "&bCommands:");
        send(sender, "&f/gpm scan &7- Scan dropbox and load new plugins");
        send(sender, "&f/gpm load <file.jar> &7- Load one JAR from dropbox");
        send(sender, "&f/gpm list &7- List currently loaded plugins");
        send(sender, "&f/gpm status &7- Show manager status");
        send(sender, "&f/gpm reload &7- Reload this plugin's config");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("gawharapm.admin")) return List.of();
        if (args.length == 1) return List.of("scan", "load", "list", "status", "reload", "help");
        if (args.length == 2 && args[0].equalsIgnoreCase("load")) {
            File[] jars = dropboxDirectory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars == null) return List.of();
            String start = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (File jar : jars) if (jar.getName().toLowerCase(Locale.ROOT).startsWith(start)) matches.add(jar.getName());
            return matches;
        }
        return List.of();
    }

    private int scanFolder(CommandSender sender, boolean automatic) {
        if (scanInProgress) return 0;
        scanInProgress = true;
        int loaded = 0;
        try {
            File[] jars = dropboxDirectory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars == null || jars.length == 0) return 0;
            List<File> pending = new ArrayList<>(List.of(jars));
            pending.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            boolean progress;
            do {
                progress = false;
                var iterator = pending.iterator();
                while (iterator.hasNext()) {
                    File jar = iterator.next();
                    LoadResult result = loadNewPlugin(jar);
                    if (result.success) {
                        loaded++;
                        progress = true;
                        iterator.remove();
                        if (sender != null) send(sender, "&aLoaded &f" + jar.getName());
                    } else if (result.retryable) {
                        // A hard dependency may be another JAR later in the same dropbox.
                    } else {
                        iterator.remove();
                        reportFailure(jar, result.message, sender, automatic);
                    }
                }
            } while (progress && !pending.isEmpty());
            for (File jar : pending) {
                reportFailure(jar, "Waiting for a required dependency or incompatible plugin metadata.", sender, automatic);
            }
            return loaded;
        } finally {
            scanInProgress = false;
        }
    }

    private void reportFailure(File jar, String message, CommandSender sender, boolean automatic) {
        long signature = jar.lastModified() ^ jar.length() ^ message.hashCode();
        Long previous = lastReportedFailure.put(jar.getName(), signature);
        if (previous != null && previous == signature && automatic) return;
        String text = "Could not load " + jar.getName() + ": " + message;
        getLogger().warning(text);
        if (sender != null) send(sender, "&c" + text);
    }

    private LoadResult loadNewPlugin(File source) {
        if (!source.isFile() || !source.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return LoadResult.fail("File not found or not a .jar file.", false);
        }
        try {
            PluginMetadata metadata = readMetadata(source);
            if (metadata == null) return LoadResult.fail("Missing or invalid plugin.yml. Paper-only plugin descriptors are not supported by this loader yet.", false);
            PluginManager manager = Bukkit.getPluginManager();
            if (getConfig().getBoolean("security.refuse-duplicate-names", true) && manager.getPlugin(metadata.name) != null) {
                return LoadResult.fail("Plugin '" + metadata.name + "' is already loaded. Updating it safely requires a server restart.", false);
            }
            if (getConfig().getBoolean("security.require-dependencies-enabled", true)) {
                for (String dependency : metadata.dependencies) {
                    Plugin dep = manager.getPlugin(dependency);
                    if (dep == null || !manager.isPluginEnabled(dep)) {
                        return LoadResult.fail("Required dependency not enabled: " + dependency, true);
                    }
                }
            }
            File target = new File(pluginsDirectory, source.getName());
            if (target.exists()) return LoadResult.fail("A file with the same name already exists in plugins/. Rename it or restart to update safely.", false);
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            Plugin loaded;
            try {
                loaded = manager.loadPlugin(target);
                if (loaded == null) throw new IllegalStateException("Paper PluginManager returned null.");
                manager.enablePlugin(loaded);
                if (!manager.isPluginEnabled(loaded)) throw new IllegalStateException("Plugin did not reach enabled state. Check the server log.");
            } catch (Throwable error) {
                getLogger().severe("Runtime load failed for " + source.getName() + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
                getLogger().severe("The server may have partially registered plugin state. Do not retry repeatedly; a restart may be required.");
                return LoadResult.fail("Load/enable failed: " + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()) + ". Check console; restart may be required.", false);
            }
            Files.deleteIfExists(source.toPath());
            getLogger().info("Successfully loaded plugin at runtime: " + metadata.name + " v" + metadata.version);
            return LoadResult.ok("Loaded " + metadata.name + " v" + metadata.version + " successfully.");
        } catch (Exception error) {
            return LoadResult.fail(error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()), false);
        }
    }

    private PluginMetadata readMetadata(File jarFile) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) return null;
            YamlConfiguration yaml;
            try (InputStream input = jar.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                yaml = YamlConfiguration.loadConfiguration(reader);
            }
            String name = yaml.getString("name", "").trim();
            String main = yaml.getString("main", "").trim();
            String version = yaml.getString("version", "").trim();
            if (name.isBlank() || main.isBlank() || version.isBlank()) return null;
            List<String> deps = new ArrayList<>(yaml.getStringList("depend"));
            return new PluginMetadata(name, version, main, deps);
        }
    }

    private record PluginMetadata(String name, String version, String mainClass, List<String> dependencies) {}
    private record LoadResult(boolean success, boolean retryable, String message) {
        static LoadResult ok(String message) { return new LoadResult(true, false, message); }
        static LoadResult fail(String message, boolean retryable) { return new LoadResult(false, retryable, message); }
    }
}
