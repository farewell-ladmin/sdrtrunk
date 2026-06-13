![Gradle Build](https://github.com/dsheirer/sdrtrunk/actions/workflows/gradle.yml/badge.svg)
![Nightly Release](https://github.com/dsheirer/sdrtrunk/actions/workflows/nightly.yml/badge.svg)

# MacOS Tahoe 26.1 Users - Attention:
Changes to USB support in Tahoe version 26.x cause sdrtrunk to fail to launch.  Do the following to install the latest libusb and create a symbolic link and then use the nightly build which includes an updated usb4java native library for Tahoe with ARM processor.  There may still be issue(s) with MacOS accessing your USB SDR tuners.

```
brew install libusb --HEAD
cd /opt
sudo mkdir local
cd local
sudo mkdir lib
```
Next, find where brew installed the libusb library, for example: ```/opt/homebrew/Cellar/libusb/HEAD-9ceaa52/lib/libusb-1.0.0.dylib```    Note: the folder "HEAD-9ceaa52" is the version stamp for HEAD when you installed from it.

Finally, create a symbolic link from the installed library to the place where usb4java is expecting to find libusb (/opt/local/lib/libusb-1.0.0.dylib)

```
sudo ln -s /opt/homebrew/Cellar/libusb/HEAD-9ceaa52/lib/libusb-1.0.0.dylib /opt/local/lib/libusb-1.0.0.dylib
```

# sdrtrunk-vibes Fork

## EDACS Trunking (Experimental — Incomplete)

**Status:** Control channel decoding works for Plan Bitmap, Site ID, and Adjacent Site messages. Group Call (talkgroup) decode is unreliable — BCH(40,28) false-passes dominate at our FM pipeline's bit error rate. Voice following and ProVoice audio are not implemented.

### What Was Tried

| Approach | Result |
|----------|--------|
| Dotting-based burst detection with integrator AFC | Control channel locks, 2000+ msgs/10s, mostly garbage |
| 48-bit sync frame detector (exact match) | Too strict at current BER |
| 48-bit sync frame detector (relaxed 44/48) | Produced some real TGs (289, 296) but low rate |
| Correlation-based sync validation (0.35–0.73 ratio) | Plan Bitmap passes, Group Calls don't |
| Resampling to 24000 sps (matching DSD-FME RTL rate) | 71.5% correlation ratio achieved |
| Resampling to 48000 sps (DSD-FME's internal rate) | Not enough samples per symbol |
| Soft voter combining inverted copy deviations | No improvement |
| 3-copy majority voting (matches DSD-FME edacs-fme.c) | More selective but still garbage |
| FM gain multiplier (2x) | No improvement |
| Zero-crossing symbol timing recovery | Produces real bits but timing drifts |
| Jitter-based clock recovery (from dsd_symbol.c) | Requires exact 5.0 sps alignment |

### Root Cause

DSD-FME decodes the same MBTA signal perfectly at 48000 sps using rtl_fm's FM demodulator. The sdrtrunk FM demodulation pipeline (channelizer + scalar FmDemodulator at 50000 sps) produces lower-quality bits. BCH(40,28) can only correct 2 errors per 40-bit word — our BER is ~15% (~6 errors). Plan Bitmap survives because its all-1s pattern is robust to bit errors; Group Calls have varied bit patterns that don't survive BCH.

### Known MBTA Talkgroups (from RadioReference)

273 Red Line Dispatcher, 280 Red Cabot Yard, 289 Orange Dispatcher, 296 Orange Wellington Yard, 305 Green Line Dispatcher, 528–537 Bus Operations, 545–546 Maintenance, 1091 Signals, 1105 Radio Techs

### Reference
- DSD-FME source at `M:\OpenCode\dsd-fme\dsd-fme\`
- `.\dsd-fme.exe -fe -i rtl:0:853.725M:424:-1:24:0:2 -o null -Z`

## Other Fork Changes

- P25P1 encryption detection hysteresis
- NBFM squelch tail hold-off
- P25P2 phase inversion detectors
- Single-instance lock (dev mode only)
- ESS processor memory reuse
- Auto-start / JMBe compatibility


A cross-platform java application for decoding, monitoring, recording and streaming trunked mobile and related radio protocols using Software Defined Radios (SDR).

* [Help/Wiki Home Page](https://github.com/DSheirer/sdrtrunk/wiki)
* [Getting Started](https://github.com/DSheirer/sdrtrunk/wiki/Getting-Started)
* [User's Manual](https://github.com/DSheirer/sdrtrunk/wiki/User-Manual)
* [Download](https://github.com/DSheirer/sdrtrunk/releases)
* [Support](https://github.com/DSheirer/sdrtrunk/wiki/Support)

![sdrtrunk Application](https://github.com/DSheirer/sdrtrunk/wiki/images/sdrtrunk.png)
**Figure 1:** sdrtrunk Application Screenshot

## Download the Latest Release
All release versions of sdrtrunk are available from the [releases](https://github.com/DSheirer/sdrtrunk/releases) tab.

* **(alpha)** These versions are under development feature previews and likely to contain bugs and unexpected behavior.
* **(beta)** These versions are currently being tested for bugs and functionality prior to final release.
* **(final)** These versions have been tested and are the current release version.

## Download Nightly Software Build
The [nightly](https://github.com/DSheirer/sdrtrunk/releases/tag/nightly) release contains current builds of the software 
for all supported operating systems.  This version of the software may contain bugs and may not run correctly.  However, 
it let's you preview the most recent changes and fixes before the next software release.  **Always backup your 
playlist(s) before you use the nightly builds.**  Note: the nightly release is updated each time code changes are 
committed to the code base, so it's not really 'nightly' as much as it is 'current'.

## Minimum System Requirements
* **Operating System:** Windows (~~32 or~~ 64-bit), Linux (~~32 or~~ 64-bit) or Mac (64-bit, 12.x or higher)
* **CPU:** 4-core
* **RAM:** 8GB or more (preferred).  Depending on usage, 4GB may be sufficient.
