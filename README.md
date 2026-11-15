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

### Analog only

Type II here decodes **analog voice only**. It has never been run against digital voice, because the test
system almost never carries any.

What adding digital voice would take (untested, my understanding of how these systems work):

1. A digital grant puts APCO-25 CAI (IMBE) voice on the traffic channel, with no P25 control channel behind it.
   The traffic channel would need the P25 Phase 1 decoder and decoder state in place of the NBFM ones, and the
   JMBE audio module in place of the analog one. That choice is made per grant in
   `DecoderFactory.processMotorolaTypeII`, which today always builds the analog chain.
2. `MotorolaTypeIITrafficChannelManager.processChannelGrant` would start a traffic channel for a digital grant
   and tell the factory which kind it is. Today it reports the grant and returns.
3. A recording of a digital call on a Type II site to test against. Without one this is guesswork.

Older ASTRO systems that use VSELP voice are a different vocoder, which JMBE does not have.

### What's Not Working

| Issue | Severity | Notes |
|-------|----------|-------|
| Digital voice | Not supported | Analog voice only. A digital grant is shown as a call event marked DIGITAL VOICE NOT SUPPORTED and is not followed. See "Analog only" below |
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
by the JMBE `PROVOICE` codec. The symbol timing is not recovered: a framer runs
on each of the 5 sample phases and each frame is taken from the best one. Live
audio on the test system is clear (listener-confirmed, November 2026). Weak
channels lose frames; up to two bit errors are tolerated in the sync word.

### Tested scope

This has been developed and tested against **one system only**: an EDACS
Extended Addressing (EA) wideband (9600 baud) system carrying ProVoice digital
voice. Voice mode **Auto (Analog/Digital by grant)** works on that system.

Not tested: EDACS Standard/Networked addressing and analog voice traffic.
EDACS narrowband is not implemented. The ProVoice voice marker default is the
value seen on that one system and may be different elsewhere (see below).

The `PROVOICE` codec is not in stock JMBE. Build the `provoice-on-v2` branch of
[farewell-ladmin/jmbe](https://github.com/farewell-ladmin/jmbe) (JMBE 2.1.1 plus
the ProVoice codec) and point the JMBE library preference at that jar. With a
stock JMBE jar, sdrtrunk logs that the codec is unsupported and ProVoice calls
are silent; P25 and other codecs are unaffected.

### ProVoice voice marker

Every ProVoice voice frame carries a fixed 16-bit marker in the middle of the
frame. Each over also starts with a short signalling burst and ends with dotting,
and both follow the same sync word as voice. Played through the vocoder they sound
as a digital burst at each end of the over. sdrtrunk plays a frame only when its
marker matches the **ProVoice Voice Marker (hex)** setting in the EDACS channel's
decoder settings, allowing up to 3 wrong bits.

The default is `2175`, which is what the MBTA system uses and what DSD-FME
`provoice.c` calls the usual value. It has only been checked on that one system.
If ProVoice calls on another system show in Now Playing but are silent, the marker
is the first thing to check:

1. Clear the marker field and save. With no marker every frame is played, bursts
   included, so you can confirm the audio itself decodes.
2. Start the channel and wait for a call. Select the traffic channel row in Now
   Playing and open its **Messages** tab. Each frame shows as
   `PROVOICE VOICE MARKER:xxxx LID:xxxx ...`.
3. The marker is the `MARKER` value that repeats through the body of an over. Expect
   an odd bit to differ on a noisy signal (`2175`, `2375`, `6175`), so take the
   value seen most often. Ignore the first few frames of an over, which are the
   signalling burst (on MBTA they read `AA55`), and the last one.
4. Enter that value in the marker field and save. Frames without it now show as
   `PROVOICE SIGNALLING` and are not played.

DSD-FME shows the same value: run it with payload output on a ProVoice call and
read the `BF:` line it prints for each frame.

This check is not in DSD-FME, which plays every frame.

### Control channel rotation

EDACS sites can move the control channel to another LCN, and the one listed on
RadioReference isn't always the one in use. Set the EDACS channel's source to
**Tuner (Multiple Frequencies)** and list the control channel candidates (the usual
one plus any known alternates, not every LCN). sdrtrunk rotates to the next
frequency when the current one stops producing control messages. The dwell defaults
to 2000 ms, the maximum, so a fading control channel isn't dropped too quickly.

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

## Creating a Release (Maintainers)
Releases are built and published automatically by the [Release](.github/workflows/release.yml) GitHub workflow
whenever a version tag is pushed to the repository.

The version is taken from the tag, so there is no version number to update in the code. Create and push a tag
with a leading `v`, following the naming convention described in `gradle.properties` (e.g. `v0.6.2-alpha-1`,
`v0.6.2-beta-1` or `v0.6.2`):
```
git tag v0.6.2-beta-1
git push origin v0.6.2-beta-1
```

Builds made from an untagged commit get a version like `0.6.2-beta-1-14-g4626d567` (14 commits after the
`v0.6.2-beta-1` tag, at commit `4626d567`), with `-dirty` appended when there are uncommitted changes.

The workflow then:
* Verifies that the version the build derives from git matches the tag and fails if it does not.
* Builds and tests the code, then creates the release zips for Linux, Mac and Windows (x86-64 and aarch64).
* Creates a GitHub release titled `Version <version>` with auto-generated release notes (a "What's Changed" list of
the PRs merged since the previous version tag), and attaches the six zip
files plus a `SHA256SUMS` checksum file.
* Marks tags containing a dash (alpha and beta versions) as pre-releases. Tags without a dash become full releases.

If the workflow fails part way through, fix the problem and re-run it from the Actions tab. It resumes from the
draft release left behind. It won't modify a release that has already been published. To rebuild a published
release, delete the release and the tag on GitHub, then push the tag again.
