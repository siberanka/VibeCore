# VibeCore

> Türkçe: [aşağıda](#türkçe)

[![Status](https://img.shields.io/badge/status-under%20development-orange)](#development-status)
[![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-6d5dfc)](CHANGELOG.md)
[![Paper](https://img.shields.io/badge/Paper-1.21.4%2B-3fa9f5)](docs/COMPATIBILITY.md)
[![Java](https://img.shields.io/badge/Java-25-e76f00)](docs/COMPATIBILITY.md)
[![License: VibeCore License (PolyForm Noncommercial 1.0.0 with additional terms)](https://img.shields.io/badge/license-VibeCore%20License%20%28PolyForm%20NC%20%2B%20terms%29-blue.svg)](LICENSE)

VibeCore is a modular Paper plugin for server interfaces and visual content: inventory menus, cursor-driven screens, HUDs and pop-ups, holograms, rank badges and resource pack generation, managed through one project model, one condition/action/variable engine and one visual workspace.

## Development status

> **VibeCore is under construction.** There is no release yet and it is not ready for production servers. The feature modules listed below are planned; only the core foundation is implemented. Configuration, commands and data formats may still change.

| Area | Status |
| --- | --- |
| Core: lifecycle, validated configuration and safe reload, Turkish and English messages, module states, bounded workers, diagnostics | Implemented, unit tested; not yet run on a live server |
| Shared model: identifiers, schema migrations, typed and scoped variables with a variable store, persistent glyph and model data registry | Implemented, unit tested |
| Condition and action engine: comparisons, branches, fail gates, variable actions with all-or-nothing commit | Implemented, unit tested |
| Inventory menus: YAML menus with states, visibility, click actions and protected sessions | Implemented, unit tested; not yet run on a live server |
| Shop actions: money through Vault, plain-item take and give, affordability conditions, reverting transactions | Implemented, unit tested; not yet run on a live server |
| Command actions, paginated and generated shops | Planned |
| Cursor screens, HUDs and pop-ups | Planned |
| Holograms, slides and motion | Planned |
| Rank badges and pixel tools | Planned |
| Java and Bedrock resource packs | Planned |
| Visual editor and deployment | Planned |
| Bedrock access through an optional bridge | Planned |

Enabling a planned module in `config.yml` reports it as `not-implemented` in `/vibecore status`; nothing claims to work before it does. See [the roadmap](docs/ROADMAP.md).

## Requirements

- Paper 1.21.4 or newer (each later version needs its own acceptance run; see [compatibility](docs/COMPATIBILITY.md))
- Java 25 on the server

## Building

Requirements: JDK 25 and Apache Maven 3.9 or newer.

```sh
mvn verify
```

The plugin JAR is written to `target/`.

## Commands and permissions

| Command | Permission | Default |
| --- | --- | --- |
| `/vibecore help` | `vibecore.command.help` | everyone |
| `/vibecore status` | `vibecore.admin.status` | operators |
| `/vibecore diagnostics` | `vibecore.admin.diagnostics` | operators |
| `/vibecore reload` | `vibecore.admin.reload` | operators |
| `/vibecore menu open <menu>` | `vibecore.menu.open` and the menu's own permission | everyone |
| `/vibecore menu open <menu> <player>` | `vibecore.menu.open.others` | operators |
| `/vibecore menu close [player]` | `vibecore.menu.close.others` for other players | operators |
| `/vibecore menu list` | `vibecore.menu.list` | operators |

`vibecore.admin` grants exactly the administration permissions listed above. Help and tab completion show only the commands the sender may use.

`/vibecore reload` validates `config.yml` and the message files as a whole on a background worker. If any problem is found, the previous configuration stays active and the problems are reported.

## Menus

Enable the module with `modules.menu: true` in `config.yml`. Menus are YAML files in `plugins/VibeCore/menus/`; `menus/shop/main.yml` becomes the menu `shop/main`. Variables are declared in `variables.yml`, and persistent values are saved to `data/variables.txt`. The bundled `menus/example.yml` shows states, visibility, sounds, messages and a reset with a fail gate; open it with `/vibecore menu open example`.

- Every interaction with an open menu is cancelled: clicks in either inventory, shift and number-key moves, double clicks, drags, drops and off-hand swaps. Only left, right, shift-left, shift-right and middle clicks on a visible item run that item's actions.
- The menu permission is checked again on every click, and each action chain re-checks its own conditions. Variable changes of a chain are saved all or none.
- Clicks are debounced per session, and a click is ignored while the previous one is still running.
- Close actions run exactly once and cannot reopen a menu.
- `cooldown: {var: daily, seconds: 86400, fail: "<red>Wait <remaining>"}` blocks a chain until the period since the time stored in a number variable has passed; the variable's scope makes it per session, per player or global.
- Shop effects `money-take`, `money-give`, `item-take` and `item-give` and the conditions `has-money`, `has-item` and `has-permission` work in any chain. Money needs Vault and an economy plugin. Only plain items are counted and taken, so a named or enchanted item is never consumed. Takes run before gives within one snapshot; if any step or the variable changes fail, the earlier steps are reverted, and a revert that fails is recorded in `data/transactions.log`.
- `/vibecore reload` validates every menu and variable file together; an invalid file keeps the previous menus in effect.

## Development

Read [CONTRIBUTING.md](CONTRIBUTING.md), [the architecture](docs/ARCHITECTURE.md), [the roadmap](docs/ROADMAP.md) and [the acceptance plan](docs/ACCEPTANCE.md). Report problems on [GitHub](https://github.com/siberanka/VibeCore/issues) or [GitLab](https://gitlab.com/siberanka/vibecore/-/issues) and security issues as described in [SECURITY.md](SECURITY.md).

## License

VibeCore is maintained by `siberanka` and licensed under the [VibeCore License](LICENSE): the PolyForm Noncommercial License 1.0.0 together with additional terms ([NOTICE](NOTICE) has a summary):

- You may build, install, change and run VibeCore on any server or network you run or administer, including servers and networks that earn money (store, ranks, subscriptions, donations, advertising).
- You may study it and share copies and changed versions free of charge, with LICENSE and NOTICE.
- You may not sell it: not VibeCore, a copy, a changed version, a fork or a renamed copy, not any part of its source code, not as part of a paid bundle or server setup, and not as a paid product or service whose value comes mainly from it.
- If you copy, quote or adapt any part of VibeCore, even a single code block, credit it where readers see it: name VibeCore and siberanka, link to this repository and say whether you changed it, for example `Adapted from VibeCore by siberanka (https://github.com/siberanka/VibeCore), VibeCore License.`

Anything beyond these terms needs a separate written license from siberanka. Third-party components keep their own licenses; see [NOTICE](NOTICE) and the [dependency policy](docs/DEPENDENCIES.md).

Minecraft, Mojang, Microsoft and Paper names belong to their respective owners and indicate compatibility targets, not affiliation. VibeCore is not an official Minecraft product.

---

## Türkçe

### VibeCore

VibeCore; envanter menüleri, imleçle kullanılan ekranlar, HUD ve açılır bildirimler, hologramlar, rütbe rozetleri ve kaynak paketi üretimini tek proje modeli, ortak koşul/eylem/değişken motoru ve tek görsel çalışma alanıyla yöneten modüler bir Paper eklentisidir.

### Geliştirme durumu

> **VibeCore yapım aşamasındadır.** Henüz bir sürüm yayımlanmadı ve canlı sunucular için hazır değildir. Aşağıdaki özellik modülleri planlanmıştır; şu an yalnız çekirdek temel uygulanmıştır. Yapılandırma, komutlar ve veri biçimleri değişebilir.

| Alan | Durum |
| --- | --- |
| Çekirdek: yaşam döngüsü, doğrulanan yapılandırma ve güvenli yeniden yükleme, Türkçe ve İngilizce mesajlar, modül durumları, sınırlı arka plan işçileri, tanı bilgisi | Uygulandı, birim testleri var; henüz canlı sunucuda çalıştırılmadı |
| Ortak model: kimlikler, şema geçişleri, tipli ve kapsamlı değişkenler ve değişken deposu, kalıcı glyph ve model verisi kaydı | Uygulandı, birim testleri var |
| Koşul ve eylem motoru: karşılaştırmalar, dallanma, başarısızlık kapıları, ya hep ya hiç işlenen değişken eylemleri | Uygulandı, birim testleri var |
| Envanter menüleri: durumlar, görünürlük, tıklama eylemleri ve korumalı oturumlarla YAML menüler | Uygulandı, birim testleri var; henüz canlı sunucuda çalıştırılmadı |
| Mağaza eylemleri: Vault ile para, düz eşya alma/verme, alım gücü koşulları, geri alınan işlemler | Uygulandı, birim testleri var; henüz canlı sunucuda çalıştırılmadı |
| Komut eylemleri, sayfalı ve üretilen mağazalar | Planlandı |
| İmleç ekranları, HUD ve açılır bildirimler | Planlandı |
| Hologramlar, slaytlar ve hareket | Planlandı |
| Rütbe rozetleri ve piksel araçları | Planlandı |
| Java ve Bedrock kaynak paketleri | Planlandı |
| Görsel editör ve dağıtım | Planlandı |
| İsteğe bağlı köprüyle Bedrock erişimi | Planlandı |

Planlanan bir modül `config.yml` içinde açılırsa `/vibecore status` onu `not-implemented` (henüz uygulanmadı) olarak gösterir; hiçbir özellik çalışmadan çalışıyormuş gibi sunulmaz. Ayrıntılar için [yol haritası](docs/ROADMAP.md).

### Gereksinimler ve derleme

Sunucuda Paper 1.21.4 veya daha yeni bir sürüm ve Java 25 gerekir. Derlemek için JDK 25 ve Maven 3.9+ ile `mvn verify` çalıştırın; JAR `target/` klasörüne yazılır.

### Komutlar

`/vibecore help` herkese açıktır. `status`, `diagnostics` ve `reload` varsayılan olarak yalnız operatörlere açıktır (`vibecore.admin.*`). `/vibecore reload` bütün yapılandırmayı doğrular; tek bir hata bile varsa önceki yapılandırma etkin kalır. Menüler için `/vibecore menu open <menü> [oyuncu]`, `close [oyuncu]` ve `list` kullanılır; başka oyuncu adına işlem `.others` yetkisi ister ve menünün kendi yetkisi her zaman görüntüleyen oyuncuya göre denetlenir.

### Menüler

`config.yml` içinde `modules.menu: true` ile açılır. Menüler `plugins/VibeCore/menus/` altındaki YAML dosyalarıdır; `menus/shop/main.yml` dosyası `shop/main` menüsü olur. Değişkenler `variables.yml` içinde tanımlanır, kalıcı değerler `data/variables.txt` dosyasına yazılır. Örnek için `/vibecore menu open example`.

- Açık menüdeki her etkileşim iptal edilir: iki envanterdeki tıklamalar, shift ve sayı tuşu taşımaları, çift tıklama, sürükleme, bırakma ve ikinci ele alma. Yalnız görünür bir öğeye yapılan sol, sağ, shift-sol, shift-sağ ve orta tıklama o öğenin eylemlerini çalıştırır.
- Menü yetkisi her tıklamada yeniden denetlenir; her eylem zinciri kendi koşullarını yeniden kontrol eder ve değişken değişikliklerini ya hep ya hiç kaydeder.
- Tıklamalar oturum başına sınırlanır; önceki tıklama sürerken yenisi yok sayılır. Kapanış eylemleri tam bir kez çalışır ve menüyü yeniden açamaz.
- `cooldown: {var: daily, seconds: 86400, fail: "<red>Bekle: <remaining>"}` sayı değişkeninde tutulan zamandan bu yana süre dolana kadar zinciri durdurur; değişkenin kapsamı bekleme süresini oturum, oyuncu veya sunucu geneli yapar.
- Mağaza efektleri `money-take`, `money-give`, `item-take`, `item-give` ve koşullar `has-money`, `has-item`, `has-permission` her zincirde kullanılabilir. Para için Vault ve bir ekonomi eklentisi gerekir. Yalnız düz eşyalar sayılır ve alınır; isimli veya büyülü bir eşya asla tüketilmez. Alımlar verilmeden önce aynı anlık görüntüde yapılır; bir adım veya değişken değişiklikleri başarısız olursa önceki adımlar geri alınır, geri alınamayan işlem `data/transactions.log` dosyasına yazılır.
- `/vibecore reload` bütün menü ve değişken dosyalarını birlikte doğrular; hatalı dosya önceki menüleri etkin bırakır.

### Lisans

VibeCore'un bakımını `siberanka` yapar; [VibeCore License](LICENSE) ile, yani ek koşullarla birlikte PolyForm Noncommercial License 1.0.0 ile lisanslanmıştır (özeti [NOTICE](NOTICE) içindedir):

- VibeCore'u yönettiğiniz veya işlettiğiniz her sunucuda ve ağda derleyebilir, kurabilir, değiştirebilir ve çalıştırabilirsiniz; para kazanan sunucular ve ağlar (mağaza, rütbeler, abonelikler, bağışlar, reklam) da buna dahildir.
- İnceleyebilir; kopyalarını ve değiştirilmiş sürümlerini LICENSE ve NOTICE ile birlikte ücretsiz paylaşabilirsiniz.
- Satamazsınız: ne VibeCore'u ne bir kopyasını, değiştirilmiş bir sürümünü, bir fork'unu, adı değiştirilmiş bir kopyasını veya kaynak kodunun herhangi bir parçasını; ne ücretli bir paketin veya sunucu kurulumunun parçası olarak ne de değerini esas olarak ondan alan ücretli bir ürün veya hizmet olarak.
- VibeCore'un herhangi bir parçasını, tek bir kod bloğu bile olsa, kopyalar, alıntılar veya uyarlarsanız okuyanların gördüğü yerde kaynak gösterin: VibeCore'u ve siberanka'yı adlandırın, bu depoya bağlantı verin ve değiştirip değiştirmediğinizi belirtin; örneğin `Adapted from VibeCore by siberanka (https://github.com/siberanka/VibeCore), VibeCore License.`

Bu koşulların ötesindeki her şey siberanka'dan ayrı ve yazılı bir lisans gerektirir.

Minecraft, Mojang, Microsoft ve Paper adları ilgili sahiplerine aittir ve herhangi bir bağlılığı değil, uyumluluk hedeflerini belirtir.
