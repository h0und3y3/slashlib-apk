# Slashlib Android (порт iOS-проекта)

Kotlin / Android Studio project. **minSdk 21 (Android 5.0)**, `armeabi-v7a` (ARMv7) and `arm64-v8a` (ARMv8/AArch64). UI based on Material components; on Android 12+ system dynamic color is not yet enabled, so theme uses a stable purple Material palette.


## Build locally
1. Open this folder in Android Studio (Giraffe or newer).
2. Allow Gradle sync to download dependencies.
3. Build → Build APK(s), or run `gradle assembleDebug` with Gradle 8.7 and JDK 17 installed.
4. The debug APK includes both `armeabi-v7a` and `arm64-v8a` native ABI targets.

## GitHub Actions build
A GitHub Actions workflow is included at `.github/workflows/android.yml`. It runs on pushes and pull requests to `main` or `master`, and can also be started manually from the Actions tab (`workflow_dispatch`). It builds a debug APK using JDK 17 and Gradle 8.7 and uploads the APK as the `Slashlib-Android-debug` artifact for 14 days. No signing secrets are required for debug builds.

To download the APK from GitHub: open the repository → **Actions** → select a successful **Android CI** run → download **Slashlib-Android-debug** from Artifacts.

## Implemented in this port
- Catalog search / popular catalog, SlashLib + MangaLib query.
- Title cards and chapter list.
- Online vertical chapter reader (image loading via Glide).
- Local favorites, chapter bookmarks, recent chapters.
- GitHub private Gist sync for favorites/bookmarks/recent; token and Gist id stored in SharedPreferences. GitHub token needs `gist` scope.
- Minimum Android 5.0 and ARMv7/ARM64 filters.

## Not yet ported from iOS source
- Offline chapter download queue and download management UI.
- Reader page-mode controls, zoom UX, progress/read chapter state, strip rendering for low RAM.
- Full title metadata screen (summary, genres/tags, status, rating).
- Reader and app settings, theme/accent options, diagnostics.
- iOS merge details such as tombstones/deletion-aware conflict resolution. Android sync currently unions lists and is a starter merge; it does not sync read-state or deletions.

API routes and image CDN are inherited from the original project and are unofficial; the site may change them.
