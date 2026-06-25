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
 *       the FM AFC bit-detection stage.</li>
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
 * <p>Reference DSD-FME log: {@code dsd-output_v4.txt} shows ~80 sync events
 * over 55 s including: System Info (CC LCN 10), Adjacent Site, Extended
 * Addressing (Site 01, Area 09), System Dynamic Regroup Plan Bitmap, and
 * 1 visible Digital Group Call (TG 0x128=296, src 0xF49=3913, LCN 6).</p>
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

        int zeros = 0;
        int ones = 0;
        int threes = 0;
        int others = 0;
        for(byte b : bytes)
        {
            int v = b & 0xFF;
            if(v == 0)
            {
                // Idle / no carrier - skip per DSD-FME convention
                zeros++;
                continue;
            }
            else if(v == 1)
            {
                // Positive FM deviation -> bit 1
                ones++;
                processor.processBit(1, listener);
            }
            else if(v == 3)
            {
                // Negative FM deviation -> bit 0
                threes++;
                processor.processBit(0, listener);
            }
            else
            {
                others++;
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
    }

    private void reportResults(String source, EDACSFrameProcessor processor, List<IMessage> messages,
                               int inputCount, int zeros, int ones, int threes, int others)
    {
        int detected = processor.getFramesDetected();
        int bchPass = processor.getBchPasses();
        int bchFail = processor.getBchFails();
        int decoded = processor.getFramesDecoded();
        double bchRate = (bchPass + bchFail) > 0 ? (bchPass * 100.0 / (bchPass + bchFail)) : 0.0;

        System.out.println();
        System.out.println("=== EDACS Decoder Test Results (" + source + ") ===");
        if(zeros + ones + threes + others > 0)
        {
            System.out.println("Input distribution: zeros=" + zeros + " ones=" + ones +
                    " threes=" + threes + " others=" + others);
        }
        System.out.println("Frames detected (dotting threshold):  " + detected);
        System.out.println("Frames BCH pass:                       " + bchPass);
        System.out.println("Frames BCH fail:                       " + bchFail);
        System.out.println("BCH pass rate:                         " + String.format("%.1f%%", bchRate));
        System.out.println("Frames producing messages:             " + decoded);
        System.out.println("Messages dispatched:                   " + messages.size());

        if(messages.isEmpty())
        {
            System.out.println("\n!!! No messages decoded. See FINDINGS.md / PLAN_EDACS.md for diagnosis.");
            return;
        }

        Map<EDACSMessageType, Integer> histogram = new EnumMap<>(EDACSMessageType.class);
        for(IMessage m : messages)
        {
            if(m instanceof EDACSMessage edacs)
            {
                histogram.merge(edacs.getMessageType(), 1, Integer::sum);
            }
        }
        System.out.println("\n--- Message type histogram ---");
        for(Map.Entry<EDACSMessageType, Integer> e : histogram.entrySet())
        {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }

        // Spot check: the System Info message should report CC LCN 10
        // (DSD-FME log shows MSG_2 [600C1CA] -> lcn=10).
        boolean foundSystemInfo = false;
        for(IMessage m : messages)
        {
            if(m instanceof EDACSMessage edacs && edacs.getMessageType() == EDACSMessageType.SYSTEM_INFO)
            {
                System.out.println("\nFirst SYSTEM_INFO: " + edacs);
                foundSystemInfo = true;
                break;
            }
        }
        if(!foundSystemInfo)
        {
            System.out.println("\n(no SYSTEM_INFO messages decoded)");
        }

        // Spot check: Adjacent Site should report Site 01 LCN 01
        boolean foundAdjSite = false;
        for(IMessage m : messages)
        {
            if(m instanceof EDACSMessage edacs && edacs.getMessageType() == EDACSMessageType.ADJACENT_SITE)
            {
                System.out.println("First ADJACENT_SITE: " + edacs);
                foundAdjSite = true;
                break;
            }
        }
        if(!foundAdjSite)
        {
            System.out.println("(no ADJACENT_SITE messages decoded)");
        }
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
