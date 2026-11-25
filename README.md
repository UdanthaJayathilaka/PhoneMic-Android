# Phone Mic — Android app

ෆෝන් එකේ මයික් එක, Wi-Fi එකෙන් PC එකේ **Phone Mic (PC app)** එකට යවන Android app එක.

- ස්ක්‍රීන් එක ඕෆ් වුණත් දිගටම වැඩ කරනවා (foreground service).
- PC එකේ IP එක එක පාරක් type කළාම සේව් වෙනවා.
- සම්බන්ධතාවය කැඩුණොත් ඉබේම ආයෙ සම්බන්ධ වෙනවා.
- PC එකේ **F9** ඔබලා mute කළාම ෆෝන් එකේ මයික් එකත් ඇත්තටම නවතිනවා (ආයෙ F9 ඔබනකම්).

## APK එක හදාගන්න ක්‍රමය

1. මේ project එක GitHub repo එකකට දාන්න (`main` branch).
2. GitHub → **Actions** tab → **Build APK** ඉබේම දුවනවා (විනාඩි 3-5). නැත්නම් **Run workflow** ඔබන්න.
3. ඉවර වුණාම repo එකේ **Releases → Phone Mic (latest APK)** එකෙන් `PhoneMic.apk` ඩවුන්ලෝඩ් කරන්න. (ෆෝන් එකේ Chrome එකෙන්ම කළාම ලේසියි.)

## ෆෝන් එකේ ඉන්ස්ටෝල් කරන විදිය

1. `PhoneMic.apk` ඇරෙද්දී "Install unknown apps" Allow කරන්න (එක පාරයි).
2. Install කරන්න.

## පාවිච්චි කරන විදිය

1. PC එකේ **PhoneMic.pyw** (PC app) ඇරන්න.
2. ෆෝන් app එකේ PC address එක ගහන්න (PC app window එකේ පේන `https://192.168.x.x:8443` එකේ `192.168.x.x` කොටස).
3. **START** ඔබන්න. Mic permission **Allow** කරන්න.
4. PC එකේ Discord/OBS වල Microphone = **CABLE Output**.

## ආරක්ෂාව

PC app එකේ self-signed සහතිකයක් තියෙනවා. ෆෝන් app එක පළවෙනි වතාවේ ඒ සහතිකය මතක තියාගන්නවා,
පස්සේ ඒක වෙනස් වුණොත් සම්බන්ධ වෙන්නේ නෑ (වෙන කෙනෙක් PC එක ලෙස රඟපාන එක නවත්වන්න).
PC එක වෙනස් කළොත් app එකේ **Reset trust** ඔබන්න.

## Samsung ගැන

ස්ක්‍රීන් ඕෆ් වෙද්දී නැවතුණොත්: Settings → Apps → Phone Mic → Battery → **Unrestricted** කරන්න.
