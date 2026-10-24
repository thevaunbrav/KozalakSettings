package tr.kozalaksmp.settings;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class KozalakSettings extends JavaPlugin implements Listener {
    private volatile boolean chatDebug = false;
    private File preferencesFile;
    private YamlConfiguration preferences;

    private enum Menu { MAIN, CHAT, NOTIFICATIONS }

    private static final String[] NOTIFICATION_KEYS = {"teleport", "money", "combat", "team", "auction", "crate", "announcements"};
    private static final String[] NOTIFICATION_NAMES = {"TPA Istekleri", "Para", "Savas", "Takim", "Acik Artirma", "Kasa", "Duyurular"};
    private static final Material[] NOTIFICATION_ICONS = {Material.ENDER_PEARL, Material.GOLD_INGOT, Material.IRON_SWORD, Material.WHITE_BANNER, Material.GOLDEN_HORSE_ARMOR, Material.CHEST, Material.BELL};
    private static final int[] NOTIFICATION_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private static final class MenuHolder implements InventoryHolder {
        private final Menu type;
        private Inventory inventory;
        private MenuHolder(Menu type) { this.type = type; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    @Override public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Plugin klasoru olusturulamadi.");
        }
        preferencesFile = new File(getDataFolder(), "players.yml");
        preferences = YamlConfiguration.loadConfiguration(preferencesFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("KozalakSettings hazir.");
    }

    @Override public void onDisable() { savePreferences(); }

    private boolean publicChatEnabled(UUID uuid) {
        return preferences.getBoolean("players." + uuid + ".public-chat", true);
    }

    private void setPublicChat(UUID uuid, boolean enabled) {
        preferences.set("players." + uuid + ".public-chat", enabled);
        savePreferences();
    }

    private void savePreferences() {
        try { preferences.save(preferencesFile); }
        catch (IOException ex) { getLogger().log(Level.SEVERE, "Oyuncu ayarlari kaydedilemedi", ex); }
    }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut sadece oyuncular icindir.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            if (!player.isOp()) {
                player.sendMessage(Component.text("Bu komut icin OP gerekli.", NamedTextColor.RED));
                return true;
            }
            chatDebug = !chatDebug;
            player.sendMessage(Component.text("Chat debug: " + (chatDebug ? "ACIK" : "KAPALI"), NamedTextColor.YELLOW));
            player.sendMessage(Component.text("Genel Sohbet ayarin: " + (publicChatEnabled(player.getUniqueId()) ? "ACIK" : "KAPALI"), NamedTextColor.YELLOW));
            return true;
        }
        openMain(player);
        return true;
    }

    private ItemStack button(Material material, String title, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.AQUA));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private Inventory menu(Menu type, int size, String title) {
        MenuHolder holder = new MenuHolder(type);
        Inventory inv = Bukkit.createInventory(holder, size, Component.text(title));
        holder.inventory = inv;
        return inv;
    }

    private void openMain(Player player) {
        Inventory inv = menu(Menu.MAIN, 54, "Kozalak SMP | Ayarlar");
        inv.setItem(22, button(Material.OAK_SIGN, "Sohbet", "Sohbet ayarlarini yonet"));
        inv.setItem(24, button(Material.BELL, "Bildirimler", "Bildirim tercihlerini yonet"));
        player.openInventory(inv);
    }

    private void openChat(Player player) {
        Inventory inv = menu(Menu.CHAT, 27, "Kozalak SMP | Sohbet");
        updateChatButton(inv, player);
        inv.setItem(18, button(Material.ARROW, "Geri", "Ana menuye don"));
        player.openInventory(inv);
    }

    private void updateChatButton(Inventory inv, Player player) {
        boolean enabled = publicChatEnabled(player.getUniqueId());
        inv.setItem(13, button(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                enabled ? "Public Chat: ACIK" : "Public Chat: KAPALI",
                "Diger oyuncularin genel sohbet mesajlarini goster/gizle"));
    }

    private boolean notificationEnabled(UUID uuid, String key) {
        return preferences.getBoolean("players." + uuid + ".notifications." + key, true);
    }

    private void openNotifications(Player player) {
        Inventory inv = menu(Menu.NOTIFICATIONS, 27, "Kozalak SMP | Bildirimler");
        for (int i = 0; i < NOTIFICATION_KEYS.length; i++) {
            updateNotificationButton(inv, player, i);
        }
        inv.setItem(18, button(Material.ARROW, "Geri", "Ana menuye don"));
        player.openInventory(inv);
    }

    private void updateNotificationButton(Inventory inv, Player player, int index) {
        boolean enabled = notificationEnabled(player.getUniqueId(), NOTIFICATION_KEYS[index]);
        inv.setItem(NOTIFICATION_SLOTS[index], button(NOTIFICATION_ICONS[index],
                NOTIFICATION_NAMES[index] + ": " + (enabled ? "ACIK" : "KAPALI"),
                "Tercih kaydedilir; bildirim entegrasyonu henuz yok"));
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (holder.type == Menu.MAIN && slot == 22) {
            openChat(player);
        } else if (holder.type == Menu.MAIN && slot == 24) {
            openNotifications(player);
        } else if (holder.type == Menu.NOTIFICATIONS && slot == 18) {
            openMain(player);
        } else if (holder.type == Menu.NOTIFICATIONS) {
            for (int i = 0; i < NOTIFICATION_SLOTS.length; i++) {
                if (slot != NOTIFICATION_SLOTS[i]) continue;
                String key = NOTIFICATION_KEYS[i];
                boolean enabled = !notificationEnabled(player.getUniqueId(), key);
                preferences.set("players." + player.getUniqueId() + ".notifications." + key, enabled);
                savePreferences();
                updateNotificationButton(event.getView().getTopInventory(), player, i);
                player.sendMessage(Component.text(NOTIFICATION_NAMES[i] + ": " + (enabled ? "ACIK" : "KAPALI"), enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
                break;
            }
        } else if (holder.type == Menu.CHAT && slot == 13) {
            boolean enabled = !publicChatEnabled(player.getUniqueId());
            setPublicChat(player.getUniqueId(), enabled);
            updateChatButton(event.getView().getTopInventory(), player);
            player.sendMessage(Component.text("Public Chat " + (enabled ? "ACIK" : "KAPALI"),
                    enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        } else if (holder.type == Menu.CHAT && slot == 18) {
            openMain(player);
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTpaCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length < 2) return;
        String command = parts[0].toLowerCase(java.util.Locale.ROOT);
        if (!command.equals("/tpa") && !command.equals("/essentials:tpa")) return;
        Player target = Bukkit.getPlayerExact(parts[1]);
        if (target == null) return;
        if (notificationEnabled(target.getUniqueId(), "teleport")) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("Bu oyuncu TPA isteklerini kapatmis.", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        int before = event.viewers().size();
        event.viewers().removeIf(viewer -> viewer instanceof Player recipient
                && !publicChatEnabled(recipient.getUniqueId()));
        if (chatDebug) {
            getLogger().info("[CHAT DEBUG] AsyncChatEvent: sender=" + event.getPlayer().getName()
                    + " viewersBefore=" + before + " viewersAfter=" + event.viewers().size());
            for (Player online : Bukkit.getOnlinePlayers()) {
                getLogger().info("[CHAT DEBUG] player=" + online.getName()
                        + " publicChat=" + publicChatEnabled(online.getUniqueId())
                        + " inViewers=" + event.viewers().contains(online));
            }
        }
    }
}
