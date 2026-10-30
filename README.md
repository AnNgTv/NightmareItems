# NightmareItems v2

Plugin quản lý vật phẩm RPG cho Paper 1.20.6, Java 21. Build được bằng Gradle hoặc Maven.

## Chức năng hiện có
- GUI quản lý item: tạo, sửa tên/material/lore, xem thử, sao chép, xóa, phân trang.
- Enchant vanilla cấp 1-255 (bỏ qua giới hạn cấp vanilla), xóa toàn bộ enchant.
- Nhiều ability trên một item; trigger: `right`, `sneak_right`, `left`, `sneak_left`, `on_hit`.
- Ability type: `DAMAGE`, `POTION`, `LIGHTNING`, `HEAL`, `EXPLOSION`, `PULL`, `PUSH`.
- Nâng cấp item theo level, tiêu hao XP và vật liệu; cộng attack damage cho kiếm/rìu/đinh ba.
- CustomModelData, Unbreakable, YAML item files.
- Tương thích cấu hình skill cũ dạng `skills.right` của bản trước.

## Lệnh
- `/nitems editor` - mở GUI (permission `nightmareitems.admin`).
- `/nitems list` - danh sách ID.
- `/nitems give <player> <id> [level]` - cấp item (admin).
- `/nitems upgrade` - nâng cấp item đang cầm (người chơi).
- `/nitems reload` - tải lại cấu hình (admin).

## Thêm ability trong GUI
Mở editor -> chọn item -> Thêm kỹ năng. Nhập: `id|trigger|type|amount|cooldown`

Ví dụ: `blood_slash|right|DAMAGE|8|5`

Với potion có thể thêm các trường: `id|trigger|POTION|amount|cooldown|effect|seconds|amplifier`

Ví dụ: `rage|sneak_right|POTION|1|10|INCREASE_DAMAGE|5|1`

## Cấu hình nâng cấp trong GUI
Nhập: `max|exp|material|amount|damage`

Ví dụ: `5|10|DIAMOND|2|2.5`

Thiết lập này áp dụng cùng chi phí và bonus cho mỗi cấp từ 2 đến max. Người chơi cầm item ở tay chính và chạy `/nitems upgrade`.

## Build trên Termux
Cài Java 21 và Gradle 8.7 trở lên, sau đó trong thư mục dự án chạy:

```bash
gradle --no-daemon clean build
```

JAR ở `build/libs/`. Hoặc Maven:

```bash
mvn clean package
```

JAR Maven ở `target/`. Không upload thư mục `target/` hoặc `build/` lên Git.

## Lưu ý
Đây là bản nâng cấp mã nguồn v2; cần build và thử trên máy chủ staging trước khi thay JAR đang chạy. EXPLOSION đang tạo nổ không phá block và không gây cháy. Skill `on_hit` chạy theo sự kiện đánh trúng.
