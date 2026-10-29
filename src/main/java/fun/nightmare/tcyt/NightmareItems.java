package fun.nightmare.tcyt;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NightmareItems extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<String, YamlConfiguration> itemConfigs = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private NamespacedKey itemIdKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        saveSampleIfMissing();
        itemIdKey = new NamespacedKey(this, "item_id");
        loadItems();
        Objects.requireNonNull(getCommand("nitems")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NightmareItems enabled. Loaded " + itemConfigs.size() + " item(s).");
    }

    private void saveSampleIfMissing() {
        File dir = new File(getDataFolder(), "items");
        if (!dir.exists() && !dir.mkdirs()) getLogger().warning("Could not create items directory.");
        File sample = new File(dir, "blood_blade.yml");
        if (!sample.exists()) saveResource("items/blood_blade.yml", false);
    }

    private void loadItems() {
        itemConfigs.clear();
        File dir = new File(getDataFolder(), "items");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            String id = cfg.getString("id", file.getName().replaceFirst("(?i)\\.yml$", ""));
            if (id == null || id.isBlank()) continue;
            itemConfigs.put(id.toLowerCase(Locale.ROOT), cfg);
        }
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private void msg(CommandSender sender, String s) {
        sender.sendMessage(color(getConfig().getString("messages.prefix", "&8[&4NI&8] ") + s));
    }

    private ItemStack createItem(String id) {
        YamlConfiguration cfg = itemConfigs.get(id.toLowerCase(Locale.ROOT));
        if (cfg == null) return null;
        Material mat = Material.matchMaterial(cfg.getString("material", "STONE"));
        if (mat == null || !mat.isItem()) mat = Material.STONE;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(cfg.getString("name", id)));
            List<String> lore = new ArrayList<>();
            for (String line : cfg.getStringList("lore")) lore.add(color(line));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id.toLowerCase(Locale.ROOT));
            meta.setUnbreakable(cfg.getBoolean("unbreakable", false));
            ConfigurationSection enchants = cfg.getConfigurationSection("enchantments");
            if (enchants != null) for (String key : enchants.getKeys(false)) {
                Enchantment ench = Enchantment.getByName(key.toUpperCase(Locale.ROOT));
                if (ench != null) {
                    try { meta.addEnchant(ench, Math.max(1, enchants.getInt(key, 1)), true); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("editor")) {
            if (!(sender instanceof Player p)) { msg(sender, getConfig().getString("messages.player-only", "&cIn-game only.")); return true; }
            if (!p.hasPermission("nightmareitems.admin")) { msg(p, getConfig().getString("messages.no-permission", "&cNo permission.")); return true; }
            openEditor(p, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            msg(sender, itemConfigs.isEmpty() ? "&cChưa có item nào." : "&eItems: &f" + String.join(", ", itemConfigs.keySet()));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("nightmareitems.admin")) { msg(sender, getConfig().getString("messages.no-permission", "&cNo permission.")); return true; }
            reloadConfig(); loadItems();
            msg(sender, getConfig().getString("messages.reloaded", "&aReloaded."));
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("nightmareitems.admin")) { msg(sender, getConfig().getString("messages.no-permission", "&cNo permission.")); return true; }
            if (args.length < 3) { msg(sender, "&cDùng: /nitems give <player> <item_id>"); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { msg(sender, "&cNgười chơi không online."); return true; }
            ItemStack item = createItem(args[2]);
            if (item == null) { msg(sender, "&cKhông tìm thấy item ID: " + args[2]); return true; }
            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(item);
            leftovers.values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
            msg(sender, "&aĐã đưa item cho &f" + target.getName());
            return true;
        }
        msg(sender, "&e/nitems editor &7| &e/nitems list &7| &e/nitems give <player> <id> &7| &e/nitems reload");
        return true;
    }

    private void openEditor(Player p, int page) {
        int size = Math.max(9, Math.min(54, getConfig().getInt("settings.gui-size", 54)));
        size = ((size + 8) / 9) * 9;
        Inventory inv = Bukkit.createInventory(null, size, color(getConfig().getString("settings.gui-title", "&4NIGHTMARE ITEMS")));
        List<String> ids = new ArrayList<>(itemConfigs.keySet());
        int start = page * (size - 9);
        for (int i = start; i < Math.min(ids.size(), start + size - 9); i++) {
            ItemStack stack = createItem(ids.get(i));
            if (stack != null) inv.setItem(i - start, stack);
        }
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) { meta.setDisplayName(color("&eChỉnh sửa bằng YAML")); meta.setLore(List.of(color("&7Sửa file trong plugins/NightmareItems/items/"), color("&7Sau đó chạy /nitems reload"))); info.setItemMeta(meta); }
        inv.setItem(size - 5, info);
        p.openInventory(inv);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!ChatColor.stripColor(e.getView().getTitle()).equalsIgnoreCase(ChatColor.stripColor(color(getConfig().getString("settings.gui-title", "&4NIGHTMARE ITEMS"))))) return;
        e.setCancelled(true);
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getInventory().getSize()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String id = clicked.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (id != null) {
            ItemStack copy = createItem(id);
            if (copy != null) {
                Map<Integer, ItemStack> leftovers = p.getInventory().addItem(copy);
                leftovers.values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                msg(p, "&aĐã nhận item &f" + id);
            }
            p.closeInventory();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getItem() == null || !e.getItem().hasItemMeta()) return;
        String id = e.getItem().getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (id == null) return;
        YamlConfiguration cfg = itemConfigs.get(id);
        if (cfg == null) return;
        String path = null;
        if (e.getAction().isRightClick()) path = e.getPlayer().isSneaking() ? "skills.sneak_right" : "skills.right";
        else if (e.getAction().isLeftClick()) path = e.getPlayer().isSneaking() ? "skills.sneak_left" : "skills.left";
        if (path == null || !cfg.isConfigurationSection(path)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ConfigurationSection skill = cfg.getConfigurationSection(path);
        String type = skill.getString("type", "POTION").toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + ":" + id + ":" + path;
        long until = cooldowns.getOrDefault(key, 0L);
        if (until > now) {
            p.sendActionBar(color("&cCooldown: " + ((until - now + 999) / 1000) + "s"));
            return;
        }
        int cd = Math.max(0, skill.getInt("cooldown", getConfig().getInt("settings.default-cooldown-seconds", 3)));
        if (cd > 0) cooldowns.put(key, now + cd * 1000L);
        switch (type) {
            case "DAMAGE" -> {
                if (p.getTargetEntity(6) instanceof org.bukkit.entity.LivingEntity target) {
                    target.damage(Math.max(0, skill.getDouble("amount", 4.0)), p);
                    p.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 12, .3, .4, .3, .1);
                } else { msg(p, "&7Không có mục tiêu trong tầm 6 block."); }
            }
            case "POTION" -> {
                PotionEffectType effect = PotionEffectType.getByName(skill.getString("effect", "SPEED").toUpperCase(Locale.ROOT));
                if (effect != null) p.addPotionEffect(new PotionEffect(effect, Math.max(1, skill.getInt("duration", 5)) * 20, Math.max(0, skill.getInt("amplifier", 0))));
            }
            case "LIGHTNING" -> {
                if (p.getTargetBlockExact(20) != null) p.getWorld().strikeLightningEffect(p.getTargetBlockExact(20).getLocation());
            }
            case "HEAL" -> p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + Math.max(0, skill.getDouble("amount", 4.0))));
            default -> msg(p, "&cSkill type không hợp lệ: " + type);
        }
        String message = skill.getString("message", "");
        if (!message.isBlank()) p.sendMessage(color(message));
    }
}
