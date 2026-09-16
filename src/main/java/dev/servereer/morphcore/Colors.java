package dev.servereer.morphcore;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legacy {@code &}-code + {@code &#RRGGBB} hex color translation, self-contained so {@link MorphFx} never
 * needs a callback into a consumer plugin just to colorize a title/boss-bar string.
 */
public final class Colors {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private Colors() { }

    public static String translate(String s) {
        return ChatColor.translateAlternateColorCodes('&', expandHex(s));
    }

    private static String expandHex(String s) {
        if (s == null || s.indexOf("&#") < 0) return s;
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder(s.length() + 16);
        while (m.find()) {
            StringBuilder rep = new StringBuilder("&x");
            for (char c : m.group(1).toCharArray()) rep.append('&').append(c);
            m.appendReplacement(sb, rep.toString());
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
