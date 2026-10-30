package fun.nightmare.tcyt;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** NightmareItems v2 - Paper 1.20.6 / Java 21. */
public final class NightmareItems extends JavaPlugin implements Listener, CommandExecutor {
    private static final String ADMIN = "nightmareitems.admin";
    private static final int PAGE_SIZE = 45;
    private final Map<String, YamlConfiguration> items = new LinkedHashMap<>();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pages = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private NamespacedKey itemKey, levelKey, actionKey;

    private record Prompt(String id, String field) {}
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "item_id");
        levelKey = new NamespacedKey(this, "item_level");
        actionKey = new NamespacedKey(this, "gui_action");
        loadItems();
        Objects.requireNonNull(getCommand("nitems"), "nitems command missing").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NightmareItems v2 enabled: " + items.size() + " items");
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private void msg(CommandSender s, String m) { s.sendMessage(color(getConfig().getString("messages.prefix", "&8[&4NightmareItems&8] ") + m)); }
    private boolean admin(CommandSender s) { if (s.hasPermission(ADMIN)) return true; msg(s, "&cBạn không có quyền."); return false; }

    private void loadItems() {
        items.clear(); File dir = new File(getDataFolder(), "items");
        if (!dir.exists() && !dir.mkdirs()) { getLogger().severe("Cannot create items folder"); return; }
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return; Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            YamlConfiguration c = YamlConfiguration.loadConfiguration(f);
            String id = c.getString("id", f.getName().replaceFirst("(?i)\\.yml$", "")).toLowerCase(Locale.ROOT);
            if (id.matches("[a-z0-9_-]{1,40}")) items.put(id, c); else getLogger().warning("Invalid item id in " + f.getName());
        }
    }
    private File file(String id) { return new File(new File(getDataFolder(), "items"), id + ".yml"); }
    private void save(String id) throws IOException { YamlConfiguration c = items.get(id); if (c == null) throw new IOException("Item not found"); c.set("id", id); c.save(file(id)); }

    private Inventory menu(int size, String title) { Holder h = new Holder(); h.inventory = Bukkit.createInventory(h, size, title); return h.inventory; }
    private ItemStack button(Material type, String name, String action, String... lore) {
        ItemStack i = new ItemStack(type); ItemMeta m = i.getItemMeta();
        m.setDisplayName(color(name)); List<String> lines = new ArrayList<>(); for (String l : lore) lines.add(color(l));
        if (!lines.isEmpty()) m.setLore(lines); m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action); i.setItemMeta(m); return i;
    }

    private ItemStack build(String id, int level) {
        YamlConfiguration c = items.get(id); if (c == null) return null;
        Material mat = Material.matchMaterial(c.getString("material", "STONE")); if (mat == null || !mat.isItem()) mat = Material.STONE;
        ItemStack stack = new ItemStack(mat); ItemMeta meta = stack.getItemMeta(); if (meta == null) return stack;
        meta.setDisplayName(color(c.getString("name", id)));
        List<String> lore = new ArrayList<>(); for (String line : c.getStringList("lore")) lore.add(color(line));
        lore.add(color("&8NightmareItems &7| &cCấp " + level)); meta.setLore(lore);
        meta.setUnbreakable(c.getBoolean("unbreakable", false));
        if (c.contains("custom-model-data")) meta.setCustomModelData(c.getInt("custom-model-data"));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        double upgradeDamage = 0.0;
        for (int lv = 2; lv <= level; lv++) upgradeDamage += c.getDouble("upgrades.levels." + lv + ".damage-bonus", 0.0);
        if (upgradeDamage > 0 && (mat.name().endsWith("SWORD") || mat.name().endsWith("AXE") || mat == Material.TRIDENT)) {
            UUID modifierId = UUID.nameUUIDFromBytes(("nightmareitems:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE,
                    new AttributeModifier(modifierId, "nightmareitems_upgrade_damage", upgradeDamage,
                            AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND));
        }
        ConfigurationSection ench = c.getConfigurationSection("enchantments");
        if (ench != null) for (String key : ench.getKeys(false)) {
            Enchantment e = Enchantment.getByName(key.toUpperCase(Locale.ROOT));
            if (e != null) try { meta.addEnchant(e, Math.max(1, Math.min(255, ench.getInt(key, 1))), true); }
            catch (IllegalArgumentException ex) { getLogger().warning("Cannot apply enchant " + key + " to " + id); }
        }
        stack.setItemMeta(meta); return stack;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("editor")) {
            if (!(sender instanceof Player p)) { msg(sender, "&cLệnh này chỉ dùng trong game."); return true; }
            if (admin(p)) openMain(p, 0); return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "list" -> msg(sender, "&eItems (&f" + items.size() + "&e): &f" + String.join(", ", items.keySet()));
            case "reload" -> { if (admin(sender)) { reloadConfig(); loadItems(); msg(sender, "&aĐã tải lại " + items.size() + " item."); } }
            case "give" -> {
                if (!admin(sender)) return true;
                if (a.length < 3) { msg(sender, "&c/nitems give <player> <item_id> [level]"); return true; }
                Player target = Bukkit.getPlayerExact(a[1]); int level = a.length > 3 ? parseInt(a[3], 1) : 1;
                ItemStack it = build(a[2].toLowerCase(Locale.ROOT), Math.max(1, level));
                if (target == null || it == null) { msg(sender, "&cKhông tìm thấy người chơi hoặc item."); return true; }
                target.getInventory().addItem(it).values().forEach(x -> target.getWorld().dropItemNaturally(target.getLocation(), x)); msg(sender, "&aĐã đưa item cho " + target.getName());
            }
            case "upgrade" -> {
                if (!(sender instanceof Player p)) { msg(sender, "&cChỉ người chơi mới nâng cấp được."); return true; }
                upgradeHeld(p);
            }
            default -> msg(sender, "&e/nitems editor | list | give <player> <id> [level] | upgrade | reload");
        }
        return true;
    }
    private int parseInt(String s, int fallback) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; } }

    private void openMain(Player p, int page) {
        if (!admin(p)) return; List<String> ids = new ArrayList<>(items.keySet()); int maxPage = Math.max(0, (ids.size() - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage)); pages.put(p.getUniqueId(), page);
        Inventory inv = menu(54, "§4§lNIGHTMARE ITEMS §8[" + (page + 1) + "/" + (maxPage + 1) + "]");
        for (int n = 0; n < PAGE_SIZE; n++) { int idx = page * PAGE_SIZE + n; if (idx >= ids.size()) break; String id = ids.get(idx); ItemStack it = build(id, 1); if (it == null) continue;
            ItemMeta m = it.getItemMeta(); m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "edit:" + id); List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>(); lore.add(color("&eBấm để quản lý")); m.setLore(lore); it.setItemMeta(m); inv.setItem(n, it); }
        inv.setItem(45, button(Material.EMERALD, "&a&lTẠO VẬT PHẨM", "create", "&7Tạo item mới"));
        inv.setItem(48, button(Material.ARROW, "&eTrang trước", "page:" + (page - 1)));
        inv.setItem(49, button(Material.BOOK, "&fHướng dẫn", "help", "&7Chọn item để chỉnh sửa", "&7Nâng cấp: cầm item rồi /nitems upgrade"));
        inv.setItem(50, button(Material.ARROW, "&eTrang sau", "page:" + (page + 1)));
        p.openInventory(inv);
    }

    private void openEdit(Player p, String id) {
        YamlConfiguration c = items.get(id); if (c == null) { openMain(p, pages.getOrDefault(p.getUniqueId(), 0)); return; }
        Inventory inv = menu(54, "§4§lITEM EDITOR §8- " + id);
        inv.setItem(10, button(Material.NAME_TAG, "&eTên vật phẩm", "prompt:" + id + ":name", "&7Hiện tại: " + c.getString("name", id), "&7Nhập tên, hỗ trợ màu &"));
        inv.setItem(11, button(Material.ITEM_FRAME, "&eMaterial", "prompt:" + id + ":material", "&7Hiện tại: " + c.getString("material", "STONE")));
        inv.setItem(12, button(Material.PAPER, "&eThêm Lore", "prompt:" + id + ":lore", "&7Thêm một dòng lore"));
        inv.setItem(13, button(Material.BOOK, "&dThêm/Sửa enchant", "prompt:" + id + ":enchant", "&7Ví dụ: SHARPNESS 10", "&7Cho phép cấp vượt vanilla"));
        inv.setItem(14, button(Material.NETHER_STAR, "&5Thêm kỹ năng", "prompt:" + id + ":ability", "&7id|trigger|type|amount|cooldown[|effect|seconds|amp]", "&7vd: slash|right|DAMAGE|8|5"));
        inv.setItem(15, button(Material.BLAZE_POWDER, "&cDanh sách kỹ năng", "abilities:" + id, "&7Xem và xóa kỹ năng"));
        inv.setItem(16, button(Material.ANVIL, "&6Cấu hình nâng cấp", "prompt:" + id + ":upgrade", "&7max|exp|material|amount|damage", "&7vd: 5|10|DIAMOND|2|2.5"));
        inv.setItem(17, button(Material.BARRIER, "&cXóa toàn bộ enchant", "clearench:" + id));
        inv.setItem(19, button(Material.REDSTONE, "&cThuộc tính / Model", "prompt:" + id + ":model", "&7Nhập CustomModelData (số nguyên)", "&7Để 0 để tắt"));
        inv.setItem(20, button(Material.IRON_CHESTPLATE, "&fUnbreakable", "toggle:" + id + ":unbreakable", "&7Hiện tại: " + c.getBoolean("unbreakable", false)));
        inv.setItem(21, button(Material.BARRIER, "&cXóa Lore", "clearlore:" + id));
        inv.setItem(22, button(Material.CHEST, "&bXem thử / nhận item", "preview:" + id));
        inv.setItem(23, button(Material.WRITABLE_BOOK, "&aSao chép item", "duplicate:" + id));
        inv.setItem(25, button(Material.LIME_DYE, "&a&lLƯU YAML", "save:" + id));
        inv.setItem(26, button(Material.TNT, "&c&lXÓA ITEM", "delete:" + id, "&7Xóa file YAML"));
        inv.setItem(49, button(Material.ARROW, "&fQuay lại", "back"));
        p.openInventory(inv);
    }

    private void openAbilities(Player p, String id) {
        YamlConfiguration c = items.get(id); if (c == null) return; Inventory inv = menu(54, "§5§lSKILLS §8- " + id); ConfigurationSection sec = c.getConfigurationSection("abilities"); int slot = 0;
        if (sec != null) for (String key : sec.getKeys(false)) { if (slot >= 45) break; ConfigurationSection ab = sec.getConfigurationSection(key); if (ab == null) continue;
            inv.setItem(slot++, button(Material.NETHER_STAR, "&d" + key, "removeability:" + id + ":" + key, "&7Trigger: " + ab.getString("trigger", "right"), "&7Type: " + ab.getString("type", "DAMAGE"), "&cBấm để xóa")); }
        inv.setItem(49, button(Material.ARROW, "&fQuay lại", "edit:" + id)); p.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true); if (!admin(p)) { p.closeInventory(); return; } if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack clicked = e.getCurrentItem(); if (clicked == null || !clicked.hasItemMeta()) return;
        String a = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING); if (a == null) return;
        if (a.equals("create")) { ask(p, "", "newid", "&eNhập ID mới (a-z, 0-9, _ -):"); return; }
        if (a.equals("help")) { msg(p, "&eSkill format: id|trigger|type|amount|cooldown[|effect|seconds|amp]. Trigger: right, sneak_right, left, sneak_left, on_hit."); return; }
        if (a.equals("back")) { openMain(p, pages.getOrDefault(p.getUniqueId(), 0)); return; }
        if (a.startsWith("page:")) { openMain(p, parseInt(a.substring(5), 0)); return; }
        if (a.startsWith("edit:")) { openEdit(p, a.substring(5)); return; }
        if (a.startsWith("prompt:")) { String[] s = a.split(":", 3); if (s.length == 3) ask(p, s[1], s[2], "&eNhập giá trị cho " + s[2] + ":"); return; }
        if (a.startsWith("abilities:")) { openAbilities(p, a.substring(10)); return; }
        if (a.startsWith("removeability:")) { String[] s = a.split(":", 3); if (s.length == 3) { YamlConfiguration c = items.get(s[1]); if (c != null) c.set("abilities." + s[2], null); openAbilities(p, s[1]); } return; }
        if (a.startsWith("toggle:")) { String[] s = a.split(":"); if (s.length == 3) { YamlConfiguration c = items.get(s[1]); if (c != null) c.set(s[2], !c.getBoolean(s[2])); openEdit(p, s[1]); } return; }
        if (a.startsWith("clearench:")) { String id = a.substring(10); YamlConfiguration c = items.get(id); if (c != null) c.set("enchantments", new LinkedHashMap<>()); openEdit(p, id); return; }
        if (a.startsWith("clearlore:")) { String id = a.substring(10); YamlConfiguration c = items.get(id); if (c != null) c.set("lore", new ArrayList<>()); openEdit(p, id); return; }
        if (a.startsWith("preview:")) { String id = a.substring(8); ItemStack it = build(id, 1); if (it != null) p.getInventory().addItem(it); msg(p, "&aĐã đưa bản xem thử vào túi đồ."); return; }
        if (a.startsWith("duplicate:")) { duplicate(p, a.substring(9)); return; }
        if (a.startsWith("save:")) { String id = a.substring(5); try { save(id); msg(p, "&aĐã lưu " + id + ".yml"); openEdit(p, id); } catch (IOException ex) { msg(p, "&cLỗi lưu: " + ex.getMessage()); } return; }
        if (a.startsWith("delete:")) { String id = a.substring(7); if (file(id).exists() && !file(id).delete()) { msg(p, "&cKhông xóa được file."); return; } items.remove(id); msg(p, "&cĐã xóa " + id); openMain(p, 0); }
    }

    private void duplicate(Player p, String id) {
        YamlConfiguration old = items.get(id); if (old == null) return; String copy = id + "_copy"; int n = 2; while (items.containsKey(copy) || file(copy).exists()) copy = id + "_copy" + n++;
        YamlConfiguration clone = new YamlConfiguration(); for (String key : old.getKeys(true)) clone.set(key, old.get(key)); clone.set("id", copy); items.put(copy, clone);
        try { save(copy); msg(p, "&aĐã sao chép thành " + copy); openEdit(p, copy); } catch (IOException ex) { items.remove(copy); msg(p, "&cKhông lưu được bản sao."); }
    }

    private void ask(Player p, String id, String field, String text) { prompts.put(p.getUniqueId(), new Prompt(id, field)); p.closeInventory(); msg(p, text); msg(p, "&7Gõ cancel để hủy."); }
    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        Prompt pr = prompts.remove(e.getPlayer().getUniqueId()); if (pr == null) return; e.setCancelled(true); String value = e.getMessage().trim(); Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> applyPrompt(p, pr, value));
    }
    private void applyPrompt(Player p, Prompt pr, String v) {
        if (!p.isOnline() || !admin(p)) return; if (v.equalsIgnoreCase("cancel")) { msg(p, "&cĐã hủy."); openMain(p, 0); return; }
        if (pr.field.equals("newid")) {
            String id = v.toLowerCase(Locale.ROOT); if (!id.matches("[a-z0-9_-]{1,40}") || items.containsKey(id) || file(id).exists()) { msg(p, "&cID không hợp lệ hoặc đã tồn tại."); openMain(p, 0); return; }
            YamlConfiguration c = new YamlConfiguration(); c.set("id", id); c.set("material", "DIAMOND_SWORD"); c.set("name", "&c" + id); c.set("lore", List.of("&7NIGHTMARE TCYT")); c.set("enchantments", new LinkedHashMap<>()); c.set("abilities", new LinkedHashMap<>()); c.set("unbreakable", false); c.set("upgrades.max-level", 5); c.set("upgrades.levels.2.exp-cost", 5); c.set("upgrades.levels.2.material", "DIAMOND"); c.set("upgrades.levels.2.material-amount", 1); c.set("upgrades.levels.2.damage-bonus", 1.0); items.put(id, c);
            try { save(id); openEdit(p, id); } catch (IOException ex) { items.remove(id); msg(p, "&cTạo thất bại: " + ex.getMessage()); } return;
        }
        YamlConfiguration c = items.get(pr.id); if (c == null) { msg(p, "&cItem không tồn tại."); return; }
        try {
            switch (pr.field) {
                case "name" -> c.set("name", v);
                case "material" -> { Material m = Material.matchMaterial(v.toUpperCase(Locale.ROOT)); if (m == null || !m.isItem()) throw new IllegalArgumentException(); c.set("material", m.name()); }
                case "lore" -> { List<String> l = new ArrayList<>(c.getStringList("lore")); l.add(v); c.set("lore", l); }
                case "model" -> { int n = Integer.parseInt(v); if (n < 0) throw new IllegalArgumentException(); if (n == 0) c.set("custom-model-data", null); else c.set("custom-model-data", n); }
                case "enchant" -> { String[] s = v.split("\\s+"); if (s.length != 2) throw new IllegalArgumentException(); Enchantment en = Enchantment.getByName(s[0].toUpperCase(Locale.ROOT)); int lvl = Integer.parseInt(s[1]); if (en == null || lvl < 1 || lvl > 255) throw new IllegalArgumentException(); c.set("enchantments." + en.getName(), lvl); }
                case "ability" -> addAbility(c, v);
                case "upgrade" -> configureUpgrade(c, v);
                default -> throw new IllegalArgumentException();
            }
            msg(p, "&aĐã cập nhật. Bấm LƯU YAML để ghi file."); openEdit(p, pr.id);
        } catch (Exception ex) { msg(p, "&cDữ liệu không hợp lệ. Kiểm tra cú pháp và thử lại."); openEdit(p, pr.id); }
    }
    private void addAbility(YamlConfiguration c, String v) {
        String[] s = v.split("\\|", -1); if (s.length < 5 || s.length > 8) throw new IllegalArgumentException();
        String id = s[0].trim().toLowerCase(Locale.ROOT), trigger = s[1].trim().toLowerCase(Locale.ROOT), type = s[2].trim().toUpperCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,32}") || !Set.of("right", "sneak_right", "left", "sneak_left", "on_hit").contains(trigger) || !Set.of("DAMAGE", "POTION", "LIGHTNING", "HEAL", "EXPLOSION", "PULL", "PUSH").contains(type)) throw new IllegalArgumentException();
        double amount = Double.parseDouble(s[3]); int cd = Integer.parseInt(s[4]); if (!Double.isFinite(amount) || amount < 0 || amount > 2048 || cd < 0 || cd > 86400) throw new IllegalArgumentException();
        String path = "abilities." + id; if (c.contains(path)) throw new IllegalArgumentException(); c.set(path + ".trigger", trigger); c.set(path + ".type", type); c.set(path + ".amount", amount); c.set(path + ".cooldown", cd);
        if (s.length > 5 && !s[5].isBlank()) { PotionEffectType effect = PotionEffectType.getByName(s[5].trim().toUpperCase(Locale.ROOT)); if (effect == null) throw new IllegalArgumentException(); c.set(path + ".effect", effect.getName()); }
        if (s.length > 6 && !s[6].isBlank()) { int duration = Integer.parseInt(s[6]); if (duration < 1 || duration > 86400) throw new IllegalArgumentException(); c.set(path + ".duration", duration); }
        if (s.length > 7 && !s[7].isBlank()) { int amplifier = Integer.parseInt(s[7]); if (amplifier < 0 || amplifier > 255) throw new IllegalArgumentException(); c.set(path + ".amplifier", amplifier); }
    }
    private void configureUpgrade(YamlConfiguration c, String v) {
        String[] s = v.split("\\|", -1); if (s.length != 5) throw new IllegalArgumentException(); int max = Integer.parseInt(s[0]), exp = Integer.parseInt(s[1]), amount = Integer.parseInt(s[3]); Material m = Material.matchMaterial(s[2].toUpperCase(Locale.ROOT)); double bonus = Double.parseDouble(s[4]);
        if (max < 1 || max > 100 || exp < 0 || amount < 0 || m == null || !m.isItem() || !Double.isFinite(bonus) || bonus < 0) throw new IllegalArgumentException();
        c.set("upgrades.max-level", max); for (int level = 2; level <= max; level++) { String path = "upgrades.levels." + level; c.set(path + ".exp-cost", exp); c.set(path + ".material", m.name()); c.set(path + ".material-amount", amount); c.set(path + ".damage-bonus", bonus); }
    }

    private void upgradeHeld(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand(); if (held.getType().isAir() || !held.hasItemMeta()) { msg(p, "&cHãy cầm vật phẩm NightmareItems trên tay chính."); return; }
        ItemMeta meta = held.getItemMeta(); String id = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING); if (id == null || !items.containsKey(id)) { msg(p, "&cĐây không phải vật phẩm NightmareItems."); return; }
        YamlConfiguration c = items.get(id); int level = meta.getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 1); int next = level + 1;
        if (next > c.getInt("upgrades.max-level", 1)) { msg(p, "&eVật phẩm đã đạt cấp tối đa."); return; }
        String path = "upgrades.levels." + next; int exp = c.getInt(path + ".exp-cost", 0), count = c.getInt(path + ".material-amount", 0); Material material = Material.matchMaterial(c.getString(path + ".material", "AIR"));
        if (p.getLevel() < exp) { msg(p, "&cCần " + exp + " cấp kinh nghiệm."); return; }
        if (count > 0 && material != null && material != Material.AIR && !p.getInventory().containsAtLeast(new ItemStack(material), count)) { msg(p, "&cThiếu nguyên liệu: " + material + " x" + count); return; }
        p.setLevel(p.getLevel() - exp); if (count > 0 && material != null && material != Material.AIR) removeMaterial(p, material, count);
        ItemStack upgraded = build(id, next); if (upgraded == null) return;
        double bonus = c.getDouble(path + ".damage-bonus", 0); if (bonus > 0) { ItemMeta um = upgraded.getItemMeta(); um.setLore(append(um.getLore(), color("&cSát thương nâng cấp: +" + bonus))); upgraded.setItemMeta(um); }
        p.getInventory().setItemInMainHand(upgraded); p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.2f); msg(p, "&aNâng cấp thành công: &eCấp " + next);
    }
    private List<String> append(List<String> src, String s) { List<String> out = src == null ? new ArrayList<>() : new ArrayList<>(src); out.add(s); return out; }
    private void removeMaterial(Player p, Material m, int amount) { for (ItemStack it : p.getInventory().getStorageContents()) { if (it == null || it.getType() != m) continue; int take = Math.min(amount, it.getAmount()); it.setAmount(it.getAmount() - take); amount -= take; if (amount <= 0) break; } }

    @EventHandler(ignoreCancelled = true) public void onInteract(PlayerInteractEvent e) {
        ItemStack held = e.getItem(); if (held == null || !held.hasItemMeta()) return; String id = held.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING); if (id == null) return;
        String trigger = e.getAction().isRightClick() ? (e.getPlayer().isSneaking() ? "sneak_right" : "right") : e.getAction().isLeftClick() ? (e.getPlayer().isSneaking() ? "sneak_left" : "left") : null;
        if (trigger == null) return; YamlConfiguration c = items.get(id); if (c == null) return; runAbilities(e.getPlayer(), id, c, trigger, null);
    }
    @EventHandler(ignoreCancelled = true) public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return; ItemStack held = p.getInventory().getItemInMainHand(); if (!held.hasItemMeta()) return;
        String id = held.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING); if (id == null) return; YamlConfiguration c = items.get(id); if (c != null) runAbilities(p, id, c, "on_hit", e.getEntity());
    }
    private void runAbilities(Player p, String itemId, YamlConfiguration c, String trigger, Entity hit) {
        ConfigurationSection sec = c.getConfigurationSection("abilities"); if (sec != null) for (String key : sec.getKeys(false)) { ConfigurationSection ab = sec.getConfigurationSection(key); if (ab != null && ab.getString("trigger", "").equalsIgnoreCase(trigger)) execute(p, itemId, key, ab, hit); }
        // Backward compatibility with the original blood_blade.yml format.
        String legacy = "skills." + trigger; ConfigurationSection old = c.getConfigurationSection(legacy); if (old != null) execute(p, itemId, "legacy_" + trigger, old, hit);
    }
    private void execute(Player p, String itemId, String skillId, ConfigurationSection s, Entity hit) {
        String key = p.getUniqueId() + ":" + itemId + ":" + skillId; long now = System.currentTimeMillis(), until = cooldowns.getOrDefault(key, 0L);
        if (until > now) { p.sendActionBar(color("&cCooldown: " + ((until - now + 999) / 1000) + "s")); return; }
        int cd = Math.max(0, s.getInt("cooldown", 3)); if (cd > 0) cooldowns.put(key, now + cd * 1000L);
        String type = s.getString("type", "DAMAGE").toUpperCase(Locale.ROOT); double amount = Math.max(0, s.getDouble("amount", 4));
        LivingEntity target = hit instanceof LivingEntity le ? le : rayTarget(p, 6);
        switch (type) {
            case "DAMAGE" -> { if (target != null) { target.damage(amount, p); target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 12, .3, .4, .3, .1); } else msg(p, "&7Không có mục tiêu trong tầm."); }
            case "POTION" -> { PotionEffectType effect = PotionEffectType.getByName(s.getString("effect", "SPEED").toUpperCase(Locale.ROOT)); if (effect != null) p.addPotionEffect(new PotionEffect(effect, Math.max(1, s.getInt("duration", 5)) * 20, Math.max(0, s.getInt("amplifier", 0)))); }
            case "LIGHTNING" -> { if (target != null) p.getWorld().strikeLightningEffect(target.getLocation()); else { var b = p.getTargetBlockExact(20); if (b != null) p.getWorld().strikeLightningEffect(b.getLocation()); } }
            case "HEAL" -> p.setHealth(Math.min(Objects.requireNonNull(p.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue(), p.getHealth() + amount));
            case "EXPLOSION" -> { if (target != null) p.getWorld().createExplosion(target.getLocation(), (float) Math.min(4, amount), false, false, p); }
            case "PULL" -> { if (target != null) target.setVelocity(p.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(Math.min(3, Math.max(.1, amount)))); }
            case "PUSH" -> { if (target != null) target.setVelocity(target.getLocation().toVector().subtract(p.getLocation().toVector()).normalize().multiply(Math.min(3, Math.max(.1, amount)))); }
            default -> msg(p, "&cSkill không hợp lệ: " + type);
        }
        String message = s.getString("message", ""); if (!message.isBlank()) msg(p, message);
    }
    private LivingEntity rayTarget(Player p, double range) {
        return p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, .5,
                entity -> entity instanceof LivingEntity && entity != p) == null ? null :
                (LivingEntity) p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, .5,
                        entity -> entity instanceof LivingEntity && entity != p).getHitEntity();
    }
}
