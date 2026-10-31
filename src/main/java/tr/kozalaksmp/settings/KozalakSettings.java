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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import java.util.Map;
import java.util.HashMap;
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
    private final Map<UUID, PermissionAttachment> lagMuteAttachments = new HashMap<>();
    private File preferencesFile;
    private YamlConfiguration preferences;

    private enum Menu { MAIN, CHAT, NOTIFICATIONS }

    private enum JoinLeaveMode { ALL, STAFF, OFF }

    private static final String[] NOTIFICATION_KEYS = {"teleport", "money", "combat", "team", "auction", "crate", "announcements", "lag"};
    private static final String[] NOTIFICATION_NAMES = {"TPA Istekleri", "Para", "Olum Mesajlari", "Takim", "Acik Artirma", "Kasa", "Duyurular", "Lag Temizleyici"};
    private static final Material[] NOTIFICATION_ICONS = {Material.ENDER_PEARL, Material.GOLD_INGOT, Material.SKELETON_SKULL, Material.WHITE_BANNER, Material.GOLDEN_HORSE_ARMOR, Material.CHEST, Material.BELL, Material.CLOCK};
    private static final int[] NOTIFICATION_SLOTS = {10, 11, 12, 13, 14, 15, 16, 25};

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
        for (Player online : Bukkit.getOnlinePlayers()) applyLagMute(online);
        getLogger().info("KozalakSettings hazir.");
    }

    @Override public void onDisable() {
        for (PermissionAttachment attachment : lagMuteAttachments.values()) {
            try { attachment.remove(); } catch (IllegalArgumentException ignored) { }
        }
        lagMuteAttachments.clear();
        savePreferences();
    }

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

    private JoinLeaveMode joinLeaveMode(UUID uuid) {
        String value = preferences.getString("players." + uuid + ".join-leave-mode", "ALL");
        try { return JoinLeaveMode.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return JoinLeaveMode.ALL; }
    }

    private String joinLeaveLabel(JoinLeaveMode mode) {
        return switch (mode) {
            case ALL -> "HERKES";
            case STAFF -> "SADECE YETKILILER";
            case OFF -> "KAPALI";
        };
    }

    private void updateJoinLeaveButton(Inventory inv, Player player) {
        JoinLeaveMode mode = joinLeaveMode(player.getUniqueId());
        inv.setItem(22, button(mode == JoinLeaveMode.OFF ? Material.GRAY_DYE :
                mode == JoinLeaveMode.STAFF ? Material.SHIELD : Material.LIME_DYE,
                "Giris/Cikis: " + joinLeaveLabel(mode),
                "Tikla: Herkes > Sadece Yetkililer > Kapali"));
    }

    private void applyLagMute(Player player) {
        PermissionAttachment attachment = lagMuteAttachments.computeIfAbsent(
                player.getUniqueId(), id -> player.addAttachment(this));
        attachment.setPermission("skclearlag.mute",
                !notificationEnabled(player.getUniqueId(), "lag"));
        player.recalculatePermissions();
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
        updateJoinLeaveButton(inv, player);
        player.openInventory(inv);
    }

    private void updateNotificationButton(Inventory inv, Player player, int index) {
        boolean enabled = notificationEnabled(player.getUniqueId(), NOTIFICATION_KEYS[index]);
        inv.setItem(NOTIFICATION_SLOTS[index], button(NOTIFICATION_ICONS[index],
                NOTIFICATION_NAMES[index] + ": " + (enabled ? "ACIK" : "KAPALI"),
                NOTIFICATION_KEYS[index].equals("combat")
                        ? "Oyuncu olum mesajlarini goster/gizle"
                        : NOTIFICATION_KEYS[index].equals("lag")
                        ? "Lag temizleyici mesajlarini ve seslerini goster/gizle"
                        : "Tercih kaydedilir; bildirim entegrasyonu henuz yok"));
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
        } else if (holder.type == Menu.NOTIFICATIONS && slot == 22) {
            JoinLeaveMode current = joinLeaveMode(player.getUniqueId());
            JoinLeaveMode next = switch (current) {
                case ALL -> JoinLeaveMode.STAFF;
                case STAFF -> JoinLeaveMode.OFF;
                case OFF -> JoinLeaveMode.ALL;
            };
            preferences.set("players." + player.getUniqueId() + ".join-leave-mode", next.name());
            savePreferences();
            updateJoinLeaveButton(event.getView().getTopInventory(), player);
            player.sendMessage(Component.text("Giris/Cikis bildirimleri: " + joinLeaveLabel(next), NamedTextColor.GREEN));
        } else if (holder.type == Menu.NOTIFICATIONS) {
            for (int i = 0; i < NOTIFICATION_SLOTS.length; i++) {
                if (slot != NOTIFICATION_SLOTS[i]) continue;
                String key = NOTIFICATION_KEYS[i];
                boolean enabled = !notificationEnabled(player.getUniqueId(), key);
                preferences.set("players." + player.getUniqueId() + ".notifications." + key, enabled);
                savePreferences();
                if (key.equals("lag")) applyLagMute(player);
                if (key.equals("combat") && Bukkit.getPluginManager().isPluginEnabled("SKDeathMessages")) {
                    boolean dispatched = Bukkit.dispatchCommand(player, "deathmessages " + (enabled ? "on" : "off"));
                    if (!dispatched) {
                        player.sendMessage(Component.text("SKDeathMessages ayari uygulanamadi.", NamedTextColor.RED));
                    }
                }
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


    // SKWelcome settings.yml: join.enabled=false and quit.enabled=false are required
    // to prevent duplicate messages. SKWelcome still handles welcome titles/greetings.
    private void broadcastJoinLeave(Player subject, boolean joining) {
        boolean staff = subject.hasPermission("skwelcome.rank.staff");
        Component message;
        if (staff) {
            message = Component.text("★ ", NamedTextColor.GOLD)
                    .append(Component.text("Yetkili ", NamedTextColor.WHITE))
                    .append(Component.text(subject.getName(), NamedTextColor.GOLD))
                    .append(Component.text(joining ? " burada." : " ayrıldı.", NamedTextColor.WHITE));
        } else {
            message = Component.text(joining ? "+ " : "- ", joining ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .append(Component.text(subject.getName(), NamedTextColor.GRAY));
        }
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            JoinLeaveMode mode = joinLeaveMode(recipient.getUniqueId());
            if (mode == JoinLeaveMode.OFF || (mode == JoinLeaveMode.STAFF && !staff)) continue;
            recipient.sendMessage(message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        applyLagMute(event.getPlayer());
        // Suppress vanilla join lines; SKWelcome's own lines must be disabled in its config.
        event.joinMessage(null);
        broadcastJoinLeave(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        lagMuteAttachments.remove(event.getPlayer().getUniqueId());
        event.quitMessage(null);
        broadcastJoinLeave(event.getPlayer(), false);
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
