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
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.moto;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.moto.message.MotorolaTypeIIMessage;
import io.github.dsheirer.module.decode.moto.osw.OswExtractor;
import io.github.dsheirer.sample.Listener;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test harness for Motorola Type II OSW extractor.
 * Processes a known-good WAV file (FM-demodulated audio at 18kHz) and compares output with OP25.
 *
 * OP25 baseline (from op25-cc-file.log):
 *   System ID: 0x0D14, Site: 05, Connect Tone: 97.30 Hz
 *   ~128 analog grants, ~1575 SYSTEM_STATUS, ~1975 NETWORK_STATUS
 */
public class MotorolaTypeIIDecoderTest
{
    private static final String WAV_FILE_PATH = "M:\\OpenCode\\sdrtrunk-vibes\\cc-recordings\\moto_t2_cc.wav";
    private static final double SAMPLE_RATE = 18000.0; // 18 kHz (5 samples/symbol at 3600 baud)

    @Test
    void testOswExtractorWithWavFile() throws Exception
    {
        File wavFile = new File(WAV_FILE_PATH);
        assertTrue(wavFile.exists(), "WAV file not found: " + WAV_FILE_PATH);

        // Read WAV file as float[] (mono 16-bit PCM at 18kHz)
        float[] audioSamples = readWavAsFloat(wavFile);
        System.out.println("Read " + audioSamples.length + " samples from WAV file");
        System.out.println("Duration: " + (audioSamples.length / SAMPLE_RATE) + " seconds");

        // Create OSW extractor with bandplan
        Bandplan bandplan = new Bandplan(BandplanType.EIGHT_HUNDRED_REBANTED);
        OswExtractor extractor = new OswExtractor(bandplan);
        extractor.setSampleRate(SAMPLE_RATE);

        // Collect messages
        List<IMessage> messages = new ArrayList<>();
        Listener<IMessage> messageListener = messages::add;

        // Process audio samples
        extractor.process(audioSamples, messageListener);

        // Analyze results
        System.out.println("\n=== Motorola Type II OSW Extractor Test Results ===");
        System.out.println("Total messages decoded: " + messages.size());

        int systemIdCount = 0;
        int amssCount = 0;
        int networkStatusCount = 0;
        int systemStatusCount = 0;
        int grantCount = 0;
        int patchCount = 0;
        int affiliationCount = 0;
        int roamingCount = 0;
        int groupUpdateCount = 0;
        int idleCount = 0;
        int ccBroadcastCount = 0;
        int systemIdCcCount = 0;

        for(IMessage msg : messages)
        {
            if(msg instanceof MotorolaTypeIIMessage motoMsg)
            {
                switch(motoMsg.getMessageType())
                {
                    case SYSTEM_ID:
                        systemIdCount++;
                        System.out.println("  SYSTEM_ID: addr=0x" +
                            String.format("%04X", motoMsg.getAddress()));
                        break;
                    case AMSS:
                        amssCount++;
                        int siteId = motoMsg.getCommand() - 0x360 + 1;
                        System.out.println("  AMSS: site=" + siteId);
                        break;
                    case NETWORK_STATUS:
                        networkStatusCount++;
                        break;
                    case SYSTEM_STATUS:
                        systemStatusCount++;
                        break;
                    case ANALOG_GROUP_GRANT:
                        grantCount++;
                        System.out.println("  ANALOG_GRANT: TG=0x" +
                            String.format("%04X", motoMsg.getAddress()) +
                            " CH=0x" + String.format("%03X", motoMsg.getChannelNumber()));
                        break;
                    case DIGITAL_GROUP_GRANT:
                        grantCount++;
                        System.out.println("  DIGITAL_GRANT: TG=0x" +
                            String.format("%04X", motoMsg.getAddress()) +
                            " CH=0x" + String.format("%03X", motoMsg.getChannelNumber()));
                        break;
                    case PATCH:
                        patchCount++;
                        break;
                    case AFFILIATION:
                        affiliationCount++;
                        break;
                    case ROAMING:
                        roamingCount++;
                        break;
                    case GROUP_UPDATE:
                        groupUpdateCount++;
                        break;
                    case IDLE:
                        idleCount++;
                        break;
                    case CC_BROADCAST:
                        ccBroadcastCount++;
                        break;
                    case SYSTEM_ID_CC:
                        systemIdCcCount++;
                        break;
                }
            }
        }

        System.out.println("\n=== Message Type Counts ===");
        System.out.println("SYSTEM_ID:       " + systemIdCount);
        System.out.println("AMSS:            " + amssCount);
        System.out.println("NETWORK_STATUS:  " + networkStatusCount);
        System.out.println("SYSTEM_STATUS:   " + systemStatusCount);
        System.out.println("GRANT:           " + grantCount);
        System.out.println("GROUP_UPDATE:    " + groupUpdateCount);
        System.out.println("PATCH:           " + patchCount);
        System.out.println("AFFILIATION:     " + affiliationCount);
        System.out.println("ROAMING:         " + roamingCount);
        System.out.println("IDLE:            " + idleCount);
        System.out.println("CC_BROADCAST:    " + ccBroadcastCount);
        System.out.println("SYSTEM_ID_CC:    " + systemIdCcCount);

        // Assertions
        assertTrue(messages.size() > 0, "No messages decoded at all");

        // OP25 sees ~128 grants in this recording
        if(grantCount == 0)
        {
            System.out.println("\n!!! WARNING: No grants decoded. OP25 sees ~128 grants in this recording.");
            System.out.println("    This indicates CRC or message parsing is broken.");
        }

        // OP25 sees ~1575 SYSTEM_STATUS messages
        if(systemStatusCount == 0)
        {
            System.out.println("\n!!! WARNING: No SYSTEM_STATUS decoded. OP25 sees ~1575 in this recording.");
        }

        // Print summary
        System.out.println("\n=== Test Status ===");
        if(messages.size() > 100 && grantCount > 10)
        {
            System.out.println("PASS: Decoder appears to be working correctly");
        }
        else
        {
            System.out.println("FAIL: Decoder has issues");
            System.out.println("  Expected: >100 messages, >10 grants");
            System.out.println("  Got: " + messages.size() + " messages, " + grantCount + " grants");
        }
    }

    /**
     * Reads a mono 16-bit PCM WAV file and converts to float[] normalized to -1.0..1.0
     */
    private float[] readWavAsFloat(File wavFile) throws Exception
    {
        AudioInputStream audioStream = AudioSystem.getAudioInputStream(wavFile);
        AudioFormat format = audioStream.getFormat();

        System.out.println("WAV format: " + format.getSampleRate() + " Hz, " +
            format.getSampleSizeInBits() + " bits, " + format.getChannels() + " channel(s)");

        assertEquals(1, format.getChannels(), "Expected mono WAV file");
        assertEquals(16, format.getSampleSizeInBits(), "Expected 16-bit samples");
        assertEquals(SAMPLE_RATE, format.getSampleRate(), 0.1, "Expected 18kHz sample rate");

        byte[] bytes = audioStream.readAllBytes();
        audioStream.close();

        // Convert 16-bit signed PCM to float[]
        float[] samples = new float[bytes.length / 2];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // WAV files are little-endian

        for(int i = 0; i < samples.length; i++)
        {
            short sample = buffer.getShort();
            samples[i] = sample / 32768.0f; // Normalize to -1.0..1.0
        }

        return samples;
    }
}
