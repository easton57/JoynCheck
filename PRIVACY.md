# JoynCheck Privacy Policy

_Effective date: 2026-09-30_

JoynCheck is developed by Easton Seidel ("I", "me"). It is a free compatibility checker that tells
you whether JoynCon will work on your phone before you buy it. There is no paid version and no
in-app purchase. This policy covers both the version distributed on Google Play and the
open-source builds published on GitHub. Both builds work the same way with respect to your data.

**In short: JoynCheck does not collect, store, transmit, sell, or share any personal data. It does
not connect to the internet.**

## What the app accesses on your device, and why

- **Input devices.** Through Shizuku, JoynCheck opens your phone's input devices to test whether
  the operations JoynCon relies on are allowed. It reads each device's hardware identifiers (such
  as vendor and product ID), name, and capabilities only to count what can be opened and to find
  connected Joy-Con controllers. It briefly creates and then removes a virtual test gamepad to
  confirm Android accepts it.
- **Controller input (live test only).** When you start the live test, JoynCheck reads button and
  stick input from your Joy-Con controllers for up to 60 seconds, only to confirm that presses and
  stick movement are detected. This input is processed in memory as it happens. It is never
  recorded, logged, or sent anywhere.
- **Running-process check.** JoynCheck checks whether JoynCon's own merge service or a leftover
  JoynCheck helper is running, so it can warn you or clean up before testing. It looks only for
  those two processes by name.
- **Installed-app check.** JoynCheck checks only whether the Shizuku app is installed so it can
  prompt you to install or open it. It does not look at any other installed apps.
- **Results.** Check results are shown on screen and kept in memory only. JoynCheck saves no
  settings or results, and it opts out of Android device backup. Closing the app discards
  everything.

## Network access and third-party code

JoynCheck does not request internet permission and cannot send data off your device. It includes
no analytics, advertising, tracking, or crash-reporting SDKs.

## Third-party services

- **Google Play.** If you install JoynCheck from Google Play, Google handles the download under the
  [Google Privacy Policy](https://policies.google.com/privacy). If you later buy JoynCon, that
  purchase is covered by JoynCon's own privacy policy.
- **Shizuku.** JoynCheck relies on Shizuku, a separate app by a different developer that you
  install yourself. It runs entirely on your device. See Shizuku's own documentation and store
  listing for its practices.
- **GitHub (open-source version only).** Downloading the source code or builds from GitHub is
  subject to [GitHub's Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
  The app itself does not communicate with GitHub.

## If you contact me

If you email me for support, I will receive your email address and whatever you choose to include
in your message. I use it only to reply to you, and I delete it on request.

## Children

JoynCheck is not directed at children under 13 and does not knowingly collect any information from
anyone, including children.

## Changes to this policy

If this policy changes, the updated version will be posted at this location with a new effective
date. If a future version of the app ever begins collecting data, this policy will be updated
before that version is released.

## Contact

Easton Seidel — eastonseidel@proton.me
