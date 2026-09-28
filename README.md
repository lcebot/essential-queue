# essential-queue

An unofficial Android app that queues Leica Essential photos for processing on the Leica Leitzphone.

In the stock Gallery you have to open each Leica Essential photo and tap **Start** one at a time. This app lists the eligible photos for a date range, then opens them in the Gallery Editor one by one and taps **Start** for you. You can add a delay between photos, 30 seconds by default.

Uploading, processing and saving are all done by the system Gallery Editor, exactly as when you tap the button yourself. This app does not connect to any server.

> Not affiliated with Leica or Xiaomi.

## Requirements

- Leica Leitzphone powered by Xiaomi, or Xiaomi 17 Ultra by Leica (China), with the system Gallery Editor (`com.miui.mediaeditor`) installed
- Android 15 or later

## Install

Download the latest APK from [Releases](../../releases) and install it. The app follows the system language (English or Chinese). You can also set its language separately: open *Settings*, go to *Apps*, choose *Essential Queue*, then *Language*.

## Usage

1. **Allow photo access** when asked. Choose **Allow all**.
2. **Turn on the accessibility service** (needed for Auto mode only). Open *Settings*, go to *Accessibility*, then *Downloaded apps*, and turn on *Essential Queue: tap Start*.
   On HyperOS, also allow **Autostart** for the app and set its battery saver to **No restrictions**. Otherwise the system may switch the service off.
3. **Process one photo by hand first.** Use the Gallery as usual. This clears any one-time prompts from the Editor, such as sign-in, notification permission or the mobile data warning.
4. **Pick a date range** and tap **Scan**.
5. **Uncheck any photos you don't want**, then tap **Start**.
6. **Keep the screen on and unlocked** while the queue runs.

### Modes

| Mode | What happens |
|---|---|
| Auto | Opens each photo in the Editor, taps **Start**, waits until processing begins, then goes back. |
| Semi-auto | Opens each photo in the Editor. You tap **Start** and return to the app, and it opens the next photo when the timer ends. Does not need the accessibility service. |

The Gallery Editor already processes one photo at a time, so the delay is optional. It can be set from 0 to 60 seconds. A longer delay spreads uploads out, which lowers the chance of your account being limited or blocked. The app shows a warning below 30 seconds.

### If the queue pauses

The queue pauses when something unexpected happens, for example a dialog appears in the Editor. You then have three options:

- **Continue**: retry the current photo.
- **Skip**: move on to the next photo.
- **Already submitted**: use this if you tapped **Start** yourself, so the same photo is not sent twice.

## What gets listed

- **What gets listed:** every Leica Essential mode is supported, including M9, M3 and any future `Legend.*` mode. A photo is listed only if its embedded data passes the same integrity check the Editor uses.
- **What gets skipped:** result images created by the Editor are never listed.
- **What is unchecked and hidden by default:**
  - originals that already have a result in your gallery
  - photos this app has sent before

## Privacy

- **Photos:** read-only access to `DCIM/Camera`. Nothing is modified or deleted.
- **Network:** no internet permission.
- **Accessibility service:** only watches the Gallery Editor, and only taps the **Start** button while a queue is running.
- **Stored data:** a list of photos this app has sent, kept in the app's private storage.

## Build

Open the project in Android Studio and build. Or push to GitHub and let the included Actions workflow build a signed release APK.

## Disclaimer

This app automates taps you could make yourself. Use it at your own risk and follow the terms of service of the processing feature.
