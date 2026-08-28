Proton Mail Ext (android-mail-ext)
=================================

A fork of [Proton Mail for Android](https://github.com/ProtonMail/android-mail) with
enhancements for sending from external email identities:

- Send from non-Proton addresses through your own SMTP servers
- Stored SMTP server configurations shared across identities
- Sent e-mails are copied into your Proton Sent folder (with optional per-identity
  labeling and a matching server-side filter)
- Attachments support for external identities

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
  - Execute `./gradlew assembleAlphaDebug` in a terminal
  - Open Android Studio and build the `:app` module

## Upstream
Original repository: https://github.com/ProtonMail/android-mail