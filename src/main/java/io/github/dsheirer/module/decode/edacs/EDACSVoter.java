package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.bch.BCH_40_28_EDACS;

/**
 * EDACS message voter. Performs majority voting across 3 copies of a 40-bit EDACS
 * control channel word (normal, inverted, normal) and applies BCH(40,28) error correction.
 */
public class EDACSVoter
{
    private BCH_40_28_EDACS mBCH = new BCH_40_28_EDACS();

    /**
     * Votes across 3 copies of an EDACS message word. Copies 1 and 3 are normal polarity,
     * copy 2 is inverted.
     *
     * @param copy1 first copy (normal)
     * @param copy2 second copy (inverted)
     * @param copy3 third copy (normal)
     * @return corrected 28-bit data message, or null if uncorrectable
     */
    public CorrectedBinaryMessage vote(CorrectedBinaryMessage copy1, CorrectedBinaryMessage copy2, CorrectedBinaryMessage copy3)
    {
        CorrectedBinaryMessage voted = new CorrectedBinaryMessage(40);

        for(int x = 0; x < 40; x++)
        {
            int count = 0;
            if(copy1.get(x)) count++;
            if(!copy2.get(x)) count++; //inverted copy
            if(copy3.get(x)) count++;

            if(count >= 2)
            {
                voted.set(x);
            }
        }

        return mBCH.decodeCodeword(voted);
    }
}
