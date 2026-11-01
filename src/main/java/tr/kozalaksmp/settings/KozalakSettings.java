package tr.kozalaksmp.settings;

import io.papermc.paper.event.player.AsyncChatEvent;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.ListenerPriority;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
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
    private PacketAdapter totemPacketListener;
    private PacketAdapter explosionParticleListener;
    private final Map<UUID, PermissionAttachment> lagMuteAttachments = new HashMap<>();
    private final Map<UUID, PermissionAttachment> announcementAttachments = new HashMap<>();
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
        for (Player online : Bukkit.getOnlinePlayers()) {
            applyLagMute(online);
            applyAnnouncements(online);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            totemPacketListener = new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.ENTITY_STATUS) {
                @Override public void onPacketSending(PacketEvent event) {
                    Player recipient = event.getPlayer();
                    if (totemEffectEnabled(recipient.getUniqueId())) return;
                    if (event.getPacket().getIntegers().read(0) != recipient.getEntityId()) return;
                    if (event.getPacket().getBytes().read(0) == (byte) 35) event.setCancelled(true);
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(totemPacketListener);
            // TNT and other explosion visuals may be produced by the client from
            // the explosion packet itself, not only from WORLD_PARTICLES.
            // Cancel only the visual explosion packet for opted-out players.
            // This does not cancel the actual server-side explosion event.
            explosionParticleListener = new PacketAdapter(this, ListenerPriority.HIGHEST,
                    PacketType.Play.Server.EXPLOSION) {
                @Override public void onPacketSending(PacketEvent event) {
                    Player player = event.getPlayer();
                    // This packet creates both the vanilla explosion visual and sound.
                    // When either is disabled, replace the requested components via
                    // the explosion events below, independently for each recipient.
                    if (!explosionEffectEnabled(player.getUniqueId())
                            || !explosionSoundEnabled(player.getUniqueId())) {
                        event.setCancelled(true);
                    }
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(explosionParticleListener);
        } else {
            getLogger().warning("ProtocolLib bulunamadi: Totem Efekti ayari calismayacak.");
        }
        getLogger().info("KozalakSettings hazir.");
    }

    @Override public void onDisable() {
        if (totemPacketListener != null) ProtocolLibrary.getProtocolManager().removePacketListener(totemPacketListener);
        if (explosionParticleListener != null) ProtocolLibrary.getProtocolManager().removePacketListener(explosionParticleListener);
        for (PermissionAttachment attachment : lagMuteAttachments.values()) {
            try { attachment.remove(); } catch (IllegalArgumentException ignored) { }
        }
        lagMuteAttachments.clear();
        for (PermissionAttachment attachment : announcementAttachments.values()) {
            try { attachment.remove(); } catch (IllegalArgumentException ignored) { }
        }
        announcementAttachments.clear();
        savePreferences();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        deliverExplosionPreferences(event.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        deliverExplosionPreferences(event.getBlock().getLocation().add(0.5, 0.5, 0.5));
    }

    private void deliverExplosionPreferences(Location location) {
        // Send only the component missing from the cancelled vanilla explosion packet.
        // Never broadcast replacements to players who have both settings enabled.
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) > 256 * 256) continue;
            boolean effect = explosionEffectEnabled(player.getUniqueId());
            boolean sound = explosionSoundEnabled(player.getUniqueId());
            if (effect && sound) continue;
            if (effect) {
                player.spawnParticle(Particle.EXPLOSION_EMITTER, location, 1);
            }
            if (sound) {
                player.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4.0f, 1.0f);
            }
        }
    }

    private boolean sidebarEnabled(UUID uuid) {
        return preferences.getBoolean("players." + uuid + ".sidebar", true);
    }

    private void updateSidebarButton(Inventory inv, Player player) {
        boolean enabled = sidebarEnabled(player.getUniqueId());
        inv.setItem(29, button(enabled ? Material.PAPER : Material.GRAY_DYE,
                "Skor Tablosu: " + (enabled ? "ACIK" : "KAPALI"),
                "TAB skor tablosunu sadece kendin icin ac/kapat"));
    }

    private boolean explosionSoundEnabled(UUID uuid) {
        return preferences.getBoolean("players." + uuid + ".explosion-sound", true);
    }

    private void updateExplosionSoundButton(Inventory inv, Player player) {
        boolean enabled = explosionSoundEnabled(player.getUniqueId());
        inv.setItem(35, button(enabled ? Material.NOTE_BLOCK : Material.GRAY_DYE,
                "Patlama Sesi: " + (enabled ? "ACIK" : "KAPALI"),
                "TNT, kristal ve diger patlama seslerini ac/kapat"));
    }

    private boolean explosionEffectEnabled(UUID uuid) {
        return preferences.getBoolean("players." + uuid + ".explosion-effect", true);
    }

    private void updateExplosionButton(Inventory inv, Player player) {
        boolean enabled = explosionEffectEnabled(player.getUniqueId());
        inv.setItem(33, button(enabled ? Material.TNT : Material.GRAY_DYE,
                "Patlama Efekti: " + (enabled ? "ACIK" : "KAPALI"),
                "Patlama goruntusunu goster/gizle (deneysel)"));
    }

    private boolean totemEffectEnabled(UUID uuid) {
        return preferences.getBoolean("players." + uuid + ".totem-effect", true);
    }

    private void updateTotemButton(Inventory inv, Player player) {
        boolean enabled = totemEffectEnabled(player.getUniqueId());
        inv.setItem(31, button(enabled ? Material.TOTEM_OF_UNDYING : Material.GRAY_DYE,
                "Totem Efekti: " + (enabled ? "ACIK" : "KAPALI"),
                "Buyuk totem animasyonunu goster/gizle"));
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
        updateSidebarButton(inv, player);
        updateTotemButton(inv, player);
        updateExplosionButton(inv, player);
        updateExplosionSoundButton(inv, player);
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

    private void applyAnnouncements(Player player) {
        PermissionAttachment attachment = announcementAttachments.computeIfAbsent(
                player.getUniqueId(), id -> player.addAttachment(this));
        attachment.setPermission("kozalaksettings.announcements",
                notificationEnabled(player.getUniqueId(), "announcements"));
        player.recalculatePermissions();
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
                        : NOTIFICATION_KEYS[index].equals("announcements")
                        ? "Otomatik sunucu duyurularini goster/gizle"
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
        if (holder.type == Menu.MAIN && slot == 29) {
            if (!Bukkit.getPluginManager().isPluginEnabled("TAB")) {
                player.sendMessage(Component.text("TAB eklentisi aktif degil.", NamedTextColor.RED));
                return;
            }
            // TAB's /sb command changes the sidebar for this player only.
            // Enable remember-toggle-choice in TAB config for reconnect persistence.
            if (!player.performCommand("sb")) {
                player.sendMessage(Component.text("TAB /sb komutu calistirilamadi.", NamedTextColor.RED));
                return;
            }
            boolean enabled = !sidebarEnabled(player.getUniqueId());
            preferences.set("players." + player.getUniqueId() + ".sidebar", enabled);
            savePreferences();
            updateSidebarButton(event.getView().getTopInventory(), player);
        } else if (holder.type == Menu.MAIN && slot == 35) {
            boolean enabled = !explosionSoundEnabled(player.getUniqueId());
            preferences.set("players." + player.getUniqueId() + ".explosion-sound", enabled);
            savePreferences();
            updateExplosionSoundButton(event.getView().getTopInventory(), player);
            player.sendMessage(Component.text("Patlama Sesi: " + (enabled ? "ACIK" : "KAPALI"), enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        } else if (holder.type == Menu.MAIN && slot == 33) {
            boolean enabled = !explosionEffectEnabled(player.getUniqueId());
            preferences.set("players." + player.getUniqueId() + ".explosion-effect", enabled);
            savePreferences();
            updateExplosionButton(event.getView().getTopInventory(), player);
            player.sendMessage(Component.text("Patlama Efekti: " + (enabled ? "ACIK" : "KAPALI"), enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        } else if (holder.type == Menu.MAIN && slot == 31) {
            boolean enabled = !totemEffectEnabled(player.getUniqueId());
            preferences.set("players." + player.getUniqueId() + ".totem-effect", enabled);
            savePreferences();
            updateTotemButton(event.getView().getTopInventory(), player);
            player.sendMessage(Component.text("Totem Efekti: " + (enabled ? "ACIK" : "KAPALI"), enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        } else if (holder.type == Menu.MAIN && slot == 22) {
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
                if (key.equals("announcements")) applyAnnouncements(player);
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
        applyAnnouncements(event.getPlayer());
        // Suppress vanilla join lines; SKWelcome's own lines must be disabled in its config.
        event.joinMessage(null);
        broadcastJoinLeave(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        lagMuteAttachments.remove(event.getPlayer().getUniqueId());
        announcementAttachments.remove(event.getPlayer().getUniqueId());
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
