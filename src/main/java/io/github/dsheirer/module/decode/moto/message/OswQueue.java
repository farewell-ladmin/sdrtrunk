package io.github.dsheirer.module.decode.moto.message;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 6-entry OSW queue for multi-OSW message assembly.
 * Messages can be 1, 2, or 3 OSWs long with IDLE OSWs interleaved.
 * Reference: OP25 tk_smartnet.py lines 806-1764
 */
public class OswQueue
{
    public static final int QUEUE_SIZE = 6;
    public static final int QUEUE_RESET_ADDRESS = 0xFFE;

    /**
     * Represents a single OSW entry in the queue.
     */
    public static class OswEntry
    {
        public final int address;
        public final boolean isGroup;
        public final int command;
        public final long timestamp;
        public final boolean isReset;

        public OswEntry(int address, boolean isGroup, int command, long timestamp)
        {
            this.address = address;
            this.isGroup = isGroup;
            this.command = command;
            this.timestamp = timestamp;
            this.isReset = false;
        }

        private OswEntry()
        {
            this.address = QUEUE_RESET_ADDRESS;
            this.isGroup = false;
            this.command = 0;
            this.timestamp = System.currentTimeMillis();
            this.isReset = true;
        }

        public static OswEntry createReset()
        {
            return new OswEntry();
        }

        public boolean isChannel()
        {
            // A channel number is valid if it maps to a valid frequency
            // For now, consider address < 0x3FF as potentially a channel
            // The bandplan will validate properly
            return address < 0x3FF && !isReset;
        }
    }

    private final Deque<OswEntry> mQueue = new ArrayDeque<>(QUEUE_SIZE);

    /**
     * Add an OSW entry to the queue. If the queue is full, the oldest entry is removed.
     */
    public void add(OswEntry entry)
    {
        if(mQueue.size() >= QUEUE_SIZE)
        {
            mQueue.removeFirst();
        }
        mQueue.addLast(entry);
    }

    /**
     * Insert a QUEUE_RESET marker.
     */
    public void addReset()
    {
        add(OswEntry.createReset());
    }

    /**
     * Push an entry to the front of the queue (for IDLE reordering).
     */
    public void pushFront(OswEntry entry)
    {
        if(mQueue.size() >= QUEUE_SIZE)
        {
            mQueue.removeLast();
        }
        ((ArrayDeque<OswEntry>)mQueue).addFirst(entry);
    }

    /**
     * Check if the queue is full (has QUEUE_SIZE entries).
     */
    public boolean isFull()
    {
        return mQueue.size() >= QUEUE_SIZE;
    }

    /**
     * Check if the queue is empty.
     */
    public boolean isEmpty()
    {
        return mQueue.isEmpty();
    }

    /**
     * Get entry at index (0 = oldest, 5 = newest).
     */
    public OswEntry get(int index)
    {
        int i = 0;
        for(OswEntry entry : mQueue)
        {
            if(i == index) return entry;
            i++;
        }
        return null;
    }

    /**
     * Get the newest entry (OSW2 in OP25 terminology).
     */
    public OswEntry getNewest()
    {
        return mQueue.peekLast();
    }

    /**
     * Get the middle entry (OSW1 in OP25 terminology).
     */
    public OswEntry getMiddle()
    {
        return get(QUEUE_SIZE - 2);
    }

    /**
     * Get the oldest entry (OSW0 in OP25 terminology).
     */
    public OswEntry getOldest()
    {
        return get(0);
    }

    /**
     * Remove the newest entry from the queue.
     */
    public void removeNewest()
    {
        mQueue.removeLast();
    }

    /**
     * Remove the oldest entry from the queue.
     */
    public void removeOldest()
    {
        mQueue.removeFirst();
    }

    /**
     * Clear the queue.
     */
    public void clear()
    {
        mQueue.clear();
    }

    /**
     * Get the current size of the queue.
     */
    public int size()
    {
        return mQueue.size();
    }

    /**
     * Check if any entry in the queue is a reset marker.
     */
    public boolean hasReset()
    {
        for(OswEntry entry : mQueue)
        {
            if(entry.isReset) return true;
        }
        return false;
    }

    /**
     * Remove all entries up to and including the first reset marker.
     */
    public void clearToReset()
    {
        while(!mQueue.isEmpty())
        {
            OswEntry entry = mQueue.removeFirst();
            if(entry.isReset) break;
        }
    }
}
