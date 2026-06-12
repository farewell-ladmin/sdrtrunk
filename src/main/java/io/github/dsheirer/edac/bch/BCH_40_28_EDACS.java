package io.github.dsheirer.edac.bch;

import io.github.dsheirer.bits.CorrectedBinaryMessage;

/**
 * BCH(40,28) decoder for EDACS control channel error correction.
 *
 * Shortened BCH code with GF(2^6), capable of correcting up to 2 bit errors
 * in a 40-bit codeword containing 28 data bits and 12 BCH parity bits.
 *
 * The full BCH code operates on 63-bit codewords. Since EDACS uses a shortened
 * 40-bit code, the leading 23 bits are treated as punctured (zero).
 */
public class BCH_40_28_EDACS extends BCH_63
{
    public static final int DATA_BITS = 28;
    public static final int PARITY_BITS = 12;
    public static final int CODEWORD_LENGTH = 40;
    public static final int PUNCTURED_BITS = 23;
    public static final int ERROR_CAPACITY = 2;

    public BCH_40_28_EDACS()
    {
        super(DATA_BITS, ERROR_CAPACITY);
    }

    /**
     * Decodes a 40-bit EDACS BCH codeword and returns the corrected 28-bit data payload.
     * @param codeword 40-bit EDACS message (data + parity)
     * @return corrected 28-bit data, or null if uncorrectable
     */
    public CorrectedBinaryMessage decodeCodeword(CorrectedBinaryMessage codeword)
    {
        CorrectedBinaryMessage padded = new CorrectedBinaryMessage(63);

        //Pad leading 23 bits as zero (punctured). Copy the 40-bit codeword to bits 23-62.
        for(int x = 0; x < CODEWORD_LENGTH; x++)
        {
            if(codeword.get(x))
            {
                padded.set(PUNCTURED_BITS + x);
            }
        }

        decode(padded);

        if(padded.getCorrectedBitCount() < 0)
        {
            return null;
        }

        CorrectedBinaryMessage data = new CorrectedBinaryMessage(DATA_BITS);
        for(int x = 0; x < DATA_BITS; x++)
        {
            if(padded.get(PUNCTURED_BITS + x))
            {
                data.set(x);
            }
        }

        data.setCorrectedBitCount(padded.getCorrectedBitCount());
        return data;
    }
}
