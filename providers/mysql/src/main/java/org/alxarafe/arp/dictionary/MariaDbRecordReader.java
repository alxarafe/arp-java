package org.alxarafe.arp.dictionary;

public class MariaDbRecordReader extends MySqlRecordReaderBase
{
    public MariaDbRecordReader(Connections connections)
    {
        this(connections, 5);
    }
    public MariaDbRecordReader(Connections connections, int timeoutSeconds)
    {
        super(connections, timeoutSeconds);
        mariaDb = true;
    }
}