package com.albionbot.bot;

import com.albionbot.access.AccessStore;
import com.albionbot.access.GameInfoClient;
import com.albionbot.access.GameInfoClient.AlbionGuild;
import com.albionbot.access.GameInfoClient.AlbionPlayer;
import com.albionbot.config.GuildSettingsStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.awt.Color;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * Locking a Discord server to one Albion guild: /guild (admins) links it, /register lets members in after the
 * game-info API confirms their character is in that guild. Servers without a link stay open to everyone.
 */
final class AccessCommands {
    /** How long a membership check is trusted before it is repeated on the member's next command. */
    static final Duration REVERIFY_AFTER = Duration.ofHours(24);

    private final AccessStore access;
    /** Linking a guild sets the server's default Albion region to the guild's. */
    private final GuildSettingsStore guilds;
    private final GameInfoClient gameInfo;
    private final ExecutorService workers;
    /** Albion server key -> label, e.g. "east" -> "Asia". */
    private final Function<String, String> serverLabel;

    AccessCommands(AccessStore access, GuildSettingsStore guilds, GameInfoClient gameInfo, ExecutorService workers,
                   Function<String, String> serverLabel) {
        this.access = access;
        this.guilds = guilds;
        this.gameInfo = gameInfo;
        this.workers = workers;
        this.serverLabel = serverLabel;
    }

    List<CommandData> definitions(OptionData serverOption) {
        return List.of(
                Commands.slash("guild", "Limit " + AlbionBot.NAME + " in this server to members of one Albion guild")
                        .setGuildOnly(true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                        .addSubcommands(
                                new SubcommandData("link", "Only members of this Albion guild can use the bot here")
                                        .addOptions(
                                                new OptionData(OptionType.STRING, "name", "Exact Albion guild name", true),
                                                serverOption.setDescription("Albion server the guild is on (default: this server's setting)")),
                                new SubcommandData("unlink", "Open the bot to everyone in this server again"),
                                new SubcommandData("members", "Who registered, and as which character"),
                                new SubcommandData("remove", "Remove someone's registration")
                                        .addOptions(new OptionData(OptionType.USER, "user", "Member to remove", true))),
                Commands.slash("register", "Register your Albion character to use " + AlbionBot.NAME + " here")
                        .setGuildOnly(true)
                        .addOptions(new OptionData(OptionType.STRING, "character", "Your exact character name", true)),
                Commands.slash("unregister", "Remove your registration in this server")
                        .setGuildOnly(true));
    }

    /** Handles /guild, /register, /unregister. Returns false for any other command. */
    boolean handle(SlashCommandInteractionEvent e, String defaultServer) {
        switch (e.getName()) {
            case "guild" -> guild(e, defaultServer);
            case "register" -> register(e);
            case "unregister" -> unregister(e);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- access check

    /**
     * Quick check before a price command runs, without network calls. Empty means allowed; otherwise the message
     * to show (privately) to the member.
     */
    Optional<String> denial(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return Optional.empty();
        Optional<AccessStore.Link> link = access.link(e.getGuild().getIdLong());
        if (link.isEmpty() || isAdmin(e.getMember())) return Optional.empty();
        if (access.member(e.getGuild().getIdLong(), e.getUser().getIdLong()).isPresent()) return Optional.empty();
        return Optional.of("This server only lets members of the Albion guild **" + link.get().guildName() + "** use "
                + AlbionBot.NAME + ".\nRegister with `/register character:<your character name>`.");
    }

    /**
     * Repeats the guild check if the last one is older than {@link #REVERIFY_AFTER}. Runs on a worker thread.
     * Throws a user-facing error if the character has left the guild; if the API is unreachable, access stays.
     */
    void reverify(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null || isAdmin(e.getMember())) return;
        long gid = e.getGuild().getIdLong(), uid = e.getUser().getIdLong();
        Optional<AccessStore.Link> link = access.link(gid);
        Optional<AccessStore.Member> m = access.member(gid, uid);
        if (link.isEmpty() || m.isEmpty()) return;
        if (Instant.now().getEpochSecond() - m.get().verifiedAt() < REVERIFY_AFTER.toSeconds()) return;
        try {
            Optional<AlbionPlayer> p = gameInfo.player(link.get().server(), m.get().playerId());
            if (p.isPresent() && p.get().guildId().equals(link.get().guildId())) {
                access.putMember(gid, uid, new AccessStore.Member(p.get().id(), p.get().name(), Instant.now().getEpochSecond()));
                return;
            }
            access.removeMember(gid, uid);
            throw new UserException("**" + m.get().playerName() + "** is no longer in **" + link.get().guildName()
                    + "**, so your registration was removed. If you rejoined, run `/register` again.");
        } catch (IOException ex) {
            System.err.println("Guild re-check skipped (game-info API unreachable): " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Thrown from {@link #reverify}; its message is shown to the member as-is. */
    static final class UserException extends RuntimeException {
        UserException(String msg) {
            super(msg);
        }
    }

    private static boolean isAdmin(Member m) {
        return m != null && m.hasPermission(Permission.MANAGE_SERVER);
    }

    // ---------------------------------------------------------------- /guild

    private void guild(SlashCommandInteractionEvent e, String defaultServer) {
        long gid = e.getGuild().getIdLong();
        String sub = e.getSubcommandName() == null ? "members" : e.getSubcommandName();
        switch (sub) {
            case "link" -> {
                String name = e.getOption("name").getAsString().trim();
                String server = e.getOption("server") == null ? defaultServer : e.getOption("server").getAsString();
                e.deferReply().queue();
                workers.submit(() -> {
                    try {
                        List<AlbionGuild> found = gameInfo.findGuilds(server, name);
                        if (found.isEmpty()) {
                            e.getHook().sendMessage("No guild called **" + name + "** on the " + serverLabel.apply(server)
                                    + " server. Check the spelling, or pick the right `server`.").queue();
                            return;
                        }
                        AlbionGuild g = found.get(0);
                        access.setLink(gid, new AccessStore.Link(server, g.id(), g.name(), Instant.now().getEpochSecond()));
                        guilds.update(gid, new GuildSettingsStore.Overrides(server, null, null, null, null));
                        e.getHook().sendMessageEmbeds(new EmbedBuilder()
                                .setAuthor(AlbionBot.NAME)
                                .setTitle("Linked to " + g.name())
                                .setColor(Color.ORANGE)
                                .setDescription("Only members of **" + g.name() + "** (" + serverLabel.apply(server)
                                        + " server) can use " + AlbionBot.NAME + " here now.\n"
                                        + "Prices now default to the " + serverLabel.apply(server)
                                        + " market (change with `/settings set server`).\n\n"
                                        + "Members: register with `/register character:<your character name>`.\n"
                                        + "Admins (Manage Server) always have access. `/guild unlink` opens it to everyone again.")
                                .build()).queue();
                    } catch (Exception ex) {
                        e.getHook().sendMessage(apiError(ex)).queue();
                    }
                });
            }
            case "unlink" -> {
                access.unlink(gid);
                e.reply(AlbionBot.NAME + " is open to everyone in this server again. Registrations were cleared.").queue();
            }
            case "remove" -> {
                var user = e.getOption("user").getAsUser();
                boolean removed = access.removeMember(gid, user.getIdLong());
                e.reply(removed ? "Removed " + user.getAsMention() + "'s registration."
                                : user.getAsMention() + " isn't registered here.")
                        .setEphemeral(true).queue();
            }
            default -> e.replyEmbeds(membersEmbed(gid)).setEphemeral(true).queue();
        }
    }

    private net.dv8tion.jda.api.entities.MessageEmbed membersEmbed(long gid) {
        Optional<AccessStore.Link> link = access.link(gid);
        EmbedBuilder eb = new EmbedBuilder().setAuthor(AlbionBot.NAME).setColor(Color.ORANGE);
        if (link.isEmpty()) {
            return eb.setTitle("Not linked").setDescription("Everyone in this server can use the bot. "
                    + "Use `/guild link name:<guild>` to limit it to one Albion guild.").build();
        }
        List<Map.Entry<Long, AccessStore.Member>> members = access.members(gid);
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (Map.Entry<Long, AccessStore.Member> m : members) {
            if (shown++ == 40) {
                b.append("... and ").append(members.size() - 40).append(" more\n");
                break;
            }
            b.append("<@").append(m.getKey()).append("> as **").append(m.getValue().playerName())
                    .append("**, checked <t:").append(m.getValue().verifiedAt()).append(":R>\n");
        }
        return eb.setTitle(link.get().guildName() + " (" + serverLabel.apply(link.get().server()) + "): "
                        + members.size() + " registered")
                .setDescription(members.isEmpty() ? "Nobody has registered yet." : b.toString())
                .setFooter("Remove someone with /guild remove. Albion can confirm a character is in the guild, "
                        + "not who owns it, so check the list if something looks off.")
                .build();
    }

    // ---------------------------------------------------------------- /register, /unregister

    private void register(SlashCommandInteractionEvent e) {
        long gid = e.getGuild().getIdLong(), uid = e.getUser().getIdLong();
        Optional<AccessStore.Link> link = access.link(gid);
        if (link.isEmpty()) {
            e.reply("This server isn't limited to a guild, so you can already use " + AlbionBot.NAME + ".")
                    .setEphemeral(true).queue();
            return;
        }
        String name = e.getOption("character").getAsString().trim();
        AccessStore.Link l = link.get();
        e.deferReply(true).queue();
        workers.submit(() -> {
            try {
                List<AlbionPlayer> found = gameInfo.findPlayers(l.server(), name);
                Optional<AlbionPlayer> inGuild = found.stream().filter(p -> p.guildId().equals(l.guildId())).findFirst();
                if (inGuild.isEmpty()) {
                    String why = found.isEmpty()
                            ? "I can't find a character called **" + name + "** on the " + serverLabel.apply(l.server()) + " server."
                            : "**" + found.get(0).name() + "** is " + (found.get(0).guildName().isEmpty()
                                    ? "not in a guild" : "in **" + found.get(0).guildName() + "**")
                                    + ", not **" + l.guildName() + "**.";
                    e.getHook().sendMessage(why + " Albion's lookup can lag a little after joining a guild; try again later if you just joined.").queue();
                    return;
                }
                AlbionPlayer p = inGuild.get();
                Optional<Long> owner = access.ownerOf(gid, p.id());
                if (owner.isPresent() && owner.get() != uid) {
                    e.getHook().sendMessage("**" + p.name() + "** is already registered by <@" + owner.get()
                            + ">. If that's wrong, ask an admin to run `/guild remove`.").queue();
                    return;
                }
                access.putMember(gid, uid, new AccessStore.Member(p.id(), p.name(), Instant.now().getEpochSecond()));
                e.getHook().sendMessage("Registered as **" + p.name() + "** of **" + l.guildName()
                        + "**. You can use " + AlbionBot.NAME + " here now.").queue();
            } catch (Exception ex) {
                e.getHook().sendMessage(apiError(ex)).queue();
            }
        });
    }

    private void unregister(SlashCommandInteractionEvent e) {
        boolean removed = access.removeMember(e.getGuild().getIdLong(), e.getUser().getIdLong());
        e.reply(removed ? "Your registration here was removed." : "You weren't registered here.")
                .setEphemeral(true).queue();
    }

    private static String apiError(Exception ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        else ex.printStackTrace();
        return "Albion's player lookup didn't answer (" + ex.getMessage() + "). It's slow at times; please try again in a minute.";
    }
}
