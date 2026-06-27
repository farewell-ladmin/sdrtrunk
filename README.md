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

This fork started as an attempt to fix P25P1/P2 encryption detection and NBFM squelch tail issues. It grew into implementing EDACS EA trunking with ProVoice digital voice (working — see below) and Motorola Type II trunking for SmartZone systems.

## Motorola Type II Trunking (MVP — Working)

**Status:** Control channel decoding, CRC validation, message parsing, talkgroup extraction, alias support, and analog voice following are functional. Validated against a live Motorola SmartZone system (Massachusetts State Police) and cross-referenced with OP25. Digital voice channels and many SmartZone features remain unimplemented.

### What's Implemented

| Component | Status | Notes |
|-----------|--------|-------|
| FSK2 demodulation + OSW sync detection | Working | 48-bit OSW extraction from raw bitstream |
| CRC validation | ~90.6% pass | Matches OP25 algorithm (polynomial 0x0225, init 0x0393, bit-inverted) |
| End-of-frame sync validation | Working | Requires trailing 0xAC sync at expected boundary before CRC/parser processing |
| 1-OSW messages | Working | IDLE, GROUP_UPDATE, CC_BROADCAST, GROUP_BUSY, EMERGENCY_BUSY, NETWORK_STATUS, SYSTEM_STATUS, AMSS, ROAMING, SYSTEM_ID, BSI_DIAGNOSTIC |
| 2-OSW messages | Working | ANALOG_GROUP_GRANT, ANALOG_PRIVATE_CALL, DIGITAL_GROUP_GRANT, DIGITAL_PRIVATE_CALL, SYSTEM_ID_CC, AFFILIATION, DEAFFILIATION, PATCH, DATE_TIME, CONTROL_CHANNEL (2-OSW variant) |
| 3-OSW messages | Working | ADJACENT_SITE, SYSTEM_INFO, CONTROL_CHANNEL (3-OSW variant) |
| Bandplan-aware frequency calc | Working | 800 MHz Rebanded/Domestic/Splinter, 800 MHz International/Intl Splinter, 900 MHz, OBT |
| Talkgroup extraction from grants | Working | Group TG as TO, source radio as FROM (P25 convention) |
| Traffic channel allocation | Working | NBFM analog voice following with automatic teardown |
| Alias support | Working | Add Identifier menu, talkgroup/radio validation, TG/radio editor formatters, display preferences |
| Legacy alias compatibility | Working | Falls back to APCO25 alias maps for existing tagged aliases |
| RadioReference import | Working | Legacy Motorola systems classify as MOTOROLA_TYPE_II; CoMIRS/Project 25 remains APCO25 |
| Connect tone extraction | Working | Decoded from NETWORK_STATUS using OP25 SmartNet 8-tone table |
| GUI configuration | Working | JavaFX editor with bandplan selection, OBT params, traffic pool, site config |
| Decoder state machine | Working | CONTROL/CALL states, identifier tracking, statistics logging |
| Now Playing integration | Working | Traffic events display correct TG/alias in both Events and Now Playing panels |

### What's Not Working

| Issue | Severity | Notes |
|-------|----------|-------|
| Digital traffic channels always use NBFM | High | No P25P1DecoderC4FM path — digital voice not implemented |
| GROUP_BUSY / EMERGENCY_BUSY transitions to CONTROL instead of FADE | Medium | Should transition to State.FADE |
| processStatus() partially implemented | Medium | Connect tone decoded; feature flags not tracked/displayed |
| Adjacent site tracking / expiry / display | Medium | Messages parsed but not stored or surfaced in UI |
| Patch group management | Medium | PATCH messages decoded but group map not maintained |
| CONTROL_CHANNEL 2-OSW variant detection | Low | 3-OSW variant works; 2-OSW needs refinement |
| Many SmartZone features missing | Low | Deaffiliation handling, idle tracking, denied/radio-check events |

### Reference Implementations

| Project | What Was Referenced |
|---------|---------------------|
| [OP25](https://github.com/boatbod/op25) | CRC algorithm, OSW extraction logic, SmartNet tone table, message dispatch patterns |
| [Trunk Recorder](https://github.com/TrunkRecorder/trunk-recorder) | SmartNet parser structure, message type definitions |
| DSD-FME | FM demodulation quality comparison |

### Test System

| Parameter | Value |
|-----------|-------|
| System | Massachusetts State Police (MSP) |
| System ID | 0D14 |
| Site | Metro Boston (005) |
| Control channel | 854.5625 MHz |
| Channel spacing | 25 kHz |
| Emission designator | 20K0F1E |
| Connect tone | 97.30 Hz |
| Traffic | Analog + APCO-25 CAI (astro digital TGs) |

### Known Issues from Live Testing

- CRC error rate ~9.4% (vs OP25's ~3.9%) — OSW extraction needs further tuning
- Some message types missing vs OP25: AFFILIATION, DEAFFILIATION, IDLE, DENIED, RADIO_CHECK
- SYSTEM_ID_CC reports inconsistent values — needs investigation
- CONTROL_CHANNEL detection partially split into sub-messages

## EDACS EA Trunking + ProVoice Digital Voice (Working)

**Status:** EDACS Extended Addressing (EA) control-channel decoding and ProVoice
digital voice are working. The control channel decodes System ID, Site ID, Area,
CC LCN, Adjacent Site, Dynamic Regroup, and Voice Group Channel
Assignment/Update messages; voice grants are followed onto traffic channels and
ProVoice IMBE 7100 voice is decoded to audio through the JMBE `PROVOICE` codec.
The message field parsing (LCN, talkgroup, source, Assignment/Update bit) was
validated bit-for-bit against DSD-FME `edacs-fme.c`.

### ProVoice audio

Each outbound ProVoice frame carries 4 interleaved IMBE 7100 voice frames. Frame
extraction (`EDACSProVoiceDecoder` / `EDACSProVoiceInterleave`) mirrors DSD-FME
`provoice.c` `processProVoice`, including the pW/pX 7×24 grid interleave schedule.
Payload bits are inverted when an inverted-variant sync (`INV_PROVOICE_SYNC` /
`INV_PROVOICE_EA_SYNC`) is matched, mirroring DSD-FME `digitize()` synctype 14
("+PV") vs 15 ("-PV") handling — without this the IMBE grids decode inverted and
the audio is garbled. The 7100→4400 conversion and speech synthesis are handled
by the JMBE `PROVOICE` codec. Audio fidelity on a clean recording is intelligible
(listener-confirmed); live decode quality tracks signal strength, since the
ProVoice symbol slicer is simpler than DSD-FME's (no matched filter), so weak
signals produce more muted/repeat frames.

### ⚠️ Caveat: disable duplicate-call-detection-by-talkgroup for EDACS EA

EDACS EA is commonly **message-trunked** — every over/PTT in a conversation is
assigned a *new* LCN, so one talkgroup legitimately appears on several traffic
channels within a few seconds. sdrtrunk's duplicate-call detection flags any two
in-progress calls sharing a talkgroup as duplicates and suppresses the later one
for **playback, recording, and streaming alike**. Against EA message trunking
this wrongly drops the continuation overs of a conversation, so you hear the
first over and then dropouts/silence.

**If you monitor an EDACS EA system, turn off "duplicate call detection by
talkgroup"** (Preferences → Calls / Duplicate Call). This is a structural
mismatch between message trunking and talkgroup-level dedup, not a decoder bug —
the LCN/talkgroup/source parsing is confirmed correct against DSD-FME. Duplicate
detection is scoped per channel **"System"** name, so leaving it on for P25 (or
any system where a call holds one channel) is unaffected; only systems that share
the same "System" name are ever compared.

### Reference
- DSD-FME `edacs-fme.c` (EA control channel), `provoice.c` / `provoice_const.h`
  (ProVoice IMBE 7100 voice frame interleave)

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
