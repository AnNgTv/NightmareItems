
package fun.nightmare.tcyt;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NightmareItems extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<String, YamlConfiguration> itemConfigs = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedSkills = new ConcurrentHashMap<>();

    private NamespacedKey itemIdKey;
    private NamespacedKey actionKey;

    private static final String ADMIN = "nightmareitems.admin";
    private static final String MAIN_TITLE = "§4§lNIGHTMARE ITEMS";
    private static final String EDIT_TITLE = "§4§lITEM EDITOR";

    private record Prompt(String id, String field) {}

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemIdKey = new NamespacedKey(this, "item_id");
        actionKey = new NamespacedKey(this, "gui_action");
        loadItems();
        Objects.requireNonNull(getCommand("nitems")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NightmareItems GUI enabled. Items: " + itemConfigs.size());
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private void message(CommandSender sender, String text) {
        sender.sendMessage(color(getConfig().getString(
                "messages.prefix", "&8[&4NightmareItems&8] ") + text));
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN)) return true;
        message(sender, getConfig().getString("messages.no-permission",
                "&cBạn không có quyền."));
        return false;
    }

    private void loadItems() {
        itemConfigs.clear();
        File dir = new File(getDataFolder(), "items");
        if (!dir.exists() && !dir.mkdirs()) {
            getLogger().warning("Cannot create items directory.");
            return;
        }

        File[] files = dir.listFiles((d, n) ->
                n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;

        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            String id = cfg.getString("id",
                    file.getName().replaceFirst("(?i)\\.yml$", ""));
            if (id != null && !id.isBlank()) {
                itemConfigs.put(id.toLowerCase(Locale.ROOT), cfg);
            }
        }
    }

    private File itemFile(String id) {
        return new File(new File(getDataFolder(), "items"), id + ".yml");
    }

    private void saveItem(String id) throws IOException {
        YamlConfiguration cfg = itemConfigs.get(id);
        if (cfg == null) throw new IOException("Item not found: " + id);
        cfg.set("id", id);
        cfg.save(itemFile(id));
    }

    private ItemStack makeButton(Material material, String title,
                                 String action, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(title));
            if (lore.length > 0) {
                List<String> lines = new ArrayList<>();
                for (String line : lore) lines.add(color(line));
                meta.setLore(lines);
            }
            meta.getPersistentDataContainer().set(
                    actionKey, PersistentDataType.STRING, action);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack createItem(String id) {
        YamlConfiguration cfg = itemConfigs.get(id);
        if (cfg == null) return null;

        Material material = Material.matchMaterial(cfg.getString("material", "STONE"));
        if (material == null || !material.isItem()) material = Material.STONE;

        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        meta.setDisplayName(color(cfg.getString("name", id)));
        List<String> lore = new ArrayList<>();
        for (String line : cfg.getStringList("lore")) lore.add(color(line));
        meta.setLore(lore);
        meta.setUnbreakable(cfg.getBoolean("unbreakable", false));
        meta.getPersistentDataContainer().set(
                itemIdKey, PersistentDataType.STRING, id);

        ConfigurationSection enchants = cfg.getConfigurationSection("enchantments");
        if (enchants != null) {
            for (String key : enchants.getKeys(false)) {
                Enchantment enchant = Enchantment.getByName(key.toUpperCase(Locale.ROOT));
                if (enchant != null) {
                    try {
                        meta.addEnchant(enchant,
                                Math.max(1, enchants.getInt(key, 1)), true);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        stack.setItemMeta(meta);
        return stack;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("editor")) {
            if (!(sender instanceof Player player)) {
                message(sender, "&cLệnh này chỉ dùng trong game.");
                return true;
            }
            if (!isAdmin(player)) return true;
            openMain(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            message(sender, "&eItems: &f" + String.join(", ", itemConfigs.keySet()));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!isAdmin(sender)) return true;
            reloadConfig();
            loadItems();
            message(sender, "&aĐã tải lại cấu hình và item.");
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!isAdmin(sender)) return true;
            if (args.length < 3) {
                message(sender, "&cDùng: /nitems give <player> <item_id>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            ItemStack item = createItem(args[2].toLowerCase(Locale.ROOT));
            if (target == null || item == null) {
                message(sender, "&cKhông tìm thấy người chơi hoặc item.");
                return true;
            }
            target.getInventory().addItem(item).values().forEach(left ->
                    target.getWorld().dropItemNaturally(target.getLocation(), left));
            message(sender, "&aĐã đưa item cho " + target.getName());
            return true;
        }

        message(sender, "&e/nitems editor &7| &e/nitems list &7| &e/nitems give <player> <id> &7| &e/nitems reload");
        return true;
    }

    private Inventory newMenu(int size, String title) {
        MenuHolder holder = new MenuHolder();
        holder.inventory = Bukkit.createInventory(holder, size, title);
        return holder.inventory;
    }

    private void openMain(Player player) {
        if (!isAdmin(player)) return;

        Inventory inv = newMenu(54, MAIN_TITLE);
        int slot = 0;

        for (String id : itemConfigs.keySet()) {
            if (slot >= 45) break;
            ItemStack item = createItem(id);
            if (item == null) continue;

            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(
                        actionKey, PersistentDataType.STRING, "edit:" + id);
                List<String> lore = meta.hasLore()
                        ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(color("&eBấm để chỉnh sửa"));
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
        }

        inv.setItem(45, makeButton(Material.EMERALD, "&a&lTẠO ITEM",
                "create", "&7Bấm để tạo vật phẩm mới"));
        inv.setItem(49, makeButton(Material.BOOK, "&eHƯỚNG DẪN",
                "help", "&7Chọn item để chỉnh sửa",
                "&7Tạo item mới bằng nút ngọc lục bảo",
                "&7Nhập dữ liệu qua chat"));
        player.openInventory(inv);
    }

    private String selectedSkill(Player player) {
        return selectedSkills.getOrDefault(player.getUniqueId(), "right");
    }

    private void openEdit(Player player, String id) {
        if (!isAdmin(player)) return;
        YamlConfiguration cfg = itemConfigs.get(id);
        if (cfg == null) {
            message(player, "&cKhông tìm thấy item: " + id);
            openMain(player);
            return;
        }

        String skillPath = "skills." + selectedSkill(player);
        Inventory inv = newMenu(27, EDIT_TITLE);

        inv.setItem(10, makeButton(Material.NAME_TAG, "&eTên: &f" +
                        cfg.getString("name", id),
                "prompt:" + id + ":name", "&7Bấm rồi nhập tên trong chat"));
        inv.setItem(11, makeButton(Material.ITEM_FRAME, "&eMaterial: &f" +
                        cfg.getString("material", "STONE"),
                "prompt:" + id + ":material", "&7Nhập Material Bukkit"));
        inv.setItem(12, makeButton(Material.PAPER, "&eThêm Lore",
                "prompt:" + id + ":lore", "&7Nhập một dòng lore mới"));
        inv.setItem(13, makeButton(Material.BARRIER, "&cXóa toàn bộ Lore",
                "clearlore:" + id, "&7Xóa danh sách lore"));
        inv.setItem(14, makeButton(Material.ENCHANTED_BOOK, "&dThêm enchant",
                "prompt:" + id + ":enchant",
                "&7Nhập dạng: SHARPNESS 5"));
        inv.setItem(15, makeButton(Material.NETHER_STAR, "&5Skill: &f" +
                        selectedSkill(player).toUpperCase(Locale.ROOT),
                "cycleskill", "&7Bấm để chọn right/sneak_right/left/sneak_left"));
        inv.setItem(16, makeButton(Material.BLAZE_POWDER, "&cLoại skill: &f" +
                        cfg.getString(skillPath + ".type", "DAMAGE"),
                "cycleskilltype:" + id, "&7Bấm để đổi loại kỹ năng"));
        inv.setItem(17, makeButton(Material.CLOCK, "&eCooldown: &f" +
                        cfg.getInt(skillPath + ".cooldown", 3) + " giây",
                "prompt:" + id + ":cooldown", "&7Nhập số giây"));
        inv.setItem(18, makeButton(Material.REDSTONE, "&cDamage/Heal: &f" +
                        cfg.getDouble(skillPath + ".amount", 4.0),
                "prompt:" + id + ":amount", "&7Nhập số, ví dụ 8"));
        inv.setItem(19, makeButton(Material.POTION, "&bPotion effect: &f" +
                        cfg.getString(skillPath + ".effect", "SPEED"),
                "prompt:" + id + ":effect", "&7Ví dụ SPEED hoặc INCREASE_DAMAGE"));
        inv.setItem(20, makeButton(Material.SUGAR, "&bPotion duration: &f" +
                        cfg.getInt(skillPath + ".duration", 5) + " giây",
                "prompt:" + id + ":duration", "&7Nhập số giây"));
        inv.setItem(21, makeButton(Material.GLOWSTONE_DUST, "&bPotion amplifier: &f" +
                        cfg.getInt(skillPath + ".amplifier", 0),
                "prompt:" + id + ":amplifier", "&7Cấp hiệu ứng: 0 = cấp I"));
        inv.setItem(22, makeButton(Material.LIME_DYE, "&a&lLƯU YAML",
                "save:" + id, "&7Ghi thay đổi vào file item"));
        inv.setItem(24, makeButton(Material.TNT, "&c&lXÓA ITEM",
                "delete:" + id, "&7Xóa file YAML của item"));
        inv.setItem(26, makeButton(Material.ARROW, "&fQuay lại",
                "back", "&7Danh sách item"));

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder)) return;

        event.setCancelled(true);
        if (!isAdmin(player)) {
            player.closeInventory();
            return;
        }
        if (event.getClickedInventory() != top) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        if (action.equals("create")) {
            ask(player, "", "newid", "Nhập ID mới (chữ thường, số, gạch dưới):");
        } else if (action.equals("help")) {
            message(player, "&eChọn item để chỉnh sửa. Các trường được nhập qua chat.");
        } else if (action.equals("back")) {
            openMain(player);
        } else if (action.startsWith("edit:")) {
            openEdit(player, action.substring(5));
        } else if (action.startsWith("prompt:")) {
            String[] parts = action.split(":", 3);
            if (parts.length == 3) {
                player.closeInventory();
                ask(player, parts[1], parts[2], "&eNhập giá trị cho &f" + parts[2] + "&e trong chat:");
            }
        } else if (action.startsWith("clearlore:")) {
            String id = action.substring("clearlore:".length());
            YamlConfiguration cfg = itemConfigs.get(id);
            if (cfg != null) cfg.set("lore", new ArrayList<>());
            openEdit(player, id);
        } else if (action.equals("cycleskill")) {
            List<String> skills = List.of("right", "sneak_right", "left", "sneak_left");
            int index = skills.indexOf(selectedSkill(player));
            selectedSkills.put(player.getUniqueId(), skills.get((index + 1) % skills.size()));
            String id = findCurrentId(top);
            if (id != null) openEdit(player, id);
            else openMain(player);
        } else if (action.startsWith("cycleskilltype:")) {
            String id = action.substring("cycleskilltype:".length());
            YamlConfiguration cfg = itemConfigs.get(id);
            if (cfg == null) return;
            String path = "skills." + selectedSkill(player) + ".type";
            List<String> types = List.of("DAMAGE", "POTION", "LIGHTNING", "HEAL");
            String current = cfg.getString(path, "DAMAGE").toUpperCase(Locale.ROOT);
            int index = types.indexOf(current);
            cfg.set(path, types.get((index + 1 + types.size()) % types.size()));
            openEdit(player, id);
        } else if (action.startsWith("save:")) {
            String id = action.substring(5);
            try {
                saveItem(id);
                loadItems();
                message(player, "&aĐã lưu " + id + ".yml");
                openEdit(player, id);
            } catch (IOException ex) {
                getLogger().severe("Save item failed: " + ex.getMessage());
                message(player, "&cLưu thất bại: " + ex.getMessage());
            }
        } else if (action.startsWith("delete:")) {
            String id = action.substring(7);
            File file = itemFile(id);
            if (file.exists() && !file.delete()) {
                message(player, "&cKhông xóa được file. Kiểm tra quyền ghi.");
                return;
            }
            itemConfigs.remove(id);
            message(player, "&cĐã xóa item " + id);
            openMain(player);
        }
    }

    private String findCurrentId(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            String action = item.getItemMeta().getPersistentDataContainer()
                    .get(actionKey, PersistentDataType.STRING);
            if (action != null && action.startsWith("edit:")) return action.substring(5);
            if (action != null && action.startsWith("save:")) return action.substring(5);
            if (action != null && action.startsWith("delete:")) return action.substring(7);
            if (action != null && action.startsWith("cycleskilltype:"))
                return action.substring("cycleskilltype:".length());
            if (action != null && action.startsWith("prompt:")) {
                String[] p = action.split(":", 3);
                if (p.length == 3) return p[1];
            }
            if (item.getItemMeta().getPersistentDataContainer()
                    .has(itemIdKey, PersistentDataType.STRING)) {
                return item.getItemMeta().getPersistentDataContainer()
                        .get(itemIdKey, PersistentDataType.STRING);
            }
        }
        return null;
    }

    private void ask(Player player, String id, String field, String prompt) {
        prompts.put(player.getUniqueId(), new Prompt(id, field));
        player.closeInventory();
        message(player, prompt);
        message(player, "&7Gõ &ccancel &7để hủy.");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) return;

        event.setCancelled(true);
        String value = event.getMessage().trim();

        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            if (!isAdmin(player)) return;
            if (value.equalsIgnoreCase("cancel")) {
                message(player, "&cĐã hủy.");
                openMain(player);
                return;
            }

            String id = prompt.id();
            String field = prompt.field();

            if (field.equals("newid")) {
                String newId = value.toLowerCase(Locale.ROOT);
                if (!newId.matches("[a-z0-9_-]{1,40}")) {
                    message(player, "&cID chỉ được có chữ thường, số, _ hoặc - (tối đa 40 ký tự).");
                    ask(player, "", "newid", "&eNhập ID hợp lệ:");
                    return;
                }
                if (itemConfigs.containsKey(newId) || itemFile(newId).exists()) {
                    message(player, "&cID đã tồn tại.");
                    openMain(player);
                    return;
                }
                YamlConfiguration cfg = new YamlConfiguration();
                cfg.set("id", newId);
                cfg.set("material", "DIAMOND_SWORD");
                cfg.set("name", "&c" + newId);
                cfg.set("lore", List.of("&7NIGHTMARE TCYT"));
                cfg.set("enchantments", new LinkedHashMap<String, Object>());
                cfg.set("unbreakable", false);
                cfg.set("skills.right.type", "DAMAGE");
                cfg.set("skills.right.amount", 4.0);
                cfg.set("skills.right.cooldown", 3);
                itemConfigs.put(newId, cfg);
                try {
                    saveItem(newId);
                    message(player, "&aĐã tạo " + newId + ".yml");
                    openEdit(player, newId);
                } catch (IOException ex) {
                    itemConfigs.remove(newId);
                    message(player, "&cTạo item thất bại: " + ex.getMessage());
                }
                return;
            }

            YamlConfiguration cfg = itemConfigs.get(id);
            if (cfg == null) {
                message(player, "&cItem không còn tồn tại.");
                openMain(player);
                return;
            }

            String skill = "skills." + selectedSkill(player) + ".";
            try {
                switch (field) {
                    case "name" -> cfg.set("name", value);
                    case "material" -> {
                        Material material = Material.matchMaterial(value.toUpperCase(Locale.ROOT));
                        if (material == null || !material.isItem()) {
                            message(player, "&cMaterial không hợp lệ.");
                            openEdit(player, id);
                            return;
                        }
                        cfg.set("material", material.name());
                    }
                    case "lore" -> {
                        List<String> lore = new ArrayList<>(cfg.getStringList("lore"));
                        lore.add(value);
                        cfg.set("lore", lore);
                    }
                    case "enchant" -> {
                        String[] parts = value.split("\\s+");
                        if (parts.length != 2) {
                            message(player, "&cDùng: SHARPNESS 5");
                            openEdit(player, id);
                            return;
                        }
                        Enchantment enchant = Enchantment.getByName(parts[0].toUpperCase(Locale.ROOT));
                        int level = Integer.parseInt(parts[1]);
                        if (enchant == null || level < 1 || level > 255) {
                            message(player, "&cEnchant hoặc cấp độ không hợp lệ.");
                            openEdit(player, id);
                            return;
                        }
                        cfg.set("enchantments." + enchant.getName(), level);
                    }
                    case "cooldown" -> {
                        int seconds = Integer.parseInt(value);
                        if (seconds < 0 || seconds > 86400) throw new IllegalArgumentException();
                        cfg.set(skill + "cooldown", seconds);
                    }
                    case "amount" -> {
                        double amount = Double.parseDouble(value);
                        if (!Double.isFinite(amount) || amount < 0 || amount > 2048)
                            throw new IllegalArgumentException();
                        cfg.set(skill + "amount", amount);
                    }
                    case "effect" -> {
                        PotionEffectType type = PotionEffectType.getByName(value.toUpperCase(Locale.ROOT));
                        if (type == null) {
                            message(player, "&cPotion effect không hợp lệ.");
                            openEdit(player, id);
                            return;
                        }
                        cfg.set(skill + "effect", type.getName());
                    }
                    case "duration" -> {
                        int seconds = Integer.parseInt(value);
                        if (seconds < 1 || seconds > 86400) throw new IllegalArgumentException();
                        cfg.set(skill + "duration", seconds);
                    }
                    case "amplifier" -> {
                        int level = Integer.parseInt(value);
                        if (level < 0 || level > 255) throw new IllegalArgumentException();
                        cfg.set(skill + "amplifier", level);
                    }
                    default -> {
                        message(player, "&cTrường không hỗ trợ.");
                        openEdit(player, id);
                        return;
                    }
                }
            } catch (IllegalArgumentException ex) {
                message(player, "&cGiá trị không hợp lệ. Hãy thử lại.");
                openEdit(player, id);
                return;
            }

            message(player, "&aĐã cập nhật trong bộ nhớ. Bấm LƯU YAML để ghi file.");
            openEdit(player, id);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack held = event.getItem();
        if (held == null || !held.hasItemMeta()) return;

        String id = held.getItemMeta().getPersistentDataContainer()
                .get(itemIdKey, PersistentDataType.STRING);
        if (id == null) return;

        YamlConfiguration cfg = itemConfigs.get(id);
        if (cfg == null) return;

        String path = null;
        if (event.getAction().isRightClick())
            path = event.getPlayer().isSneaking() ? "skills.sneak_right" : "skills.right";
        else if (event.getAction().isLeftClick())
            path = event.getPlayer().isSneaking() ? "skills.sneak_left" : "skills.left";

        if (path == null || !cfg.isConfigurationSection(path)) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        ConfigurationSection skill = cfg.getConfigurationSection(path);
        if (skill == null) return;

        String key = player.getUniqueId() + ":" + id + ":" + path;
        long now = System.currentTimeMillis();
        long until = cooldowns.getOrDefault(key, 0L);
        if (until > now) {
            player.sendActionBar(color("&cCooldown: " + ((until - now + 999) / 1000) + "s"));
            return;
        }

        int cd = Math.max(0, skill.getInt("cooldown",
                getConfig().getInt("settings.default-cooldown-seconds", 3)));
        if (cd > 0) cooldowns.put(key, now + cd * 1000L);

        String type = skill.getString("type", "DAMAGE").toUpperCase(Locale.ROOT);
        switch (type) {
            case "DAMAGE" -> {
                if (player.getTargetEntity(6) instanceof org.bukkit.entity.LivingEntity target) {
                    target.damage(Math.max(0, skill.getDouble("amount", 4.0)), player);
                    player.getWorld().spawnParticle(Particle.CRIT,
                            target.getLocation().add(0, 1, 0), 12, .3, .4, .3, .1);
                } else message(player, "&7Không có mục tiêu trong tầm 6 block.");
            }
            case "POTION" -> {
                PotionEffectType effect = PotionEffectType.getByName(
                        skill.getString("effect", "SPEED").toUpperCase(Locale.ROOT));
                if (effect != null) player.addPotionEffect(new PotionEffect(
                        effect, Math.max(1, skill.getInt("duration", 5)) * 20,
                        Math.max(0, skill.getInt("amplifier", 0))));
            }
            case "LIGHTNING" -> {
                Location target = player.getTargetBlockExact(20) == null
                        ? null : player.getTargetBlockExact(20).getLocation();
                if (target != null) player.getWorld().strikeLightningEffect(target);
            }
            case "HEAL" -> player.setHealth(Math.min(player.getMaxHealth(),
                    player.getHealth() + Math.max(0, skill.getDouble("amount", 4.0))));
            default -> message(player, "&cSkill không hợp lệ: " + type);
        }

        String msg = skill.getString("message", "");
        if (!msg.isBlank()) player.sendMessage(color(msg));
    }
}
