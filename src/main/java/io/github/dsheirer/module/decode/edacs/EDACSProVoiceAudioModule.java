// Reference: DSD-FME provoice.c, sdrtrunk P25P1AudioModule
package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.codec.mbe.JmbeAudioModule;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSProVoiceMessage;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio module for EDACS ProVoice traffic channels. Receives
 * {@link EDACSProVoiceMessage} instances from the
 * {@link EDACSProVoiceDecoder}, decodes each of the 4 IMBE 7100
 * voice frames via the JMBE {@code PROVOICE} codec, and emits 80 ms
 * of 8 kHz PCM audio per ProVoice frame.
 */
public class EDACSProVoiceAudioModule extends JmbeAudioModule
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSProVoiceAudioModule.class);
    private static final String PROVOICE_CODEC = "PROVOICE";

    private final SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private int mFramesProcessed = 0;
    private int mDecodeErrors = 0;
    private long mLastDecodeErrorLogTimestamp = 0;
    private long mLastAudioLogTime = 0;
    private int mSegmentCloseCount = 0;
    private long mSegmentOpenTime = 0;

    public EDACSProVoiceAudioModule(UserPreferences userPreferences, AliasList aliasList)
    {
        super(userPreferences, aliasList, DEFAULT_TIMESLOT);
    }

    @Override
    protected String getCodecName()
    {
        return PROVOICE_CODEC;
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    @Override
    public void reset()
    {
        mLog.info("PROVOICE RESET called - framesProcessed={} decodeErrors={} segmentCloses={}",
                mFramesProcessed, mDecodeErrors, mSegmentCloseCount);
        getIdentifierCollection().clear();
    }

    @Override
    public void start()
    {
    }

    @Override
    public void receive(IMessage message)
    {
        if(hasAudioCodec() && message instanceof EDACSProVoiceMessage pv && pv.isValid())
        {
            if(mSegmentOpenTime == 0)
            {
                mSegmentOpenTime = System.currentTimeMillis();
            }
            long now = System.currentTimeMillis();
            boolean logAudio = (now - mLastAudioLogTime) > 2000;
            for(byte[] frame : pv.getPackedImbe7100Frames())
            {
                try
                {
                    float[] audio = getAudioCodec().getAudio(frame);
                    addAudio(audio);
                    mFramesProcessed++;
                    if(logAudio)
                    {
                        float maxAbs = 0;
                        float sum = 0;
                        for(float s : audio)
                        {
                            float abs = Math.abs(s);
                            if(abs > maxAbs) maxAbs = abs;
                            sum += s;
                        }
                        mLog.info("PROVOICE AUDIO frame#{} samples={} maxAbs={} mean={} segAgeMs={} segCloses={}",
                                mFramesProcessed, audio.length, maxAbs, sum / audio.length,
                                now - mSegmentOpenTime, mSegmentCloseCount);
                        mLastAudioLogTime = now;
                        logAudio = false;
                    }
                }
                catch(Exception e)
                {
                    mDecodeErrors++;
                    logDecodeError(e);
                }
            }
        }
    }

    private void logDecodeError(Exception exception)
    {
        long now = System.currentTimeMillis();

        if(now - mLastDecodeErrorLogTimestamp > 10000)
        {
            mLastDecodeErrorLogTimestamp = now;
            mLog.warn("ProVoice IMBE decode errors: {} latest: {}", mDecodeErrors, exception.getMessage());
        }
        else
        {
            mLog.debug("ProVoice IMBE decode error: {}", exception.getMessage());
        }
    }

    public int getFramesProcessed()
    {
        return mFramesProcessed;
    }

    public class SquelchStateListener implements Listener<SquelchStateEvent>
    {
        @Override
        public void receive(SquelchStateEvent event)
        {
            mLog.info("PROVOICE SQUELCH EVENT: state={} timeslot={} segAge={}ms framesProcessed={}",
                    event.getSquelchState(), event.getTimeslot(),
                    mSegmentOpenTime > 0 ? System.currentTimeMillis() - mSegmentOpenTime : -1,
                    mFramesProcessed);
            if(event.getSquelchState() == SquelchState.SQUELCH)
            {
                mSegmentCloseCount++;
                long segDuration = mSegmentOpenTime > 0 ? System.currentTimeMillis() - mSegmentOpenTime : -1;
                mLog.info("PROVOICE CLOSE segment #{} duration={}ms framesProcessed={}",
                        mSegmentCloseCount, segDuration, mFramesProcessed);
                closeAudioSegment();
                mSegmentOpenTime = 0;
            }
        }
    }
}
