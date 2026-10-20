package com.albionbot.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Downloads the ao-bin-dumps game data and writes data/recipes.json.
 *
 * <pre>
 * java -cp target/albion-bot.jar com.albionbot.data.BuildRecipes [--out data/recipes.json]
 *                                                              [--items items.json --names names.json]
 * </pre>
 * Pass --items/--names to use files you already downloaded instead of fetching.
 */
public final class BuildRecipes {
    private static final String BASE = "https://raw.githubusercontent.com/ao-data/ao-bin-dumps/master/";

    public static void main(String[] args) throws Exception {
        Path out = Path.of("data/recipes.json");
        Path items = null;
        Path names = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[i + 1]);
                case "--items" -> items = Path.of(args[i + 1]);
                case "--names" -> names = Path.of(args[i + 1]);
                default -> { }
            }
        }
        ObjectMapper om = new ObjectMapper();
        JsonNode itemsJson = items != null ? om.readTree(items.toFile()) : om.readTree(download("items.json"));
        JsonNode namesJson = names != null ? om.readTree(names.toFile()) : om.readTree(download("formatted/items.json"));

        RecipeFile file = RecipeBuilder.build(itemsJson, namesJson);
        Files.createDirectories(out.toAbsolutePath().getParent());
        om.enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), file);
        System.out.println("Wrote " + file.recipes().size() + " recipes to " + out);
    }

    private static byte[] download(String path) throws Exception {
        System.out.println("Downloading " + path + " ...");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofMinutes(5)).build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("GET " + BASE + path + " returned HTTP " + res.statusCode());
        }
        return res.body();
    }
}
