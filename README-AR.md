# GawharaPluginManager 1.0.0

مدير بسيط لتحميل **إضافات Bukkit/Spigot جديدة** أثناء تشغيل Paper، بدون إعادة تشغيل السيرفر، باستخدام `PluginManager.loadPlugin(File)` ثم `PluginManager.enablePlugin(plugin)`.

## مهم جدًا - الحدود التقنية

- هذا ليس نظام Hot Reload كاملًا.
- يمكنه محاولة تحميل **إضافة جديدة** لا يوجد Plugin بنفس اسمها محمّل حاليًا.
- لا يمكنه تحديث/استبدال إضافة شغالة بأمان أو تفريغ كلاسّاتها من الذاكرة؛ تحديث إضافة موجودة يحتاج Restart.
- بعض الإضافات لا تدعم runtime loading لأنها تفترض أن `onLoad()` يحدث أثناء إقلاع السيرفر، أو لأنها تحتاج registries/commands/world initialization مبكرًا. لذلك التحميل Best-effort وليس ضمانًا لكل إضافة.
- الإضافات التي تتطلب dependencies يجب أن تكون متوفرة ومفعّلة أولًا. الماسح يحاول أكثر من دورة لترتيب الاعتماديات الموجودة في dropbox.
- النسخة الحالية تقبل Bukkit-style `plugin.yml`، ولا تقبل Paper-only `paper-plugin.yml`.
- لا ترفع JARs من مصدر غير موثوق. ملف JAR كود تنفيذي كامل بصلاحيات السيرفر.

## طريقة الاستخدام

1. ضع `GawharaPluginManager-1.0.0.jar` في `plugins/` وشغّل Paper مرة واحدة.
2. سيُنشأ `plugins/dropbox/`.
3. ضع **إضافة جديدة** بصيغة `.jar` داخل `plugins/dropbox/`.
4. الماسح التلقائي يفحص كل 20 ثانية افتراضيًا. أو استخدم `/gpm scan`.
5. عند النجاح، ينسخ JAR إلى مجلد `plugins/` الرئيسي، ويحمله ويفعّله، ثم يحذف نسخة dropbox.

## الأوامر

- `/gpm scan` فحص يدوي.
- `/gpm load <file.jar>` تحميل ملف بعينه من dropbox.
- `/gpm list` عرض الإضافات المحمّلة.
- `/gpm status` حالة الماسح.
- `/gpm reload` إعادة قراءة إعدادات المدير فقط.

الصلاحية: `gawharapm.admin` (افتراضيًا OP).

## البناء

يتطلب JDK 25 وMaven واتصالًا بمستودع Paper Maven.

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

الـ JAR الناتج:
`target/GawharaPluginManager-1.0.0.jar`

## GitHub Actions

تم تضمين `.github/workflows/main.yml` للبناء التلقائي ورفع الـ JAR كـ Artifact.
