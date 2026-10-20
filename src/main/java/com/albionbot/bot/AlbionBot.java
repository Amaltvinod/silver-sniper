package com.albionbot.bot;

import com.albionbot.access.AccessStore;
import com.albionbot.access.GameInfoClient;
import com.albionbot.config.Config;
import com.albionbot.config.Game;
import com.albionbot.config.GuildSettings;
import com.albionbot.config.GuildSettingsStore;
import com.albionbot.config.UserSettingsStore;
import com.albionbot.data.RecipeBook;
import com.albionbot.market.MarketClient;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AlbionBot {
    /** Shown on every reply and in the bot's status. The Discord username itself is set in the Developer Portal. */
    public static final String NAME = "Silver Sniper";

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();
        if (config.token().isBlank()) {
            System.err.println("Set DISCORD_TOKEN (see README).");
            System.exit(1);
        }
        RecipeBook recipes = RecipeBook.load(config.recipesPath());
        GuildSettingsStore guilds = GuildSettingsStore.load(config.guildsPath(), GuildSettings.defaults(config));
        System.out.println("Loaded " + recipes.size() + " recipes; default market server: " + config.server());

        // One client per Albion server: each has its own cache and its own share of the API rate limit.
        Map<String, MarketClient> markets = new LinkedHashMap<>();
        for (String server : Game.SERVERS.keySet()) {
            markets.put(server, new MarketClient(
                    Game.SERVERS.get(server),
                    Duration.ofMinutes((long) (config.maxAgeHours() * 60)),
                    Duration.ofMinutes(5),
                    Clock.systemUTC()));
        }
        AccessStore access = AccessStore.load(config.accessPath());
        UserSettingsStore users = UserSettingsStore.load(config.usersPath());
        CommandHandler handler = new CommandHandler(recipes, markets, guilds, users, access, new GameInfoClient());

        JDA jda = JDABuilder.createLight(config.token())
                .setActivity(Activity.watching("the Black Market | /price"))
                .addEventListeners(handler, new ListenerAdapter() {
                    @Override
                    public void onGuildJoin(GuildJoinEvent e) {
                        System.out.println("Joined server: " + e.getGuild().getName() + " (" + e.getGuild().getId() + ")");
                        // A dev guild invited after startup gets its commands right away, no restart needed.
                        if (config.devGuildIds().contains(e.getGuild().getIdLong())) {
                            e.getGuild().updateCommands().addCommands(handler.definitions()).queue();
                        }
                    }
                }).build();
        jda.awaitReady();

        if (config.devGuildIds().isEmpty()) {
            // Global commands work in every server the bot is invited to; changes can take up to ~1h to show.
            jda.updateCommands().addCommands(handler.definitions()).queue();
        } else {
            // Guild commands appear instantly: handy while testing.
            for (long id : config.devGuildIds()) {
                Guild guild = jda.getGuildById(id);
                if (guild == null) {
                    System.err.println("DEV_GUILD_ID " + id + ": bot is not in that server yet; commands will be added when it joins.");
                    continue;
                }
                guild.updateCommands().addCommands(handler.definitions()).queue();
            }
        }
        System.out.println(NAME + " ready as " + jda.getSelfUser().getName() + " in " + jda.getGuilds().size() + " server(s)");
    }
}
