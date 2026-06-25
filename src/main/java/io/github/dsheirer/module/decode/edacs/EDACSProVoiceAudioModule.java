// Reference: DSD-FME provoice.c, sdrtrunk P25P1AudioModule
package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.codec.mbe.JmbeAudioModule;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.dsp.gain.NonClippingGain;
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

    private final NonClippingGain mGain = new NonClippingGain(5.0f, 0.95f);
    private final SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private int mFramesProcessed = 0;
    private int mDecodeErrors = 0;
    private long mLastDecodeErrorLogTimestamp = 0;

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
            for(byte[] frame : pv.getPackedImbe7100Frames())
            {
                try
                {
                    float[] audio = getAudioCodec().getAudio(frame);
                    audio = mGain.apply(audio);
                    addAudio(audio);
                    mFramesProcessed++;
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
            if(event.getSquelchState() == SquelchState.SQUELCH)
            {
                closeAudioSegment();
            }
        }
    }
}
