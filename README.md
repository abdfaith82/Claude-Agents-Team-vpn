# Claude-Agents-Team-vpn

نسخة من Claude-Agents-Team مع **نواة OpenVPN مدمجة** (ics-openvpn، رخصة GPLv2) مبنية من المصدر كوحدة `:openvpn`،
وواجهة لاختيار الدولة من خوادم VPN Gate العامة (شاشة VPN).

- المكتبات الفرعية (openvpn, openssl, mbedtls, openvpn3, asio, lz4, fmt) تُسحب كـ git submodules عند البناء.
- يتطلب البناء `swig` (يُثبَّت في GitHub Actions).
- ABI الافتراضية arm64-v8a؛ نسخة x86 عبر `-Pabis=x86,x86_64`.
- حالة: لم تُجرَّب على جهاز.
