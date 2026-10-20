# Silver Sniper

A Discord bot (Java 21, JDA) that reads crowd-sourced prices from the [Albion Online Data Project](https://www.albion-online-data.com/) and answers:

| Command | What it does |
|---|---|
| `/price item tier [enchant] [quality]` | Price in every city + Black Market, cheapest city to buy in, and the profit if you flip it to the Black Market |
| `/craft item tier [enchant] [rrr] [quality]` | Ingredient list with the cheapest city for each one, station fee, Black Market price, profit and ROI. Also shows the cost of buying everything in a single city |
| `/topcraft tier [enchant] [category] [rrr] [sort]` | Ranks every craftable gear item at that tier by profit (or ROI) when sold to Black Market buy orders |
| `/topflip tier [enchant] [category]` | Ranks items you can buy in a city and sell to the Black Market for more |
| `/scan tier [enchant] [category]` | Lists out-of-date prices (Black Market orders, city gear, materials), most traded first, so your guild can refresh them in game with the [Albion Data Client](https://www.albion-online-data.com/) running |
| `/settings show \| set \| reset` | Per-server configuration (needs **Manage Server**): Albion server, premium tax, station fee, max price age, default return rate |
| `/mysettings show \| set \| reset` | Your own premium status and station fee. Used in every server, and wins over the server's settings |

Every price command also takes an optional `server` option (Americas / Asia / Europe) to check another region without changing the server's setting.

## Limiting a server to one Albion guild

| Command | Who | What it does |
|---|---|---|
| `/guild link name [server]` | Manage Server | Only members of that Albion guild can use the bot in this Discord server. Also sets this server's default Albion server to the guild's |
| `/register character` | Anyone | Checks with Albion's official game-info API that the character is in the linked guild, then grants access |
| `/unregister` | Anyone | Removes your own registration |
| `/guild members` / `/guild remove user` | Manage Server | See who registered as which character; remove someone |
| `/guild unlink` | Manage Server | Open the bot to everyone again (clears registrations) |

Admins with Manage Server always have access. A character can be registered by one Discord user per server. Membership is re-checked against the game-info API once a day, on the member's next command; if the API is down the member keeps access. Albion's API confirms a character is in the guild, not who owns it, so `/guild members` is there to spot anyone registered as someone else. Servers that never run `/guild link` stay open to everyone. Price commands only work inside servers (not DMs), so the lock cannot be sidestepped.

## Multiple Discord servers

The bot can be invited to any number of Discord servers. Each one keeps its own settings, saved in `data/guilds.json`; anything a server has not set falls back to the environment variables below. Commands are registered globally, so they appear in every server the bot joins (Discord can take up to an hour the first time). One market client per Albion server is shared by all Discord servers, so prices fetched for one are cached for the rest.

## Setup

1. **Create the bot**: Discord Developer Portal -> New Application -> Bot -> copy the token. Under OAuth2 -> URL Generator pick scopes `bot` and `applications.commands`, then open the URL to invite it. No privileged intents needed.
2. **Build**: `mvn package` (runs the tests, produces `target/albion-bot.jar`).
3. **Build the recipe file** (one time, and again after big game patches):
   ```
   java -cp target/albion-bot.jar com.albionbot.data.BuildRecipes
   ```
   This downloads `items.json` and `formatted/items.json` from [ao-bin-dumps](https://github.com/ao-data/ao-bin-dumps) and writes `data/recipes.json`. If you already have the two files, pass `--items items.json --names names.json`. It needs roughly 1 GB of heap for the large dump (`java -Xmx1g ...`).
4. **Configure**: set the variables in `.env.example` as real environment variables. At minimum `DISCORD_TOKEN`. `ALBION_SERVER`, `PREMIUM`, `STATION_FEE` and `MAX_DATA_AGE_HOURS` are defaults that each Discord server can override with `/settings`. Set `DEV_GUILD_ID` (one ID or a comma-separated list) while testing so commands appear instantly; leave it empty in production so commands register globally.
5. **Run**: `java -jar target/albion-bot.jar`

## How profit is calculated

```
material cost = sum(count x cheapest city price) x (1 - return rate)   // artifacts are not refunded
station fee   = item value x 0.1125 x STATION_FEE / 100
net revenue   = Black Market highest buy order x (1 - sales tax)       // 4% premium, 8% otherwise
profit        = net revenue - material cost - station fee
```

Prices count as old after **3h** for Black Market orders, **6h** for city gear listings and **24h** for materials. Top lists rank old rows (flagged `O`) after fresh ones instead of hiding them, so a list is never empty just because the data is old.

When a Black Market order is old or missing, the bot prices the sale at the item's 7-day average Black Market sale instead (flagged `H`), as long as there are at least 2 days of sales. If an old order is lower than the average, the old order is kept, so the estimate never makes an existing order look better than it is.

Ingredients are priced as instant buys (cheapest sell order). Enchanted gear is priced with enchanted resources (`T4_METALBAR_LEVEL1@1` etc.). Rows older than `MAX_DATA_AGE_HOURS` (default 24) are ignored, so a missing price usually means nobody has scanned that market recently.

## Things to check on first run

I could not reach the Albion API, GitHub or Maven Central from where this was written, so these were never run against live data:

- **Return rates**: gear crafting uses 15.2% (no bonus), 24.8% (city crafting bonus), 43.5% (focus) and 47.9% (city + focus); refining in a resource's bonus city uses 36.7% / 53.9%. These follow the wiki's production bonuses (18% base, +15% crafting city, +40% refining city, +59% focus). Hideouts and islands differ.
- **Station fee** defaults to a placeholder of 500 silver per 100 nutrition. Set `STATION_FEE` to what your crafting station shows. The item-value input for enchanted items is the base item's value, so their fee is approximate.
- **Recipe parsing** is tested against a miniature dump with the same shape, not the real file. After building, `/craft` on a known item is a good sanity check against the in-game recipe. Only the first recipe variant of each item is used.
- **Black Market demand** is not in this data. A high buy order may only want a few items, so a large profit on paper can be a small real opportunity.
- Prices are crowd-sourced and can be wrong or stale; the "Seen" column shows how old each row is.

## Project layout

```
access/   AccessStore (guild links + registrations), GameInfoClient (Albion's official player/guild lookup)
config/   Config (env vars), GuildSettings + GuildSettingsStore (per-server settings), Game (cities, return-rate presets, BM categories)
data/     RecipeBuilder + BuildRecipes (game dumps -> recipes.json), RecipeBook (lookup/autocomplete)
market/   MarketClient (HTTP, chunking, throttling, cache), PriceBook (freshness filtering)
calc/     Calculator (all the profit maths, no I/O)
bot/      AlbionBot (entry point), CommandHandler (slash commands), Fmt (tables)
```
