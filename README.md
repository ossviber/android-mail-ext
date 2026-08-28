Proton Mail Ext (android-mail-ext)
=================================

A fork of [Proton Mail for Android](https://github.com/ProtonMail/android-mail) with
enhancements for sending from external email identities:

- Send from non-Proton addresses through your own SMTP servers
- Stored SMTP server configurations shared across identities
- Sent e-mails are copied into your Proton Sent folder
- Attachments support for external identities

> **Current status**
>
> The sent-copy automation (per-identity labeling and its server-side filter) is
> currently **dormant**: its UI was removed from the app, but the underlying
> functions remain in the codebase for a future re-enable. Push notifications via
> Google (GMS/FCM) are not available in this fork: the upstream Firebase API key
> is restricted to the official package name and signing certificate, so this
> re-signed build cannot obtain an FCM token.

> **How this fork was made**
>
> This fork was built with **vibe coding** - assisted development where the
> code is written collaboratively by humans and several different AI coding
> models. Expect the code to be pragmatic rather than perfect; review anything
> sensitive before relying on it.
>
> **Disclaimer**
>
> This project is **not affiliated with, endorsed by, or sponsored by Proton AG** or
> any of its subsidiaries. "Proton" and "Proton Mail" are trademarks of their
> respective owners, used here only to describe the upstream project this fork is
> based on. The software is provided "AS IS" without warranty of any kind; use it
> entirely at your own risk. The author is not responsible for any loss, damage or
> misuse arising from the use of this software.

## Signing
This build is signed with its own debug and release keys (under `keystore/`, which is
not committed). The package name is `ossviber.protonmail.android`.

## Build instructions
- Install and configure the environment (two options available)
  - [Android Studio bundle](https://developer.android.com/studio/install)
  - [Standalone Android tools](https://developer.android.com/tools)
- Install and configure Java 17+ (not needed for Android Studio bundle as it's included)
- Clone this repository
- Ensure [Git LFS](https://git-lfs.com/) is installed (`git lfs install`) for snapshot test assets
- Setup `google-services.json` file (a local-dev placeholder is committed)
- Build with any of the following:
  - Debug: `./gradlew assembleDevDebug` in a terminal
  - Release: `./gradlew assembleProdRelease -x uploadSentryProguardMappingsProdRelease -x uploadSentryNativeSymbolsForProdRelease`
  - Open Android Studio and build the `:app` module

## Releases
Pre-built release APKs are published on the
[releases page](https://github.com/ossviber/android-mail-ext/releases).

## Upstream
Original repository: https://github.com/ProtonMail/android-mail
