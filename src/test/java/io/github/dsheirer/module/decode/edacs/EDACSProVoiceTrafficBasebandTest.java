package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSProVoiceMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline debug harness for EDACS ProVoice traffic-channel baseband recordings.
 *
 * <p>The EDACS traffic recorder writes stereo signed 16-bit PCM WAVE files where
 * channel 0 is I and channel 1 is Q. This test feeds those channelized captures
 * through {@link EDACSProVoiceDecoder} exactly like a live traffic channel and
 * prints the extracted IMBE frame bytes for comparison against DSD-FME/mbelib.</p>
 */
public class EDACSProVoiceTrafficBasebandTest
{
    private static final File RECORDINGS_DIR = new File("C:\\Users\\ethan\\SDRTrunk\\recordings");
    private static final int MAX_FILES = 25;
    private static final int MIN_BYTES = 500_000;
    private static final int CHUNK_COMPLEX_SAMPLES = 4096;
    private static final Path DEBUG_DUMP = Path.of("C:\\Users\\ethan\\AppData\\Local\\Temp\\opencode\\edacs-provoice-debug.txt");

    @Test
    void testRecentTrafficBasebandRecordings() throws Exception
    {
        List<File> files = findRecentTrafficBasebandFiles();
        assertFalse(files.isEmpty(), "No EDACS traffic baseband recordings found in " + RECORDINGS_DIR);

        int filesWithMessages = 0;
        int totalMessages = 0;
        StringBuilder debug = new StringBuilder();

        System.out.println();
        System.out.println("=== EDACS ProVoice Traffic Baseband Test ===");

        for(File file : files)
        {
            if(file.length() < MIN_BYTES)
            {
                continue;
            }

            WavComplexData wav = readComplexWav(file);
            if(wav.payloadBytes < MIN_BYTES)
            {
                continue;
            }

            DecodeResult result = decode(file, wav);
            totalMessages += result.messages.size();

            if(!result.messages.isEmpty())
            {
                filesWithMessages++;
                printFirstMessage(result.messages.get(0));
                appendDebug(debug, file, result.messages.get(0));
            }
        }

        if(!debug.isEmpty())
        {
            Files.writeString(DEBUG_DUMP, debug.toString(), StandardCharsets.US_ASCII);
            System.out.println("Debug dump: " + DEBUG_DUMP);
        }

        System.out.println("\nFiles with ProVoice messages: " + filesWithMessages + " / " + files.size());
        System.out.println("Total ProVoice messages:      " + totalMessages);

        assertTrue(filesWithMessages > 0, "No ProVoice messages extracted from recent EDACS traffic baseband recordings");
    }

    private DecodeResult decode(File file, WavComplexData wav)
    {
        EDACSProVoiceDecoder decoder = new EDACSProVoiceDecoder();
        List<EDACSProVoiceMessage> messages = new ArrayList<>();
        Listener<IMessage> listener = message -> {
            if(message instanceof EDACSProVoiceMessage proVoiceMessage)
            {
                messages.add(proVoiceMessage);
            }
        };

        decoder.setMessageListener(listener);
        decoder.getSourceEventListener().receive(SourceEvent.sampleRateChange(wav.sampleRate));

        long timestamp = 0;
        for(int offset = 0; offset < wav.i.length; offset += CHUNK_COMPLEX_SAMPLES)
        {
            int length = Math.min(CHUNK_COMPLEX_SAMPLES, wav.i.length - offset);
            float[] i = new float[length];
            float[] q = new float[length];
            System.arraycopy(wav.i, offset, i, 0, length);
            System.arraycopy(wav.q, offset, q, 0, length);
            decoder.receive(new ComplexSamples(i, q, timestamp));
            timestamp += length;
        }

        System.out.println(String.format(Locale.US,
                "%s | %.2fs @ %.0f Hz | detected=%d decoded=%d syncRejects=%d errors=%d messages=%d",
                file.getName(), wav.i.length / wav.sampleRate, wav.sampleRate,
                decoder.getFramesDetected(), decoder.getFramesDecoded(), decoder.getSyncRejects(),
                decoder.getDecodeErrors(), messages.size()));

        return new DecodeResult(messages);
    }

    private void printFirstMessage(EDACSProVoiceMessage message)
    {
        System.out.println("  First message sync=" + message.getSyncPattern() +
                " LID=" + String.format("0x%04X", message.getLid()) +
                " BF=" + String.format("0x%04X", message.getBfMarker()) +
                " (" + message.getBfMarker() + ") valid=" + message.isValid());
        byte[][] frames = message.getImbeFrames();
        for(int x = 0; x < frames.length; x++)
        {
            System.out.println("  IMBE" + (x + 1) + ": " + toHex(frames[x]));
        }
    }

    private void appendDebug(StringBuilder sb, File file, EDACSProVoiceMessage message)
    {
        sb.append("FILE ").append(file.getName()).append('\n');
        sb.append("SYNC ").append(message.getSyncPattern()).append('\n');
        sb.append(String.format(Locale.US, "LID 0x%04X\n", message.getLid()));
        sb.append(String.format(Locale.US, "BF 0x%04X %d\n", message.getBfMarker(), message.getBfMarker()));

        byte[][] frames = message.getImbeFrames();
        for(int x = 0; x < frames.length; x++)
        {
            sb.append("IMBE").append(x + 1).append(' ').append(toHex(frames[x])).append('\n');
        }

        int[][][] grids = message.getImbeGrids();
        for(int frame = 0; frame < grids.length; frame++)
        {
            sb.append("GRID").append(frame + 1).append('\n');
            for(int row = 0; row < grids[frame].length; row++)
            {
                for(int column = 0; column < grids[frame][row].length; column++)
                {
                    sb.append(grids[frame][row][column]);
                }
                sb.append('\n');
            }
        }

        sb.append("BITS ");
        for(boolean bit : message.getFrameBits())
        {
            sb.append(bit ? '1' : '0');
        }
        sb.append("\n\n");
    }

    private String toHex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder();
        for(byte b : bytes)
        {
            if(!sb.isEmpty())
            {
                sb.append(' ');
            }
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private List<File> findRecentTrafficBasebandFiles()
    {
        File[] files = RECORDINGS_DIR.listFiles((dir, name) ->
                name.contains("EDACS-T-MBTA-EDACS") && name.endsWith("_baseband.wav"));

        if(files == null)
        {
            return List.of();
        }

        List<File> result = new ArrayList<>(List.of(files));
        result.sort(Comparator.comparingLong(File::lastModified).reversed());

        if(result.size() > MAX_FILES)
        {
            return new ArrayList<>(result.subList(0, MAX_FILES));
        }

        return result;
    }

    private WavComplexData readComplexWav(File file) throws Exception
    {
        byte[] all = Files.readAllBytes(file.toPath());
        assertTrue(all.length >= 44, "WAV file too short: " + file);
        assertChunk(all, 0, "RIFF");
        assertChunk(all, 8, "WAVE");

        int offset = 12;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataSize = -1;

        while(offset + 8 <= all.length)
        {
            String chunkId = new String(all, offset, 4, StandardCharsets.US_ASCII);
            int chunkSize = ByteBuffer.wrap(all, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int chunkDataOffset = offset + 8;

            if("fmt ".equals(chunkId))
            {
                ByteBuffer fmt = ByteBuffer.wrap(all, chunkDataOffset, chunkSize).order(ByteOrder.LITTLE_ENDIAN);
                int audioFormat = fmt.getShort() & 0xFFFF;
                channels = fmt.getShort() & 0xFFFF;
                sampleRate = fmt.getInt();
                fmt.getInt(); // byte rate
                fmt.getShort(); // block align
                bitsPerSample = fmt.getShort() & 0xFFFF;
                assertTrue(audioFormat == 1, "Expected PCM WAV: " + file);
            }
            else if("data".equals(chunkId))
            {
                dataOffset = chunkDataOffset;
                dataSize = chunkSize > 0 ? chunkSize : all.length - chunkDataOffset;
                break;
            }

            offset = chunkDataOffset + chunkSize + (chunkSize & 1);
        }

        assertTrue(dataOffset >= 0, "WAV file has no data chunk: " + file);
        assertTrue(channels == 2, "Expected stereo I/Q WAV: " + file + " channels=" + channels);
        assertTrue(bitsPerSample == 16, "Expected 16-bit I/Q WAV: " + file + " bits=" + bitsPerSample);

        int frames = dataSize / 4;
        float[] i = new float[frames];
        float[] q = new float[frames];
        ByteBuffer samples = ByteBuffer.wrap(all, dataOffset, frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for(int x = 0; x < frames; x++)
        {
            i[x] = samples.getShort() / 32768.0f;
            q[x] = samples.getShort() / 32768.0f;
        }

        return new WavComplexData(sampleRate, dataSize, i, q);
    }

    private void assertChunk(byte[] bytes, int offset, String expected)
    {
        String actual = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
        assertTrue(expected.equals(actual), "Expected WAV chunk " + expected + " at offset " + offset + " but found " + actual);
    }

    private record WavComplexData(double sampleRate, int payloadBytes, float[] i, float[] q) {}
    private record DecodeResult(List<EDACSProVoiceMessage> messages) {}
}
