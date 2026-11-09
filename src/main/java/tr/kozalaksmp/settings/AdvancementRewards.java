package tr.kozalaksmp.settings;

import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class AdvancementRewards implements Listener, CommandExecutor {
    private static final int PAGE_SIZE = 36;
    private static final String[] CATEGORIES = {"story", "nether", "end", "adventure", "husbandry"};
    private static final String[] CATEGORY_NAMES = {"Minecraft", "Nether", "The End", "Macera", "Çiftçilik"};
    private static final org.bukkit.Material[] CATEGORY_ICONS = {
            org.bukkit.Material.GRASS_BLOCK, org.bukkit.Material.NETHERRACK,
            org.bukkit.Material.END_STONE, org.bukkit.Material.IRON_SWORD,
            org.bukkit.Material.WHEAT
    };
    private static final int[] CATEGORY_SLOTS = {20, 21, 22, 23, 24};
    private final JavaPlugin plugin;
    private final File ledgerFile;
    private final YamlConfiguration ledger;

    private static final class PageHolder implements InventoryHolder {
        private final int page;
        private final int category;
        private Inventory inventory;
        private PageHolder(int category, int page) { this.category = category; this.page = page; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    public AdvancementRewards(JavaPlugin plugin) {
        this.plugin = plugin;
        ledgerFile = new File(plugin.getDataFolder(), "advancement-rewards.yml");
        ledger = YamlConfiguration.loadConfiguration(ledgerFile);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        java.util.Objects.requireNonNull(plugin.getCommand("basarimlar")).setExecutor(this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> rewardCompleted(player), 20L);
        }
    }

    private List<Advancement> advancements() {
        List<Advancement> result = new ArrayList<>();
        Iterator<Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            NamespacedKey key = advancement.getKey();
            if (!key.getNamespace().equals("minecraft")) continue;
            if (advancement.getDisplay() == null) continue;
            result.add(advancement);
        }
        result.sort(Comparator.comparing(a -> a.getKey().toString()));
        return result;
    }

    private int points(Advancement advancement) {
        AdvancementDisplay display = advancement.getDisplay();
        if (display == null) return 0;
        return switch (display.frame()) {
            case CHALLENGE -> 30;
            case GOAL -> 20;
            case TASK -> {
                String key = advancement.getKey().getKey();
                if (key.startsWith("end/") || key.startsWith("nether/")) yield 10;
                if (key.startsWith("adventure/") || key.startsWith("husbandry/")) yield 5;
                yield 3;
            }
        };
    }

    private String ledgerKey(Player player, Advancement advancement) {
        return "players." + player.getUniqueId() + "." + advancement.getKey().getNamespace()
                + "." + advancement.getKey().getKey().replace('/', '.');
    }

    private boolean completed(Player player, Advancement advancement) {
        return player.getAdvancementProgress(advancement).isDone();
    }

    private void saveLedger() throws IOException {
        ledger.save(ledgerFile);
    }

    private boolean givePoints(UUID uuid, int amount) {
        org.bukkit.plugin.Plugin points = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (points == null || !points.isEnabled()) return false;
        try {
            Object api = points.getClass().getMethod("getAPI").invoke(points);
            Method give = api.getClass().getMethod("give", UUID.class, int.class);
            Object result = give.invoke(api, uuid, amount);
            return result instanceof Boolean success && success;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "PlayerPoints API ile odul verilemedi.", ex);
            return false;
        }
    }

    private void reward(Player player, Advancement advancement, boolean announce) {
        if (!player.isOnline() || !completed(player, advancement)) return;
        String path = ledgerKey(player, advancement);
        // PENDING is persisted before transfer to avoid duplicate payment after a crash.
        // A pending entry is intentionally not retried automatically: staff can audit it.
        if (ledger.contains(path)) return;
        ledger.set(path, "pending");
        try {
            saveLedger();
        } catch (IOException ex) {
            ledger.set(path, null);
            plugin.getLogger().log(Level.SEVERE, "Basarim odul kaydi yazilamadi; odul verilmedi.", ex);
            return;
        }
        int amount = points(advancement);
        if (!givePoints(player.getUniqueId(), amount)) {
            ledger.set(path, null);
            try { saveLedger(); } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Basarim odul kaydi temizlenemedi.", ex);
            }
            return;
        }
        ledger.set(path, "paid");
        try {
            saveLedger();
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Basarim odulu verildi fakat kayit tamamlanamadi: " + path, ex);
        }
        if (announce) {
            player.sendMessage(Component.text("Basarim odulu: +" + amount + " Puan!", NamedTextColor.GREEN));
        }
    }

    private void rewardCompleted(Player player) {
        if (!player.isOnline()) return;
        for (Advancement advancement : advancements()) {
            if (completed(player, advancement)) reward(player, advancement, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Advancement advancement = event.getAdvancement();
        if (!advancement.getKey().getNamespace().equals("minecraft") || advancement.getDisplay() == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> reward(event.getPlayer(), advancement, true));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> rewardCompleted(event.getPlayer()), 40L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut oyuncular icindir.");
            return true;
        }
        rewardCompleted(player);
        openCategories(player);
        return true;
    }

    private List<Advancement> inCategory(int category) {
        String prefix = CATEGORIES[category] + "/";
        List<Advancement> result = new ArrayList<>();
        for (Advancement advancement : advancements()) {
            if (advancement.getKey().getKey().startsWith(prefix)) result.add(advancement);
        }
        return result;
    }

    private int completedCount(Player player, List<Advancement> list) {
        int count = 0;
        for (Advancement advancement : list) if (completed(player, advancement)) count++;
        return count;
    }

    private void openCategories(Player player) {
        PageHolder holder = new PageHolder(-1, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Kozalak SMP | Başarımlar"));
        holder.inventory = inventory;
        List<Advancement> all = new ArrayList<>();
        for (int i = 0; i < CATEGORIES.length; i++) {
            List<Advancement> entries = inCategory(i);
            all.addAll(entries);
            int done = completedCount(player, entries);
            ItemStack item = new ItemStack(CATEGORY_ICONS[i]);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(CATEGORY_NAMES[i], NamedTextColor.GOLD));
            meta.lore(List.of(
                    Component.text("Tamamlanan: " + done + "/" + entries.size(), NamedTextColor.GREEN),
                    Component.text("İlerleme: %" + (entries.isEmpty() ? 0 : done * 100 / entries.size()), NamedTextColor.AQUA),
                    Component.text("Açmak için tıkla", NamedTextColor.YELLOW)));
            item.setItemMeta(meta);
            inventory.setItem(CATEGORY_SLOTS[i], item);
        }
        int totalDone = completedCount(player, all);
        ItemStack summary = navigation(org.bukkit.Material.NETHER_STAR,
                "Toplam ilerleme: " + totalDone + "/" + all.size());
        inventory.setItem(49, summary);
        player.openInventory(inventory);
    }

    private void openPage(Player player, int category, int requestedPage) {
        List<Advancement> all = inCategory(category);
        int maxPage = Math.max(0, (all.size() - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        PageHolder holder = new PageHolder(category, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text(CATEGORY_NAMES[category] + " | " + (page + 1) + "/" + (maxPage + 1)));
        holder.inventory = inventory;
        int start = page * PAGE_SIZE;
        for (int i = start; i < Math.min(all.size(), start + PAGE_SIZE); i++) {
            Advancement advancement = all.get(i);
            AdvancementDisplay display = advancement.getDisplay();
            if (display == null) continue;
            ItemStack item = display.icon().clone();
            ItemMeta meta = item.getItemMeta();
            boolean done = completed(player, advancement);
            meta.displayName(display.title().colorIfAbsent(done ? NamedTextColor.GREEN : NamedTextColor.RED));
            meta.lore(List.of(
                    display.description(),
                    Component.text("Ödül: " + points(advancement) + " Puan", NamedTextColor.GOLD),
                    Component.text(done ? "✓ Tamamlandı" : "✗ Tamamlanmadı",
                            done ? NamedTextColor.GREEN : NamedTextColor.RED),
                    Component.text(done && "paid".equals(ledger.getString(ledgerKey(player, advancement)))
                            ? "Ödül alındı" : done ? "Ödül bekleniyor" : "Henüz kazanılmadı",
                            NamedTextColor.GRAY)));
            item.setItemMeta(meta);
            inventory.setItem(i - start, item);
        }
        if (page > 0) inventory.setItem(45, navigation(org.bukkit.Material.ARROW, "Önceki sayfa"));
        inventory.setItem(48, navigation(org.bukkit.Material.OAK_DOOR, "Kategorilere dön"));
        inventory.setItem(49, navigation(org.bukkit.Material.BOOK,
                "İlerleme: " + completedCount(player, all) + "/" + all.size()));
        if (page < maxPage) inventory.setItem(53, navigation(org.bukkit.Material.ARROW, "Sonraki sayfa"));
        player.openInventory(inventory);
    }

    private ItemStack navigation(org.bukkit.Material material, String title) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.AQUA));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PageHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (holder.category == -1) {
            for (int i = 0; i < CATEGORY_SLOTS.length; i++) {
                if (slot == CATEGORY_SLOTS[i]) {
                    openPage(player, i, 0);
                    return;
                }
            }
            return;
        }
        if (slot == 48) openCategories(player);
        else if (slot == 45 && holder.page > 0) openPage(player, holder.category, holder.page - 1);
        else if (slot == 53 && (holder.page + 1) * PAGE_SIZE < inCategory(holder.category).size())
            openPage(player, holder.category, holder.page + 1);
    }

    @EventHandler
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PageHolder) event.setCancelled(true);
    }
}
