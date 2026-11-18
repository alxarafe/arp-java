package org.alxarafe.arp.dictionary;

/** Sanitized storage failure, without driver details or credentials. */
public final class ReadFailure extends RuntimeException
{
    final String code;
    public ReadFailure(String code)
    {
        super(code);
        this.code = code;
    }
    public String code()
    {
        return code;
    }
}