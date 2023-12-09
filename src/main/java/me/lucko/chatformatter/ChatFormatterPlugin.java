package me.lucko.chatformatter;

import me.squeeglii.plugin.dislink.display.VerifierPrefixes;
import me.squeeglii.plugin.dislink.storage.LinkedAccount;
import me.squeeglii.plugin.dislink.storage.LinkedAccountCache;
import net.milkbowl.vault.chat.Chat;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A super simple chat formatting plugin using Vault.
 */
public class ChatFormatterPlugin extends JavaPlugin implements Listener {

    // Format placeholders
    private static final String NAME_PLACEHOLDER = "{name}";
    private static final String DISPLAYNAME_PLACEHOLDER = "{displayname}";
    private static final String MESSAGE_PLACEHOLDER = "{message}";
    private static final String PREFIX_PLACEHOLDER = "{prefix}";
    private static final String SUFFIX_PLACEHOLDER = "{suffix}";
    private static final String DISLINK_VERIFIER_PLACEHOLDER = "{dislink_verifier}";

    // Format placeholder patterns
    private static final Pattern NAME_PLACEHOLDER_PATTERN = Pattern.compile(NAME_PLACEHOLDER, Pattern.LITERAL);
    private static final Pattern PREFIX_PLACEHOLDER_PATTERN = Pattern.compile(PREFIX_PLACEHOLDER, Pattern.LITERAL);
    private static final Pattern SUFFIX_PLACEHOLDER_PATTERN = Pattern.compile(SUFFIX_PLACEHOLDER, Pattern.LITERAL);
    private static final Pattern DISLINK_VERIFIER_PLACEHOLDER_PATTERN = Pattern.compile(DISLINK_VERIFIER_PLACEHOLDER, Pattern.LITERAL);

    /** The default format */
    private static final String DEFAULT_FORMAT = "<" + PREFIX_PLACEHOLDER + NAME_PLACEHOLDER + SUFFIX_PLACEHOLDER + "> " + MESSAGE_PLACEHOLDER;

    /** Pattern matching "nicer" legacy hex chat color codes - &#rrggbb */
    private static final Pattern NICER_HEX_COLOR_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");

    /** The format used by this chat formatter instance */
    private String format;

    /**
     * The current Vault chat implementation registered on the server.
     * Automatically updated as new services are registered.
     */
    private Chat vaultChat = null;

    private VerifierPrefixes verifierPrefixes = null;
    private LinkedAccountCache linkedAccountCache = null;


    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.reloadConfigValues();

        this.refreshVault();
        this.refreshDislink();

        this.getServer().getPluginManager().registerEvents(this, this);
    }

    private void reloadConfigValues() {
        // Replace the {displayname} and {message} placeholders used in our format with
        // the String.format tokens used by Bukkit.
        this.format = colorize(getConfig().getString("format", DEFAULT_FORMAT)
                .replace(DISPLAYNAME_PLACEHOLDER, "%1$s")
                .replace(MESSAGE_PLACEHOLDER, "%2$s"));
    }

    private void refreshVault() {
        Chat newImpl = getServer().getServicesManager().load(Chat.class);

        if (newImpl != this.vaultChat) {
            this.getLogger().info("New Vault Chat implementation registered: " + (newImpl == null ? "null" : newImpl.getName()));
        }

        this.vaultChat = newImpl;
    }

    private void refreshDislink() {
        LinkedAccountCache accountCache = getServer().getServicesManager().load(LinkedAccountCache.class);
        VerifierPrefixes prefixRegistry = getServer().getServicesManager().load(VerifierPrefixes.class);

        if (accountCache != this.linkedAccountCache) {
            this.getLogger().info("Linked Account Cache source changed!");
        }

        if (prefixRegistry != this.verifierPrefixes) {
            this.getLogger().info("Verifier Prefix Registry source changed!");
        }

        this.linkedAccountCache = accountCache;
        this.verifierPrefixes = prefixRegistry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 0 && args[0].equalsIgnoreCase("reload")) {
            this.reloadConfig();
            this.reloadConfigValues();

            sender.sendMessage("Reloaded successfully.");
            return true;
        }

        return false;
    }

    @EventHandler
    public void onServiceChange(ServiceRegisterEvent e) {
        if (e.getProvider().getService() == Chat.class)
            this.refreshVault();

        boolean hasDislinkUpdated = e.getProvider().getService() == LinkedAccountCache.class ||
                                    e.getProvider().getService() == VerifierPrefixes.class;

        if(hasDislinkUpdated)
            this.refreshDislink();
    }

    @EventHandler
    public void onServiceChange(ServiceUnregisterEvent e) {
        if (e.getProvider().getService() == Chat.class)
            this.refreshVault();

        boolean hasDislinkUpdated = e.getProvider().getService() == LinkedAccountCache.class ||
                                    e.getProvider().getService() == VerifierPrefixes.class;

        if(hasDislinkUpdated)
            this.refreshDislink();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatLow(AsyncPlayerChatEvent e) {
        // Set out format on the lowest priority - allow other plugins to override or add their own parts.
        e.setFormat(this.format);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChatHigh(AsyncPlayerChatEvent e) {
        // Replace our placeholders on highest - just before
        String format = e.getFormat();

        if (this.vaultChat != null) {
            format = replaceAll(PREFIX_PLACEHOLDER_PATTERN, format, () -> colorize(this.vaultChat.getPlayerPrefix(e.getPlayer())));
            format = replaceAll(SUFFIX_PLACEHOLDER_PATTERN, format, () -> colorize(this.vaultChat.getPlayerSuffix(e.getPlayer())));
        }

        processDislinkFormatting(format, e.getPlayer().getUniqueId());



        format = replaceAll(NAME_PLACEHOLDER_PATTERN, format, () -> e.getPlayer().getName());

        e.setFormat(format);
    }

    private String processDislinkFormatting(String format, UUID playerId) {
        if(this.verifierPrefixes == null) return format;
        if(this.linkedAccountCache == null) return format;

        Optional<LinkedAccount> optLink = this.linkedAccountCache.getAccount(playerId);

        if(optLink.isEmpty())
            return format;

        LinkedAccount account = optLink.get();
        String verifier = account.verifier();
        String verifierPrefix = this.verifierPrefixes.getPrefixForVerifier(verifier).orElse("");

        if(verifierPrefix.isEmpty())
            return format;

        return replaceAll(
                DISLINK_VERIFIER_PLACEHOLDER_PATTERN, format,
                () -> colorize(verifierPrefix) + " "
        );
    }

    /**
     * Equivalent to {@link String#replace(CharSequence, CharSequence)}, but uses a
     * {@link Supplier} for the replacement.
     *
     * @param pattern the pattern for the replacement target
     * @param input the input string
     * @param replacement the replacement
     * @return the input string with the replacements applied
     */
    private static String replaceAll(Pattern pattern, String input, Supplier<String> replacement) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.replaceAll(Matcher.quoteReplacement(replacement.get()));
        }
        return input;
    }

    /**
     * Translates color codes in the given input string.
     *
     * @param string the string to "colorize"
     * @return the colorized string
     */
    private static String colorize(String string) {
        if (string == null) {
            return "null";
        }

        // Convert from the '&#rrggbb' hex color format to the '&x&r&r&g&g&b&b' one used by Bukkit.
        Matcher matcher = NICER_HEX_COLOR_PATTERN.matcher(string);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder(14).append("&x");
            for (char character : matcher.group(1).toCharArray()) {
                replacement.append('&').append(character);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);

        // Translate from '&' to '§' (section symbol)
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

}
