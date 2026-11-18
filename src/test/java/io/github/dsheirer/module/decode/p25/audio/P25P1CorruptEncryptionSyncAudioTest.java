/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.SingleChannelState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderState;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HeaderData;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.EncryptionSyncParameters;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDULCMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDUMessage;
import io.github.dsheirer.preference.UserPreferences;
import java.lang.reflect.Proxy;
import jmbe.iface.IAudioCodec;
import org.junit.jupiter.api.Test;

/**
 * Tests that a corrupt HDU or LDU2 that passes error correction and reports encrypted audio doesn't mute a clear
 * transmission.
 *
 * The test feeds messages to a P25 Phase 1 traffic channel decoder state, channel state (state machine and squelch
 * controller) and audio module that are connected the way a processing chain connects them: the decoder state receives
 * each message before the audio module, and the audio module listens to the squelch state of the channel state.  The
 * audio codec is replaced with one that counts the IMBE voice frames it is asked to decode.
 */
class P25P1CorruptEncryptionSyncAudioTest
{
    private static final int NAC = 0x293;
    private static final int FRAMES_PER_LDU = 9;

    /**
     * Audio module with a stand-in audio codec that counts the voice frames that are decoded to audio.
     */
    private static class CountingAudioModule extends P25P1AudioModule
    {
        private final IAudioCodec mCodec = (IAudioCodec)Proxy.newProxyInstance(IAudioCodec.class.getClassLoader(),
            new Class<?>[]{IAudioCodec.class}, (proxy, method, args) ->
            {
                if(method.getReturnType() == float[].class)
                {
                    return new float[160];
                }
                else if(method.getReturnType() == boolean.class)
                {
                    return false;
                }
                else if(method.getReturnType() == int.class)
                {
                    return 0;
                }

                return null;
            });
        private int mFramesPlayed;

        CountingAudioModule()
        {
            super(new UserPreferences(), new AliasList("test"));
        }

        @Override
        public IAudioCodec getAudioCodec()
        {
            return mCodec;
        }

        @Override
        public void addAudio(float[] audioBuffer)
        {
            mFramesPlayed++;
        }

        @Override
        protected void closeAudioSegment()
        {
        }
    }

    /**
     * Traffic channel under test.
     */
    private static class Rig
    {
        private final P25P1DecoderState mDecoderState;
        private final SingleChannelState mChannelState;
        private final CountingAudioModule mAudioModule = new CountingAudioModule();
        private long mTimestamp = System.currentTimeMillis();

        Rig()
        {
            Channel channel = new Channel("test", Channel.ChannelType.TRAFFIC);
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            mDecoderState = new P25P1DecoderState(channel);
            mChannelState = new SingleChannelState(channel, new AliasModel());
            mDecoderState.setDecoderStateListener(mChannelState.getDecoderStateListener());
            mChannelState.setSquelchStateListener(mAudioModule.getSquelchStateListener());
        }

        /**
         * Sends the messages to the decoder state and then the audio module, as the processing chain does.
         */
        void send(P25P1Message... messages)
        {
            for(P25P1Message message : messages)
            {
                mDecoderState.receive(message);
                mAudioModule.receive(message);
            }
        }

        int ldusPlayed()
        {
            return mAudioModule.mFramesPlayed / FRAMES_PER_LDU;
        }

        long timestamp()
        {
            mTimestamp += 180;
            return mTimestamp;
        }

        HDUMessage clearHdu()
        {
            return new HDUMessage(zero(P25P1DataUnitID.HEADER_DATA_UNIT), NAC, timestamp())
            {
                private final HeaderData mHeaderData = new HeaderData(bits(120, 80));

                @Override
                public HeaderData getHeaderData()
                {
                    return mHeaderData;
                }
            };
        }

        /**
         * An HDU for algorithm 0x84 (AES-256), key 1 and talkgroup 1.
         */
        HDUMessage encryptedHdu()
        {
            return new HDUMessage(zero(P25P1DataUnitID.HEADER_DATA_UNIT), NAC, timestamp())
            {
                private final HeaderData mHeaderData = new HeaderData(bits(120, 80, 85, 103, 119));

                @Override
                public HeaderData getHeaderData()
                {
                    return mHeaderData;
                }
            };
        }

        /**
         * An all-zero HDU: a valid codeword that decodes as algorithm 0x00.  It carries no information and isn't
         * evidence that the transmission is encrypted.
         */
        HDUMessage zeroHdu()
        {
            return new HDUMessage(zero(P25P1DataUnitID.HEADER_DATA_UNIT), NAC, timestamp());
        }

        LDU1Message ldu1()
        {
            return new LDU1Message(zero(P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1), NAC, timestamp())
            {
                @Override
                public LinkControlWord getLinkControlWord()
                {
                    return null;
                }
            };
        }

        /**
         * An LDU2 with valid encryption sync parameters for algorithm 0x80 (unencrypted).
         */
        LDU2Message clearLdu2()
        {
            return new LDU2Message(zero(P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2), NAC, timestamp())
            {
                private final EncryptionSyncParameters mParameters = new EncryptionSyncParameters(bits(96, 72));

                @Override
                public EncryptionSyncParameters getEncryptionSyncParameters()
                {
                    return mParameters;
                }
            };
        }

        /**
         * An all-zero LDU2, which is what is substituted for a lost LDU2.  sdrtrunk treats its all-zero encryption
         * sync as unencrypted.
         */
        LDU2Message zeroLdu2()
        {
            return new LDU2Message(zero(P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2), NAC, timestamp());
        }

        /**
         * An LDU2 with valid encryption sync parameters for algorithm 0x84 (AES-256) and key 1.
         */
        LDU2Message encryptedLdu2()
        {
            return new LDU2Message(zero(P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_2), NAC, timestamp())
            {
                private final EncryptionSyncParameters mParameters = new EncryptionSyncParameters(bits(96, 72, 77, 95));

                @Override
                public EncryptionSyncParameters getEncryptionSyncParameters()
                {
                    return mParameters;
                }
            };
        }

        /**
         * A TDULC whose link control word failed error correction.  The decoder state ignores it.
         */
        TDULCMessage invalidTdulc()
        {
            return new TDULCMessage(zero(P25P1DataUnitID.TERMINATOR_DATA_UNIT_LINK_CONTROL), NAC, timestamp())
            {
                @Override
                public LinkControlWord getLinkControlWord()
                {
                    return null;
                }
            };
        }

        TDUMessage tdu()
        {
            return new TDUMessage(new CorrectedBinaryMessage(64), NAC, timestamp());
        }
    }

    private static CorrectedBinaryMessage zero(P25P1DataUnitID dataUnitID)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(dataUnitID.getMessageLength());
        message.setSize(dataUnitID.getMessageLength());
        return message;
    }

    private static BinaryMessage bits(int length, int... setBits)
    {
        BinaryMessage message = new BinaryMessage(length);

        for(int bit : setBits)
        {
            message.set(bit);
        }

        return message;
    }

    @Test
    void testMessagesAreWhatTheTestsAssume()
    {
        Rig rig = new Rig();
        assertTrue(rig.zeroHdu().isValid(), "all-zero HDU passes error correction");
        assertTrue(rig.zeroHdu().getHeaderData().isEncryptedAudio(), "all-zero HDU reads as encrypted");
        assertTrue(rig.zeroHdu().getHeaderData().isEmpty());
        assertFalse(rig.clearHdu().getHeaderData().isEmpty());
        assertTrue(rig.encryptedHdu().isValid());
        assertTrue(rig.encryptedHdu().getHeaderData().isEncryptedAudio());
        assertFalse(rig.encryptedHdu().getHeaderData().isEmpty());
        assertTrue(rig.clearHdu().isValid());
        assertFalse(rig.clearHdu().getHeaderData().isEncryptedAudio());
        assertTrue(rig.clearLdu2().getEncryptionSyncParameters().isValid());
        assertFalse(rig.clearLdu2().getEncryptionSyncParameters().isEncryptedAudio());
        assertFalse(rig.clearLdu2().getEncryptionSyncParameters().isEmpty());
        assertTrue(rig.zeroLdu2().getEncryptionSyncParameters().isValid());
        assertFalse(rig.zeroLdu2().getEncryptionSyncParameters().isEncryptedAudio(), "all-zero LDU2 reads as clear");
        assertTrue(rig.zeroLdu2().getEncryptionSyncParameters().isEmpty());
        assertFalse(rig.encryptedLdu2().getEncryptionSyncParameters().isEmpty());
        assertTrue(rig.encryptedLdu2().getEncryptionSyncParameters().isValid());
        assertTrue(rig.encryptedLdu2().getEncryptionSyncParameters().isEncryptedAudio());
    }

    @Test
    void clearTransmissionPlays()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(), rig.clearLdu2(), rig.tdu());
        assertEquals(4, rig.ldusPlayed());
    }

    @Test
    void lateEntryClearTransmissionPlays()
    {
        Rig rig = new Rig();
        rig.send(rig.ldu1(), rig.clearLdu2(), rig.ldu1(), rig.clearLdu2(), rig.tdu());
        assertEquals(4, rig.ldusPlayed());
    }

    @Test
    void encryptedTransmissionIsNotPlayed()
    {
        Rig rig = new Rig();
        rig.send(rig.encryptedHdu(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());

        //Late entry
        rig.send(rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void encryptedTransmissionIsNotPlayedAfterOneClearLdu2()
    {
        Rig rig = new Rig();
        rig.send(rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.clearLdu2(),
            rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void encryptedTransmissionIsNotPlayedAfterZeroFilledLdu2s()
    {
        //Zero-filled LDU2s are substituted for lost ones and read as clear.  Any number of them in a row must not
        //change an encrypted transmission to clear.
        Rig rig = new Rig();
        rig.send(rig.encryptedHdu(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(),
            rig.zeroLdu2(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void lateEntryEncryptedTransmissionIsNotPlayedWhenFirstLdu2IsZeroFilled()
    {
        Rig rig = new Rig();
        rig.send(rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(),
            rig.tdu());
        assertEquals(0, rig.ldusPlayed());

        //The same after an encrypted transmission on the channel
        rig.send(rig.encryptedHdu(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        rig.send(rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(),
            rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void encryptedTransmissionAfterMissedTerminatorIsNotPlayedWhenFirstLdu2IsZeroFilled()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2());
        assertEquals(2, rig.ldusPlayed());

        rig.send(rig.encryptedHdu(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(),
            rig.encryptedLdu2(), rig.tdu());
        assertEquals(2, rig.ldusPlayed(), "no LDU of the encrypted transmission is played");
    }

    @Test
    void lateEntryTransmissionWithSingleEmptySyncPlays()
    {
        //A system that sends an all-zero encryption sync with unencrypted audio: a late-entry transmission that ends
        //after one LDU2 is played when the channel squelches at the terminator.
        Rig rig = new Rig();
        rig.send(rig.ldu1(), rig.zeroLdu2(), rig.tdu());
        assertEquals(2, rig.ldusPlayed());

        rig.send(rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.tdu());
        assertEquals(5, rig.ldusPlayed());
    }

    @Test
    void heldFramesAreNotPlayedWhenChannelSquelchesForEncryptedState()
    {
        //One encrypted HDU, then the audio module is reset by a TDULC that the decoder state ignores, then a
        //zero-filled LDU2.  The channel squelches when the decoder state confirms the transmission as encrypted, which
        //is not a terminator, so the held frames must not be played.
        Rig rig = new Rig();
        rig.send(rig.encryptedHdu(), rig.ldu1(), rig.invalidTdulc(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(),
            rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void lateEntryWithSingleEmptySyncIsNotPlayedWhileChannelIsEncrypted()
    {
        //After an encrypted transmission the channel stays in the ENCRYPTED state.  A late-entry transmission whose
        //only encryption sync is a zero-filled LDU2 is not played, as it wasn't before.
        Rig rig = new Rig();
        rig.send(rig.encryptedHdu(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        rig.send(rig.ldu1(), rig.zeroLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void clearTransmissionWithZeroFilledLdu2sPlays()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.clearLdu2(), rig.tdu());
        assertEquals(4, rig.ldusPlayed());

        //Late entry where every LDU2 has an all-zero encryption sync
        rig.send(rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.zeroLdu2(), rig.tdu());
        assertEquals(8, rig.ldusPlayed());
    }

    @Test
    void heldEncryptedFramesAreNotPlayedWhenClearHduFollows()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2());
        assertEquals(2, rig.ldusPlayed());

        //The terminator is missed, a short encrypted transmission follows whose terminator is also missed, and then
        //a clear transmission starts.  The frames held from the encrypted transmission must not be played.
        rig.send(rig.ldu1(), rig.encryptedLdu2(), rig.ldu1());
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(), rig.clearLdu2(), rig.tdu());
        assertEquals(2 + 1 + 4, rig.ldusPlayed(), "the LDU1 before the encrypted LDU2 and the next clear " +
            "transmission are played, and the held encrypted frames are not");
    }

    @Test
    void clearTransmissionAfterMissedTerminatorOfEncryptedTransmissionPlays()
    {
        Rig rig = new Rig();
        rig.send(rig.encryptedHdu(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2());
        assertEquals(0, rig.ldusPlayed());

        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(), rig.clearLdu2(), rig.tdu());
        assertTrue(rig.ldusPlayed() >= 3, "the clear transmission plays once its HDU and first LDU2 agree, played: " +
            rig.ldusPlayed());
    }

    @Test
    void corruptHduInTheMiddleOfClearTransmissionDoesNotMuteIt()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2(), rig.zeroHdu(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(),
            rig.clearLdu2(), rig.tdu());
        assertEquals(6, rig.ldusPlayed(), "every LDU of the clear transmission is played");
    }

    @Test
    void corruptLdu2InTheMiddleOfClearTransmissionDoesNotMuteIt()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(),
            rig.clearLdu2(), rig.ldu1(), rig.clearLdu2(), rig.tdu());
        assertEquals(8, rig.ldusPlayed(), "every LDU of the clear transmission is played");
    }

    @Test
    void corruptLastLdu2DoesNotMuteTheNextClearTransmission()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        int first = rig.ldusPlayed();
        assertTrue(first >= 3, "the first transmission is played up to its corrupt last LDU2, played: " + first);

        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(), rig.clearLdu2(), rig.tdu());
        assertEquals(4, rig.ldusPlayed() - first, "every LDU of the next clear transmission is played");
    }

    @Test
    void allZeroFirstHduDoesNotMuteClearTransmission()
    {
        Rig rig = new Rig();
        rig.send(rig.zeroHdu(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(), rig.clearLdu2(),
            rig.tdu());
        assertEquals(6, rig.ldusPlayed(), "every LDU of the clear transmission is played");
    }

    @Test
    void encryptedTransmissionThatStartsWithAllZeroHduIsNotPlayed()
    {
        Rig rig = new Rig();
        rig.send(rig.zeroHdu(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());

        //The same when one of its LDU2s was lost and zero-filled
        rig.send(rig.zeroHdu(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void encryptedTransmissionWithAllZeroHduAndZeroFilledLdu2sIsNotPlayed()
    {
        //An all-zero HDU followed by any number of zero-filled LDU2s is no evidence of a clear transmission.
        Rig rig = new Rig();
        rig.send(rig.zeroHdu(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(),
            rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void zeroFilledLdu2sAcrossAnAllZeroHduDoNotEstablishClear()
    {
        //A zero-filled LDU2 before the all-zero HDU and one after it aren't two consecutive ones.
        Rig rig = new Rig();
        rig.send(rig.ldu1(), rig.zeroLdu2(), rig.zeroHdu(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(),
            rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void zeroFilledLdu2sDoNotSettlePendingChangeAfterAllZeroHdu()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2());
        assertEquals(2, rig.ldusPlayed());

        //The terminator is missed and an encrypted transmission follows with an all-zero HDU and lost LDU2s.
        rig.send(rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.zeroLdu2(), rig.zeroHdu(), rig.ldu1(),
            rig.zeroLdu2(), rig.ldu1(), rig.zeroLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(3, rig.ldusPlayed(), "only the LDU1 before the first encrypted LDU2 is played");
    }

    @Test
    void allZeroHduDoesNotChangeAnEncryptedTransmission()
    {
        Rig rig = new Rig();
        rig.send(rig.encryptedHdu(), rig.ldu1(), rig.zeroHdu(), rig.ldu1(), rig.clearLdu2(), rig.ldu1(),
            rig.encryptedLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void encryptedTransmissionWithAllZeroHduAfterMissedTerminatorIsNotPlayed()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2());
        assertEquals(2, rig.ldusPlayed());

        //The terminator of the clear transmission is missed.  The all-zero HDU holds the voice frames until the
        //next LDU2 decides.
        rig.send(rig.zeroHdu(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(2, rig.ldusPlayed(), "no LDU of the encrypted transmission is played");
    }

    @Test
    void allZeroHduWithSingleEmptySyncIsNotPlayedAtTheTerminator()
    {
        //An all-zero HDU and a zero-filled LDU2 are no evidence of a clear transmission.
        Rig rig = new Rig();
        rig.send(rig.zeroHdu(), rig.ldu1(), rig.zeroLdu2(), rig.tdu());
        assertEquals(0, rig.ldusPlayed());
    }

    @Test
    void encryptedTransmissionAfterMissedTerminatorIsNotPlayed()
    {
        Rig rig = new Rig();
        rig.send(rig.clearHdu(), rig.ldu1(), rig.clearLdu2());
        assertEquals(2, rig.ldusPlayed());

        //The terminator of the clear transmission is missed and an encrypted transmission follows.
        rig.send(rig.encryptedHdu(), rig.ldu1(), rig.encryptedLdu2(), rig.ldu1(), rig.encryptedLdu2(), rig.tdu());
        assertEquals(2, rig.ldusPlayed(), "no LDU of the encrypted transmission is played");
    }
}
