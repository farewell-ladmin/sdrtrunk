package io.github.dsheirer.module.decode.edacs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.edacs.channel.EDACSChannel;
import io.github.dsheirer.module.decode.edacs.identifier.EDACSTalkgroup;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Checks that one EDACS call produces one call event, whether or not its traffic channel can be started.
 */
class EDACSTrafficChannelManagerTest
{
    private static final long FREQUENCY = 851175000L;

    /**
     * Stands in for ChannelProcessingManager.  Rejects starts synchronously with TUNER UNAVAILABLE, like
     * ChannelProcessingManager does when no tuner can reach the frequency.
     */
    private static class FakeChannelProcessingManager
    {
        private final EDACSTrafficChannelManager mManager;
        private final boolean mReject;
        private int mStartRequests;

        FakeChannelProcessingManager(EDACSTrafficChannelManager manager, boolean reject)
        {
            mManager = manager;
            mReject = reject;
        }

        @Subscribe
        public void start(ChannelStartProcessingRequest request)
        {
            mStartRequests++;

            if(mReject)
            {
                mManager.getChannelEventListener().receive(new ChannelEvent(request.getChannel(),
                    ChannelEvent.Event.NOTIFICATION_PROCESSING_START_REJECTED, "TUNER UNAVAILABLE"));
            }
        }
    }

    private static EDACSTrafficChannelManager manager(Set<IDecodeEvent> events)
    {
        EDACSTrafficChannelManager manager = new EDACSTrafficChannelManager(4);
        manager.createTrafficChannels(new Channel("MBTA", Channel.ChannelType.STANDARD), new DecodeConfigEDACS());
        manager.addDecodeEventListener(events::add);
        return manager;
    }

    private static EDACSChannel lcn1()
    {
        EDACSChannel channel = new EDACSChannel(1);
        channel.setFrequencyLookup(lcn -> FREQUENCY);
        return channel;
    }

    private static void grant(EDACSTrafficChannelManager manager, EDACSChannel channel, int talkgroup, long timestamp)
    {
        EDACSMessage message = new EDACSMessage(EDACSMessageType.DIGITAL_GROUP_CALL, new CorrectedBinaryMessage(40),
            timestamp);
        IdentifierCollection ic = new IdentifierCollection(List.of(new EDACSTalkgroup(talkgroup, Role.TO)));
        manager.processChannelGrant(message, ic, channel);
    }

    @Test
    void repeatedGrantsAfterRejectedStartUpdateOneEvent()
    {
        Set<IDecodeEvent> events = Collections.newSetFromMap(new IdentityHashMap<>());
        EDACSTrafficChannelManager manager = manager(events);
        FakeChannelProcessingManager cpm = new FakeChannelProcessingManager(manager, true);
        EventBus bus = new EventBus();
        bus.register(cpm);
        manager.setInterModuleEventBus(bus);

        EDACSChannel channel = lcn1();
        long start = System.currentTimeMillis();

        //EDACS repeats the grant several times a second for the whole call
        for(int x = 0; x < 10; x++)
        {
            grant(manager, channel, 530, start + (x * 200));
        }

        assertEquals(1, events.size(), "one call should produce one event");
        assertEquals(1, cpm.mStartRequests, "repeated grants should not request another traffic channel");
    }

    @Test
    void trafficVoiceUpdatesTheGrantEvent()
    {
        Set<IDecodeEvent> events = Collections.newSetFromMap(new IdentityHashMap<>());
        EDACSTrafficChannelManager manager = manager(events);
        EventBus bus = new EventBus();
        bus.register(new FakeChannelProcessingManager(manager, false));
        manager.setInterModuleEventBus(bus);

        EDACSChannel channel = lcn1();
        long start = System.currentTimeMillis();
        grant(manager, channel, 530, start);
        IDecodeEvent grantEvent = events.iterator().next();

        manager.processTrafficVoice(FREQUENCY, start + 1500);

        assertEquals(1, events.size(), "traffic voice should update the grant event, not add one");
        assertSame(grantEvent, events.iterator().next());
        assertEquals(1500, grantEvent.getDuration());
    }

    @Test
    void sameTalkgroupAfterStaleGapIsANewCall()
    {
        Set<IDecodeEvent> events = Collections.newSetFromMap(new IdentityHashMap<>());
        EDACSTrafficChannelManager manager = manager(events);
        EventBus bus = new EventBus();
        bus.register(new FakeChannelProcessingManager(manager, true));
        manager.setInterModuleEventBus(bus);

        EDACSChannel channel = lcn1();
        long start = System.currentTimeMillis();
        grant(manager, channel, 530, start);
        grant(manager, channel, 530, start + 10000);

        assertEquals(2, events.size(), "a grant after the stale threshold should start a new event");
    }
}
