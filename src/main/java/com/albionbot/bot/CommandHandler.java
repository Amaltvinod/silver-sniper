package com.albionbot.bot;

import com.albionbot.access.AccessStore;
import com.albionbot.access.GameInfoClient;
import com.albionbot.calc.Calculator;
import com.albionbot.calc.CraftResult;
import com.albionbot.calc.FlipResult;
import com.albionbot.calc.IngredientQuote;
import com.albionbot.calc.Settings;
import com.albionbot.calc.StaleScan;
import com.albionbot.config.Game;
import com.albionbot.config.GuildSettings;
import com.albionbot.config.GuildSettingsStore;
import com.albionbot.config.UserSettingsStore;
import com.albionbot.data.Ingredient;
import com.albionbot.data.Recipe;
import com.albionbot.data.RecipeBook;
import com.albionbot.market.Demand;
import com.albionbot.market.Freshness;
import com.albionbot.market.MarketClient;
import com.albionbot.market.PriceBook;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.awt.Color;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Slash commands: /price, /craft, /topcraft, /topflip, /scan, /settings, /mysettings, and the guild access commands (see {@link AccessCommands}). */
public final class CommandHandler extends ListenerAdapter {
    /** Thrown for problems the user can fix (bad item name, no data); shown as-is. */
    private static final class UserError extends RuntimeException {
        UserError(String msg) {
            super(msg);
        }
    }

    private final RecipeBook recipes;
    private final Map<String, MarketClient> markets;
    private final GuildSettingsStore guilds;
    private final UserSettingsStore users;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final AccessCommands accessCommands;

    /** @param markets one client per Albion server key (west, east, europe) */
    public CommandHandler(RecipeBook recipes, Map<String, MarketClient> markets, GuildSettingsStore guilds,
                          UserSettingsStore users, AccessStore access, GameInfoClient gameInfo) {
        this.recipes = recipes;
        this.markets = markets;
        this.guilds = guilds;
        this.users = users;
        this.accessCommands = new AccessCommands(access, guilds, gameInfo, workers, CommandHandler::serverLabel);
    }

    // ---------------------------------------------------------------- command definitions

    public List<CommandData> definitions() {
        List<CommandData> all = new ArrayList<>(List.of(
                Commands.slash("price", "Compare an item's price in every city and the Black Market")
                        .addOptions(itemOption(), tierOption(), enchantOption(), qualityOption(), serverOption()),
                Commands.slash("craft", "Cost to craft an item (best city per ingredient) vs Black Market price")
                        .addOptions(itemOption(), tierOption(), enchantOption(), rrrOption(), qualityOption(), serverOption()),
                Commands.slash("topcraft", "Most profitable items to craft and sell to the Black Market")
                        .addOptions(tierOption(), enchantOption(), categoryOption(), rrrOption(), qualityOption(),
                                new OptionData(OptionType.INTEGER, "top", "How many rows (default 10)").setRequiredRange(1, 20),
                                new OptionData(OptionType.STRING, "sort", "Rank by")
                                        .addChoice("Profit per item", "profit").addChoice("Return on investment", "roi")
                                        .addChoice("Profit x daily Black Market sales", "daily"),
                                serverOption()),
                Commands.slash("topflip", "Most profitable items to buy in a city and sell to the Black Market")
                        .addOptions(tierOption(), enchantOption(), categoryOption(), qualityOption(),
                                new OptionData(OptionType.INTEGER, "top", "How many rows (default 10)").setRequiredRange(1, 20),
                                new OptionData(OptionType.STRING, "sort", "Rank by")
                                        .addChoice("Profit per item", "profit")
                                        .addChoice("Profit x daily Black Market sales", "daily"),
                                serverOption()),
                Commands.slash("scan", "Out-of-date prices to refresh in game (with the Albion Data Client running)")
                        .addOptions(tierOption(), enchantOption(), categoryOption(), qualityOption(), serverOption()),
                Commands.slash("settings", "Configure " + AlbionBot.NAME + " for this server")
                        .setGuildOnly(true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                        .addSubcommands(
                                new SubcommandData("show", "Show this server's settings"),
                                new SubcommandData("set", "Change one or more settings (others stay as they are)")
                                        .addOptions(
                                                serverOption().setDescription("Albion server your members play on"),
                                                new OptionData(OptionType.BOOLEAN, "premium", "Default premium (4% tax, else 8%) for members who haven't used /mysettings"),
                                                new OptionData(OptionType.NUMBER, "station_fee", "Default station fee (silver/100 nutrition) for members without /mysettings")
                                                        .setRequiredRange(0, 100_000),
                                                new OptionData(OptionType.NUMBER, "max_age", "Ignore prices older than this many hours")
                                                        .setRequiredRange(1, 168),
                                                rrrOption().setDescription("Default resource return rate for /craft and /topcraft")),
                                new SubcommandData("reset", "Go back to the bot's default settings")),
                Commands.slash("mysettings", "Your own premium status and station fee, used in every server")
                        .addSubcommands(
                                new SubcommandData("show", "Show your settings"),
                                new SubcommandData("set", "Change your settings (others stay as they are)")
                                        .addOptions(
                                                new OptionData(OptionType.BOOLEAN, "premium", "Do you have premium? (4% market tax instead of 8%)"),
                                                new OptionData(OptionType.NUMBER, "station_fee", "Silver per 100 nutrition at the station you craft at")
                                                        .setRequiredRange(0, 100_000)),
                                new SubcommandData("reset", "Use this server's settings again"))));
        // Price commands are server-only, so a server's guild lock cannot be sidestepped in DMs.
        all.forEach(c -> c.setGuildOnly(true));
        all.addAll(accessCommands.definitions(serverOption()));
        return all;
    }

    private static OptionData serverOption() {
        return new OptionData(OptionType.STRING, "server", "Albion server (default: this Discord server's setting)")
                .addChoice("Americas (west)", "west")
                .addChoice("Asia (east)", "east")
                .addChoice("Europe", "europe");
    }

    private static OptionData itemOption() {
        return new OptionData(OptionType.STRING, "item", "Item name, e.g. Broadsword", true, true);
    }

    private static OptionData tierOption() {
        return new OptionData(OptionType.INTEGER, "tier", "Item tier (4-8)", true).setRequiredRange(4, 8);
    }

    private static OptionData enchantOption() {
        return new OptionData(OptionType.INTEGER, "enchant", "Enchantment level (default 0)").setRequiredRange(0, 4);
    }

    private static OptionData qualityOption() {
        return new OptionData(OptionType.INTEGER, "quality", "1 Normal ... 5 Masterpiece (default 1)").setRequiredRange(1, 5);
    }

    private static OptionData rrrOption() {
        return new OptionData(OptionType.STRING, "rrr", "Resource return rate (default: this server's setting)")
                .addChoice("No bonus (15.2%)", "none")
                .addChoice("City crafting bonus (24.8%)", "city")
                .addChoice("Focus (43.5%)", "focus")
                .addChoice("City crafting bonus + focus (47.9%)", "city_focus")
                .addChoice("Refining, bonus city (36.7%)", "refine_city")
                .addChoice("Refining, bonus city + focus (53.9%)", "refine_city_focus");
    }

    private static OptionData categoryOption() {
        OptionData o = new OptionData(OptionType.STRING, "category", "Limit to one gear category (default all)");
        for (String c : List.of("melee", "ranged", "magic", "offhand", "armor", "accessories")) {
            o.addChoice(c, c);
        }
        return o;
    }

    // ---------------------------------------------------------------- event wiring

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent e) {
        if (!e.getFocusedOption().getName().equals("item")) return;
        List<Command.Choice> choices = new ArrayList<>();
        for (Map.Entry<String, String> s : recipes.suggest(e.getFocusedOption().getValue(), 25)) {
            choices.add(new Command.Choice(Fmt.trunc(s.getValue(), 100), s.getKey()));
        }
        e.replyChoices(choices).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        if (e.getName().equals("settings")) {
            settingsCommand(e);
            return;
        }
        if (e.getName().equals("mysettings")) {
            mySettingsCommand(e);
            return;
        }
        // This server's settings, then the member's own settings, then the per-command server option (if given).
        GuildSettings base = users.apply(e.getUser().getIdLong(),
                guilds.get(e.getGuild() == null ? null : e.getGuild().getIdLong()));
        if (accessCommands.handle(e, base.server())) return;
        Optional<String> denied = accessCommands.denial(e);
        if (denied.isPresent()) {
            e.reply(denied.get()).setEphemeral(true).queue();
            return;
        }
        GuildSettings gs = base.withServer(strOpt(e, "server", base.server()));
        Supplier<MessageEmbed> action = switch (e.getName()) {
            case "price" -> () -> price(e, gs);
            case "craft" -> () -> craft(e, gs);
            case "topcraft" -> () -> topCraft(e, gs);
            case "topflip" -> () -> topFlip(e, gs);
            case "scan" -> () -> scan(e, gs);
            default -> null;
        };
        if (action == null) return;

        e.deferReply().queue();
        workers.submit(() -> {
            try {
                accessCommands.reverify(e);
                MessageEmbed embed = new EmbedBuilder(action.get())
                        .setAuthor(AlbionBot.NAME + " \u2022 " + serverLabel(gs.server()) + " server").build();
                e.getHook().sendMessageEmbeds(embed).queue();
            } catch (UserError | AccessCommands.UserException ex) {
                e.getHook().sendMessage(ex.getMessage()).queue();
            } catch (Exception ex) {
                ex.printStackTrace();
                e.getHook().sendMessage("Something went wrong talking to the market API: " + ex.getMessage()).queue();
            }
        });
    }

    // ---------------------------------------------------------------- /price

    private MessageEmbed price(SlashCommandInteractionEvent e, GuildSettings gs) {
        Recipe r = resolve(e);
        int q = intOpt(e, "quality", 1);
        List<String> locations = new ArrayList<>(Game.CITIES);
        locations.add(Game.BLACK_MARKET);
        PriceBook book = fetch(gs, List.of(r.itemId()), locations, List.of(q));

        // Cheapest city to buy in
        String cheapest = null;
        int cheapestPrice = Integer.MAX_VALUE;
        for (String city : Game.CITIES) {
            OptionalInt p = book.sell(r.itemId(), city, q);
            if (p.isPresent() && p.getAsInt() < cheapestPrice) {
                cheapestPrice = p.getAsInt();
                cheapest = city;
            }
        }

        List<List<String>> rows = new ArrayList<>();
        for (String loc : locations) {
            OptionalInt sell = book.sell(r.itemId(), loc, q);
            OptionalInt buy = book.buy(r.itemId(), loc, q);
            Optional<java.time.Duration> age = loc.equals(Game.BLACK_MARKET)
                    ? book.buyAge(r.itemId(), loc, q) : book.sellAge(r.itemId(), loc, q);
            rows.add(List.of(
                    (loc.equals(cheapest) ? "* " : "  ") + loc,
                    loc.equals(Game.BLACK_MARKET) ? "-" : sell.isPresent() ? Fmt.silver(sell.getAsInt()) : "-",
                    buy.isPresent() ? Fmt.silver(buy.getAsInt()) : "-",
                    Fmt.age(age)));
        }

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle(r.displayName() + " (quality " + q + ")")
                .setColor(Color.ORANGE)
                .setDescription(Fmt.code(Fmt.table(List.of("Market", "Sell", "Buy order", "Seen"), rows))
                        + "`Sell` = cheapest listing (what you pay). `Buy order` = highest bid (what you get)."
                        + " `*` = cheapest city. Rows older than " + (int) gs.maxAgeHours() + "h are hidden.");

        Settings s = new Settings(0, gs.salesTax(), 0);
        Demand d = demand(gs, List.of(r.itemId()), q).get(r.itemId());
        Optional<FlipResult> flip = Calculator.evaluateFlip(book, r.itemId(), Game.CITIES, q, s, d);
        if (flip.isPresent()) {
            FlipResult f = flip.get();
            eb.addField("Flip to Black Market",
                    "Buy in **" + f.buyCity() + "** for " + Fmt.silver(f.buyPrice())
                            + ", sell for " + (f.bmFromHistory() ? "~" : "") + Fmt.silver(f.blackMarketPrice())
                            + (f.bmFromHistory() ? " (7-day average sale, the buy order is old)" : "")
                            + " (" + Fmt.percent(gs.salesTax()) + " tax)\n"
                            + "**Profit " + Fmt.signed(f.profit()) + "** (" + Fmt.percent(f.roi()) + " ROI)", false);
        } else {
            eb.addField("Flip to Black Market", "Not enough data (need a city listing, and a Black Market buy order or recent sales).", false);
        }
        eb.addField("Black Market demand", d == null ? "No Black Market sales recorded in the last 7 days." : demandLine(d), false);

        List<String> warn = new ArrayList<>();
        OptionalInt bm = book.buy(r.itemId(), Game.BLACK_MARKET, q);
        Optional<Duration> bmAge = book.buyAge(r.itemId(), Game.BLACK_MARKET, q);
        if (bm.isPresent() && Freshness.stale(bmAge, Freshness.BLACK_MARKET)) warn.add("Black Market buy order is " + Fmt.age(bmAge) + " old.");
        if (cheapest != null && Freshness.stale(book.sellAge(r.itemId(), cheapest, q), Freshness.CITY_GEAR)) {
            warn.add(cheapest + " listing is " + Fmt.age(book.sellAge(r.itemId(), cheapest, q)) + " old.");
        }
        if (bm.isPresent() && d != null && d.isSpike(bm.getAsInt())) warn.add(spikeLine(bm.getAsInt(), d));
        if (!warn.isEmpty()) eb.addField("\u26a0 Check in game before trading", String.join("\n", warn), false);

        if (!r.ingredients().isEmpty()) {
            eb.addField("Materials to craft it (cheapest city each)", materialsTable(gs, r), false);
        }
        return eb.build();
    }

    /** Ingredient rows (name, qty, unit price, city) for quotes already worked out by the calculator. */
    private String quotesTable(List<IngredientQuote> quotes) {
        List<List<String>> rows = new ArrayList<>();
        for (IngredientQuote iq : quotes) {
            rows.add(materialRow(iq.ingredient(), Fmt.silver(iq.unitPrice()), shortCity(iq.city())));
        }
        return Fmt.code(Fmt.table(List.of("Material", "Qty", "Unit", "In"), rows)) + materialLegend(quotes.stream().map(IngredientQuote::ingredient).toList());
    }

    /** Material name (with {@code *} when the return rate doesn't refund it), quantity, unit price, city code. */
    private List<String> materialRow(Ingredient ing, String unit, String city) {
        return List.of(
                Fmt.trunc(recipes.display(ing.itemId()), 22) + (ing.returnable() ? "" : "*"),
                String.valueOf(ing.count()),
                unit,
                city);
    }

    /** Key for the city codes under a materials table, plus {@code *} when one of the materials uses it. */
    private static String materialLegend(List<Ingredient> ingredients) {
        String legend = CITY_LEGEND.strip();
        return ingredients.stream().anyMatch(i -> !i.returnable())
                ? legend + "\n`*` = not refunded by the return rate (artifacts and the like)."
                : legend;
    }

    /** Each ingredient of {@code r} with the city that sells it cheapest right now, plus the total for one craft. */
    private String materialsTable(GuildSettings gs, Recipe r) {
        PriceBook mats = fetch(gs, ingredientIds(List.of(r)), Game.CITIES, List.of(1));
        List<List<String>> rows = new ArrayList<>();
        double total = 0;
        boolean complete = true;
        for (Ingredient ing : r.ingredients()) {
            Optional<IngredientQuote> q = Calculator.cheapest(mats, ing, Game.CITIES);
            if (q.isPresent()) {
                total += (double) ing.count() * q.get().unitPrice();
                rows.add(materialRow(ing, Fmt.silver(q.get().unitPrice()), shortCity(q.get().city())));
            } else {
                complete = false;
                rows.add(materialRow(ing, "-", "-"));
            }
        }
        String note = complete
                ? "Buying all of it: **" + Fmt.silver(total) + "** before return rate. `/craft` shows profit."
                : "Some materials (`-`) have no fresh price in any city right now.";
        return Fmt.code(Fmt.table(List.of("Material", "Qty", "Unit", "In"), rows)) + note + "\n" + materialLegend(r.ingredients());
    }

    // ---------------------------------------------------------------- /craft

    private MessageEmbed craft(SlashCommandInteractionEvent e, GuildSettings gs) {
        Recipe r = resolve(e);
        int q = intOpt(e, "quality", 1);
        Settings s = settings(e, gs);

        PriceBook gear = fetch(gs, List.of(r.itemId()), List.of(Game.BLACK_MARKET), List.of(q));
        PriceBook mats = fetch(gs, ingredientIds(List.of(r)), Game.CITIES, List.of(1));
        PriceBook book = PriceBook.merge(gear, mats);

        Demand d = demand(gs, List.of(r.itemId()), q).get(r.itemId());
        Optional<CraftResult> result = Calculator.evaluateCraft(book, r, s, Game.CITIES, q, d);
        if (result.isEmpty()) {
            return new EmbedBuilder().setTitle(r.displayName())
                    .setColor(Color.RED)
                    .setDescription(explainMissing(book, r, q, d)).build();
        }
        CraftResult c = result.get();

        StringBuilder summary = new StringBuilder();
        summary.append(line("Materials (" + Fmt.percent(s.returnRate()) + " return)", Fmt.silver(c.materialCost())));
        summary.append(line("Station fee", Fmt.silver(c.stationFeeSilver())));
        summary.append(line("Total cost", Fmt.silver(c.totalCost())));
        summary.append(line(c.bmFromHistory() ? "Black Market (7-day avg sale)" : "Black Market buy order",
                Fmt.silver(c.blackMarketPrice())));
        summary.append(line("After " + Fmt.percent(s.salesTax()) + " tax", Fmt.silver(c.netRevenue())));
        summary.append(line("PROFIT", Fmt.signed(c.profit()) + "  (" + Fmt.percent(c.roi()) + " ROI)"));
        summary.append(line("BM sales per day", d == null ? "none recorded (7d)" : "~" + Fmt.perDay(d.perDay())));

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("Craft " + r.displayName() + " -> Black Market")
                .setColor(c.profit() > 0 ? Color.GREEN : Color.RED)
                .setDescription(quotesTable(c.quotes()) + Fmt.code(summary.toString()));

        if (!c.singleCityCost().isEmpty()) {
            StringBuilder one = new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, Double> en : c.singleCityCost().entrySet()) {
                if (shown++ == 3) break;
                one.append(en.getKey()).append(": ").append(Fmt.silver(en.getValue()))
                        .append(" (profit ").append(Fmt.signed(c.netRevenue() - en.getValue())).append(")\n");
            }
            eb.addField("Buying everything in one city", one.toString(), false);
        }

        List<String> warn = new ArrayList<>();
        Optional<Duration> bmAge = book.buyAge(r.itemId(), Game.BLACK_MARKET, q);
        if (c.bmFromHistory()) {
            warn.add((bmAge.isEmpty() ? "No recent Black Market buy order" : "Black Market buy order is " + Fmt.age(bmAge) + " old")
                    + ", so the price is the 7-day average sale. Check the order in game.");
        } else if (Freshness.stale(bmAge, Freshness.BLACK_MARKET)) {
            warn.add("Black Market buy order is " + Fmt.age(bmAge) + " old.");
        }
        for (IngredientQuote iq : c.quotes()) {
            Optional<Duration> age = book.sellAge(iq.ingredient().itemId(), iq.city(), 1);
            if (Freshness.stale(age, Freshness.MATERIALS)) warn.add(recipes.display(iq.ingredient().itemId()) + " in " + iq.city() + " is " + Fmt.age(age) + " old.");
        }
        if (!c.bmFromHistory() && d != null && d.isSpike(c.blackMarketPrice())) warn.add(spikeLine(c.blackMarketPrice(), d));
        if (d == null) warn.add("No Black Market sales recorded in the last 7 days: the order may not refill.");
        if (!warn.isEmpty()) eb.addField("\u26a0 Check in game before crafting", String.join("\n", warn), false);
        eb.setFooter("Station fee " + (int) gs.stationFee() + " silver/100 nutrition. Prices from the Albion Data Project, crowd-sourced.");
        return eb.build();
    }

    private String explainMissing(PriceBook book, Recipe r, int q, Demand sales) {
        if (Calculator.blackMarketPrice(book, r.itemId(), q, sales).isEmpty()) {
            return "No recent Black Market buy order or sales history for **" + r.displayName() + "** (quality " + q + ").\n"
                    + "Either nobody wants it right now or nobody running the data client has looked recently.";
        }
        List<String> missing = new ArrayList<>();
        for (Ingredient ing : r.ingredients()) {
            boolean any = Game.CITIES.stream().anyMatch(c -> book.sell(ing.itemId(), c, 1).isPresent());
            if (!any) missing.add(recipes.display(ing.itemId()));
        }
        return "No fresh price in any city for: " + String.join(", ", missing)
                + ".\nNote some artifacts are only sold on a few markets or not at all.";
    }

    private static String line(String label, String value) {
        return String.format("%-26s %s%n", label, value);
    }

    // ---------------------------------------------------------------- /topcraft

    private MessageEmbed topCraft(SlashCommandInteractionEvent e, GuildSettings gs) {
        int tier = (int) e.getOption("tier").getAsLong();
        int enchant = intOpt(e, "enchant", 0);
        int q = intOpt(e, "quality", 1);
        int top = intOpt(e, "top", 10);
        String sort = strOpt(e, "sort", "profit");
        boolean byRoi = sort.equals("roi");
        Settings s = settings(e, gs);

        List<Recipe> candidates = recipes.filter(tier, enchant, categories(e));
        if (candidates.isEmpty()) throw new UserError("No recipes found for T" + tier + "." + enchant + " in that category.");

        PriceBook gear = fetch(gs, candidates.stream().map(Recipe::itemId).toList(), List.of(Game.BLACK_MARKET), List.of(q));
        PriceBook mats = fetch(gs, ingredientIds(candidates), Game.CITIES, List.of(1));
        PriceBook book = PriceBook.merge(gear, mats);

        // Sales history first: it stands in for Black Market orders that are old or missing.
        Map<String, Demand> demand = demand(gs, candidates.stream().map(Recipe::itemId).toList(), q);
        List<CraftResult> results = new ArrayList<>();
        for (Recipe r : candidates) {
            Calculator.evaluateCraft(book, r, s, Game.CITIES, q, demand.get(r.itemId())).ifPresent(results::add);
        }
        Set<String> stale = new HashSet<>();
        for (CraftResult c : results) {
            if (!c.bmFromHistory() && Freshness.stale(book.buyAge(c.recipe().itemId(), Game.BLACK_MARKET, q), Freshness.BLACK_MARKET)
                    || c.quotes().stream().anyMatch(iq -> Freshness.stale(
                            book.sellAge(iq.ingredient().itemId(), iq.city(), 1), Freshness.MATERIALS))) {
                stale.add(c.recipe().itemId());
            }
        }
        Comparator<CraftResult> rank = switch (sort) {
            case "roi" -> Calculator.CRAFT_BY_ROI;
            case "daily" -> Comparator.comparingDouble((CraftResult c) -> -c.profit() * perDay(demand, c.recipe().itemId()));
            default -> Calculator.CRAFT_BY_PROFIT;
        };
        // Stale rows go after every fresh one instead of being hidden, so the list is never empty just because of age.
        results.sort(Comparator.comparing((CraftResult c) -> stale.contains(c.recipe().itemId())).thenComparing(rank));

        List<List<String>> rows = new ArrayList<>();
        for (CraftResult c : results.subList(0, Math.min(top, results.size()))) {
            boolean old = stale.contains(c.recipe().itemId());
            Demand d = demand.get(c.recipe().itemId());
            rows.add(List.of(
                    Fmt.trunc(c.recipe().baseName(), 14),
                    Fmt.silver(c.totalCost()),
                    Fmt.silver(c.blackMarketPrice()),
                    Fmt.signed(c.profit()),
                    Fmt.percent(c.roi()),
                    d == null ? "0" : Fmt.perDay(d.perDay()),
                    rowNote(old, c.bmFromHistory(), d, c.blackMarketPrice())));
        }
        int fresh = results.size() - stale.size();
        String body = rows.isEmpty()
                ? "No item had prices for both its ingredients and a Black Market buy order. Try `/scan` to see what to refresh."
                : staleNotice(fresh, results.size())
                        + Fmt.code(Fmt.table(List.of("Item", "Cost", "BM", "Profit", "ROI", "/day", "Note"), rows, 1)) + NOTE_LEGEND;
        String by = switch (sort) {
            case "roi" -> " (by ROI)";
            case "daily" -> " (by profit x daily sales)";
            default -> " (by profit)";
        };
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("Top crafts to Black Market: T" + tier + "." + enchant + by)
                .setColor(Color.GREEN)
                .setDescription(body);
        if (!results.isEmpty()) {
            CraftResult best = results.get(0);
            eb.addField("Where to buy materials for #1: " + Fmt.trunc(best.recipe().baseName(), 40), quotesTable(best.quotes()), false);
        }
        return eb
                .setFooter("Scanned " + candidates.size() + " recipes, " + results.size() + " with prices (" + fresh + " fresh). "
                        + Fmt.percent(s.returnRate()) + " return, " + Fmt.percent(s.salesTax()) + " tax. Use /craft for details.")
                .build();
    }

    // ---------------------------------------------------------------- /topflip

    private MessageEmbed topFlip(SlashCommandInteractionEvent e, GuildSettings gs) {
        int tier = (int) e.getOption("tier").getAsLong();
        int enchant = intOpt(e, "enchant", 0);
        int q = intOpt(e, "quality", 1);
        int top = intOpt(e, "top", 10);
        Settings s = new Settings(0, gs.salesTax(), 0);

        List<Recipe> candidates = recipes.filter(tier, enchant, categories(e));
        if (candidates.isEmpty()) throw new UserError("No items found for T" + tier + "." + enchant + " in that category.");

        List<String> locations = new ArrayList<>(Game.CITIES);
        locations.add(Game.BLACK_MARKET);
        PriceBook book = fetch(gs, candidates.stream().map(Recipe::itemId).toList(), locations, List.of(q));

        // Sales history first: it stands in for Black Market orders that are old or missing.
        Map<String, Demand> demand = demand(gs, candidates.stream().map(Recipe::itemId).toList(), q);
        List<Map.Entry<Recipe, FlipResult>> flips = new ArrayList<>();
        for (Recipe r : candidates) {
            Calculator.evaluateFlip(book, r.itemId(), Game.CITIES, q, s, demand.get(r.itemId()))
                    .ifPresent(f -> flips.add(Map.entry(r, f)));
        }
        boolean daily = "daily".equals(strOpt(e, "sort", "profit"));
        Set<String> stale = new HashSet<>();
        for (Map.Entry<Recipe, FlipResult> en : flips) {
            FlipResult f = en.getValue();
            if (!f.bmFromHistory() && Freshness.stale(book.buyAge(f.itemId(), Game.BLACK_MARKET, q), Freshness.BLACK_MARKET)
                    || Freshness.stale(book.sellAge(f.itemId(), f.buyCity(), q), Freshness.CITY_GEAR)) {
                stale.add(f.itemId());
            }
        }
        // Fresh flips first, then stale ones (flagged), each group by profit.
        flips.sort(Comparator.comparing((Map.Entry<Recipe, FlipResult> en) -> stale.contains(en.getValue().itemId()))
                .thenComparingDouble(en -> -en.getValue().profit() * (daily ? perDay(demand, en.getValue().itemId()) : 1)));

        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<Recipe, FlipResult> en : flips.subList(0, Math.min(top, flips.size()))) {
            FlipResult f = en.getValue();
            boolean old = stale.contains(f.itemId());
            Demand d = demand.get(f.itemId());
            rows.add(List.of(
                    Fmt.trunc(en.getKey().baseName(), 14),
                    shortCity(f.buyCity()),
                    Fmt.silver(f.buyPrice()),
                    Fmt.silver(f.blackMarketPrice()),
                    Fmt.signed(f.profit()),
                    d == null ? "0" : Fmt.perDay(d.perDay()),
                    rowNote(old, f.bmFromHistory(), d, f.blackMarketPrice())));
        }
        String body = rows.isEmpty()
                ? "No item had prices in both a city and the Black Market. Try `/scan` to see what to refresh."
                : staleNotice(flips.size() - stale.size(), flips.size())
                        + Fmt.code(Fmt.table(List.of("Item", "In", "Cost", "BM", "Profit", "/day", "Note"), rows, 1))
                        + NOTE_LEGEND + CITY_LEGEND;
        return new EmbedBuilder()
                .setTitle("Top Black Market flips: T" + tier + "." + enchant + (daily ? " (by profit x daily sales)" : ""))
                .setColor(Color.GREEN)
                .setDescription(body)
                .setFooter("Scanned " + candidates.size() + " items, " + flips.size() + " with fresh data. "
                        + Fmt.percent(s.salesTax()) + " tax.")
                .build();
    }

    // ---------------------------------------------------------------- /scan

    /** How many rows each /scan section shows. */
    private static final int SCAN_ROWS = 10;

    private MessageEmbed scan(SlashCommandInteractionEvent e, GuildSettings gs) {
        int tier = (int) e.getOption("tier").getAsLong();
        int enchant = intOpt(e, "enchant", 0);
        int q = intOpt(e, "quality", 1);

        List<Recipe> candidates = recipes.filter(tier, enchant, categories(e));
        if (candidates.isEmpty()) throw new UserError("No items found for T" + tier + "." + enchant + " in that category.");
        List<String> ids = candidates.stream().map(Recipe::itemId).toList();

        List<String> locations = new ArrayList<>(Game.CITIES);
        locations.add(Game.BLACK_MARKET);
        PriceBook gear = fetch(gs, ids, locations, List.of(q));
        PriceBook mats = fetch(gs, ingredientIds(candidates), Game.CITIES, List.of(1));
        Map<String, Demand> demand = demand(gs, ids, q);

        List<StaleScan.Entry> bm = StaleScan.blackMarket(gear, candidates, demand, q);
        List<StaleScan.Entry> city = StaleScan.cityGear(gear, candidates, demand, q);
        List<StaleScan.Entry> mat = StaleScan.materials(mats, candidates);
        int materialCount = ingredientIds(candidates).size();

        String summary = line("Black Market orders", bm.size() + " of " + candidates.size() + " old")
                + line("City gear listings", city.size() + " of " + candidates.size() + " old")
                + line("Materials", mat.size() + " of " + materialCount + " old");
        return new EmbedBuilder()
                .setTitle("Prices to refresh: T" + tier + "." + enchant + (q > 1 ? " quality " + q : ""))
                .setColor(bm.isEmpty() && city.isEmpty() && mat.isEmpty() ? Color.GREEN : Color.ORANGE)
                .setDescription("Open these in game with the [Albion Data Client](https://www.albion-online-data.com/) running: "
                        + "each price uploads as soon as you look at it. Most traded first.\n" + Fmt.code(summary))
                .addField("Black Market buy orders (old after 3h)", scanTable(bm, "/day"), false)
                .addField("City gear listings (old after 6h)", scanTable(city, "/day"), false)
                .addField("Materials (old after 24h)", scanTable(mat, "Used by"), false)
                .setFooter("Seen = age of the newest price the bot has. never = nobody has scanned it yet. "
                        + "/day = Black Market sales per day; Used by = how many of these recipes need it.")
                .build();
    }

    /** One /scan section: the most important {@link #SCAN_ROWS} entries, with a count of the rest. */
    private String scanTable(List<StaleScan.Entry> entries, String weightHeader) {
        if (entries.isEmpty()) return "All fresh.";
        List<List<String>> rows = new ArrayList<>();
        for (StaleScan.Entry en : entries.subList(0, Math.min(SCAN_ROWS, entries.size()))) {
            rows.add(List.of(
                    Fmt.trunc(recipes.display(en.itemId()), 24),
                    en.age().isEmpty() ? "never" : Fmt.age(en.age()),
                    weightHeader.equals("/day") ? (en.weight() == 0 ? "0" : Fmt.perDay(en.weight())) : String.valueOf((int) en.weight())));
        }
        String more = entries.size() > SCAN_ROWS ? "... and " + (entries.size() - SCAN_ROWS) + " more." : "";
        return Fmt.code(Fmt.table(List.of("Item", "Seen", weightHeader), rows)) + more;
    }

    // ---------------------------------------------------------------- /settings

    private void settingsCommand(SlashCommandInteractionEvent e) {
        long guildId = e.getGuild().getIdLong(); // guild-only command
        String sub = e.getSubcommandName() == null ? "show" : e.getSubcommandName();
        switch (sub) {
            case "set" -> {
                Double fee = e.getOption("station_fee") == null ? null : e.getOption("station_fee").getAsDouble();
                Double age = e.getOption("max_age") == null ? null : e.getOption("max_age").getAsDouble();
                Boolean premium = e.getOption("premium") == null ? null : e.getOption("premium").getAsBoolean();
                GuildSettingsStore.Overrides change = new GuildSettingsStore.Overrides(
                        strOpt(e, "server", null), premium, fee, age, strOpt(e, "rrr", null));
                if (change.equals(new GuildSettingsStore.Overrides(null, null, null, null, null))) {
                    e.reply("Pick at least one option to change, e.g. `/settings set server:Europe`.").setEphemeral(true).queue();
                    return;
                }
                guilds.update(guildId, change);
                e.replyEmbeds(settingsEmbed(guildId, "Settings updated")).queue();
            }
            case "reset" -> {
                guilds.reset(guildId);
                e.replyEmbeds(settingsEmbed(guildId, "Settings reset to defaults")).queue();
            }
            default -> e.replyEmbeds(settingsEmbed(guildId, "Settings")).setEphemeral(true).queue();
        }
    }

    private MessageEmbed settingsEmbed(long guildId, String title) {
        GuildSettings gs = guilds.get(guildId);
        GuildSettingsStore.Overrides set = guilds.overrides(guildId);
        StringBuilder b = new StringBuilder();
        b.append(line("Albion server", serverLabel(gs.server()) + mark(set.server())));
        b.append(line("Premium (market tax)", (gs.premium() ? "yes (" : "no (") + Fmt.percent(gs.salesTax()) + ")" + mark(set.premium())));
        b.append(line("Station fee", (int) gs.stationFee() + " silver/100 nutrition" + mark(set.stationFee())));
        b.append(line("Max price age", (int) gs.maxAgeHours() + "h" + mark(set.maxAgeHours())));
        b.append(line("Default return rate", gs.returnRate() + " (" + Fmt.percent(Game.RETURN_PRESETS.get(gs.returnRate())) + ")" + mark(set.returnRate())));
        return new EmbedBuilder()
                .setAuthor(AlbionBot.NAME)
                .setTitle(title)
                .setColor(Color.ORANGE)
                .setDescription(Fmt.code(b.toString()))
                .setFooter("* = set for this server, the rest are bot defaults. Change with /settings set. "
                        + "Members can set their own premium and station fee with /mysettings. "
                        + "Anyone can still check another region with the server option on a command.")
                .build();
    }

    // ---------------------------------------------------------------- /mysettings

    private void mySettingsCommand(SlashCommandInteractionEvent e) {
        long userId = e.getUser().getIdLong();
        String sub = e.getSubcommandName() == null ? "show" : e.getSubcommandName();
        switch (sub) {
            case "set" -> {
                Boolean premium = e.getOption("premium") == null ? null : e.getOption("premium").getAsBoolean();
                Double fee = e.getOption("station_fee") == null ? null : e.getOption("station_fee").getAsDouble();
                if (premium == null && fee == null) {
                    e.reply("Pick at least one option to change, e.g. `/mysettings set premium:False`.").setEphemeral(true).queue();
                    return;
                }
                users.update(userId, new UserSettingsStore.Overrides(premium, fee));
                e.replyEmbeds(mySettingsEmbed(e, "Your settings updated")).setEphemeral(true).queue();
            }
            case "reset" -> {
                users.reset(userId);
                e.replyEmbeds(mySettingsEmbed(e, "Your settings reset")).setEphemeral(true).queue();
            }
            default -> e.replyEmbeds(mySettingsEmbed(e, "Your settings")).setEphemeral(true).queue();
        }
    }

    private MessageEmbed mySettingsEmbed(SlashCommandInteractionEvent e, String title) {
        GuildSettings gs = users.apply(e.getUser().getIdLong(),
                guilds.get(e.getGuild() == null ? null : e.getGuild().getIdLong()));
        UserSettingsStore.Overrides set = users.get(e.getUser().getIdLong());
        StringBuilder b = new StringBuilder();
        b.append(line("Premium (market tax)", (gs.premium() ? "yes (" : "no (") + Fmt.percent(gs.salesTax()) + ")" + mark(set.premium())));
        b.append(line("Station fee", (int) gs.stationFee() + " silver/100 nutrition" + mark(set.stationFee())));
        return new EmbedBuilder()
                .setAuthor(AlbionBot.NAME)
                .setTitle(title)
                .setColor(Color.ORANGE)
                .setDescription(Fmt.code(b.toString()))
                .setFooter("* = your own setting, used in every server. The rest come from this server's settings. "
                        + "Change with /mysettings set.")
                .build();
    }

    private static String mark(Object override) {
        return override == null ? "" : " *";
    }

    private static String serverLabel(String server) {
        return switch (server) {
            case "west" -> "Americas";
            case "east" -> "Asia";
            case "europe" -> "Europe";
            default -> server.toUpperCase(Locale.ROOT);
        };
    }

    // ---------------------------------------------------------------- reliability

    private static final String NOTE_LEGEND = "`/day` = Black Market sales per day (7-day average). Note: "
            + "`O` = old price (" + Freshness.LIMITS + "), ranked after fresh rows. "
            + "`H` = Black Market order old or missing, so BM is its 7-day average sale. "
            + "`S` = spike (Black Market order far above its usual sale price), `R` = rare (under 1 sale/day).";
    /** Shown under /topflip: what the two-letter codes in the "In" column mean. */
    private static final String CITY_LEGEND = "\n`In` = city to buy in: BW Bridgewatch, BR Brecilien, CL Caerleon, "
            + "FS Fort Sterling, LH Lymhurst, ML Martlock, TF Thetford.";

    /** Black Market sales history; empty (never an error) if the history endpoint is unavailable. */
    private Map<String, Demand> demand(GuildSettings gs, Collection<String> ids, int quality) {
        if (ids.isEmpty()) return Map.of();
        try {
            return markets.get(gs.server()).demand(ids, Game.BLACK_MARKET, quality);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (IOException ex) {
            System.err.println("Sales history unavailable: " + ex.getMessage());
            return Map.of();
        }
    }

    private static double perDay(Map<String, Demand> demand, String itemId) {
        Demand d = demand.get(itemId);
        return d == null ? 0 : d.perDay();
    }

    /** A line above a top list when nothing in it is fresh; empty otherwise (stale rows are flagged `O` per row). */
    static String staleNotice(int fresh, int total) {
        return total > 0 && fresh == 0
                ? "**No fresh prices right now**, so these are the best stale ones (`O`). Run `/scan` to see what to refresh in game.\n"
                : "";
    }

    private static String demandLine(Demand d) {
        return "~**" + Fmt.perDay(d.perDay()) + "** sold per day at an average of " + Fmt.silver(d.avgPrice())
                + " (" + d.days() + " day" + (d.days() == 1 ? "" : "s") + " of data in the last week).";
    }

    private static String spikeLine(int price, Demand d) {
        return "Black Market order (" + Fmt.silver(price) + ") is " + Fmt.percent(price / d.avgPrice() - 1)
                + " above its recent average sale (" + Fmt.silver(d.avgPrice()) + "): probably a one-off.";
    }

    /**
     * One-letter flags for a table row (O old, H price from sales history, S spike, R rare); empty when nothing looks
     * off. A history price is the average itself, so it can't be a spike.
     */
    static String rowNote(boolean old, boolean fromHistory, Demand d, int bmPrice) {
        StringBuilder n = new StringBuilder();
        if (old) n.append('O');
        if (fromHistory) n.append('H');
        else if (d != null && d.isSpike(bmPrice)) n.append('S');
        if (d == null || d.perDay() < 1) n.append('R');
        return n.toString();
    }

    /** The usual two-letter city codes, so flip tables stay narrow enough not to wrap. */
    static String shortCity(String city) {
        return switch (city) {
            case "Fort Sterling" -> "FS";
            case "Bridgewatch" -> "BW";
            case "Brecilien" -> "BR";
            case "Caerleon" -> "CL";
            case "Lymhurst" -> "LH";
            case "Martlock" -> "ML";
            case "Thetford" -> "TF";
            default -> Fmt.trunc(city, 2);
        };
    }

    // ---------------------------------------------------------------- helpers

    private Recipe resolve(SlashCommandInteractionEvent e) {
        String item = e.getOption("item").getAsString();
        int tier = (int) e.getOption("tier").getAsLong();
        int enchant = intOpt(e, "enchant", 0);
        return recipes.find(item, tier, enchant).orElseThrow(() -> new UserError(
                "I can't find **" + item + "** at T" + tier + "." + enchant
                        + ". Pick a name from the autocomplete list, and check that tier/enchant exists for it."));
    }

    private static Settings settings(SlashCommandInteractionEvent e, GuildSettings gs) {
        double rrr = Game.RETURN_PRESETS.get(strOpt(e, "rrr", gs.returnRate()));
        return new Settings(rrr, gs.salesTax(), gs.stationFee());
    }

    private PriceBook fetch(GuildSettings gs, java.util.Collection<String> ids, java.util.Collection<String> locations,
                            java.util.Collection<Integer> qualities) {
        try {
            return markets.get(gs.server()).fetch(ids, locations, qualities, gs.maxAge());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UserError("Interrupted while fetching prices.");
        } catch (java.io.IOException ex) {
            throw new UserError("Couldn't reach the market API (" + Game.SERVERS.get(gs.server()) + "): " + ex.getMessage());
        }
    }

    private static Set<String> ingredientIds(List<Recipe> rs) {
        Set<String> ids = new LinkedHashSet<>();
        for (Recipe r : rs) r.ingredients().forEach(i -> ids.add(i.itemId()));
        return ids;
    }

    private static Set<String> categories(SlashCommandInteractionEvent e) {
        String c = strOpt(e, "category", "");
        return c.isEmpty() ? Game.BM_CATEGORIES : Set.of(c);
    }

    private static int intOpt(SlashCommandInteractionEvent e, String name, int dflt) {
        var m = e.getOption(name);
        return m == null ? dflt : (int) m.getAsLong();
    }

    private static String strOpt(SlashCommandInteractionEvent e, String name, String dflt) {
        var m = e.getOption(name);
        return m == null ? dflt : m.getAsString();
    }
}
