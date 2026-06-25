/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.sample.Listener;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test harness for EDACS control channel decoder. Two test methods exercise
 * the decoder against pre-recorded captures:
 *
 * <ol>
 *   <li>{@link #testEdacsFromBinFile()} bypasses the FM demodulator and
 *       symbol-timing stages by feeding a DSD-FME {@code .bin} symbol
 *       capture (1 byte per dibit, value 1 or 3) directly into the
 *       {@link EDACSFrameProcessor}. This isolates the sync detection,
 *       3-copy majority voting, and BCH(40,28) error correction stages
 *       from the FM pipeline quality.</li>
 *   <li>{@link #testEdacsFromWavFile()} feeds the FM-demodulated audio
 *       capture into the full {@link EDACSSyncDetector} pipeline, including
 *       the FM AFC bit-detection stage. This WAV path is the authoritative
 *       parity check against DSD-FME.</li>
 * </ol>
 *
 * <p>Reference recordings:</p>
 * <ul>
 *   <li>{@code raw_signal_v2.bin} — 524288 bytes, 9600 sps DSD-FME symbol
 *       capture of MBTA (SID 3476) at 853.725 MHz</li>
 *   <li>{@code raw_signal_v2.wav} — 48000 Hz mono 16-bit PCM FM-demod audio
 *       from rtl_fm, same source</li>
 * </ul>
 *
 * <p>Reference DSD-FME log: {@code dsd-output_v4.txt} shows clean decoding
 * over 55 s including: System Info (CC LCN 10), Adjacent Site, Extended
 * Addressing (Site 01, Area 09), System Dynamic Regroup Plan Bitmap, and
 * Digital Group Calls including TG 296, source 3913, LCN 6.</p>
 */
public class EDACSDecoderTest
{
    private static final String BIN_FILE_PATH = "M:\\OpenCode\\sdrtrunk-vibes\\EDACS CC Recording\\raw_signal_v2.bin";
    private static final String WAV_FILE_PATH = "M:\\OpenCode\\sdrtrunk-vibes\\EDACS CC Recording\\raw_signal_v2.wav";
    private static final double WAV_SAMPLE_RATE = 48000.0;

    @Test
    void testEdacsFromBinFile() throws Exception
    {
        File binFile = new File(BIN_FILE_PATH);
        assertTrue(binFile.exists(), "BIN file not found: " + BIN_FILE_PATH);

        byte[] bytes = java.nio.file.Files.readAllBytes(binFile.toPath());
        System.out.println("Read " + bytes.length + " bytes from BIN file (" +
                (bytes.length / 9600.0) + " seconds at 9600 sps)");

        EDACSFrameProcessor processor = new EDACSFrameProcessor();
        List<IMessage> messages = new ArrayList<>();
        Listener<IMessage> listener = messages::add;

        // Polarity is INVERTED in this capture vs the DSD-FME display
        // convention. Verified by searching for the sync word 0x555557125555
        // (positive polarity, MSB-first): the alternating dotting portion
        // (24 bits) must be 010101... but the file shows 1,3,1,3,... at the
        // first clean alternating run, which only maps to 0x555557125555 if
        // 1 -> 0 and 3 -> 1. DSD-FME's getSymbol() writes 1 for positive and
        // 3 for negative, but this capture appears to have been recorded
        // through a different code path that flips the polarity (perhaps the
        // recording tool that produced the .bin file post-processes the
        // stream to invert it). Either way, 1=0 / 3=1 is empirically correct.
        int zeros = 0;
        int ones = 0;
        int threes = 0;
        int others = 0;
        for(byte b : bytes)
        {
            int v = b & 0xFF;
            if(v == 1)
            {
                // Byte 1 -> bit 0
                ones++;
                processor.processBit(0, listener);
            }
            else if(v == 3)
            {
                // Byte 3 -> bit 1
                threes++;
                processor.processBit(1, listener);
            }
            else
            {
                if(v == 0)
                {
                    // 0s are weak-positive artifacts in this capture; treat
                    // as bit 0 (matching the 1-byte convention above) so
                    // they don't break the alternating sync pattern.
                    zeros++;
                    processor.processBit(0, listener);
                }
                else
                {
                    others++;
                }
            }
        }

        reportResults("BIN", processor, messages, bytes.length, zeros, ones, threes, others);
    }

    @Test
    void testEdacsFromWavFile() throws Exception
    {
        File wavFile = new File(WAV_FILE_PATH);
        assertTrue(wavFile.exists(), "WAV file not found: " + WAV_FILE_PATH);

        float[] samples = readWavAsFloat(wavFile);
        System.out.println("Read " + samples.length + " samples from WAV file (" +
                (samples.length / WAV_SAMPLE_RATE) + " seconds)");

        EDACSSyncDetector detector = new EDACSSyncDetector();
        detector.setSampleRate(WAV_SAMPLE_RATE);

        List<IMessage> messages = new ArrayList<>();
        Listener<IMessage> listener = messages::add;
        detector.process(samples, listener);

        reportResults("WAV", detector.getFrameProcessor(), messages, samples.length, 0, 0, 0, 0);
        assertReferenceBehavior(messages);
    }

    private void reportResults(String source, EDACSFrameProcessor processor, List<IMessage> messages,
                               int inputCount, int zeros, int ones, int threes, int others)
    {
        int detected = processor.getFramesDetected();
        int bchPass = processor.getBchPasses();
        int bchFail = processor.getBchFails();
        int oneWordRejects = processor.getOneWordBchRejects();
        int decoded = processor.getFramesDecoded();
        double bchRate = (bchPass + bchFail) > 0 ? (bchPass * 100.0 / (bchPass + bchFail)) : 0.0;

        System.out.println();
        System.out.println("=== EDACS Decoder Test Results (" + source + ") ===");
        if(zeros + ones + threes + others > 0)
        {
            System.out.println("Input distribution: zeros=" + zeros + " ones=" + ones +
                    " threes=" + threes + " others=" + others);
        }
        System.out.println("Frames detected (48-bit sync):        " + detected);
        System.out.println("Frames BCH pass:                       " + bchPass);
        System.out.println("Frames BCH fail:                       " + bchFail);
        System.out.println("Frames rejected with one BCH word:     " + oneWordRejects);
        System.out.println("BCH pass rate:                         " + String.format("%.1f%%", bchRate));
        System.out.println("Frames producing messages:             " + decoded);
        System.out.println("Messages dispatched:                   " + messages.size());

        if(messages.isEmpty())
        {
            System.out.println("\n!!! No messages decoded. See FINDINGS.md / PLAN_EDACS.md for diagnosis.");
            return;
        }

        Map<EDACSMessageType, Integer> histogram = new EnumMap<>(EDACSMessageType.class);
        Map<Integer, Integer> mt1Histogram = new java.util.TreeMap<>();
        Map<Integer, Integer> mt2Histogram = new java.util.TreeMap<>();
        Map<Integer, Integer> secondMt1Histogram = new java.util.TreeMap<>();
        Map<Integer, Integer> secondMt2Histogram = new java.util.TreeMap<>();
        int known = 0;
        for(IMessage m : messages)
        {
            if(m instanceof EDACSMessage edacs)
            {
                histogram.merge(edacs.getMessageType(), 1, Integer::sum);
                int payload = getInt(edacs.getData(), 0, 28);
                int mt1 = (payload >> 23) & 0x1F;
                int mt2 = (payload >> 19) & 0x0F;
                mt1Histogram.merge(mt1, 1, Integer::sum);
                if(mt1 == 0x1F)
                {
                    mt2Histogram.merge(mt2, 1, Integer::sum);
                }
                if(edacs.getData2() != null)
                {
                    int payload2 = getInt(edacs.getData2(), 0, 28);
                    int secondMt1 = (payload2 >> 23) & 0x1F;
                    int secondMt2 = (payload2 >> 19) & 0x0F;
                    secondMt1Histogram.merge(secondMt1, 1, Integer::sum);
                    if(secondMt1 == 0x1F)
                    {
                        secondMt2Histogram.merge(secondMt2, 1, Integer::sum);
                    }
                }
                if(edacs.getMessageType() != EDACSMessageType.UNKNOWN)
                {
                    known++;
                }
            }
        }
        System.out.println("\n--- Message type histogram (vs DSD-FME) ---");
        Map<EDACSMessageType, Integer> dsdFme = dsdFmeHistogram();
        for(Map.Entry<EDACSMessageType, Integer> e : histogram.entrySet())
        {
            Integer expected = dsdFme.get(e.getKey());
            String exp = expected == null ? "  n/a" : String.format("%4d", expected);
            System.out.println(String.format("  %-26s %5d  (DSD-FME: %s)", e.getKey(), e.getValue(), exp));
        }
        int classified = known;
        int total = messages.size();
        double knownPct = total > 0 ? (classified * 100.0 / total) : 0.0;
        System.out.println(String.format("\nClassified: %d / %d (%.1f%%)  Unknown: %d",
                classified, total, knownPct, total - classified));
        printOpcodeHistogram("MT1 histogram", mt1Histogram);
        printOpcodeHistogram("MT2 histogram for MT1=1F", mt2Histogram);
        printOpcodeHistogram("Second word MT1 histogram", secondMt1Histogram);
        printOpcodeHistogram("Second word MT2 histogram for MT1=1F", secondMt2Histogram);

        // Spot check key messages
        System.out.println();
        printFirstOfType(messages, EDACSMessageType.SYSTEM_INFO, "First SYSTEM_INFO");
        printFirstOfType(messages, EDACSMessageType.EXTENDED_ADDRESSING, "First EXTENDED_ADDRESSING");
        printFirstOfType(messages, EDACSMessageType.ADJACENT_SITE, "First ADJACENT_SITE");
        printFirstOfType(messages, EDACSMessageType.DYNAMIC_REGROUP_PLAN, "First DYNAMIC_REGROUP_PLAN");
        printFirstOfType(messages, EDACSMessageType.DIGITAL_GROUP_CALL, "First DIGITAL_GROUP_CALL");
        printFirstOfType(messages, EDACSMessageType.ANALOG_GROUP_CALL, "First ANALOG_GROUP_CALL");
        printFirstOfType(messages, EDACSMessageType.LOGIN, "First LOGIN");
        printFirstOfType(messages, EDACSMessageType.INDIVIDUAL_CALL, "First INDIVIDUAL_CALL");
        printFirstOfType(messages, EDACSMessageType.ALL_CALL, "First ALL_CALL");
    }

    private void printOpcodeHistogram(String label, Map<Integer, Integer> histogram)
    {
        System.out.println("\n--- " + label + " ---");
        for(Map.Entry<Integer, Integer> entry : histogram.entrySet())
        {
            System.out.println(String.format("  %02X  %5d", entry.getKey(), entry.getValue()));
        }
    }

    private static int getInt(CorrectedBinaryMessage msg, int offset, int length)
    {
        int value = 0;
        for(int x = 0; x < length; x++)
        {
            if(msg.get(offset + x))
            {
                value |= (1 << (length - 1 - x));
            }
        }
        return value;
    }

    private void printFirstOfType(List<IMessage> messages, EDACSMessageType type, String label)
    {
        for(IMessage m : messages)
        {
            if(m instanceof EDACSMessage edacs && edacs.getMessageType() == type)
            {
                System.out.println(label + ": " + edacs);
                return;
            }
        }
    }

    private void assertReferenceBehavior(List<IMessage> messages)
    {
        Map<EDACSMessageType, Integer> histogram = new EnumMap<>(EDACSMessageType.class);
        int unknown = 0;

        for(IMessage message : messages)
        {
            if(message instanceof EDACSMessage edacs)
            {
                histogram.merge(edacs.getMessageType(), 1, Integer::sum);
                if(edacs.getMessageType() == EDACSMessageType.UNKNOWN)
                {
                    unknown++;
                }
            }
        }

        assertEquals(0, unknown, "WAV decode should not produce UNKNOWN messages when aligned with DSD-FME");
        assertCountWithin(histogram, EDACSMessageType.EXTENDED_ADDRESSING, 684, 20);
        assertCountWithin(histogram, EDACSMessageType.SYSTEM_INFO, 452, 10);
        assertCountWithin(histogram, EDACSMessageType.DYNAMIC_REGROUP_PLAN, 300, 15);
        assertCountWithin(histogram, EDACSMessageType.ADJACENT_SITE, 149, 10);
        assertCountWithin(histogram, EDACSMessageType.DIGITAL_GROUP_CALL, 134, 20);
        assertCountWithin(histogram, EDACSMessageType.LOGIN, 7, 2);
        assertCountWithin(histogram, EDACSMessageType.CHANNEL_ASSIGNMENT, 6, 2);

        EDACSMessage systemInfo = firstOfType(messages, EDACSMessageType.SYSTEM_INFO);
        assertNotNull(systemInfo, "Expected System Info message");
        assertEquals(0, systemInfo.getSystemId(), "Expected MBTA OTA system ID 0000");
        assertEquals(10, systemInfo.getCcLcn(), "Expected MBTA CC LCN 10");

        EDACSMessage extendedAddressing = firstOfType(messages, EDACSMessageType.EXTENDED_ADDRESSING);
        assertNotNull(extendedAddressing, "Expected Extended Addressing message");
        assertEquals(1, extendedAddressing.getSiteId(), "Expected MBTA site 1");
        assertEquals(9, extendedAddressing.getArea(), "Expected MBTA area 9");

        assertTrue(messages.stream().anyMatch(message -> message instanceof EDACSMessage edacs &&
                edacs.getMessageType() == EDACSMessageType.DIGITAL_GROUP_CALL && edacs.getGroup() == 296 &&
                        edacs.getSource() == 3913 && edacs.getLCN() == 6),
                "Expected DSD-FME-matching digital grant TG 296 source 3913 LCN 6");
    }

    private void assertCountWithin(Map<EDACSMessageType, Integer> histogram, EDACSMessageType type, int expected,
                                   int tolerance)
    {
        int actual = histogram.getOrDefault(type, 0);
        assertTrue(Math.abs(actual - expected) <= tolerance,
                "Expected " + type + " count within " + tolerance + " of " + expected + ", actual " + actual);
    }

    private EDACSMessage firstOfType(List<IMessage> messages, EDACSMessageType type)
    {
        for(IMessage message : messages)
        {
            if(message instanceof EDACSMessage edacs && edacs.getMessageType() == type)
            {
                return edacs;
            }
        }

        return null;
    }

    /**
     * Reference DSD-FME message type histogram from dsd-output_v4.txt for
     * the same 55 s MBTA recording. Used to compare the sdrtrunk decoder
     * output against the DSD-FME reference.
     */
    private static Map<EDACSMessageType, Integer> dsdFmeHistogram()
    {
        Map<EDACSMessageType, Integer> map = new EnumMap<>(EDACSMessageType.class);
        map.put(EDACSMessageType.EXTENDED_ADDRESSING, 684);
        map.put(EDACSMessageType.SYSTEM_INFO, 452);
        map.put(EDACSMessageType.DYNAMIC_REGROUP_PLAN, 300);
        map.put(EDACSMessageType.ADJACENT_SITE, 149);
        map.put(EDACSMessageType.DIGITAL_GROUP_CALL, 134);
        map.put(EDACSMessageType.LOGIN, 7);
        map.put(EDACSMessageType.CHANNEL_ASSIGNMENT, 6);
        return map;
    }

    private float[] readWavAsFloat(File wavFile) throws Exception
    {
        // Read the entire file as bytes - AudioInputStream is unreliable for
        // streaming WAVE files (where the 'data' chunk size is 0 in the
        // header) and for non-standard channel layouts.
        byte[] all = java.nio.file.Files.readAllBytes(wavFile.toPath());
        System.out.println("WAV file size: " + all.length + " bytes");

        // Parse the RIFF/WAVE header manually. We expect:
        //   0..3   "RIFF"
        //   4..7   file size - 8
        //   8..11  "WAVE"
        //   12..   chunks (at least 'fmt ' and 'data')
        // Find the 'fmt ' and 'data' chunks; the data chunk may have size 0
        // (streaming WAVE) in which case the data extends to EOF.
        assertEquals('R', all[0]);
        assertEquals('I', all[1]);
        assertEquals('F', all[2]);
        assertEquals('F', all[3]);
        assertEquals('W', all[8]);
        assertEquals('A', all[9]);
        assertEquals('V', all[10]);
        assertEquals('E', all[11]);

        int sampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataSize = 0;

        int offset = 12;
        while(offset + 8 <= all.length)
        {
            String id = new String(all, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = java.nio.ByteBuffer.wrap(all, offset + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            int payloadOffset = offset + 8;

            if("fmt ".equals(id))
            {
                java.nio.ByteBuffer fmt = java.nio.ByteBuffer.wrap(all, payloadOffset, Math.min(size, 16))
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int format = fmt.getShort() & 0xFFFF;
                channels = fmt.getShort() & 0xFFFF;
                sampleRate = fmt.getInt();
                fmt.getInt(); // byte rate
                fmt.getShort(); // block align
                bitsPerSample = fmt.getShort() & 0xFFFF;
                assertEquals(1, format, "Only PCM (format 1) is supported");
            }
            else if("data".equals(id))
            {
                dataOffset = payloadOffset;
                // Streaming WAVE: size may be 0; treat as "to EOF".
                dataSize = (size == 0) ? (all.length - payloadOffset) : size;
            }

            // Chunks are word-aligned.
            int advance = 8 + size;
            if(size % 2 == 1) advance++;
            offset += advance;
        }

        assertTrue(dataOffset >= 0, "WAV file has no 'data' chunk");
        System.out.println("WAV format: " + sampleRate + " Hz, " + bitsPerSample + " bits, " + channels + " channel(s)");
        System.out.println("WAV PCM payload: " + dataSize + " bytes starting at offset " + dataOffset);

        assertEquals(16, bitsPerSample, "Expected 16-bit samples");

        if(channels == 1)
        {
            float[] result = new float[dataSize / 2];
            ByteBuffer buffer = ByteBuffer.wrap(all, dataOffset, dataSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            for(int i = 0; i < result.length; i++)
            {
                result[i] = buffer.getShort() / 32768.0f;
            }
            return result;
        }
        else if(channels == 2)
        {
            int stereoSamples = dataSize / 2;
            float[] stereoFloats = new float[stereoSamples];
            ByteBuffer stereoBuf = ByteBuffer.wrap(all, dataOffset, dataSize);
            stereoBuf.order(ByteOrder.LITTLE_ENDIAN);
            for(int i = 0; i < stereoSamples; i++)
            {
                stereoFloats[i] = stereoBuf.getShort() / 32768.0f;
            }
            int monoSamples = stereoSamples / 2;
            float[] mono = new float[monoSamples];
            for(int i = 0; i < monoSamples; i++)
            {
                mono[i] = (stereoFloats[i * 2] + stereoFloats[i * 2 + 1]) * 0.5f;
            }
            return mono;
        }
        else
        {
            throw new IllegalStateException("Unsupported channel count: " + channels);
        }
    }
}
