package Vortex.aliases;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import me.lucko.fabric.api.permissions.v0.Permissions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Aliases implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Map<String, AliasData> aliasMap = new HashMap<>();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("aliases.json");

    public static class AliasData {
        public String permission;
        public boolean suggestPlayers;
        public List<String> commands;

        public AliasData(String permission, boolean suggestPlayers, List<String> commands) {
            this.permission = permission;
            this.suggestPlayers = suggestPlayers;
            this.commands = commands;
        }
    }

    @Override
    public void onInitialize() {
        loadConfig();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerAdminCommands(dispatcher);
            refreshAliases(dispatcher);
        });
    }

    private void registerAdminCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        var baseCommand = CommandManager.literal("commandaliases")
                .requires(Permissions.require("aliasy.admin", 2));

        baseCommand.then(CommandManager.literal("reload").executes(context -> {
            loadConfig();
            refreshAliases(dispatcher);
            updatePlayerCommands(context.getSource());
            context.getSource().sendFeedback(() -> Text.literal("§a[Aliasy] Przeładowano konfigurację!"), true);
            return 1;
        }));

        baseCommand.then(CommandManager.literal("list").executes(context -> {
            if (aliasMap.isEmpty()) {
                context.getSource().sendFeedback(() -> Text.literal("§eBrak zarejestrowanych aliasów."), false);
                return 1;
            }
            context.getSource().sendFeedback(() -> Text.literal("§6Lista aliasów:"), false);
            aliasMap.forEach((name, data) -> context.getSource().sendFeedback(() ->
                    Text.literal("§7- §f/" + name + " §8(Sugerowanie: " + data.suggestPlayers + ")"), false));
            return 1;
        }));

        baseCommand.then(CommandManager.literal("remove").then(CommandManager.argument("nazwa", StringArgumentType.word()).executes(context -> {
            String nazwa = StringArgumentType.getString(context, "nazwa");
            if (aliasMap.remove(nazwa) != null) {
                saveConfig();
                refreshAliases(dispatcher);
                updatePlayerCommands(context.getSource());
                context.getSource().sendFeedback(() -> Text.literal("§cUsunięto alias: /" + nazwa), true);
            }
            return 1;
        })));

        baseCommand.then(CommandManager.argument("nazwa", StringArgumentType.word())
                .then(CommandManager.argument("permisja", StringArgumentType.word())
                        .then(CommandManager.argument("komendy", StringArgumentType.greedyString())
                                .executes(context -> {
                                    saveAlias(context, false);
                                    refreshAliases(dispatcher);
                                    updatePlayerCommands(context.getSource());
                                    return 1;
                                }))));

        baseCommand.then(CommandManager.literal("set")
                .then(CommandManager.argument("nazwa", StringArgumentType.word())
                        .then(CommandManager.argument("permisja", StringArgumentType.word())
                                .then(CommandManager.argument("suggestPlayers", BoolArgumentType.bool())
                                        .then(CommandManager.argument("komendy", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    saveAliasWithBool(context);
                                                    refreshAliases(dispatcher);
                                                    updatePlayerCommands(context.getSource());
                                                    return 1;
                                                }))))));

        dispatcher.register(baseCommand);
    }

    private void saveAlias(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context, boolean suggest) {
        String nazwa = StringArgumentType.getString(context, "nazwa");
        String permisja = StringArgumentType.getString(context, "permisja");
        String raw = StringArgumentType.getString(context, "komendy");
        aliasMap.put(nazwa, new AliasData(permisja, suggest, Arrays.asList(raw.split(";"))));
        saveConfig();
        context.getSource().sendFeedback(() -> Text.literal("§aZapisano alias: /" + nazwa + " (Sugerowanie: " + suggest + ")"), true);
    }

    private void saveAliasWithBool(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context) {
        String nazwa = StringArgumentType.getString(context, "nazwa");
        String permisja = StringArgumentType.getString(context, "permisja");
        boolean suggest = BoolArgumentType.getBool(context, "suggestPlayers");
        String raw = StringArgumentType.getString(context, "komendy");
        aliasMap.put(nazwa, new AliasData(permisja, suggest, Arrays.asList(raw.split(";"))));
        saveConfig();
        context.getSource().sendFeedback(() -> Text.literal("§aZapisano alias: /" + nazwa + " (Sugerowanie: " + suggest + ")"), true);
    }

    private void refreshAliases(CommandDispatcher<ServerCommandSource> dispatcher) {
        for (Map.Entry<String, AliasData> entry : aliasMap.entrySet()) {
            String aliasName = entry.getKey();
            AliasData data = entry.getValue();

            if (data.commands.isEmpty()) continue;

            var node = CommandManager.literal(aliasName)
                    .requires(Permissions.require(data.permission, 0))
                    .executes(context -> {
                        executeAll(context.getSource(), data.commands, "");
                        return 1;
                    });

            var argsArg = CommandManager.argument("args", StringArgumentType.greedyString())
                    .executes(context -> {
                        executeAll(context.getSource(), data.commands, StringArgumentType.getString(context, "args"));
                        return 1;
                    });

            if (data.suggestPlayers) {
                argsArg.suggests((context, builder) -> {
                    ServerCommandSource source = context.getSource();
                    return CommandSource.suggestMatching(source.getPlayerNames(), builder);
                });
            }

            dispatcher.register(node.then(argsArg));
        }
    }

    private void executeAll(ServerCommandSource source, List<String> commands, String rawArgs) {
        String extra = rawArgs.trim();
        for (String cmd : commands) {
            String finalCmd = cmd.trim();

            // Logika placeholderu %args%
            if (finalCmd.contains("%args%")) {
                finalCmd = finalCmd.replace("%args%", extra);
            } else {
                // Zachowanie kompatybilności wstecznej (dopisanie na końcu)
                if (!extra.isEmpty()) {
                    finalCmd = finalCmd + " " + extra;
                }
            }

            if (finalCmd.startsWith("/")) finalCmd = finalCmd.substring(1);
            source.getServer().getCommandManager().executeWithPrefix(source, finalCmd);
        }
    }

    private void updatePlayerCommands(ServerCommandSource source) {
        if (source.getServer() != null) {
            source.getServer().getPlayerManager().getPlayerList().forEach(player ->
                    source.getServer().getCommandManager().sendCommandTree(player));
        }
    }

    private void loadConfig() {
        try {
            if (!Files.exists(CONFIG_DIR)) Files.createDirectories(CONFIG_DIR);
            if (!Files.exists(CONFIG_FILE)) { saveConfig(); return; }
            try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
                Map<String, AliasData> loaded = GSON.fromJson(reader, new TypeToken<Map<String, AliasData>>(){}.getType());
                if (loaded != null) aliasMap = loaded;
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void saveConfig() {
        try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(aliasMap, writer);
        } catch (IOException e) { e.printStackTrace(); }
    }
}